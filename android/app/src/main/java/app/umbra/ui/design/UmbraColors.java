package app.umbra.ui.design;

/**
 * Semantic color tokens of the UMBRA design system (dark theme is the primary design).
 *
 * <p>Pure Java (no Android imports) so contrast rules are verified by JVM tests. Screens must use
 * these semantic names; raw hex values are not allowed in screen code. Color is never the only
 * carrier of a security state: every tone is paired with an icon and a text label in components.
 */
public final class UmbraColors {
    private UmbraColors() {}

    // Backgrounds and surfaces (anthracite, not pure black, to reduce halation on OLED).
    public static final int BACKGROUND_PRIMARY = 0xFF0B0C10;
    public static final int BACKGROUND_SECONDARY = 0xFF111319;
    public static final int SURFACE = 0xFF171A21;
    public static final int SURFACE_ELEVATED = 0xFF1F232C;
    public static final int SURFACE_PRESSED = 0xFF272C37;
    public static final int OUTLINE = 0xFF2C3240;
    public static final int SCRIM = 0xCC050608;

    // Accents: electric violet (primary) and electric blue (secondary / offline transport).
    public static final int ACCENT_PRIMARY = 0xFF9A8CFF;
    public static final int ACCENT_PRIMARY_CONTAINER = 0xFF2B2656;
    public static final int ACCENT_SECONDARY = 0xFF5AB0FF;
    public static final int ACCENT_SECONDARY_CONTAINER = 0xFF15314D;
    public static final int ON_ACCENT = 0xFF0C0A1A;

    // Text.
    public static final int TEXT_PRIMARY = 0xFFF3F4F7;
    public static final int TEXT_SECONDARY = 0xFFAAB1BF;
    public static final int TEXT_TERTIARY = 0xFF8A92A3;
    public static final int TEXT_DISABLED = 0xFF6B7280;

    // Status tones. Each has a dark container used behind the tone-colored text/icon.
    public static final int SUCCESS = 0xFF5FD39B;
    public static final int SUCCESS_CONTAINER = 0xFF12301F;
    public static final int WARNING = 0xFFF4B860;
    public static final int WARNING_CONTAINER = 0xFF3A2A10;
    public static final int DANGER = 0xFFFF7B7B;
    public static final int DANGER_CONTAINER = 0xFF3D1518;
    public static final int ON_DANGER = 0xFF1A0506;

    // Security semantics (aliases keep intent explicit in screen code).
    public static final int VERIFIED = SUCCESS;
    public static final int VERIFIED_CONTAINER = SUCCESS_CONTAINER;
    public static final int UNVERIFIED = WARNING;
    public static final int UNVERIFIED_CONTAINER = WARNING_CONTAINER;
    public static final int IDENTITY_CHANGED = WARNING;
    public static final int IDENTITY_CHANGED_CONTAINER = WARNING_CONTAINER;
    public static final int BLOCKED = DANGER;
    public static final int BLOCKED_CONTAINER = DANGER_CONTAINER;
    public static final int OFFLINE = ACCENT_SECONDARY;
    public static final int OFFLINE_CONTAINER = ACCENT_SECONDARY_CONTAINER;

    // Conversation bubbles.
    public static final int BUBBLE_OUTGOING = 0xFF2A2556;
    public static final int BUBBLE_INCOMING = 0xFF1B1F28;

    /** WCAG 2.x relative luminance of an opaque ARGB color. */
    public static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF) + 0.7152 * channel((argb >> 8) & 0xFF) + 0.0722 * channel(argb & 0xFF);
    }
    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    /** WCAG contrast ratio between two opaque colors (1..21). */
    public static double contrast(int foreground, int background) {
        double a = luminance(foreground), b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }
}
