package app.umbra.ui.model;

import java.util.Locale;

/**
 * Maps the REAL local voice-processor state reported by the media engine
 * ({@code NativeVoiceSession.modulationStatus()}: OFF, ENABLING, ON, DISABLING, ERROR_MUTED) to UI.
 *
 * <p>The icon is always the voice-modulation glyph; its color and the headline carry the state.
 * <p>Invariants: "Modulada" is only shown as active when the engine reports ON; a failure is shown as
 * muted; mute always wins over modulation in the transmission indicator; no wording claims anonymity.
 */
public record ModulatorPresentation(EngineState state, Mode selected, boolean busy, String headline, String detail,
                                    Tone tone, Glyph glyph, boolean microphoneSilenced, String transmission,
                                    boolean naturalRequiresConfirmation) {

    public enum EngineState { OFF, ENABLING, ON, DISABLING, ERROR_MUTED }
    /** Segment highlighted in the Natural/Modulada control. NONE while the engine is transitioning. */
    public enum Mode { NATURAL, MODULATED, NONE }

    public static final String NATURAL_CONFIRM_TITLE = "¿Usar voz natural?";
    public static final String NATURAL_CONFIRM_BODY = "No quita el silencio ni concede el micrófono.";
    public static final String NATURAL_CONFIRM_ACTION = "Voz natural";
    public static final String DISCLAIMER = "Cambia el timbre. No garantiza que no te reconozcan.";

    public static EngineState parse(String engineStatus) {
        if (engineStatus == null) return EngineState.ERROR_MUTED;
        try { return EngineState.valueOf(engineStatus.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { return EngineState.ERROR_MUTED; } // Fail closed.
    }

    public static ModulatorPresentation of(String engineStatus, boolean muted) {
        EngineState s = parse(engineStatus);
        Mode selected; boolean busy; String headline, detail; Tone tone; Glyph glyph; boolean silenced;
        switch (s) {
            case ON -> { selected = Mode.MODULATED; busy = false; headline = "Voz modulada"; detail = "Confirmada por el motor."; tone = Tone.ACCENT; glyph = Glyph.VOICE; silenced = false; }
            case ENABLING -> { selected = Mode.NONE; busy = true; headline = "Activando modulación…"; detail = "Esperando confirmación."; tone = Tone.NEUTRAL; glyph = Glyph.VOICE; silenced = false; }
            case DISABLING -> { selected = Mode.NONE; busy = true; headline = "Desactivando modulación…"; detail = "Esperando confirmación."; tone = Tone.NEUTRAL; glyph = Glyph.VOICE; silenced = false; }
            case ERROR_MUTED -> { selected = Mode.NONE; busy = false; headline = "Modulación fallida"; detail = "Micrófono silenciado."; tone = Tone.DANGER; glyph = Glyph.VOICE; silenced = true; }
            default -> { selected = Mode.NATURAL; busy = false; headline = "Voz natural"; detail = "Sin modificar."; tone = Tone.NEUTRAL; glyph = Glyph.VOICE; silenced = false; }
        }
        String transmission;
        if (muted) transmission = "Silenciado";
        else if (silenced) transmission = "Silenciado por fallo";
        else if (busy) transmission = "Confirmando…";
        else if (s == EngineState.ON) transmission = "Voz modulada";
        else transmission = "Voz natural";
        boolean confirm = s != EngineState.OFF;
        return new ModulatorPresentation(s, selected, busy, headline, detail, tone, glyph, muted || silenced, transmission, confirm);
    }

    /** True only if the engine reports ON; the UI must never infer this from the user's request. */
    public boolean modulatedActive() { return state == EngineState.ON; }
}
