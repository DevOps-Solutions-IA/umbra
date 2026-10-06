package app.umbra.ui.design;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Subtle, non-blocking motion. Follows the system animator scale: when the user disables animations
 * (or Android reports reduced motion through a zero scale) every helper applies the end state at once.
 * Nothing here delays a security operation; animations only decorate a state change already applied.
 */
public final class Motion {
    private Motion() {}

    /** True when the platform allows animators (Settings → Accessibility → Remove animations sets it false). */
    public static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }

    /** Fade + small rise for content that replaces a loader or a previous state. */
    public static void enter(View view) {
        if (view == null) return;
        if (!enabled()) { view.setAlpha(1f); view.setTranslationY(0f); return; }
        view.setAlpha(0f); view.setTranslationY(view.getResources().getDisplayMetrics().density * 6);
        view.animate().alpha(1f).translationY(0f).setDuration(UmbraTokens.MOTION_STANDARD)
            .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Short emphasis for a confirmed result (contact added, verified). Purely visual. */
    public static void confirm(View view) {
        if (view == null || !enabled()) return;
        view.setScaleX(0.92f); view.setScaleY(0.92f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(UmbraTokens.MOTION_EMPHASIS)
            .setInterpolator(new DecelerateInterpolator()).start();
    }
}
