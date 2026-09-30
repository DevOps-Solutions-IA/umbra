package app.umbra.ui.media;

import android.content.Context;
import android.media.AudioDeviceInfo;
import app.umbra.content.RestrictedContentService;
import app.umbra.content.RestrictedRecording;
import app.umbra.crypto.Engine;
import java.util.function.BooleanSupplier;

/**
 * Connected edition: presentation entry point to the domain's foreground restricted note capture.
 * The UI requests RECORD_AUDIO only on a real local action, passes the explicitly selected input and the
 * original send review; the domain enforces permission, consent, the 8 s bound and emergency cancellation.
 */
public final class NoteCapture {
    private NoteCapture() {}
    public static final boolean AVAILABLE = true;
    /** Worker thread only. */
    public static RestrictedContentService.Prepared record(Context context, Engine engine, RestrictedContentService.Review review,
                                                          AudioDeviceInfo input, BooleanSupplier stop) throws Exception {
        return RestrictedRecording.record(context, engine, review, true, input, stop);
    }
}
