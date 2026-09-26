package app.umbra;

import app.umbra.ui.design.StateColors;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.model.ModulatorPresentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

/**
 * Static checks of the branding and icon resources (not a render): launcher/adaptive/monochrome,
 * notification and splash wiring, one source of truth for the symbol, the 24dp icon grid, resolvable
 * references, no emoji used as icons, and icon-state contrast.
 */
public class UiIconResourcesTest {
    private static final Path SRC = Path.of("src"), RES = Path.of("src/main/res");
    private static final String A = "http://schemas.android.com/apk/res/android";

    private static Document xml(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(p.toFile());
    }
    private static List<Path> files(Path dir, String suffix) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) { return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList(); }
    }
    private static Map<String, String> values() throws Exception {
        Map<String, String> v = new HashMap<>();
        for (Path p : files(RES.resolve("values"), ".xml")) {
            NodeList n = xml(p).getDocumentElement().getChildNodes();
            for (int i = 0; i < n.getLength(); i++) if (n.item(i) instanceof Element e) v.put("@" + e.getTagName() + "/" + e.getAttribute("name"), e.getTextContent().trim());
        }
        return v;
    }
    private static boolean exists(String ref, Map<String, String> values) {
        if (ref.startsWith("@android:")) return true;
        String[] t = ref.substring(1).split("/", 2);
        return switch (t[0]) {
            case "string", "color" -> values.containsKey(ref);
            case "style" -> values.containsKey(ref);
            case "drawable" -> Files.exists(RES.resolve("drawable/" + t[1] + ".xml"));
            case "mipmap" -> Files.exists(RES.resolve("mipmap-anydpi-v26/" + t[1] + ".xml"));
            case "xml" -> Files.exists(RES.resolve("xml/" + t[1] + ".xml"));
            default -> false;
        };
    }

    @Test public void launcherIsAdaptiveWithMonochromeAndSplitLayers() throws Exception {
        Map<String, String> values = values();
        for (String name : new String[]{"ic_launcher", "ic_launcher_round"}) {
            Element root = xml(RES.resolve("mipmap-anydpi-v26/" + name + ".xml")).getDocumentElement();
            assertEquals("adaptive-icon", root.getTagName());
            for (String layer : new String[]{"background", "foreground", "monochrome"}) {
                NodeList l = root.getElementsByTagName(layer);
                assertEquals(name + " " + layer, 1, l.getLength());
                String ref = ((Element) l.item(0)).getAttributeNS(A, "drawable");
                assertTrue(name + " " + layer + " -> " + ref, exists(ref, values));
            }
            assertEquals("#FF0E120F", values.get(((Element) root.getElementsByTagName("background").item(0)).getAttributeNS(A, "drawable")));
        }
        String manifest = Files.readString(SRC.resolve("main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""));
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""));
        for (String flavor : new String[]{"connected", "offline"}) {
            Path overlay = SRC.resolve(flavor + "/AndroidManifest.xml");
            if (Files.exists(overlay)) assertFalse(flavor + " must not override the icon", Files.readString(overlay).contains("android:icon"));
            assertTrue(flavor + " must not ship its own launcher", files(SRC.resolve(flavor + "/res"), ".xml").stream().noneMatch(p -> p.getFileName().toString().startsWith("ic_launcher")));
            assertTrue(flavor + " must not ship raster icons", files(SRC.resolve(flavor + "/res"), ".png").isEmpty());
        }
        assertTrue("minSdk >= 26: adaptive icons only, no legacy PNG launcher", files(RES, ".png").isEmpty() && files(RES, ".webp").isEmpty());
    }

    @Test public void symbolHasOneSourceOfTruthAndFitsTheSafeZone() throws Exception {
        String path = values().get("@string/umbra_symbol_path");
        assertNotNull(path);
        for (String d : new String[]{"umbra_symbol", "ic_launcher_foreground", "ic_notification_umbra"})
            assertTrue(d, Files.readString(RES.resolve("drawable/" + d + ".xml")).contains("android:pathData=\"@string/umbra_symbol_path\""));
        for (Path p : files(RES, ".xml")) assertFalse(p + " duplicates the symbol geometry", Files.readString(p).contains(path) && !p.endsWith("brand.xml"));
        Element group = (Element) xml(RES.resolve("drawable/ic_launcher_foreground.xml")).getDocumentElement().getElementsByTagName("group").item(0);
        double s = Double.parseDouble(group.getAttributeNS(A, "scaleX")), tx = Double.parseDouble(group.getAttributeNS(A, "translateX")), ty = Double.parseDouble(group.getAttributeNS(A, "translateY"));
        double max = 0;
        for (double[] p : PathPoints.of(path)) max = Math.max(max, Math.hypot(tx + s * p[0] - 54, ty + s * p[1] - 54));
        assertTrue("symbol reaches " + max + "dp from center; safe zone radius is 33dp", max <= 33.0);
        assertTrue("symbol too small for recognition", max >= 24.0);
        String styles = Files.readString(RES.resolve("values/styles.xml"));
        assertTrue(styles.contains("windowSplashScreenAnimatedIcon\">@drawable/ic_launcher_foreground"));
        assertTrue(styles.contains("windowSplashScreenBackground\">@color/umbra_launcher_background"));
    }

    @Test public void notificationIconIsAWhiteSilhouette() throws Exception {
        String text = Files.readString(RES.resolve("drawable/ic_notification_umbra.xml"));
        Element root = xml(RES.resolve("drawable/ic_notification_umbra.xml")).getDocumentElement();
        assertEquals("vector", root.getTagName());
        NodeList paths = root.getElementsByTagName("path");
        for (int i = 0; i < paths.getLength(); i++) {
            Element p = (Element) paths.item(i);
            assertEquals("#FFFFFFFF", p.getAttributeNS(A, "fillColor"));
            assertTrue(p.getAttributeNS(A, "strokeColor").isEmpty());
        }
        assertFalse(text.contains("gradient")); assertFalse(text.contains("fillAlpha")); assertFalse(text.contains("@mipmap"));
    }

    @Test public void everyVectorParsesAndEveryReferenceResolves() throws Exception {
        Map<String, String> values = values();
        Pattern ref = Pattern.compile("\"(@(?:drawable|color|string|mipmap|style|xml)/[A-Za-z0-9_.]+)\"");
        List<Path> xmls = new ArrayList<>(files(RES, ".xml")); xmls.add(SRC.resolve("main/AndroidManifest.xml"));
        for (Path p : xmls) {
            xml(p); // Well-formed.
            Matcher m = ref.matcher(Files.readString(p));
            while (m.find()) assertTrue(p + " references missing " + m.group(1), exists(m.group(1), values));
        }
        for (Path p : files(RES.resolve("drawable"), ".xml")) {
            Element root = xml(p).getDocumentElement();
            assertEquals(p + " root", "vector", root.getTagName());
            assertFalse(root.getAttributeNS(A, "viewportWidth").isEmpty());
        }
        Pattern java = Pattern.compile("R\\.(drawable|mipmap)\\.([a-z0-9_]+)");
        for (Path p : files(SRC.resolve("main/java"), ".java")) {
            Matcher m = java.matcher(Files.readString(p));
            while (m.find()) assertTrue(p + " uses missing R." + m.group(1) + "." + m.group(2), exists("@" + m.group(1) + "/" + m.group(2), values));
        }
    }

    @Test public void internalIconsShareOneGridAndStroke() throws Exception {
        String icons = Files.readString(SRC.resolve("main/java/app/umbra/ui/design/Icons.java"));
        int count = 0;
        for (Path p : files(RES.resolve("drawable"), ".xml")) {
            String name = p.getFileName().toString().replace(".xml", "");
            if (!name.startsWith("ic_") || name.startsWith("ic_launcher") || name.equals("ic_notification_umbra")) continue;
            count++;
            assertTrue(name + " is not mapped from a Glyph", icons.contains("R.drawable." + name + ";"));
            Element root = xml(p).getDocumentElement();
            assertEquals(name, "24dp", root.getAttributeNS(A, "width"));
            assertEquals(name, "24", root.getAttributeNS(A, "viewportWidth"));
            NodeList paths = root.getElementsByTagName("path");
            assertTrue(paths.getLength() > 0);
            for (int i = 0; i < paths.getLength(); i++) {
                Element e = (Element) paths.item(i);
                assertEquals(name + " white stroke, tinted at runtime", "#FFFFFFFF", e.getAttributeNS(A, "strokeColor"));
                assertEquals(name, "#00000000", e.getAttributeNS(A, "fillColor"));
                assertEquals(name, "round", e.getAttributeNS(A, "strokeLineCap"));
                assertEquals(name, "round", e.getAttributeNS(A, "strokeLineJoin"));
                String width = e.getAttributeNS(A, "strokeWidth");
                assertTrue(name + " stroke " + width, width.equals("1.8") || (name.equals("ic_more") && width.equals("3")));
                for (double[] pt : PathPoints.of(e.getAttributeNS(A, "pathData")))
                    assertTrue(name + " point outside the 1.5..22.5 live area: " + pt[0] + "," + pt[1], pt[0] >= 1.5 && pt[0] <= 22.5 && pt[1] >= 1.5 && pt[1] <= 22.5);
            }
        }
        assertTrue("icon set unexpectedly small: " + count, count >= 60);
    }

    @Test public void noEmojiOrSymbolCharactersAreUsedAsProductIcons() throws Exception {
        for (Path p : files(SRC.resolve("main/java"), ".java")) {
            String text = Files.readString(p);
            text.codePoints().forEach(c -> {
                boolean emoji = (c >= 0x1F000 && c <= 0x1FAFF) || (c >= 0x2600 && c <= 0x27BF) || c == 0xFE0F || (c >= 0x2B00 && c <= 0x2BFF);
                assertFalse(p + " contains U+" + Integer.toHexString(c), emoji);
            });
        }
    }

    @Test public void iconStatesKeepNonTextContrastOnEveryBackground() {
        int[] backgrounds = {UmbraColors.BACKGROUND_PRIMARY, UmbraColors.BACKGROUND_SECONDARY, UmbraColors.SURFACE, UmbraColors.SURFACE_ELEVATED};
        for (int state : StateColors.ALL) for (int bg : backgrounds) {
            // WCAG exempts inactive controls; TextDisabled #697166 is kept (>= 2.8:1) and always paired with a reason.
            double min = state == StateColors.DISABLED ? 2.8 : 3.0;
            assertTrue(String.format(Locale.ROOT, "%08X on %08X", state, bg), UmbraColors.contrast(state, bg) >= min);
        }
        assertTrue("disabled must read differently from normal", UmbraColors.contrast(StateColors.NORMAL, StateColors.DISABLED) >= 1.8);
    }

    @Test public void modulatorIconFollowsEngineStateAndIsDescribedInWords() {
        assertEquals(UmbraColors.ACCENT_SECONDARY, StateColors.modulator(ModulatorPresentation.of("ON", false)));
        assertEquals(StateColors.DANGER, StateColors.modulator(ModulatorPresentation.of("ERROR_MUTED", false)));
        assertEquals(StateColors.SECONDARY, StateColors.modulator(ModulatorPresentation.of("ENABLING", false)));
        assertEquals(StateColors.SECONDARY, StateColors.modulator(ModulatorPresentation.of("DISABLING", false)));
        assertEquals(StateColors.NORMAL, StateColors.modulator(ModulatorPresentation.of("OFF", false)));
        assertEquals("Modulación de voz activada", StateColors.modulatorDescription(ModulatorPresentation.of("ON", false)));
        for (ModulatorPresentation.EngineState s : ModulatorPresentation.EngineState.values()) {
            String d = StateColors.modulatorDescription(ModulatorPresentation.of(s.name(), false));
            assertFalse(d, d.contains("ic_") || d.contains("_"));
        }
    }

    /** Minimal SVG path walker: endpoints, control points and sampled arcs (absolute coordinates). */
    static final class PathPoints {
        static List<double[]> of(String d) {
            List<double[]> out = new ArrayList<>();
            Matcher m = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz]|-?\\d*\\.?\\d+(?:e-?\\d+)?").matcher(d);
            List<String> tok = new ArrayList<>(); while (m.find()) tok.add(m.group());
            double x = 0, y = 0, sx = 0, sy = 0; char cmd = 'M'; int i = 0;
            while (i < tok.size()) {
                String t = tok.get(i);
                if (Character.isLetter(t.charAt(0))) { cmd = t.charAt(0); i++; if (cmd == 'Z' || cmd == 'z') { x = sx; y = sy; } continue; }
                boolean rel = Character.isLowerCase(cmd);
                switch (Character.toUpperCase(cmd)) {
                    case 'M', 'L', 'T' -> { double nx = n(tok, i++), ny = n(tok, i++); x = rel ? x + nx : nx; y = rel ? y + ny : ny; if (Character.toUpperCase(cmd) == 'M') { sx = x; sy = y; cmd = rel ? 'l' : 'L'; } out.add(new double[]{x, y}); }
                    case 'H' -> { double nx = n(tok, i++); x = rel ? x + nx : nx; out.add(new double[]{x, y}); }
                    case 'V' -> { double ny = n(tok, i++); y = rel ? y + ny : ny; out.add(new double[]{x, y}); }
                    case 'C' -> { for (int k = 0; k < 3; k++) { double px = n(tok, i++), py = n(tok, i++); px = rel ? x + px : px; py = rel ? y + py : py; out.add(new double[]{px, py}); if (k == 2) { x = px; y = py; } } }
                    case 'S', 'Q' -> { for (int k = 0; k < 2; k++) { double px = n(tok, i++), py = n(tok, i++); px = rel ? x + px : px; py = rel ? y + py : py; out.add(new double[]{px, py}); if (k == 1) { x = px; y = py; } } }
                    case 'A' -> {
                        double rx = n(tok, i++), ry = n(tok, i++); i++; int large = (int) n(tok, i++), sweep = (int) n(tok, i++);
                        double nx = n(tok, i++), ny = n(tok, i++); nx = rel ? x + nx : nx; ny = rel ? y + ny : ny;
                        arc(out, x, y, rx, ry, large == 1, sweep == 1, nx, ny); x = nx; y = ny;
                    }
                    default -> i++;
                }
            }
            return out;
        }
        private static double n(List<String> t, int i) { return Double.parseDouble(t.get(i)); }
        /** Circular arcs only (rx == ry in this icon set); samples 24 points along the arc. */
        private static void arc(List<double[]> out, double x1, double y1, double rx, double ry, boolean large, boolean sweep, double x2, double y2) {
            double r = Math.max(rx, ry), dx = (x1 - x2) / 2, dy = (y1 - y2) / 2, d2 = dx * dx + dy * dy;
            if (d2 > r * r) r = Math.sqrt(d2);
            double f = Math.sqrt(Math.max(0, (r * r - d2) / d2)) * (large == sweep ? -1 : 1);
            double cx = (x1 + x2) / 2 + f * dy, cy = (y1 + y2) / 2 - f * dx;
            double a1 = Math.atan2(y1 - cy, x1 - cx), a2 = Math.atan2(y2 - cy, x2 - cx), da = a2 - a1;
            if (sweep && da < 0) da += 2 * Math.PI; if (!sweep && da > 0) da -= 2 * Math.PI;
            for (int k = 0; k <= 24; k++) { double a = a1 + da * k / 24; out.add(new double[]{cx + r * Math.cos(a), cy + r * Math.sin(a)}); }
        }
    }
}
