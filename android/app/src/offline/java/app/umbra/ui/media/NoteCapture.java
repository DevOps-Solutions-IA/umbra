package app.umbra.ui.media;

import android.content.Context;
import android.media.AudioDeviceInfo;
import app.umbra.content.RestrictedContentService;
import app.umbra.crypto.Engine;
import java.util.function.BooleanSupplier;

/**
 * Offline edition: no capture. The edition declares no RECORD_AUDIO permission and the UI shows no capture
 * entry point (Feature.RESTRICTED_CAPTURE is NOT_IN_FLAVOR). Import, receive and playback remain available.
 */
public final class NoteCapture {
    private NoteCapture() {}
    public static final boolean AVAILABLE = false;
    public static RestrictedContentService.Prepared record(Context context, Engine engine, RestrictedContentService.Review review,
                                                          AudioDeviceInfo input, BooleanSupplier stop) {
        throw new SecurityException("Capture is not part of the offline edition");
    }
}
