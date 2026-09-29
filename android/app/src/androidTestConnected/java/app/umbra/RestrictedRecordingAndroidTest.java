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

/** Owned AVD with -no-audio only: native AudioRecord path, not acoustic capture acceptance. */
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
            // Separate bounded diagnostic before the product path: distinguish HAL/capture
            // output from the native encoder. Test APK only, guarded by owned AVD/no-host-audio.
            var raw=observeAvdInput(selected);
            final long[] began={0};
            try(var prepared=RestrictedRecording.record(context,a,review,true,selected,()->{
                long now=System.nanoTime();if(began[0]==0)began[0]=now;return now-began[0]>=1_200_000_000L;
            })) {
                String id=a.restricted().send(review,prepared,true);
                for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
                var session=b.restricted().open(b.restricted().reviewOpen(id),true);
                SyntheticRestrictedAudio.CaptureObservation captured;
                try {captured=SyntheticRestrictedAudio.captureStatistics(session);}
                finally {session.close();session.closure().toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS);}
                String zeroId=a.restricted().send(a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),
                    SyntheticRestrictedAudio.silence(ar.authorization()),true);
                for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
                var zeroSession=b.restricted().open(b.restricted().reviewOpen(zeroId),true);
                SyntheticRestrictedAudio.CaptureObservation zero;
                try {zero=SyntheticRestrictedAudio.captureStatistics(zeroSession);}
                finally {zeroSession.close();zeroSession.closure().toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS);}
                var status=new android.os.Bundle();status.putString("syntheticCaptureStatistics",
                    "capturedSamples="+captured.samples()+",capturedPeak="+captured.peak()+",capturedRms="+captured.rms()+
                    ",knownZeroPeak="+zero.peak()+",knownZeroRms="+zero.rms()+
                    ",rawInputSamples="+raw.samples()+",rawInputPeak="+raw.peak()+",rawInputRms="+raw.rms());
                instrumentation.sendStatus(0,status);
                // -no-audio excludes host audio; it does not specify the samples returned
                // by the guest capture stack. The raw-input diagnostic established that
                // nonzero values already exist BEFORE our encoder. Exact silence belongs
                // to the known-zero PCM control, not an assumed HAL output contract.
                assertEquals("Known-zero native codec input must decode to zero",0,zero.peak());
                assertTrue("Recording produced no decodable native samples",captured.samples()>=1024);
                assertTrue("Capture exceeded the bounded note profile",captured.samples()<=9*RestrictedAudio.SAMPLE_RATE);
            }
            ar.gate.lock();ar.gate.unlock();
            final AudioDeviceInfo input=selected;
            assertThrows(SecurityException.class,()->RestrictedRecording.record(context,a,review,true,input,()->true));
        }
    }
    private static SyntheticRestrictedAudio.CaptureObservation observeAvdInput(AudioDeviceInfo selected)throws Exception {
        short[] pcm=new short[4096];android.media.AudioRecord recorder=null;
        try {
            recorder=new android.media.AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(16000)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(8192).build();
            assertTrue(recorder.setPreferredDevice(selected));recorder.startRecording();
            long began=System.nanoTime();int count=0;
            while(count<pcm.length && System.nanoTime()-began<1_000_000_000L) {
                int read=recorder.read(pcm,count,pcm.length-count,android.media.AudioRecord.READ_NON_BLOCKING);
                assertTrue("AVD diagnostic read failed",read>=0);count+=read;if(read==0)Thread.sleep(5);
            }
            assertNotNull(recorder.getRoutedDevice());assertEquals(selected.getId(),recorder.getRoutedDevice().getId());
            assertTrue("AVD diagnostic has no captured data",count>=1024);
            double energy=0;int peak=0;
            for(int i=0;i<count;i++){energy+=(double)pcm[i]*pcm[i];peak=Math.max(peak,Math.abs((int)pcm[i]));}
            return new SyntheticRestrictedAudio.CaptureObservation(count,peak,Math.sqrt(energy/count));
        } finally {
            try {if(recorder!=null)recorder.release();}finally{java.util.Arrays.fill(pcm,(short)0);}
        }
    }

}
