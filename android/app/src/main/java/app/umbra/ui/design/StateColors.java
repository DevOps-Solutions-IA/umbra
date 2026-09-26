package app.umbra.ui.design;

import app.umbra.ui.model.ModulatorPresentation;

/**
 * Icon colors by interaction/security state (pure Java, JVM-tested). Icons inherit these through
 * tint; vector files stay white. Non-text contrast target is 3:1 on every background.
 */
public final class StateColors {
    private StateColors() {}

    public static final int NORMAL = UmbraColors.TEXT_SECONDARY;
    public static final int SECONDARY = UmbraColors.TEXT_TERTIARY;
    public static final int SELECTED = UmbraColors.ACCENT_MUTED;
    public static final int DISABLED = UmbraColors.TEXT_DISABLED;
    public static final int WARNING = UmbraColors.WARNING_FG;
    public static final int DANGER = UmbraColors.DANGER_FG;
    public static final int VERIFIED = UmbraColors.VERIFIED_FG;
    public static final int IDENTITY_CHANGED = UmbraColors.IDENTITY_CHANGED_FG;
    public static final int BLOCKED = UmbraColors.BLOCKED_FG;
    public static final int[] ALL = {NORMAL, SECONDARY, SELECTED, DISABLED, WARNING, DANGER, VERIFIED, IDENTITY_CHANGED, BLOCKED,
        UmbraColors.ACCENT_SECONDARY};

    /**
     * Voice-modulation icon color from the engine-reported state: OFF neutral, ENABLING/DISABLING
     * secondary (same geometry, transitional), ON AccentSecondary #879676, ERROR_MUTED danger.
     * DANGER_FG is used because Danger #A35D57 falls below 3:1 on SurfaceElevated.
     */
    public static int modulator(ModulatorPresentation m) {
        return switch (m.state()) {
            case ON -> UmbraColors.ACCENT_SECONDARY;
            case ERROR_MUTED -> DANGER;
            case ENABLING, DISABLING -> SECONDARY;
            default -> NORMAL;
        };
    }
    /** Spoken description of the modulator state (never a resource name). */
    public static String modulatorDescription(ModulatorPresentation m) {
        return switch (m.state()) {
            case ON -> "Modulación de voz activada";
            case ENABLING -> "Activando modulación de voz";
            case DISABLING -> "Desactivando modulación de voz";
            case ERROR_MUTED -> "La modulación de voz falló; micrófono silenciado";
            default -> "Modulación de voz desactivada";
        };
    }
}
