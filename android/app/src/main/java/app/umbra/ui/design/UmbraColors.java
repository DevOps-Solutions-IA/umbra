package app.umbra.ui.design;

/**
 * Semantic color tokens of the UMBRA design system: dark, sober, olive/military palette.
 * No neon, mint or electric blue.
 *
 * <p>Pure Java (no Android imports) so contrast rules are verified by JVM tests. Screens must use
 * these semantic names; raw hex values are not allowed in screen code. Color is never the only
 * carrier of a security state: every tone is paired with an icon and a text label in components.
 *
 * <p>Base values are the approved palette. Some status tones (Success, Danger, Blocked,
 * Verified) are too dark to be read as text on dark surfaces, so they are used for fills,
 * borders and outlines, and a derived {@code *_FG} variant (the same hue mixed toward
 * TextPrimary) is used for text and icons. Derived values are marked as such.
 */
public final class UmbraColors {
    private UmbraColors() {}

    // Backgrounds.
    public static final int BACKGROUND_PRIMARY = 0xFF0E120F;
    public static final int BACKGROUND_SECONDARY = 0xFF141A16;
    public static final int BACKGROUND_TERTIARY = 0xFF1A211C;

    // Surfaces and borders.
    public static final int SURFACE = 0xFF1B231D;
    public static final int SURFACE_ELEVATED = 0xFF222C24;
    public static final int SURFACE_SOFT = 0xFF2A342C;
    public static final int SURFACE_PRESSED = SURFACE_SOFT;
    public static final int BORDER_SUBTLE = 0xFF313A33;
    public static final int BORDER_DEFAULT = 0xFF404A41;
    public static final int OUTLINE = BORDER_DEFAULT;
    public static final int SCRIM = 0xCC070907;

    // Olive accents.
    public static final int ACCENT_PRIMARY = 0xFF6F7F62;   // outlines, focus, selected borders
    public static final int ACCENT_SECONDARY = 0xFF879676; // offline/Bluetooth, group marks
    public static final int ACCENT_STRONG = 0xFF55634B;    // filled primary buttons, selected segments
    public static final int ACCENT_MUTED = 0xFFA0AD93;     // accent text and icons
    public static final int ACCENT_CONTAINER = 0xFF283027; // derived: accent at 22% over BackgroundSecondary
    /** Text/icon color on {@link #ACCENT_STRONG} fills (5.7:1). */
    public static final int ON_ACCENT = 0xFFF1F3EE;

    // Text.
    public static final int TEXT_PRIMARY = 0xFFF1F3EE;
    public static final int TEXT_SECONDARY = 0xFFC4CBBF;
    public static final int TEXT_TERTIARY = 0xFF8E968B;    // TextMuted
    public static final int TEXT_DISABLED = 0xFF697166;

    // Status base colors (fills, borders).
    public static final int SUCCESS = 0xFF6E8B63;
    public static final int WARNING = 0xFFB39A62;
    public static final int DANGER = 0xFFA35D57;
    public static final int VERIFIED = 0xFF7E9472;
    public static final int IDENTITY_CHANGED = 0xFFC0A269;
    public static final int BLOCKED = 0xFF7B4D4D;
    /** Icon color on a {@link #DANGER} fill (end-call button). */
    public static final int ON_DANGER = 0xFFF1F3EE;

    // Derived readable foregrounds (text/icons), each >= 4.5:1 on every surface and its container.
    public static final int SUCCESS_FG = 0xFF8FA586;
    public static final int WARNING_FG = 0xFFB69E69;
    public static final int DANGER_FG = 0xFFBE928C;
    public static final int VERIFIED_FG = 0xFF8FA285;
    public static final int IDENTITY_CHANGED_FG = IDENTITY_CHANGED;
    public static final int BLOCKED_FG = 0xFFB09895;
    public static final int OFFLINE_FG = ACCENT_SECONDARY;

    // Derived dark containers (tone at ~22% over BackgroundSecondary).
    public static final int SUCCESS_CONTAINER = 0xFF283327;
    public static final int WARNING_CONTAINER = 0xFF373627;
    public static final int DANGER_CONTAINER = 0xFF332924;
    public static final int VERIFIED_CONTAINER = 0xFF2B352A;
    public static final int IDENTITY_CHANGED_CONTAINER = 0xFF3A3828;
    public static final int BLOCKED_CONTAINER = 0xFF2B2522;
    public static final int OFFLINE_CONTAINER = BACKGROUND_TERTIARY;
    public static final int UNVERIFIED = WARNING;
    public static final int UNVERIFIED_CONTAINER = WARNING_CONTAINER;

    // Conversation bubbles.
    public static final int BUBBLE_OUTGOING = 0xFF2E3B2C; // derived olive tint
    public static final int BUBBLE_INCOMING = SURFACE_ELEVATED;

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
