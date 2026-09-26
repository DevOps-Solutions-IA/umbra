package app.umbra.ui.screens;

import android.view.View;

/**
 * A rendered destination: fixed top chrome, a body and optional fixed bottom chrome (composer,
 * navigation). {@code bodyScrolls} is true when the body virtualizes/scrolls itself (ListView).
 * Screens only receive presentation state and callbacks; they never touch the Engine.
 */
public record Screen(View top, View body, View bottom, boolean bodyScrolls) {
    public static Screen of(View top, View body, View bottom) { return new Screen(top, body, bottom, false); }
    public static Screen list(View top, View body, View bottom) { return new Screen(top, body, bottom, true); }
}
