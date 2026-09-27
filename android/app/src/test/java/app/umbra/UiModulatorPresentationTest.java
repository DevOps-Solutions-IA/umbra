package app.umbra;

import app.umbra.ui.model.ModulatorPresentation;
import app.umbra.ui.model.ModulatorPresentation.EngineState;
import app.umbra.ui.model.ModulatorPresentation.Mode;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** The voice control mirrors the engine; a request is never displayed as an applied effect. */
public class UiModulatorPresentationTest {
    @Test public void modulatedIsShownOnlyWhenEngineReportsOn() {
        for (EngineState s : EngineState.values()) {
            ModulatorPresentation p = ModulatorPresentation.of(s.name(), false);
            assertEquals(s.name(), s == EngineState.ON, p.modulatedActive());
            assertEquals(s.name(), s == EngineState.ON, p.selected() == Mode.MODULATED);
            assertEquals(s.name(), s == EngineState.ON, p.headline().equals("Voz modulada"));
        }
    }
    @Test public void transitionsAreShownAsPendingNotAsTheTargetMode() {
        ModulatorPresentation enabling = ModulatorPresentation.of("ENABLING", false);
        assertEquals("Activando modulación…", enabling.headline());
        assertEquals(Mode.NONE, enabling.selected());
        assertTrue(enabling.busy());
        ModulatorPresentation disabling = ModulatorPresentation.of("DISABLING", false);
        assertEquals(Mode.NONE, disabling.selected());
        assertTrue(disabling.busy());
    }
    @Test public void failureKeepsMicrophoneSilencedAndSaysSo() {
        ModulatorPresentation failed = ModulatorPresentation.of("ERROR_MUTED", false);
        assertEquals("La modulación falló.", failed.headline());
        assertTrue(failed.detail().contains("Tu micrófono permanece silenciado"));
        assertTrue(failed.microphoneSilenced());
        assertFalse(failed.modulatedActive());
    }
    @Test public void unknownOrMissingEngineStatusFailsClosed() {
        assertEquals(EngineState.ERROR_MUTED, ModulatorPresentation.parse(null));
        assertEquals(EngineState.ERROR_MUTED, ModulatorPresentation.parse("MODULATED"));
        assertFalse(ModulatorPresentation.of("bogus", false).modulatedActive());
    }
    @Test public void muteAlwaysWinsInTheTransmissionIndicator() {
        for (EngineState s : EngineState.values()) {
            ModulatorPresentation p = ModulatorPresentation.of(s.name(), true);
            assertTrue(p.microphoneSilenced());
            assertTrue(p.transmission().startsWith("Micrófono silenciado"));
        }
        assertEquals("Transmitiendo tu voz natural", ModulatorPresentation.of("OFF", false).transmission());
        assertEquals("Transmitiendo voz modulada", ModulatorPresentation.of("ON", false).transmission());
    }
    @Test public void returningToNaturalVoiceRequiresConfirmationUnlessAlreadyNatural() {
        assertFalse(ModulatorPresentation.of("OFF", false).naturalRequiresConfirmation());
        for (String s : new String[]{"ON", "ENABLING", "DISABLING", "ERROR_MUTED"})
            assertTrue(s, ModulatorPresentation.of(s, false).naturalRequiresConfirmation());
        assertEquals("Vas a transmitir tu voz natural.", ModulatorPresentation.NATURAL_CONFIRM_TITLE);
        assertEquals("Usar voz natural", ModulatorPresentation.NATURAL_CONFIRM_ACTION);
    }
    @Test public void noWordingClaimsAnonymity() {
        StringBuilder all = new StringBuilder(ModulatorPresentation.DISCLAIMER).append(ModulatorPresentation.NATURAL_CONFIRM_BODY);
        for (EngineState s : EngineState.values()) {
            ModulatorPresentation p = ModulatorPresentation.of(s.name(), false);
            all.append(p.headline()).append(p.detail()).append(p.transmission());
        }
        String text = all.toString().toLowerCase(Locale.ROOT);
        assertFalse(text.contains("anónim"));
        assertFalse(text.contains("no pueden reconocerte") && !text.contains("no garantiza que no puedan reconocerte"));
        assertTrue(ModulatorPresentation.DISCLAIMER.startsWith("Modificación local de voz"));
    }
}
