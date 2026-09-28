package app.umbra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source-reading helper for the UI resource/policy tests.
 *
 * <p>Android unit tests compile against the android.jar boot classpath, which does not
 * expose {@code Files.readString(Path)}; {@code readAllBytes} + UTF-8 is available there.
 */
final class UiTestFiles {
    private UiTestFiles() {}

    static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
