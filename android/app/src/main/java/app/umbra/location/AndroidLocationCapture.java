package app.umbra.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** AOSP provider, foreground Activity only. No service, restart receiver, cached fixes or network SDK. */
public final class AndroidLocationCapture implements AutoCloseable {
    private final Context context; private final LocationManager manager; private final Executor worker;
    private final BooleanSupplier foreground; private final LocationService service; private final Handler timer=new Handler(Looper.getMainLooper());
    private final Consumer<String> status;
    private volatile String session; private volatile LocationListener listener;
    private long lastSample; private String provider;
    public AndroidLocationCapture(Context context,Executor worker,BooleanSupplier foreground,LocationService service,Consumer<String> status) {
        this.context=context; this.worker=worker; this.foreground=foreground; this.service=service; this.status=status;
        manager=context.getSystemService(LocationManager.class);
    }
    private boolean permitted(String permission) {
        if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) return false;
        android.app.AppOpsManager ops=context.getSystemService(android.app.AppOpsManager.class);
        if(ops==null) return false;
        int mode=ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.permissionToOp(permission),android.os.Process.myUid(),context.getPackageName());
        return mode==android.app.AppOpsManager.MODE_ALLOWED || (mode==android.app.AppOpsManager.MODE_FOREGROUND && foreground.getAsBoolean());
    }
    private void check(String id,LocationPayload.Mode mode) throws Exception {
        if(!id.equals(session) || !foreground.getAsBoolean()) throw new SecurityException("Location capture interrupted");
        service.authorizeCapture(id);
        boolean fine=permitted(Manifest.permission.ACCESS_FINE_LOCATION), coarse=permitted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if(!fine && !coarse || mode==LocationPayload.Mode.PRECISE && !fine || LocationManager.GPS_PROVIDER.equals(provider) && !fine)
            throw new SecurityException("Location permission unavailable");
        if(manager==null || !manager.isProviderEnabled(provider)) throw new SecurityException("Location provider unavailable");
    }
    /** Invoke only after explicit local service.start confirmation, on the serial worker. */
    public void start(String id,LocationPayload.Mode mode,boolean live) throws Exception {
        close(); session=id; lastSample=Long.MIN_VALUE/2;
        provider=permitted(Manifest.permission.ACCESS_FINE_LOCATION)?LocationManager.GPS_PROVIDER:LocationManager.NETWORK_PROVIDER;
        try {
            if(mode==LocationPayload.Mode.MANUAL) throw new SecurityException("Manual point does not use provider");
            check(id,mode);
            LocationListener callback=new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    try {
                        check(id,mode); long now=SystemClock.elapsedRealtime();
                        if(now-lastSample<LocationPayload.INTERVAL_MS) return;
                        long measuredElapsed=location.getElapsedRealtimeNanos()/1_000_000L;
                        if(measuredElapsed<0 || measuredElapsed>now || now-measuredElapsed>LocationPayload.MAX_AGE*1000 || !location.hasAccuracy())
                            throw new SecurityException("Location estimate stale or incomplete");
                        service.publish(id,location.getLatitude(),location.getLongitude(),location.getAccuracy(),location.getTime()/1000,
                            permitted(Manifest.permission.ACCESS_FINE_LOCATION)?"ANDROID_FINE":"ANDROID_COARSE");
                        lastSample=now; status.accept(live?"Ubicación activa; entrega sujeta a conexión":"Punto cifrado en cola");
                        if(!live) close();
                    } catch(Exception failure) { fail(id); }
                }
                @Override public void onProviderDisabled(String name) { fail(id); }
            };
            listener=callback;
            // Framework checks permission again; revocation between check and request fails closed.
            manager.requestLocationUpdates(provider,new LocationRequest.Builder(LocationPayload.INTERVAL_MS)
                .setMinUpdateIntervalMillis(LocationPayload.INTERVAL_MS).build(),worker,callback);
            timer.postDelayed(new Runnable() {
                @Override public void run() {
                    if(!id.equals(session)) return;
                    worker.execute(() -> { try { check(id,mode); } catch(Exception failure) { fail(id); } });
                    timer.postDelayed(this,1000);
                }
            },1000);
            status.accept("Captura visible activa; esperando medición");
        } catch(SecurityException denied) { fail(id); throw denied; }
        catch(Exception failure) { fail(id); throw failure; }
    }
    private void fail(String id) {
        if(!id.equals(session)) return;
        close();
        try { service.interrupt(id); } catch(Exception failure) { /* No grant remains; reopen cleans storage when available. */ }
        status.accept("Ubicación interrumpida; requiere nuevo consentimiento");
    }
    public String activeSession() { return session; }
    @Override public void close() {
        session=null; timer.removeCallbacksAndMessages(null);
        LocationListener previous=listener; listener=null;
        if(manager!=null && previous!=null) manager.removeUpdates(previous);
    }
}
