package app.umbra.ui.model;

import java.util.Locale;

/**
 * Video state as reported by the media engine ({@code NativeVoiceSession.videoStatus()}) plus the
 * camera capture evidence (last captured frame age). Consent is per direction and never implied.
 */
public record VideoPresentation(String remote, Tone remoteTone, boolean remoteVisible, String camera, Tone cameraTone, boolean cameraTransmitting) {
    /** Captured frame considered live within this window. */
    public static final long CAMERA_FRESH_NANOS = 2_000_000_000L;

    public static VideoPresentation of(String videoStatus, long nowNanos, long lastCaptureNanos, long closedNanos) {
        String v = videoStatus == null ? "OFF" : videoStatus.toUpperCase(Locale.ROOT);
        boolean capturing = lastCaptureNanos > 0 && lastCaptureNanos > closedNanos && nowNanos - lastCaptureNanos < CAMERA_FRESH_NANOS;
        String remote; Tone tone; boolean visible;
        switch (v) {
            case "ACTIVE" -> { remote = "Video recibido"; tone = Tone.SUCCESS; visible = true; }
            case "WAITING_FOR_FRAME" -> { remote = "Esperando imagen…"; tone = Tone.NEUTRAL; visible = false; }
            case "STALE" -> { remote = "Video en pausa o conexión inestable"; tone = Tone.WARNING; visible = true; }
            case "NEGOTIATING" -> { remote = "Preparando video…"; tone = Tone.NEUTRAL; visible = false; }
            case "CAMERA_UNAVAILABLE" -> { remote = "Cámara no disponible · el audio continúa"; tone = Tone.WARNING; visible = false; }
            default -> { remote = "Video desactivado"; tone = Tone.NEUTRAL; visible = false; }
        }
        return new VideoPresentation(remote, tone, visible,
            capturing ? "Tu cámara está transmitiendo" : "Tu cámara no transmite",
            capturing ? Tone.ACCENT : Tone.NEUTRAL, capturing);
    }

    public static final String CONSENT_REJECT = "Rechazar";
    public static final String CONSENT_RECEIVE = "Permitir recibir";
    public static final String CONSENT_SHARE = "Compartir mi cámara";
    public static String requestHeadline(String alias) { return alias + " solicita activar video."; }
}
