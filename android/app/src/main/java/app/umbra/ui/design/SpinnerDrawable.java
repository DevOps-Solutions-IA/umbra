package app.umbra.ui.design;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * Small indeterminate arc for inline operation loaders (inside a busy button, next to a phase label).
 * It never shows a percentage: UMBRA reports phases, not invented progress. With animations disabled the
 * arc is drawn static, so the busy state is still visible (and also spoken by the owning view).
 */
public final class SpinnerDrawable extends Drawable implements Animatable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final ValueAnimator rotation = ValueAnimator.ofFloat(0f, 360f);
    private float angle;

    public SpinnerDrawable(int color, float strokePx) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(strokePx); paint.setColor(color);
        rotation.setDuration(UmbraTokens.MOTION_SPINNER_TURN); rotation.setRepeatCount(ValueAnimator.INFINITE);
        rotation.setInterpolator(new LinearInterpolator());
        rotation.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidateSelf(); });
    }

    @Override public void draw(Canvas canvas) {
        float inset = paint.getStrokeWidth() / 2f;
        oval.set(getBounds()); oval.inset(inset, inset);
        canvas.drawArc(oval, angle - 90f, 270f, false, paint);
    }
    @Override public void start() { if (Motion.enabled() && !rotation.isStarted()) rotation.start(); }
    @Override public void stop() { rotation.cancel(); }
    @Override public boolean isRunning() { return rotation.isRunning(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
