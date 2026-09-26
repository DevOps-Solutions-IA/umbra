package app.umbra;

import app.umbra.ui.design.UmbraColors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.Test;
import static app.umbra.ui.design.UmbraColors.*;
import static org.junit.Assert.*;

/** Olive palette: WCAG AA contrast for text/icon tokens, no bright/neon hues, XML theme mirror. */
public class UiDesignTokensTest {
    private static final int[] BACKGROUNDS = {BACKGROUND_PRIMARY, BACKGROUND_SECONDARY, BACKGROUND_TERTIARY, SURFACE, SURFACE_ELEVATED};
    /** Every token used for text or icons. Base status colors are fills/borders, not text. */
    private static final int[] FOREGROUNDS = {TEXT_PRIMARY, TEXT_SECONDARY, TEXT_TERTIARY, ACCENT_MUTED, ACCENT_SECONDARY, SUCCESS_FG,
        WARNING_FG, DANGER_FG, VERIFIED_FG, IDENTITY_CHANGED_FG, BLOCKED_FG, OFFLINE_FG, WARNING, IDENTITY_CHANGED};

    @Test public void textAndStatusColorsMeetAaOnEverySurface() {
        for (int fg : FOREGROUNDS) for (int bg : BACKGROUNDS)
            assertTrue(String.format(Locale.ROOT, "%08X on %08X = %.2f", fg, bg, contrast(fg, bg)), contrast(fg, bg) >= 4.5);
    }
    @Test public void tonesMeetAaOnTheirContainers() {
        int[][] pairs = {{SUCCESS_FG, SUCCESS_CONTAINER}, {WARNING_FG, WARNING_CONTAINER}, {DANGER_FG, DANGER_CONTAINER},
            {VERIFIED_FG, VERIFIED_CONTAINER}, {IDENTITY_CHANGED_FG, IDENTITY_CHANGED_CONTAINER}, {BLOCKED_FG, BLOCKED_CONTAINER},
            {OFFLINE_FG, OFFLINE_CONTAINER}, {ACCENT_MUTED, ACCENT_CONTAINER}, {ON_ACCENT, ACCENT_STRONG},
            {TEXT_PRIMARY, BUBBLE_OUTGOING}, {TEXT_SECONDARY, BUBBLE_OUTGOING}, {TEXT_PRIMARY, BUBBLE_INCOMING},
            {TEXT_SECONDARY, BUBBLE_INCOMING}, {ACCENT_MUTED, BUBBLE_INCOMING}, {ACCENT_MUTED, SURFACE_SOFT}, {TEXT_PRIMARY, SURFACE_SOFT}};
        for (int[] p : pairs) assertTrue(String.format(Locale.ROOT, "%08X/%08X = %.2f", p[0], p[1], contrast(p[0], p[1])), contrast(p[0], p[1]) >= 4.5);
        assertTrue("end-call glyph on danger fill (non-text 3:1)", contrast(ON_DANGER, DANGER) >= 3.0);
    }
    @Test public void paletteIsSoberWithoutNeonOrElectricHues() {
        int[] all = {BACKGROUND_PRIMARY, BACKGROUND_SECONDARY, BACKGROUND_TERTIARY, SURFACE, SURFACE_ELEVATED, SURFACE_SOFT, BORDER_SUBTLE,
            BORDER_DEFAULT, ACCENT_PRIMARY, ACCENT_SECONDARY, ACCENT_STRONG, ACCENT_MUTED, TEXT_PRIMARY, TEXT_SECONDARY, TEXT_TERTIARY,
            TEXT_DISABLED, SUCCESS, WARNING, DANGER, VERIFIED, IDENTITY_CHANGED, BLOCKED, SUCCESS_FG, WARNING_FG, DANGER_FG, VERIFIED_FG,
            BLOCKED_FG, BUBBLE_OUTGOING};
        for (int c : all) {
            float[] hsv = new float[3]; hsv(c, hsv);
            assertTrue(String.format(Locale.ROOT, "%08X saturation %.2f", c, hsv[1]), hsv[1] <= 0.50f);
            boolean blueOrCyan = hsv[0] >= 160 && hsv[0] <= 260;
            assertFalse(String.format(Locale.ROOT, "%08X hue %.0f is blue/cyan", c, hsv[0]), blueOrCyan && hsv[1] > 0.12f);
        }
        assertTrue(BACKGROUND_PRIMARY == 0xFF0E120F);
        assertTrue(ACCENT_PRIMARY == 0xFF6F7F62);
    }
    private static void hsv(int c, float[] out) {
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = d == 0 ? 0 : max == r ? 60 * (((g - b) / d) % 6) : max == g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
        out[0] = h < 0 ? h + 360 : h; out[1] = max == 0 ? 0 : d / max; out[2] = max;
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
        assertTrue(xml.contains(hex("umbra_accent_muted", ACCENT_MUTED)));
        assertTrue(xml.contains(hex("umbra_text_primary", TEXT_PRIMARY)));
        assertTrue(xml.contains(hex("umbra_text_secondary", TEXT_SECONDARY)));
    }
    private static String hex(String name, int color) { return String.format(Locale.ROOT, "<color name=\"%s\">#%08X</color>", name, color); }
    private static void assertEquals(double expected, double actual, double delta) { assertTrue(expected + " vs " + actual, Math.abs(expected - actual) <= delta); }
}
