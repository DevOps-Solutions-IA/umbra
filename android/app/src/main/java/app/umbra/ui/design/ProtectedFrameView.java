package app.umbra.ui.design;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Presentation-owned frame for restricted image/PDF pages. The domain decoder draws into a Bitmap owned by
 * this view (on a worker, inside the authorized session); the view only shows that last frame and erases it
 * on {@link #clear()} — the codec cannot erase a Canvas it does not own. The window is already protected
 * (FLAG_SECURE) by the Activity before any frame exists.
 *
 * <p>Zoom and pan change only the destination rectangle requested for the next render inside the SAME session;
 * they never open the object again.
 */
public final class ProtectedFrameView extends View {
    public interface ViewportListener { void viewportChanged(float scale, float offsetX, float offsetY); }

    public static final float MAX_SCALE = 4f;
    private Bitmap frame;
    private float scale = 1f, offsetX, offsetY;
    private ViewportListener listener;
    private final ScaleGestureDetector pinch;
    private float lastX, lastY; private boolean dragging;

    public ProtectedFrameView(Context context) {
        super(context);
        setBackgroundColor(UmbraColors.BACKGROUND_TERTIARY);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setImportantForContentCapture(IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        setSaveEnabled(false);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(1f, Math.min(MAX_SCALE, scale * d.getScaleFactor())); clamp(); notifyViewport(); return true;
            }
        });
    }

    public void setViewportListener(ViewportListener listener) { this.listener = listener; }
    public float scale() { return scale; }
    public float offsetX() { return offsetX; }
    public float offsetY() { return offsetY; }
    public void resetViewport() { scale = 1f; offsetX = 0; offsetY = 0; }

    /** Destination rectangle for a frame of {@code width}×{@code height} under the current zoom/pan. */
    public static Rect destination(int width, int height, float scale, float offsetX, float offsetY) {
        int w = Math.round(width * scale), h = Math.round(height * scale);
        int left = Math.round((width - w) / 2f + offsetX), top = Math.round((height - h) / 2f + offsetY);
        return new Rect(left, top, left + w, top + h);
    }

    /** Takes ownership of a frame rendered for this view; the previous frame is erased first. UI thread. */
    public void show(Bitmap rendered) {
        Bitmap old = frame; frame = rendered;
        if (old != null && old != rendered) { old.eraseColor(Color.TRANSPARENT); old.recycle(); }
        invalidate();
    }

    /** Erases and releases the last frame immediately (pause, lock, emergency, expiry). UI thread. */
    public void clear() {
        Bitmap old = frame; frame = null;
        if (old != null) { old.eraseColor(Color.TRANSPARENT); old.recycle(); }
        resetViewport(); invalidate();
    }
    public boolean hasFrame() { return frame != null; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap f = frame;
        if (f != null && !f.isRecycled()) canvas.drawBitmap(f, 0, 0, null);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> { lastX = e.getX(); lastY = e.getY(); dragging = true; }
            case MotionEvent.ACTION_MOVE -> {
                if (dragging && !pinch.isInProgress() && scale > 1f) {
                    offsetX += e.getX() - lastX; offsetY += e.getY() - lastY; clamp(); notifyViewport();
                }
                lastX = e.getX(); lastY = e.getY();
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false;
            default -> { }
        }
        return true;
    }
    private void clamp() {
        float maxX = getWidth() * (scale - 1f) / 2f, maxY = getHeight() * (scale - 1f) / 2f;
        offsetX = Math.max(-maxX, Math.min(maxX, offsetX)); offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
    }
    private void notifyViewport() { if (listener != null) listener.viewportChanged(scale, offsetX, offsetY); }
}
