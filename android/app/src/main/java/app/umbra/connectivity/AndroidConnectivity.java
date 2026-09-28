package app.umbra.connectivity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit Android binding. Registers observation only after domain consent; never requests a network. */
public final class AndroidConnectivity {
    private AndroidConnectivity() {}
    public static void connect(Context context,ConnectivityService service,String origin,boolean confirmed) throws Exception {
        service.connect(origin,confirmed);
        ConnectivityService.Lease lease=service.networkLease(origin);
        try {
            ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
            if(manager==null) throw new IllegalStateException("Network observation unavailable");
            AtomicBoolean cancelled=new AtomicBoolean(),registered=new AtomicBoolean();
            AtomicReference<Network> initial=new AtomicReference<>();
            ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    Network first=initial.get();
                    if(first==null) initial.compareAndSet(null,network);
                    else if(!first.equals(network)) lease.failed();
                }
                @Override public void onLost(Network network) { lease.failed(); }
                @Override public void onBlockedStatusChanged(Network network,boolean blocked) { if(blocked) lease.failed(); }
            };
            lease.attach(()->{
                cancelled.set(true);
                if(registered.compareAndSet(true,false)) manager.unregisterNetworkCallback(callback);
            });
            // Do not hold the Vault monitor while entering the platform service.
            lease.check(); manager.registerDefaultNetworkCallback(callback); registered.set(true);
            if(cancelled.get() && registered.compareAndSet(true,false)) manager.unregisterNetworkCallback(callback);
            lease.check();
            if(manager.getActiveNetwork()==null) throw new IllegalStateException("No default network available");
        } catch(Exception failure) { lease.failed(); throw failure; }
    }
}
