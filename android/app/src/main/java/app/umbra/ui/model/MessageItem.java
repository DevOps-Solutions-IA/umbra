package app.umbra.ui.model;

import java.util.Locale;

/** A rendered message. {@code sender} is only set for incoming group messages. */
public record MessageItem(String id, Kind kind, boolean outgoing, String text, String fileName, String sizeLabel,
                          String time, Delivery delivery, String sender) {
    public enum Kind { TEXT, FILE, IMAGE, LOCATION, CALL_EVENT, SYSTEM }

    /** Delivery state as reported by the engine's message status. */
    public record Delivery(String label, Glyph glyph, Tone tone) {
        public static Delivery ofEngine(String status) {
            if (status == null) return new Delivery("", Glyph.TIMER, Tone.NEUTRAL);
            return switch (status) {
                case "Pendiente" -> new Delivery("Pendiente", Glyph.TIMER, Tone.NEUTRAL);
                case "En cola del servidor" -> new Delivery("En el servidor", Glyph.CLOUD, Tone.NEUTRAL);
                case "Enlace Bluetooth" -> new Delivery("Enviado por Bluetooth", Glyph.BLUETOOTH, Tone.OFFLINE);
                case "Entregado" -> new Delivery("Entregado", Glyph.CHECK, Tone.SUCCESS);
                case "Recibido" -> new Delivery("", Glyph.CHECK, Tone.NEUTRAL);
                default -> new Delivery(status, Glyph.INFO, Tone.NEUTRAL);
            };
        }
        public static Delivery failed() { return new Delivery("No enviado · toca para reintentar", Glyph.RETRY, Tone.DANGER); }
    }

    public static MessageItem text(String id, boolean outgoing, String text, String time, String status, String sender) {
        return new MessageItem(id, Kind.TEXT, outgoing, text, null, null, time, Delivery.ofEngine(status), sender);
    }
    public static MessageItem file(String id, boolean outgoing, String name, int bytes, String time, String status, String sender) {
        return new MessageItem(id, looksLikeImage(name) ? Kind.IMAGE : Kind.FILE, outgoing, null, name, size(bytes), time, Delivery.ofEngine(status), sender);
    }
    public static MessageItem system(String id, String text, String time) {
        return new MessageItem(id, Kind.SYSTEM, false, text, null, null, time, Delivery.ofEngine(null), null);
    }

    static boolean looksLikeImage(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic");
    }
    public static String size(int bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
