package app.umbra.ui.screens;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.Ui;

/**
 * A rendered destination: fixed top chrome, a body and optional fixed bottom chrome (composer,
 * navigation). {@code bodyScrolls} is true when the body virtualizes/scrolls itself (ListView).
 * Screens only receive presentation state and callbacks; they never touch the Engine.
 */
public record Screen(View top, View body, View bottom, boolean bodyScrolls) {
    public static Screen of(View top, View body, View bottom) { return new Screen(top, body, bottom, false); }
    public static Screen list(View top, View body, View bottom) { return new Screen(top, body, bottom, true); }

    /**
     * Production composition shared by the Activity and the rendering tests: fixed top, a body that
     * takes the remaining height (wrapped in a ScrollView unless it scrolls itself) and fixed bottom.
     */
    public LinearLayout compose(Ui ui) {
        LinearLayout root = ui.column(); root.setBackgroundColor(UmbraColors.BACKGROUND_PRIMARY);
        root.setFilterTouchesWhenObscured(true); root.setSaveEnabled(false);
        root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        if (top != null) root.addView(top, Ui.match());
        if (bodyScrolls) root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        else {
            ScrollView scroll = new ScrollView(ui.context()); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        if (bottom != null) root.addView(bottom, Ui.match());
        return root;
    }
}
