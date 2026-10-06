package app.umbra.media;

import android.app.Activity;
import android.app.AlertDialog;

/** Cleanup belongs to the dialog lifecycle, independently of external listeners. */
final class MediaDialog extends AlertDialog {
    private Runnable cleanup;
    MediaDialog(Activity activity,Runnable cleanup) {
        super(activity);
        this.cleanup=java.util.Objects.requireNonNull(cleanup);
    }
    @Override protected void onStop() {
        try {
            Runnable release=cleanup;
            cleanup=null; // Exactly once, including reentrant dismissal.
            if(release!=null)release.run();
        } finally { super.onStop(); }
    }
}
