package app.umbra.ui.model;

import java.util.Map;

/**
 * Maps the fixed status sentences emitted by the Bluetooth link and the location capture to the short
 * on-screen labels of the clean interface. Unknown or non-Spanish text never reaches the screen verbatim.
 * Display only: the transport and capture logic are unchanged.
 */
public final class ShortStatus {
    private ShortStatus() {}

    private static final Map<String, String> TRANSPORT = Map.of(
        "Bluetooth: enlace cerrado por tiempo límite", "Tiempo agotado",
        "Bluetooth: esperando vinculación explícita", "Esperando vínculo",
        "Bluetooth: esperando contacto verificado", "Esperando contacto",
        "Bluetooth: escucha finalizada", "Escucha finalizada",
        "Bluetooth: conexión fallida", "Conexión fallida",
        "Clave del dispositivo comprobada · verifica el código del contacto", "Clave comprobada · verifica el código",
        "Bluetooth: contacto verificado conectado", "Contacto conectado",
        "Bluetooth: enlace cerrado o paquete no aceptado", "Enlace cerrado");

    private static final Map<String, String> LOCATION = Map.of(
        "Ubicación activa; entrega sujeta a conexión", "Compartiendo · según conexión",
        "Punto cifrado en cola", "Punto en cola",
        "Captura visible activa; esperando medición", "Midiendo…",
        "Ubicación interrumpida; requiere nuevo consentimiento", "Interrumpida · autoriza de nuevo");

    public static final int MAX = 40;

    public static String transport(String text) { return map(TRANSPORT, text, "Cercanía"); }
    public static String location(String text) { return map(LOCATION, text, "Compartiendo"); }

    private static String map(Map<String, String> known, String text, String fallback) {
        if (text == null) return fallback;
        String k = known.get(text);
        if (k != null) return k;
        return SpanishText.isSpanish(text) && text.length() <= MAX ? text : fallback;
    }
}
