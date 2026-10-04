package app.umbra.ui.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import app.umbra.pairing.PairingException;
import app.umbra.pairing.PairingQrCodec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connected edition: camera scanner for the pairing invitation QR.
 *
 * <p>Privacy guarantees: frames are only held in memory long enough to copy the luminance (Y) plane into a
 * single reused buffer that is wiped on close; nothing is written to disk, no thumbnail or capture file is
 * produced, nothing is logged (neither image data nor the decoded payload), the torch is never used, and the
 * decoded invitation is handed to the listener exactly once and not retained by the scanner. The camera is
 * released as soon as the first valid invitation is decoded.
 *
 * <p>Threading: {@link #open} and {@link #close} run on the main thread; camera callbacks and frame decoding
 * run on a dedicated {@code umbra-qr-scanner} HandlerThread; listener callbacks are posted to the main thread
 * and are suppressed once the scanner is closed.
 */
public final class QrScanner implements AutoCloseable {
    public static final boolean AVAILABLE = true;

    public enum Failure { PERMISSION_DENIED, CAMERA_UNAVAILABLE, NOT_A_PAIRING_CODE }

    /** Callbacks are delivered on the MAIN thread. decoded() is delivered AT MOST ONCE per scanner instance. */
    public interface Listener { void decoded(String signedInvitation); void failed(Failure failure); }

    private static final int TARGET_W = 1280, TARGET_H = 720, MAX_ANALYSIS_PIXELS = 1_300_000;
    private static final long DECODE_INTERVAL_MS = 150, REJECTION_INTERVAL_MS = 3_000;

    private final Context context;
    private final TextureView preview;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("umbra-qr-scanner");
    private final Handler camera;
    private final AtomicBoolean closed = new AtomicBoolean(), accepted = new AtomicBoolean(),
            failureReported = new AtomicBoolean();
    private final Object lock = new Object();
    // Guarded by lock.
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    // Camera thread only.
    private byte[] luminance;
    private long lastDecode, lastRejection = -REJECTION_INTERVAL_MS;

    private QrScanner(Context context, TextureView preview, Listener listener) {
        this.context = context.getApplicationContext();
        this.preview = preview;
        this.listener = listener;
        thread.start();
        camera = new Handler(thread.getLooper());
    }

    /** Main thread. Opens the back camera into the given TextureView. Throws SecurityException if CAMERA is not granted. */
    public static QrScanner open(Context context, TextureView preview, Listener listener) {
        if (context == null || preview == null || listener == null) throw new IllegalArgumentException();
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Camera permission not granted");
        QrScanner scanner = new QrScanner(context, preview, listener);
        if (preview.isAvailable()) scanner.start();
        else preview.setSurfaceTextureListener(scanner.new PreviewListener());
        return scanner;
    }

    public boolean isClosed() { return closed.get(); }

    /** Idempotent; main thread; closes camera, session, ImageReader and background thread immediately. After close no callback is delivered. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        listener = null;
        if (preview.getSurfaceTextureListener() instanceof PreviewListener) preview.setSurfaceTextureListener(null);
        releaseCamera();
        // The ImageReader and buffer are released on the camera thread so a frame being copied right now never
        // reads from a buffer that was freed underneath it; quitSafely still runs this already-posted task.
        camera.post(this::releaseFrames);
        thread.quitSafely();
    }

    // ---- setup (main thread) ----

    @android.annotation.SuppressLint("MissingPermission")
    private void start() {
        if (closed.get()) return;
        CameraManager manager = context.getSystemService(CameraManager.class);
        try {
            String id = chooseCamera(manager);
            if (id == null) { fail(Failure.CAMERA_UNAVAILABLE); return; }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { fail(Failure.CAMERA_UNAVAILABLE); return; }
            Size analysis = chooseAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (analysis == null) { fail(Failure.CAMERA_UNAVAILABLE); return; }
            Size shown = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class), analysis);
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            boolean continuousAf = afModes != null && Arrays.stream(afModes)
                    .anyMatch(m -> m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) { fail(Failure.CAMERA_UNAVAILABLE); return; }
            texture.setDefaultBufferSize(shown.getWidth(), shown.getHeight());
            main.post(() -> { if (!closed.get()) fitPreview(shown); });
            ImageReader frames = ImageReader.newInstance(analysis.getWidth(), analysis.getHeight(), ImageFormat.YUV_420_888, 2);
            frames.setOnImageAvailableListener(this::onFrame, camera);
            synchronized (lock) {
                if (closed.get()) { frames.close(); return; }
                reader = frames;
                previewSurface = new Surface(texture);
            }
            // Permission was verified in open(); a revocation since then surfaces as SecurityException below.
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException();
            manager.openCamera(id, new DeviceCallback(continuousAf), camera);
        } catch (SecurityException e) {
            fail(Failure.PERMISSION_DENIED);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            fail(Failure.CAMERA_UNAVAILABLE);
        }
    }

    /**
     * Center-crop the landscape camera buffer into the TextureView for the current display rotation, so the
     * preview is neither stretched nor sideways. Presentation only: decoding uses the separate ImageReader.
     */
    private void fitPreview(Size buffer) {
        int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) { preview.post(() -> { if (!closed.get()) fitPreview(buffer); }); return; }
        int rotation = preview.getDisplay() == null ? Surface.ROTATION_0 : preview.getDisplay().getRotation();
        android.graphics.Matrix m = new android.graphics.Matrix();
        float cx = vw / 2f, cy = vh / 2f;
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            android.graphics.RectF view = new android.graphics.RectF(0, 0, vw, vh);
            android.graphics.RectF buf = new android.graphics.RectF(0, 0, buffer.getHeight(), buffer.getWidth());
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(view, buf, android.graphics.Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) vh / buffer.getHeight(), (float) vw / buffer.getWidth());
            m.postScale(scale, scale, cx, cy);
            m.postRotate(90 * (rotation - 2), cx, cy);
        } else {
            // Portrait: the platform already rotates the sensor image; the buffer appears as height x width.
            float contentW = buffer.getHeight(), contentH = buffer.getWidth();
            float scale = Math.max(vw / contentW, vh / contentH);
            m.setScale(contentW * scale / vw, contentH * scale / vh, cx, cy);
            if (rotation == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        }
        preview.setTransform(m);
    }

    private static String chooseCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    /** Closest to 1280x720 with at most 1.3 MP (always far below PairingQrCodec.MAX_PIXELS). */
    private static Size chooseAnalysisSize(Size[] sizes) {
        if (sizes == null) return null;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels <= 0 || pixels > MAX_ANALYSIS_PIXELS || pixels > PairingQrCodec.MAX_PIXELS) continue;
            long score = Math.abs(s.getWidth() - TARGET_W) + Math.abs(s.getHeight() - TARGET_H);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    /** Largest SurfaceTexture size (up to 1080p) with the analysis aspect ratio; the analysis size otherwise. */
    private static Size choosePreviewSize(Size[] sizes, Size analysis) {
        if (sizes == null) return analysis;
        double aspect = (double) analysis.getWidth() / analysis.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > 1920L * 1080) continue;
            if (Math.abs((double) s.getWidth() / s.getHeight() - aspect) > 0.02) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : analysis;
    }

    // ---- camera callbacks (camera thread) ----

    private final class DeviceCallback extends CameraDevice.StateCallback {
        private final boolean continuousAf;
        DeviceCallback(boolean continuousAf) { this.continuousAf = continuousAf; }

        @Override public void onOpened(CameraDevice opened) {
            Surface shown, analysis;
            synchronized (lock) {
                if (closed.get() || accepted.get() || reader == null) { opened.close(); return; }
                device = opened;
                shown = previewSurface;
                analysis = reader.getSurface();
            }
            try {
                opened.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        List.of(new OutputConfiguration(shown), new OutputConfiguration(analysis)),
                        camera::post, new SessionCallback(opened, shown, analysis, continuousAf)));
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                fail(Failure.CAMERA_UNAVAILABLE);
            }
        }
        @Override public void onDisconnected(CameraDevice lost) { lost.close(); fail(Failure.CAMERA_UNAVAILABLE); }
        @Override public void onError(CameraDevice failed, int error) { failed.close(); fail(Failure.CAMERA_UNAVAILABLE); }
    }

    private final class SessionCallback extends CameraCaptureSession.StateCallback {
        private final CameraDevice opened;
        private final Surface shown, analysis;
        private final boolean continuousAf;
        SessionCallback(CameraDevice opened, Surface shown, Surface analysis, boolean continuousAf) {
            this.opened = opened; this.shown = shown; this.analysis = analysis; this.continuousAf = continuousAf;
        }

        @Override public void onConfigured(CameraCaptureSession configured) {
            synchronized (lock) {
                if (closed.get() || accepted.get() || device != opened) { configured.close(); return; }
                session = configured;
            }
            try {
                CaptureRequest.Builder request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(shown);
                request.addTarget(analysis);
                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                if (continuousAf)
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                configured.setRepeatingRequest(request.build(), null, camera);
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                if (!closed.get() && !accepted.get()) fail(Failure.CAMERA_UNAVAILABLE);
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession failed) {
            failed.close();
            fail(Failure.CAMERA_UNAVAILABLE);
        }
    }

    // ---- frame processing (camera thread) ----

    private void onFrame(ImageReader frames) {
        Image image;
        try { image = frames.acquireLatestImage(); } catch (IllegalStateException e) { return; }
        if (image == null) return;
        String invitation = null;
        try {
            if (closed.get() || accepted.get()) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastDecode < DECODE_INTERVAL_MS) return;
            lastDecode = now;
            int width = image.getWidth(), height = image.getHeight();
            if ((long) width * height > MAX_ANALYSIS_PIXELS) return;
            copyLuminance(image.getPlanes()[0], width, height);
            invitation = PairingQrCodec.decodeLuminance(luminance, width, height);
        } catch (PairingException e) {
            switch (e.code()) {
                case INVALID_FORMAT, PAYLOAD_TOO_LARGE -> { /* no QR in this frame */ }
                default -> rejectCode();
            }
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            // Image or reader closed concurrently, or an unexpected plane layout: drop the frame.
        } finally {
            image.close();
        }
        if (invitation != null && accepted.compareAndSet(false, true)) {
            releaseCamera();
            Arrays.fill(luminance, (byte) 0);
            final String result = invitation;
            main.post(() -> {
                Listener target = listener;
                if (!closed.get() && target != null) target.decoded(result);
            });
        }
    }

    /** Copies the Y plane into the reused, tightly packed width*height buffer, honoring row and pixel stride. */
    private void copyLuminance(Image.Plane plane, int width, int height) {
        int size = width * height;
        if (luminance == null || luminance.length != size) {
            if (luminance != null) Arrays.fill(luminance, (byte) 0);
            luminance = new byte[size];
        }
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        for (int row = 0; row < height; row++) {
            int base = row * rowStride;
            if (pixelStride == 1) {
                buffer.position(base);
                buffer.get(luminance, row * width, width);
            } else {
                for (int col = 0, out = row * width; col < width; col++, out++)
                    luminance[out] = buffer.get(base + col * pixelStride);
            }
        }
    }

    /** A QR was read but is not a valid pairing invitation (e.g. the safety-verification QR); keep scanning. */
    private void rejectCode() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRejection < REJECTION_INTERVAL_MS) return;
        lastRejection = now;
        main.post(() -> {
            Listener target = listener;
            if (!closed.get() && !accepted.get() && target != null) target.failed(Failure.NOT_A_PAIRING_CODE);
        });
    }

    // ---- teardown ----

    /** Terminal failure: release the camera now, report once on main, then close. */
    private void fail(Failure failure) {
        if (closed.get() || !failureReported.compareAndSet(false, true)) return;
        releaseCamera();
        main.post(() -> {
            Listener target = listener;
            if (closed.get()) return;
            if (target != null) target.failed(failure);
            close();
        });
    }

    /** Any thread: stops streaming and closes session and device. Idempotent. */
    private void releaseCamera() {
        CameraCaptureSession s;
        CameraDevice d;
        Surface p;
        synchronized (lock) {
            s = session; d = device; p = previewSurface;
            session = null; device = null; previewSurface = null;
        }
        if (s != null) {
            try { s.stopRepeating(); } catch (CameraAccessException | IllegalStateException ignored) { }
            s.close();
        }
        if (d != null) d.close();
        if (p != null) p.release();
    }

    /** Camera thread (posted by close): closes the ImageReader and wipes the luminance buffer. */
    private void releaseFrames() {
        ImageReader r;
        synchronized (lock) { r = reader; reader = null; }
        if (r != null) r.close();
        if (luminance != null) { Arrays.fill(luminance, (byte) 0); luminance = null; }
    }

    private final class PreviewListener implements TextureView.SurfaceTextureListener {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            preview.setSurfaceTextureListener(null);
            start();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    }
}
