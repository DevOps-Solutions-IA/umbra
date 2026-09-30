package app.umbra.ui.model;

import java.util.List;

/**
 * Wording and choices for restricted objects (F01 photo, F02 voice note, F03 file-video, F04 static PDF,
 * F05 "Solo en UMBRA", F06 expiry). Everything shown comes from domain metadata
 * ({@code RestrictedContentService.Status}) or from the playback state; nothing here grants access,
 * counts views or claims that a person saw or heard anything.
 *
 * <p>Pure Java: format and mode are passed by their domain enum names so the model stays JVM-testable.
 */
public final class RestrictedPresentation {
    private RestrictedPresentation() {}

    // ------------------------------------------------------------------ formats (domain: RestrictedPayload.Format)
    public enum Kind {
        PHOTO("PNG", "Foto", Glyph.PHOTO, "JPEG o PNG · hasta 4 MiB"),
        NOTE("AAC_ADTS", "Nota de voz", Glyph.MIC, "AAC 16 kHz mono · hasta 256 KiB"),
        VIDEO("AVC_MP4", "Video", Glyph.VIDEO, "MP4 AVC · hasta 3 s · 320×240"),
        PDF("PDF_PAGES", "PDF", Glyph.FILE, "Hasta 256 KiB · 4 páginas · copia estática");
        public final String format, label, limits; public final Glyph glyph;
        Kind(String format, String label, Glyph glyph, String limits) { this.format = format; this.label = label; this.glyph = glyph; this.limits = limits; }
        /** Unknown or future formats have no presentation: the caller must not offer to open them. */
        public static Kind ofFormat(String format) {
            for (Kind k : values()) if (k.format.equals(format)) return k;
            return null;
        }
        /** MIME filter for the Android picker; the domain re-validates the actual bytes. */
        public String[] mimeTypes() {
            return switch (this) {
                case PHOTO -> new String[]{"image/jpeg", "image/png"};
                case NOTE -> new String[]{"audio/aac", "audio/aacp", "audio/x-aac", "audio/*"};
                case VIDEO -> new String[]{"video/mp4"};
                case PDF -> new String[]{"application/pdf"};
            };
        }
        /** Upper bound read from the picked file before the domain adapter validates it. */
        public int maxInputBytes() { return this == PHOTO ? 4 * 1024 * 1024 : 262_144; }
    }

    // ------------------------------------------------------------------ modes (domain: RestrictedPayload.Mode) — F05
    public static final String ONCE = "ONCE", UMBRA_ONLY = "UMBRA_ONLY";
    public static final String[] MODES = {ONCE, UMBRA_ONLY};
    public static String modeLabel(String mode) { return UMBRA_ONLY.equals(mode) ? "Solo en UMBRA" : "Una vez"; }
    public static String modeDetail(String mode) {
        return UMBRA_ONLY.equals(mode) ? "Se abre en UMBRA hasta que caduque." : "Se consume al abrirse.";
    }

    // ------------------------------------------------------------------ expiry (F06): object vs active session
    /** Object lifetime (domain TTL 60..86400 s). */
    public static final long[] TTL_SECONDS = {600, 3600, 86_400};
    public static final String[] TTL_LABELS = {"10 min", "1 h", "24 h"};
    /** Active session limit (domain 1..60 s). */
    public static final long[] SESSION_SECONDS = {10, 30, 60};
    public static final String[] SESSION_LABELS = {"10 s", "30 s", "60 s"};
    public static final int DEFAULT_TTL = 1, DEFAULT_SESSION = 1;

    public record Choice(String mode, int ttlIndex, int sessionIndex) {
        public long ttlSeconds() { return TTL_SECONDS[ttlIndex]; }
        public long sessionSeconds() { return SESSION_SECONDS[sessionIndex]; }
        /** Lines of the confirmation shown before the domain review is used to prepare and send. */
        public List<String[]> summary(String recipient, Kind kind) {
            return List.of(
                new String[]{"Para", recipient},
                new String[]{"Contenido", kind.label + " protegido"},
                new String[]{"Modo", modeLabel(mode)},
                new String[]{"Caduca en", TTL_LABELS[ttlIndex]},
                new String[]{"Sesión", "hasta " + SESSION_LABELS[sessionIndex]});
        }
    }
    public static Choice defaultChoice() { return new Choice(ONCE, DEFAULT_TTL, DEFAULT_SESSION); }

    /** What a successful {@code send} means: committed to the encrypted outbox, nothing more. */
    public static final String SENT = "En cola cifrada. No indica entrega.";
    public static final String NOT_SENT = "No enviado. Nada se reintentó.";

    // ------------------------------------------------------------------ received objects
    public record Received(String id, Kind kind, String mode, String state, Tone tone, boolean canOpen, String detail, String expires) {}

    /**
     * @param format   domain format name
     * @param expired  domain-reported object expiry
     * @param consumed domain-reported persistent consumption (ONCE)
     * @param expiresAtLabel formatted object deadline
     */
    public static Received received(String id, String format, String mode, boolean consumed, boolean expired, String expiresAtLabel) {
        Kind kind = Kind.ofFormat(format);
        String state; Tone tone; boolean open;
        if (kind == null) { state = "No admitido"; tone = Tone.DANGER; open = false; }
        else if (expired) { state = "Caducado"; tone = Tone.NEUTRAL; open = false; }
        else if (consumed) { state = "Ya abierto"; tone = Tone.NEUTRAL; open = false; }
        else { state = "Disponible"; tone = Tone.ACCENT; open = true; }
        String detail = modeLabel(mode) + (expired ? "" : " · caduca " + expiresAtLabel);
        return new Received(id, kind, mode, state, tone, open, detail, expiresAtLabel);
    }

    /** Confirmation before a persistent open: ONCE is consumed before anything is shown. */
    public static String openWarning(String mode) {
        return UMBRA_ONLY.equals(mode) ? "Se abre dentro de UMBRA. No se exporta." : "Se consume al abrir. No se podrá abrir otra vez.";
    }

    // ------------------------------------------------------------------ playback (domain: RestrictedPlayback.State)
    /** Observed player state. COMPLETED is "Terminó", never "escuchado" or "visto". */
    public static String playbackLabel(String state) {
        return switch (state == null ? "" : state) {
            case "READY" -> "Listo";
            case "ROUTING" -> "Confirmando salida…";
            case "PLAYING" -> "Reproduciendo";
            case "COMPLETED" -> "Terminó";
            case "INTERRUPTED" -> "Interrumpido";
            case "FAILED" -> "Falló";
            case "CLOSED" -> "Cerrado";
            default -> "Sin estado";
        };
    }
    public static boolean playbackTerminal(String state) {
        return switch (state == null ? "" : state) { case "READY", "ROUTING", "PLAYING" -> false; default -> true; };
    }
    public static final String SESSION_ENDED = "Sesión terminada.";
    public static final String CLOSURE_UNCONFIRMED = "Cierre no confirmado.";
}
