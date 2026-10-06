package app.umbra.privacy;

import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

/** Nonvisual adapter; caller MUST invoke before setContentView/show/surface attachment. */
public final class PrivateAndroidSurface {
    private PrivateAndroidSurface() {}
    public static void protect(Activity owner) {
        protect(owner.getWindow());
        if(Build.VERSION.SDK_INT>=33) owner.setRecentsScreenshotEnabled(false);
    }
    public static void protect(Window window) {
        java.util.Objects.requireNonNull(window).addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setHideOverlayWindows(true);
    }
    public static void protect(SurfaceView surface) { surface.setSecure(true); }
    /** Does not grant permission, generate an intent, start networking or post a notification. */
    public static Notification notification(Context context,String channel,int icon) {
        return new Notification.Builder(context,channel).setSmallIcon(icon)
                .setContentTitle("UMBRA").setContentText("Actividad privada")
                .setVisibility(Notification.VISIBILITY_SECRET).setLocalOnly(true)
                .setShowWhen(false).setOnlyAlertOnce(true).build();
    }
    /** Caller still owns and clears editable text on pause/lock; no auto-save/restore allowed. */
    public static void sensitiveInput(EditText input) {
        input.setSaveEnabled(false); input.setFreezesText(false);
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImportantForContentCapture(android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(input.getImeOptions()|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    }
}
