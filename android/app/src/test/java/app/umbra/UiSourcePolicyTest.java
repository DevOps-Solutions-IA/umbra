package app.umbra;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Static guard for the presentation layer (not a behavior test): UI code must not log, print
 * traces, copy to the clipboard, open links or reach the network, and the model layer stays free of
 * Android and engine types so its security wording is JVM-tested.
 */
public class UiSourcePolicyTest {
    private static List<Path> sources(String dir) throws Exception {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/app/umbra/ui", dir))) { return s.filter(p -> p.toString().endsWith(".java")).toList(); }
    }
    @Test public void presentationCodeNeverLogsCopiesOrReachesTheNetwork() throws Exception {
        List<Path> files = new java.util.ArrayList<>(sources(""));
        assertFalse(files.isEmpty());
        String[] forbidden = {"android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "printStackTrace", "System.out", "System.err",
            "ClipboardManager", "setPrimaryClip", "setTextIsSelectable(true)", "Linkify", "setAutoLinkMask", "java.net.", "android.webkit"};
        for (Path file : files) {
            String text = Files.readString(file);
            for (String f : forbidden) assertFalse(file + " uses " + f, text.contains(f));
        }
    }
    @Test public void modelLayerIsFreeOfAndroidAndEngineTypes() throws Exception {
        for (Path file : sources("model")) {
            String text = Files.readString(file);
            assertFalse(file + " imports Android", text.contains("import android."));
            assertFalse(file + " imports the engine", text.contains("app.umbra.crypto") || text.contains("org.signal"));
        }
    }
    @Test public void screensNeverTouchTheEngineOrStorage() throws Exception {
        for (Path file : sources("screens")) {
            String text = Files.readString(file);
            for (String f : new String[]{"app.umbra.crypto", "app.umbra.data", "app.umbra.transport", "app.umbra.devices.DeviceService", "Engine ", "Vault", "SharedPreferences"})
                assertFalse(file + " references " + f, text.contains(f));
        }
    }
}
