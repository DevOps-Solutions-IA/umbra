package app.umbra;

import app.umbra.ui.design.UmbraColors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.Test;
import static app.umbra.ui.design.UmbraColors.*;
import static org.junit.Assert.*;

/** WCAG AA contrast for text/icon tokens and consistency with the XML theme mirror. */
public class UiDesignTokensTest {
    private static final int[] BACKGROUNDS = {BACKGROUND_PRIMARY, BACKGROUND_SECONDARY, SURFACE, SURFACE_ELEVATED};
    private static final int[] FOREGROUNDS = {TEXT_PRIMARY, TEXT_SECONDARY, TEXT_TERTIARY, ACCENT_PRIMARY, ACCENT_SECONDARY, SUCCESS, WARNING, DANGER};

    @Test public void textAndStatusColorsMeetAaOnEverySurface() {
        for (int fg : FOREGROUNDS) for (int bg : BACKGROUNDS)
            assertTrue(String.format(Locale.ROOT, "%08X on %08X = %.2f", fg, bg, contrast(fg, bg)), contrast(fg, bg) >= 4.5);
    }
    @Test public void tonesMeetAaOnTheirContainers() {
        int[][] pairs = {{SUCCESS, SUCCESS_CONTAINER}, {WARNING, WARNING_CONTAINER}, {DANGER, DANGER_CONTAINER},
            {ACCENT_SECONDARY, ACCENT_SECONDARY_CONTAINER}, {ACCENT_PRIMARY, ACCENT_PRIMARY_CONTAINER}, {ON_ACCENT, ACCENT_PRIMARY},
            {ON_DANGER, DANGER}, {TEXT_PRIMARY, BUBBLE_OUTGOING}, {TEXT_SECONDARY, BUBBLE_OUTGOING}, {TEXT_PRIMARY, BUBBLE_INCOMING},
            {TEXT_SECONDARY, BUBBLE_INCOMING}, {TEXT_PRIMARY, ACCENT_PRIMARY_CONTAINER}};
        for (int[] p : pairs) assertTrue(String.format(Locale.ROOT, "%08X/%08X", p[0], p[1]), contrast(p[0], p[1]) >= 4.5);
    }
    @Test public void contrastFormulaMatchesWcagExtremes() {
        assertEquals(21.0, contrast(0xFFFFFFFF, 0xFF000000), 0.01);
        assertEquals(1.0, contrast(0xFF777777, 0xFF777777), 0.001);
    }
    @Test public void xmlThemeMirrorsJavaTokens() throws Exception {
        Path colors = Path.of("src/main/res/values/colors.xml");
        String xml = Files.readString(colors);
        assertTrue(xml.contains(hex("umbra_background_primary", BACKGROUND_PRIMARY)));
        assertTrue(xml.contains(hex("umbra_surface", SURFACE)));
        assertTrue(xml.contains(hex("umbra_accent_primary", ACCENT_PRIMARY)));
        assertTrue(xml.contains(hex("umbra_text_primary", TEXT_PRIMARY)));
        assertTrue(xml.contains(hex("umbra_text_secondary", TEXT_SECONDARY)));
    }
    private static String hex(String name, int color) { return String.format(Locale.ROOT, "<color name=\"%s\">#%08X</color>", name, color); }
    private static void assertEquals(double expected, double actual, double delta) { assertTrue(expected + " vs " + actual, Math.abs(expected - actual) <= delta); }
}
