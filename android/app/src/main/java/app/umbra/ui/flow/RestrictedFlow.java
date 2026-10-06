package app.umbra.ui.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import app.umbra.content.RestrictedAudio;
import app.umbra.content.RestrictedContentService;
import app.umbra.content.RestrictedDocuments;
import app.umbra.content.RestrictedImages;
import app.umbra.content.RestrictedPayload;
import app.umbra.content.RestrictedPlayback;
import app.umbra.content.RestrictedVideo;
import app.umbra.crypto.Engine;
import app.umbra.ui.design.ProtectedFrameView;
import app.umbra.ui.model.RestrictedPresentation.Kind;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Worker-side bridge to the restricted content service (contract UI_SECURITY_CONTENT_API_V1, F01–F06).
 * The UI only requests, presents and closes: the domain reviews, consumes, authorizes, decodes and expires.
 * No method returns plaintext bytes, a URI or a file; restricted objects are never exported, shared or copied.
 * Every method here runs on the Activity worker, never on the UI thread (storage/codec work).
 */
public final class RestrictedFlow {
    private RestrictedFlow() {}

    // ------------------------------------------------------------------ send: reviewSend -> prepare -> send
    /** One exact verified/admitted recipient; the review lasts 60 s and is invalidated by lock. */
    public static RestrictedContentService.Review reviewSend(Engine engine, String peer, String mode, long ttlSeconds, long sessionSeconds) throws Exception {
        return engine.restricted().reviewSend(peer, RestrictedPayload.Mode.valueOf(mode), ttlSeconds, sessionSeconds);
    }

    /**
     * Prepares the caller-owned bytes with the format adapter and sends with the SAME review. The input is
     * erased afterwards; a Prepared that was not consumed by {@code send} is closed (its closure is domain-owned).
     * Success means "committed to the encrypted outbox", never delivered or opened. Never retried automatically.
     */
    public static String prepareAndSend(Context context, Engine engine, RestrictedContentService.Review review, Kind kind, byte[] input) throws Exception {
        RestrictedContentService.Prepared prepared = null;
        try {
            prepared = switch (kind) {
                case PHOTO -> RestrictedImages.prepare(engine, review, input, true);
                case NOTE -> RestrictedAudio.prepare(engine, review, input, true);
                case VIDEO -> RestrictedVideo.prepare(context, engine, review, input, true);
                case PDF -> RestrictedDocuments.prepare(context, engine, review, input, true);
            };
            return send(engine, review, prepared);
        } finally {
            Arrays.fill(input, (byte) 0);
            if (prepared != null) prepared.close(); // idempotent; send already transferred ownership
        }
    }
    /** Sends an already prepared object (capture); ownership transfers even on failure. */
    public static String send(Engine engine, RestrictedContentService.Review review, RestrictedContentService.Prepared prepared) throws Exception {
        try { return engine.restricted().send(review, prepared, true); }
        finally { prepared.close(); }
    }

    // ------------------------------------------------------------------ receive: reviewOpen -> open -> present -> close
    public static RestrictedContentService.Review reviewOpen(Engine engine, String id) throws Exception { return engine.restricted().reviewOpen(id); }

    /** One open presentation: at most one Session and one decoder/player, owned until {@link #close()}. */
    public static final class Viewer implements AutoCloseable {
        private final RestrictedContentService.Session session;
        public final Kind kind;
        private RestrictedImages.Decoder image;
        private RestrictedDocuments.Decoder pages;
        private volatile RestrictedPlayback playback;
        private int pageCount = 1;
        private Viewer(RestrictedContentService.Session session, Kind kind) { this.session = session; this.kind = kind; }

        /**
         * Persistent open with the person's fresh consent. ONCE is consumed BEFORE anything is presented;
         * a later decoder failure does not restore the opening (domain contract). Image/PDF decoders are
         * created here; audio/video players need an explicit route/surface first.
         */
        public static Viewer open(Engine engine, RestrictedContentService.Review review) throws Exception {
            RestrictedContentService.Session session = engine.restricted().open(review, true);
            Viewer viewer = null;
            try {
                Kind kind = Kind.ofFormat(session.format().name());
                if (kind == null) throw new app.umbra.content.ContentException(app.umbra.content.ContentException.Code.INVALID);
                viewer = new Viewer(session, kind);
                if (kind == Kind.PHOTO) viewer.image = new RestrictedImages.Decoder(session);
                else if (kind == Kind.PDF) { viewer.pages = new RestrictedDocuments.Decoder(session); viewer.pageCount = viewer.pages.pageCount(); }
                return viewer;
            } catch (Exception | Error failure) { session.close(); throw failure; }
        }

        public int pageCount() { return pageCount; }

        /**
         * Renders the current page into a NEW presentation-owned bitmap for a view of {@code width}×{@code height}
         * with the given zoom/pan. Navigation and zoom stay inside this session. The caller owns the bitmap and
         * must erase it on invalidation ({@link ProtectedFrameView#clear()}).
         */
        public Bitmap render(int page, int width, int height, float scale, float offsetX, float offsetY) throws Exception {
            if (width < 1 || height < 1) throw new IllegalArgumentException("Empty frame");
            Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(frame);
                android.graphics.Rect target = ProtectedFrameView.destination(width, height, scale, offsetX, offsetY);
                if (image != null) image.render(canvas, target);
                else if (pages != null) pages.render(page, canvas, target);
                else throw new IllegalStateException("No visual decoder");
                return frame;
            } catch (Exception | Error failure) { frame.eraseColor(Color.TRANSPARENT); frame.recycle(); throw failure; }
        }

        /** Voice note: explicit selected sink, one-shot start, no seek/repeat, no speaker fallback. */
        public void startAudio(Context context, AudioDeviceInfo sink) throws Exception {
            if (kind != Kind.NOTE || playback != null) throw new IllegalStateException("Not an audio note");
            RestrictedPlayback p = new RestrictedPlayback(context, session, sink);
            playback = p; p.start();
        }
        /** File video on a protected caller-owned surface (ownership transfers); a silent clip may pass a null sink. */
        public void startVideo(Context context, AudioDeviceInfo sink, RestrictedPlayback.VideoOutput output) throws Exception {
            if (kind != Kind.VIDEO || playback != null) { try { output.close(); } catch (Exception ignored) { /* already released */ } throw new IllegalStateException("Not a video"); }
            RestrictedPlayback p = new RestrictedPlayback(context, session, sink, output);
            playback = p; p.start();
        }
        /** Observed state name, or null before a player exists. Not a human-attention receipt. */
        public String playbackState() { RestrictedPlayback p = playback; return p == null ? null : p.state().name(); }
        /** Zero until the native rendering-start callback; a technical observation only. */
        public long firstVideoFrameNanos() { RestrictedPlayback p = playback; return p == null ? 0 : p.firstVideoFrameNanos(); }

        /** Worker-side validity probe; the domain closes the session itself when it expired or was denied. */
        public void check() throws Exception { session.check(); }

        /**
         * Immediate denial; decoder/player resources are registered with the session and released on the domain
         * cleanup worker. Closing the session directly never waits on a decoder monitor held by a worker render.
         * Idempotent; safe on the UI thread.
         */
        @Override public void close() { session.close(); }
        /** Separate confirmation of cleanup; a failure stays denied and never reopens anything. */
        public CompletionStage<Void> closure() { return session.closure(); }
    }

    /** Completed stage for UI code paths that had nothing open. */
    public static CompletionStage<Void> nothing() { return CompletableFuture.completedFuture(null); }
}
