package app.umbra.ui.model;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The interface is Spanish only. Domain and transport messages are often English; they are never shown
 * verbatim unless they pass this check, otherwise the caller shows its own fixed Spanish text.
 * The same word list backs the "no English on screen" tests.
 */
public final class SpanishText {
    private SpanishText() {}

    /** Whole words that must never appear on screen (lower case). Brand/proper nouns like UMBRA, Android, Bluetooth are allowed. */
    public static final Set<String> ENGLISH = Set.of(
        "the", "and", "or", "not", "required", "requires", "unavailable", "available", "failed", "failure", "invalid", "denied",
        "explicit", "consent", "admission", "authority", "mismatch", "locked", "unlock", "vault", "relay", "device", "devices",
        "key", "keys", "missing", "only", "must", "cannot", "already", "pending", "expired", "request", "capacity",
        "consumed", "nearby", "backend", "loading", "retry", "settings", "server", "session", "network", "connect", "connected",
        "disconnect", "offline", "online", "password", "verified", "unverified", "blocked", "enrollment", "credential", "revoked",
        "operation", "cancelled", "unknown", "damaged", "timeout", "please", "ui", "ok");
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");

    /** First forbidden English word found, or null when the text is clean. */
    public static String englishWord(String text) {
        if (text == null) return null;
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (ENGLISH.contains(w)) return w;
        }
        return null;
    }

    public static boolean isSpanish(String text) { return text != null && !text.isBlank() && englishWord(text) == null; }
}
