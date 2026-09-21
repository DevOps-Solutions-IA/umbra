package app.umbra.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.*;

/** Owns capture resources only; authorization and the peer connection belong to NativeVoiceSession. */
final class NativeVideoCapture implements AutoCloseable {
    interface Check { void run() throws Exception; }
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final VideoCapturer capturer;
    private final VideoSource source;
    final VideoTrack track;
    private EglBase egl;
    private SurfaceTextureHelper helper;
    private final Check check;
    private final Runnable onFailure;
    volatile long lastCaptureNanos, stopRequestedNanos, closedNanos;
    volatile int completedSwitches;volatile boolean lastSwitchFront;
    private boolean disposed;

    static VideoCapturer camera(Context context,boolean front,Runnable failure) {
        if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Camera permission required; review consent again after the permission dialog");
        Camera2Enumerator cameras=new Camera2Enumerator(context);
        String chosen=null;
        for(String name:cameras.getDeviceNames()) if(front?cameras.isFrontFacing(name):cameras.isBackFacing(name)) { chosen=name;break; }
        if(chosen==null) throw new IllegalStateException("Requested camera unavailable");
        return cameras.createCapturer(chosen,new CameraVideoCapturer.CameraEventsHandler() {
            public void onCameraError(String ignored) { failure.run(); }
            public void onCameraDisconnected() { failure.run(); }
            public void onCameraFreezed(String ignored) { failure.run(); }
            public void onCameraOpening(String ignored) {}
            public void onFirstFrameAvailable() {}
            public void onCameraClosed() {}
        });
    }
    NativeVideoCapture(Context context,PeerConnectionFactory factory,VideoCapturer capturer,Check check,Runnable failure) throws Exception {
        this.capturer=capturer;this.check=check;this.onFailure=failure;
        source=factory.createVideoSource(false);
        track=factory.createVideoTrack("umbra-video",source);track.setEnabled(false);
        try {
            check.run(); egl=EglBase.create(); helper=SurfaceTextureHelper.create("umbra-camera",egl.getEglBaseContext());
            if(helper==null) throw new IllegalStateException("Video surface unavailable");
            CapturerObserver delegate=source.getCapturerObserver();
            capturer.initialize(helper,context,new CapturerObserver() {
                public void onCapturerStarted(boolean success) {
                    if(stopped.get()) return;
                    if(!success) { invalidate();onFailure.run();return; }
                    delegate.onCapturerStarted(true);
                }
                public void onCapturerStopped() { delegate.onCapturerStopped(); }
                public void onFrameCaptured(VideoFrame frame) {
                    lastCaptureNanos=SystemClock.elapsedRealtimeNanos();
                    if(stopped.get()) return;
                    try {
                        check.run();
                        if(stopped.get()) return;
                        // No application queue/copy/history. WebRTC owns adaptation and encoding.
                        delegate.onFrameCaptured(frame);
                    } catch(Exception invalid) { invalidate();onFailure.run(); }
                }
            });
        } catch(Exception invalid) { close();throw invalid; }
    }
    synchronized void start() throws Exception {
        check.run();if(stopped.get()) throw new SecurityException("Video capture cancelled");
        source.adaptOutputFormat(320,240,15);
        track.setEnabled(true);capturer.startCapture(320,240,15);
        if(stopped.get()) close();
    }
    synchronized void switchCamera() throws Exception {
        check.run();if(stopped.get() || !(capturer instanceof CameraVideoCapturer camera)) throw new SecurityException("Camera not active");
        camera.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            public void onCameraSwitchDone(boolean front) { lastSwitchFront=front;completedSwitches++;if(stopped.get())onFailure.run(); }
            public void onCameraSwitchError(String ignored) { invalidate();onFailure.run(); }
        });
    }
    /** Synchronous gate; actual camera stop/disposal runs on the owning media worker. */
    void invalidate() { if(stopped.compareAndSet(false,true)) stopRequestedNanos=SystemClock.elapsedRealtimeNanos(); }
    @Override public synchronized void close() {
        invalidate();if(disposed)return;disposed=true;
        try { track.setEnabled(false); }
        finally {
            try { capturer.stopCapture(); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();onFailure.run(); }
            finally {
                try {capturer.dispose();}
                finally {try {track.dispose();} finally {try {source.dispose();} finally {
                    try {if(helper!=null)helper.dispose();} finally {if(egl!=null)egl.release();closedNanos=SystemClock.elapsedRealtimeNanos();}
                }}}
            }
        }
    }
}
