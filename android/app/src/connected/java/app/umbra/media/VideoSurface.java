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
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Video del interlocutor")
            .setMessage("Esta vista no autoriza tu cámara. Una imagen anterior no demuestra conexión actual.")
            .setView(layout).setPositiveButton("Cerrar vista",null).create();
        Runnable refresh=new Runnable() {
            public void run() {
                status.setText(session.videoStatus());
                if(session.videoStatus().equals("OFF") || session.state()!=NativeVoiceSession.State.ACTIVE)view.clearImage();
                if(dialog.isShowing())status.postDelayed(this,500);
            }
        };
        dialog.setOnDismissListener(ignored->{session.setRemoteVideoSink(null);status.removeCallbacks(refresh);view.clearImage();view.release();egl.release();});
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);track.accept(dialog);dialog.show();
        session.setRemoteVideoSink(view);refresh.run();
    }
}
