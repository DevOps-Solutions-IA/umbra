package app.umbra.ui.model;

import java.util.List;

/**
 * The owner's choice before the engine review. The final confirmation always shows the engine's
 * reviewed recipient/devices, not this draft. Values mirror {@code LocationPayload.Mode} names.
 */
public record LocationShareDraft(Precision precision, boolean live, long durationSeconds) {
    public enum Precision {
        PRECISE("Precisa", "La del sensor"),
        APPROXIMATE("Aproximada", "≈ 1 km"),
        ZONE("Zona", "≈ 10 km"),
        MANUAL("Manual", "Coordenadas escritas, sin GPS");
        public final String label, detail;
        Precision(String label, String detail) { this.label = label; this.detail = detail; }
        /** Name of the matching {@code LocationPayload.Mode}. */
        public String engineMode() { return name(); }
    }
    public static final long[] LIVE_DURATIONS = {900, 3600, 28800};
    public static final long SINGLE_POINT_SECONDS = 120;

    public static LocationShareDraft single(Precision p) { return new LocationShareDraft(p, false, SINGLE_POINT_SECONDS); }
    public static LocationShareDraft live(Precision p, long seconds) {
        if (p == Precision.MANUAL) throw new IllegalArgumentException("Manual points are never live");
        return new LocationShareDraft(p, true, seconds);
    }

    public static String durationLabel(long seconds) {
        if (seconds >= 3600) return (seconds / 3600) + (seconds / 3600 == 1 ? " hora" : " horas");
        return Math.max(1, seconds / 60) + " minutos";
    }

    /** Lines shown in the review: who, for how long, with which precision. */
    public List<String[]> summary(String recipient, int devices) {
        return List.of(
            new String[]{"Para", recipient + (devices > 0 ? " · " + devices + (devices == 1 ? " dispositivo aprobado" : " dispositivos aprobados") : "")},
            new String[]{"Duración", live ? "En vivo · " + durationLabel(durationSeconds) : "Un solo punto (válido " + durationLabel(durationSeconds) + ")"},
            new String[]{"Precisión", precision.label + " · " + precision.detail});
    }
}
