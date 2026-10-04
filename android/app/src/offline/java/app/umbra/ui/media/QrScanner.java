package app.umbra.ui.media;

import android.content.Context;
import android.view.TextureView;

/**
 * Offline edition: no camera. The edition declares no CAMERA permission and the UI shows no scan entry point;
 * pairing invitations are exchanged without camera acquisition. This stub never touches camera APIs.
 */
public final class QrScanner implements AutoCloseable {
    public static final boolean AVAILABLE = false;

    public enum Failure { PERMISSION_DENIED, CAMERA_UNAVAILABLE, NOT_A_PAIRING_CODE }

    /** Callbacks are delivered on the MAIN thread. decoded() is delivered AT MOST ONCE per scanner instance. */
    public interface Listener { void decoded(String signedInvitation); void failed(Failure failure); }

    private QrScanner() {}

    /** Always throws: the camera is not part of the offline edition. */
    public static QrScanner open(Context context, TextureView preview, Listener listener) {
        throw new SecurityException("Camera is not part of the offline edition");
    }

    @Override public void close() {}

    public boolean isClosed() { return true; }
}
