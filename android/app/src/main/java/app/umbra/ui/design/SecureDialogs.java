package app.umbra.ui.design;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import app.umbra.R;
import java.util.function.Consumer;

/**
 * Dialogs and bottom sheets that inherit the screen protections: FLAG_SECURE (no screenshots or
 * Recents thumbnail), obscured-touch filtering, and registration with the Activity so locking
 * dismisses them. Confirmation of destructive actions is always explicit.
 */
public final class SecureDialogs {
    private SecureDialogs() {}

    public static void protect(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            w.setHideOverlayWindows(true);
        }
    }
    public static void show(Dialog dialog, Consumer<Dialog> track) {
        protect(dialog);
        if (track != null) track.accept(dialog);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) w.getDecorView().setFilterTouchesWhenObscured(true);
    }

    public static AlertDialog confirm(Activity activity, Consumer<Dialog> track, String title, String message,
                                      String confirmLabel, boolean destructive, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(confirmLabel, (d, w) -> onConfirm.run()).create();
        show(dialog, track);
        if (destructive) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UmbraColors.DANGER);
        return dialog;
    }

    /** Bottom sheet with rounded top, scrollable content and a drag-free close (back or outside tap). */
    public static Dialog sheet(Activity activity, Consumer<Dialog> track, View content) {
        Ui ui = new Ui(activity);
        Dialog dialog = new Dialog(activity, R.style.Theme_Umbra_Sheet);
        LinearLayout frame = ui.column();
        GradientDrawable bg = new GradientDrawable(); bg.setColor(UmbraColors.SURFACE);
        float r = ui.dp(24); bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        frame.setBackground(bg);
        frame.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(24));
        View handle = new View(activity); handle.setBackground(ui.shape(UmbraColors.OUTLINE, 3));
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)); hp.gravity = Gravity.CENTER_HORIZONTAL; hp.bottomMargin = ui.dp(14);
        frame.addView(handle, hp);
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        frame.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(frame);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        show(dialog, track);
        return dialog;
    }
}
