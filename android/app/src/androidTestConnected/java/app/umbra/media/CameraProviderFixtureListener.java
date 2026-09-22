package app.umbra.media;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

import org.webrtc.*;
import static org.junit.Assert.*;

/** Camera2 provider integration only. Not an Engine call, codec test or physical camera claim. */
public final class CameraProviderFixtureListener extends RunListener {
    @Override public void testRunStarted(Description ignored) throws Exception {
        String mode=InstrumentationRegistry.getArguments().getString("cameraCase");
        if("denied".equals(mode))deniedPermissionDoesNotCreateCapturer();
        else if("capture".equals(mode))explicitSyntheticCameraCaptureSwitchAndClose();
        else throw new AssertionError("Explicit provider test case required");
        var status=new android.os.Bundle();status.putString("cameraProvider", "PASS "+mode+"; synthetic AVD Camera2 only");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    private void explicitSyntheticCameraCaptureSwitchAndClose() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        assertTrue("Only disposable AOSP AVD cameras",Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"));
        assertEquals("Host must explicitly grant camera for this provider test",PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.CAMERA));
        NativeVoiceSession.initialize(context);
        var factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
        var authorized=new AtomicBoolean(true);var failed=new AtomicBoolean();var frames=new AtomicInteger();
        var dimensions=new AtomicBoolean();var rotations=new AtomicBoolean(true);
        NativeVideoCapture capture=null;
        try {
            var camera=NativeVideoCapture.camera(context,true,()->failed.set(true));
            capture=new NativeVideoCapture(context,factory,camera,()->{if(!authorized.get())throw new SecurityException("Synthetic permission lease revoked");},()->failed.set(true));
            capture.track.addSink(frame->{
                int width=frame.getBuffer().getWidth(),height=frame.getBuffer().getHeight();
                dimensions.set(width>0 && width<=320 && height>0 && height<=320);
                if(frame.getRotation()!=0 && frame.getRotation()!=90 && frame.getRotation()!=180 && frame.getRotation()!=270)rotations.set(false);
                frames.incrementAndGet();
            });
            capture.start();awaitFrames(frames,10,failed);
            capture.switchCamera();long deadline=SystemClock.elapsedRealtime()+10000;
            while(capture.completedSwitches==0 && SystemClock.elapsedRealtime()<deadline && !failed.get())Thread.sleep(20);
            assertEquals(1,capture.completedSwitches);assertFalse("Front to back",capture.lastSwitchFront);
            int before=frames.get();awaitFrames(frames,before+10,failed);
            assertTrue(dimensions.get());assertTrue(rotations.get());
            // Start a second real provider switch, then revoke the local capture lease.
            // This is a provider race, separate from Engine/vault lock acceptance.
            capture.switchCamera();
            long requested=SystemClock.elapsedRealtimeNanos();authorized.set(false);capture.invalidate();capture.close();
            assertTrue(capture.closedNanos>=requested);assertTrue(capture.closedNanos-requested<2_000_000_000L);
            int closedFrames=frames.get();Thread.sleep(1000);assertEquals(closedFrames,frames.get());
            try {capture.start();fail("Closed capture restarted");}catch(SecurityException expected){}
            try {capture.switchCamera();fail("Closed capture switched camera");}catch(SecurityException expected){}
        } finally {if(capture!=null)capture.close();factory.dispose();}
    }
    private void deniedPermissionDoesNotCreateCapturer() {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Separate invocation after host revocation; no alternative camera path.
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA));
        try {NativeVideoCapture.camera(context,true,()->{});fail("Denied camera opened");}catch(SecurityException expected){}
    }
    private static void awaitFrames(AtomicInteger frames,int required,AtomicBoolean failed) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(frames.get()<required && !failed.get() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
        assertFalse("Camera provider reported failure",failed.get());assertTrue("Missing real Camera2 frames",frames.get()>=required);
    }
}
