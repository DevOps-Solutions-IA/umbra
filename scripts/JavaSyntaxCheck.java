import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Parses syntax only. Does NOT resolve Android or libsignal APIs. */
public class JavaSyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK required");
        List<java.io.File> files;
        try (var paths = Files.walk(Path.of(args[0]))) {
            files = paths.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "--release", "21"), null, manager.getJavaFileObjectsFromFiles(files));
            for (var tree : task.parse()) { /* Force parse of all compilation units. */ }
        }
        boolean failed = false;
        for (var d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) { System.err.println(d); failed = true; }
        }
        if (failed) System.exit(1);
        System.out.println("Parsed Java syntax: " + files.size() + " files. Android/libsignal compilation NOT performed.");
    }
}
