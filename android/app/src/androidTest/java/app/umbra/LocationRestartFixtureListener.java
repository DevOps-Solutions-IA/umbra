package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.crypto.Engine;
import app.umbra.core.Bytes;
import app.umbra.location.LocationPayload;
import app.umbra.lab.SqliteDeviceRecords;
import android.os.Bundle;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** Host kills the actual target process with a committed pending location. Never part of release. */
public final class LocationRestartFixtureListener extends RunListener {
    @Override public void testRunStarted(Description description) throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("locationPhase","");
        if(phase.equals("prepare")) {
            // Intentionally left open: the host must force-stop this process, not call close/reopen.
            var ar=new SqliteDeviceRecords("location-restart",false);
            try(var br=new SqliteDeviceRecords()) {
                Engine a=new Engine(ar),b=new Engine(br); LocationAndroidTest.pair(a,b,ar,br);
                String session=a.locations().start(a.locations().review(b.id(),LocationPayload.Mode.ZONE,900,true),true);
                a.locations().publish(session,12.345678,45.678912,5,Bytes.now(),"ANDROID_FINE");
                if(app.umbra.calls.CallPlatform.ENABLED) a.calls().invite(a.calls().reviewInvite(b.id(),app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),true);
                if(a.outbox().size()!=(app.umbra.calls.CallPlatform.ENABLED?3:2)) throw new AssertionError("Pending location missing");
                Bundle status=new Bundle(); status.putString("locationRestart","READY committed synthetic session; awaiting actual force-stop");
                InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
                while(true) Thread.sleep(1000);
            }
        } else if(phase.equals("verify")) {
            try(var ar=new SqliteDeviceRecords("location-restart",true)) {
                Engine a=new Engine(ar);
                if(!a.initialized() || ar.keys("location-out").size()!=1 || !a.outbox().isEmpty()) throw new AssertionError("Restart restored backlog");
                if(app.umbra.calls.CallPlatform.ENABLED) {
                    if(ar.keys("calls").size()!=1 || !a.calls().session(ar.keys("calls").get(0)).getString("state").equals("FAILED")) throw new AssertionError("Call resumed after force-stop");
                    Bundle callStatus=new Bundle();callStatus.putString("callRestart","PASS no call grant or signaling backlog restored after actual force-stop");
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,callStatus);
                }
                String id=ar.keys("location-out").get(0);
                if(!a.get("location-out",id).getString("state").equals("INTERRUPTED")) throw new AssertionError("Restart did not interrupt");
                try { a.locations().authorizeCapture(id); throw new AssertionError("Restart restored capture"); } catch(SecurityException expected) { /* Required. */ }
                Bundle status=new Bundle(); status.putString("locationRestart","PASS no capture grant or queued coordinates restored after process force-stop");
                InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
            }
        } else throw new SecurityException("Specify synthetic restart phase");
    }
}
