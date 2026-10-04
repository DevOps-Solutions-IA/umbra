package app.umbra.ui.design;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.Button;
import app.umbra.ui.model.Glyph;

/**
 * Framework Button that remembers its idle label so it can enter a real busy state: disabled, a phase
 * label ("Abriendo…") and an inline spinner, with double taps ignored. Leaving busy restores the label.
 * The busy state is presentation of an operation that the domain is running; it never decides anything.
 */
public final class UmbraButton extends Button {
    final String label; final Glyph glyph;
    int foreground;
    boolean busy;
    private SpinnerDrawable spinner;

    UmbraButton(Context context, String label, Glyph glyph) { super(context); this.label = label; this.glyph = glyph; }

    public boolean isBusy() { return busy; }

    void enterBusy(String phase, Drawable idleIcon, int sizePx) {
        if (busy) { setText(phase); setContentDescription(phase); return; }
        busy = true;
        setEnabled(false);
        setText(phase); setContentDescription(phase); setStateDescription("En curso");
        setAlpha(UmbraTokens.OPACITY_BUSY_LABEL);
        spinner = new SpinnerDrawable(foreground, Math.max(2f, sizePx / 9f));
        spinner.setBounds(0, 0, sizePx, sizePx);
        setCompoundDrawablesRelative(spinner, null, null, null);
        spinner.start();
    }

    void exitBusy(Drawable idleIcon) {
        if (!busy) return;
        busy = false;
        if (spinner != null) { spinner.stop(); spinner = null; }
        setText(label); setContentDescription(null); setStateDescription(null);
        setAlpha(1f); setEnabled(true);
        setCompoundDrawablesRelative(idleIcon, null, null, null);
    }

    @Override protected void onDetachedFromWindow() {
        if (spinner != null) spinner.stop();
        super.onDetachedFromWindow();
    }
}
