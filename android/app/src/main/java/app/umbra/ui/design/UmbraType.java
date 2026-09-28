package app.umbra.ui.design;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Typographic scale. Sizes are in sp so Android font scaling applies; layouts never fix text
 * heights, so scaled text wraps instead of being clipped.
 */
public enum UmbraType {
    DISPLAY(32, 700, 0f, false, UmbraColors.TEXT_PRIMARY, 1.15f),
    TITLE(22, 600, 0f, false, UmbraColors.TEXT_PRIMARY, 1.2f),
    HEADING(17, 600, 0f, false, UmbraColors.TEXT_PRIMARY, 1.25f),
    BODY(16, 400, 0f, false, UmbraColors.TEXT_PRIMARY, 1.35f),
    BODY_SECONDARY(15, 400, 0f, false, UmbraColors.TEXT_SECONDARY, 1.35f),
    LABEL(15, 600, 0.01f, false, UmbraColors.TEXT_PRIMARY, 1.2f),
    CAPTION(13, 400, 0.01f, false, UmbraColors.TEXT_SECONDARY, 1.3f),
    SECURITY_LABEL(12, 700, 0.08f, false, UmbraColors.TEXT_SECONDARY, 1.2f),
    MONOSPACE(16, 500, 0.04f, true, UmbraColors.TEXT_PRIMARY, 1.5f);

    public final int sp, weight, color; public final float letterSpacing, lineMultiplier; public final boolean mono;

    UmbraType(int sp, int weight, float letterSpacing, boolean mono, int color, float lineMultiplier) {
        this.sp = sp; this.weight = weight; this.letterSpacing = letterSpacing; this.mono = mono; this.color = color; this.lineMultiplier = lineMultiplier;
    }

    public Typeface typeface() {
        return Typeface.create(mono ? Typeface.MONOSPACE : Typeface.create("sans-serif", Typeface.NORMAL), weight, false);
    }

    public void apply(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(typeface());
        view.setLetterSpacing(letterSpacing);
        view.setTextColor(color);
        view.setLineSpacing(0, lineMultiplier);
        view.setIncludeFontPadding(true);
        if (this == SECURITY_LABEL) view.setAllCaps(true);
    }
}
