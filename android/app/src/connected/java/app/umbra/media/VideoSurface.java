package app.umbra.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;

/** Minimal foreground remote surface, no snapshots or persistence. Owned by the tracked dialog. */
final class VideoSurface {
    private VideoSurface() {}
    static void show(Activity activity,NativeVoiceSession session,Consumer<Dialog> track) {
        EglBase egl=EglBase.create();SurfaceViewRenderer view=new SurfaceViewRenderer(activity);
        view.init(egl.getEglBaseContext(),null);
        TextView status=new TextView(activity);LinearLayout layout=new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);layout.addView(status);
        layout.addView(view,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,480));
        Runnable[] refreshHolder=new Runnable[1];
        var released=new java.util.concurrent.CompletableFuture<Void>();
        app.umbra.core.EmergencyLock.Registration[] registration=new app.umbra.core.EmergencyLock.Registration[1];
        MediaDialog dialog=new MediaDialog(activity,()->{
            session.setRemoteVideoSink(null);
            if(refreshHolder[0]!=null)status.removeCallbacks(refreshHolder[0]);
            try {
                try {view.clearImage();} finally {try {view.release();} finally {egl.release();}}
                released.complete(null);
            } catch(RuntimeException failure) {released.completeExceptionally(new IllegalStateException("Video surface closure failed"));}
            finally {if(!released.isCompletedExceptionally() && registration[0]!=null)registration[0].close();}
        });
        dialog.setTitle("Video del interlocutor");
        dialog.setMessage("Esta vista no autoriza tu cámara. Una imagen anterior no demuestra conexión actual.");
        dialog.setView(layout);dialog.setButton(Dialog.BUTTON_POSITIVE,"Cerrar vista",(android.content.DialogInterface.OnClickListener)null);
        Runnable refresh=new Runnable() {
            public void run() {
                String current=session.videoStatus();status.setText(current);
                if((!current.equals("ACTIVE") && !current.equals("STALE")) || session.state()!=NativeVoiceSession.State.ACTIVE)view.clearImage();
                if(dialog.isShowing())status.postDelayed(this,500);
            }
        };
        refreshHolder[0]=refresh;
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(session.emergency()!=null) {
            try {registration[0]=session.emergency().register(app.umbra.core.EmergencyLock.Subsystem.MEDIA,()->{
                activity.runOnUiThread(dialog::dismiss);return released.thenApply(value->value);
            });} catch(RuntimeException denied) {view.release();egl.release();throw denied;}
        }
        track.accept(dialog);dialog.show();
        session.setRemoteVideoSink(view);refresh.run();
    }
}
