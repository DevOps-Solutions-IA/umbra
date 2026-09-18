import app.umbra.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Executable tests for the dependency-free JVM utility layer, NOT an E2EE audit. */
public class CoreSelfTest {
    private static int passed;
    interface Test { void run() throws Exception; }
    static void test(String name, Test test) throws Exception { test.run(); passed++; System.out.println("PASS " + name); }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    static void rejects(Test test) throws Exception {
        try { test.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Input was not rejected");
    }
    public static void main(String[] args) throws Exception {
        test("Base64 roundtrip", () -> { byte[] input = Bytes.random(131); check(Arrays.equals(input, Bytes.unb64(Bytes.b64(input)))); });
        test("SHA-256 known vector", () -> check(Bytes.sha256(Bytes.utf8("abc")).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")));
        test("Symmetric safety code", () -> check(Bytes.safetyCode("a".repeat(64), "b".repeat(64)).equals(Bytes.safetyCode("b".repeat(64), "a".repeat(64)))));
        test("Safety code rejects bad identities", () -> rejects(() -> Bytes.safetyCode("a", "b")));
        test("Tokens: shape and uniqueness", () -> { Set<String> values = new HashSet<>(); for (int i = 0; i < 1000; i++) { String t = Bytes.token(); check(t.matches("[A-Za-z0-9_-]{43}")); check(values.add(t)); } });
        test("Padding: boundary cases", () -> { for (int size : new int[]{1,2,1019,1020,1021,1023,1024,1025,599999,600000}) { byte[] value = Bytes.random(size); byte[] padded = Padding.pad(value); check(padded.length % 1024 == 0); check(Arrays.equals(value, Padding.unpad(padded))); } });
        test("Padding: random cases", () -> { Random r = new Random(1); for (int i = 0; i < 300; i++) { byte[] value = Bytes.random(1 + r.nextInt(600000)); check(Arrays.equals(value, Padding.unpad(Padding.pad(value)))); } });
        test("Padding rejects empty message", () -> rejects(() -> Padding.pad(new byte[0])));
        test("Padding rejects oversized message", () -> rejects(() -> Padding.pad(new byte[600001])));
        test("Padding rejects invalid block length", () -> rejects(() -> Padding.unpad(new byte[1025])));
        test("Padding rejects forged inner length", () -> { byte[] p = new byte[1024]; ByteBuffer.wrap(p).putInt(5000); rejects(() -> Padding.unpad(p)); });
        test("Padding is randomized", () -> check(!Arrays.equals(Padding.pad(Bytes.utf8("same")), Padding.pad(Bytes.utf8("same")))));
        test("Frame roundtrip", () -> { byte[] raw = Bytes.random(10000); ByteArrayOutputStream out = new ByteArrayOutputStream(); Framing.write(out, raw); check(Arrays.equals(raw, Framing.read(new ByteArrayInputStream(out.toByteArray())))); });
        test("Fragmented stream", () -> { byte[] raw = Bytes.random(4096); ByteArrayOutputStream out = new ByteArrayOutputStream(); Framing.write(out, raw); InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(out.toByteArray())) { @Override public int read(byte[] b, int off, int n) throws IOException { return super.read(b, off, Math.min(n, 3)); } }; check(Arrays.equals(raw, Framing.read(fragmented))); });
        test("Multiple consecutive frames", () -> { ByteArrayOutputStream out = new ByteArrayOutputStream(); Framing.write(out, new byte[]{1}); Framing.write(out, new byte[]{2}); ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray()); check(Framing.read(in)[0] == 1); check(Framing.read(in)[0] == 2); });
        test("Frame rejects invalid prefixes", () -> { for (int size : new int[]{-1,0,1000001,Integer.MAX_VALUE}) rejects(() -> Framing.read(new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(size).array()))); });
        test("Frame rejects truncation", () -> rejects(() -> Framing.read(new ByteArrayInputStream(new byte[]{0,0,0,5,1}))));
        test("Frame rejects empty output", () -> rejects(() -> Framing.write(new ByteArrayOutputStream(), new byte[0])));
        test("Frame accepts maximum bounded size", () -> { byte[] raw = new byte[Framing.MAX_FRAME]; ByteArrayOutputStream out = new ByteArrayOutputStream(); Framing.write(out, raw); check(Framing.read(new ByteArrayInputStream(out.toByteArray())).length == Framing.MAX_FRAME); });
        test("Constant-time comparator functional behavior", () -> { check(Bytes.equal(new byte[]{1,2}, new byte[]{1,2})); check(!Bytes.equal(new byte[]{1,2}, new byte[]{1,3})); });
        System.out.println(passed + " utility test scenarios passed. libsignal/Android/hardware NOT exercised.");
    }
}
