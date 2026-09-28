package app.umbra;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.content.*;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import org.junit.Test;
import static org.junit.Assert.*;

/** AVD with -no-audio only: native AudioRecord silence, not acoustic capture acceptance. */
public class RestrictedRecordingAndroidTest {
    @Test public void deniedPermissionThenEmulatorCaptureUsesReviewedLeaseAndNativeCodec()throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();var context=instrumentation.getTargetContext();
        assertEquals("Required runner guarantee: emulator started with -no-audio", "true",
            InstrumentationRegistry.getArguments().getString("syntheticNoHostAudio"));
        try(var fd=instrumentation.getUiAutomation().executeShellCommand("getprop ro.kernel.qemu");
            var input=new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)))) {
            assertEquals("No physical microphone permitted", "1",input.readLine());
        }
        try(var host=androidx.test.core.app.ActivityScenario.launch(context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
                var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            assertNotEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
            assertEquals(ContentException.Code.CONSENT_REQUIRED,assertThrows(ContentException.class,
                ()->RestrictedRecording.record(context,a,review,true,null,()->true)).code());
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);
            assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
            AudioDeviceInfo selected=null;
            for(var input:context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))
                if(input.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){selected=input;break;}
            assertNotNull("Synthetic AVD input required",selected);
            final long[] began={0};
            try(var prepared=RestrictedRecording.record(context,a,review,true,selected,()->{
                long now=System.nanoTime();if(began[0]==0)began[0]=now;return now-began[0]>=1_200_000_000L;
            })) {
                String id=a.restricted().send(review,prepared,true);
                for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
                var session=b.restricted().open(b.restricted().reviewOpen(id),true);
                try {assertTrue(SyntheticRestrictedAudio.requireSilentCapture(session)>=1024);}
                finally {session.close();session.closure().toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS);}
            }
            ar.gate.lock();ar.gate.unlock();
            final AudioDeviceInfo input=selected;
            assertThrows(SecurityException.class,()->RestrictedRecording.record(context,a,review,true,input,()->true));
        }
    }
}
