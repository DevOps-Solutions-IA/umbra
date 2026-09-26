package app.umbra.ui.model;

import java.util.Locale;

/**
 * Presentation mirror of {@code Engine.TrustState}. Values are only ever created from the name the
 * Engine reports ({@link #fromEngine(String)}); the UI never derives trust from its own flags.
 */
public enum TrustLevel {
    UNVERIFIED, VERIFIED, IDENTITY_CHANGED, BLOCKED;

    /** Unknown or missing engine values fail closed: they are never treated as verified. */
    public static TrustLevel fromEngine(String engineState) {
        if (engineState == null) return UNVERIFIED;
        return switch (engineState.toUpperCase(Locale.ROOT)) {
            case "VERIFIED" -> VERIFIED;
            case "IDENTITY_CHANGED" -> IDENTITY_CHANGED;
            case "BLOCKED" -> BLOCKED;
            default -> UNVERIFIED;
        };
    }
}
