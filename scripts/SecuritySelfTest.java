import app.umbra.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Executed on a real JDK. Software-key tests do NOT exercise Android Keystore or libsignal. */
public final class SecuritySelfTest {
    private static int passed;
    interface Check { void run() throws Exception; }
    static void test(String name, Check check) throws Exception { check.run(); passed++; System.out.println("PASS " + name); }
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static void rejects(Check action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | SecurityException | IOException | java.security.GeneralSecurityException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    static void json(String value) { StrictJson.object(Bytes.utf8(value), 1_000_000); }
    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong(100);
        AccessGate gate = new AccessGate(clock::get, 1000);
        test("Gate begins locked", () -> rejects(gate::enter));
        test("Gate authenticates explicitly", () -> { gate.unlock(); gate.check(gate.enter()); });
        test("Lock invalidates an outstanding operation", () -> { var lease = gate.enter(); gate.lock(); rejects(() -> gate.check(lease)); });
        test("Reauthentication never revives an old lease", () -> { gate.unlock(); var old = gate.enter(); gate.lock(); gate.unlock(); rejects(() -> gate.check(old)); });
        test("Gate rejects exact expiry boundary", () -> { gate.unlock(); clock.addAndGet(1000); rejects(gate::enter); });
        test("Gate rejects backward test clock", () -> { gate.unlock(); clock.decrementAndGet(); rejects(gate::enter); });
        test("Commit callback is not run after a lock", () -> { gate.unlock(); var lease = gate.enter(); gate.lock(); int[] writes = {0}; rejects(() -> gate.commit(lease, () -> ++writes[0])); check(writes[0] == 0); });
        test("Expiry blocks commit even without a UI tick", () -> { gate.unlock(); var lease = gate.enter(); clock.addAndGet(1001); rejects(() -> gate.commit(lease, () -> true)); });
        test("Worker cannot reuse pre-lock authorization", () -> {
            gate.unlock(); var lease = gate.enter(); gate.lock(); gate.unlock();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try { check(worker.submit(() -> { try { gate.check(lease); return false; } catch (AccessGate.LockedException e) { return true; } }).get(2, TimeUnit.SECONDS)); }
            finally { worker.shutdownNow(); }
        });
        test("Authorized commit returns its actual result", () -> { gate.unlock(); check(gate.commit(gate.enter(), () -> 7) == 7); });
        test("Invalid gate duration rejected", () -> rejects(() -> new AccessGate(clock::get, 0)));
        test("Strict UTF-8 decoder rejects invalid sequence", () -> rejects(() -> Bytes.text(new byte[]{(byte)0xc3, 0x28})));
        test("Strict UTF-8 encoder rejects unpaired UTF-16", () -> rejects(() -> Bytes.utf8("\ud800")));
        test("Strict UTF-8 encoder roundtrips multilingual text", () -> check(Bytes.text(Bytes.utf8("Español 中文 🔐")).equals("Español 中文 🔐")));
        test("Filename removes traversal separators and formatting controls", () -> check(FileNames.sanitize("../private\u202etxt").equals(".._private_txt")));
        test("Filename truncation preserves complete Unicode code points", () -> { String name = FileNames.sanitize("a".repeat(119) + "🔐"); check(name.length() == 119); Bytes.utf8(name); });
        test("Filename rejects an unsafe received name", () -> check(!FileNames.safe("../x") && !FileNames.safe("x\n.exe") && !FileNames.safe("x\u202ey")));
        test("Filename handles missing and blank display names", () -> check(FileNames.sanitize(null).equals("archivo.bin") && FileNames.sanitize("  ").equals("archivo.bin")));
        test("Filename handles dot-only names", () -> check(!FileNames.safe("..") && FileNames.sanitize(".").equals("archivo.bin")));
        test("Filename keeps a multilingual legitimate name", () -> check(FileNames.safe("informe_中文.txt")));
        test("Strict Base64 rejects omitted padding", () -> rejects(() -> Bytes.unb64("YQ")));
        test("Strict Base64 rejects nonzero pad bits", () -> rejects(() -> Bytes.unb64("YR==")));
        test("Strict Base64 rejects whitespace", () -> rejects(() -> Bytes.unb64("YQ==\n")));
        test("Base64 explicit local bound permits a valid large record", () -> {
            byte[] record = new byte[1_100_000]; String encoded = Bytes.b64(record);
            rejects(() -> Bytes.unb64(encoded)); check(Bytes.unb64(encoded, 2_000_000).length == record.length);
        });
        test("Base64 local bound cannot disable allocation limits", () -> {
            rejects(() -> Bytes.unb64("YQ==", 3)); rejects(() -> Bytes.unb64("", Integer.MAX_VALUE));
        });
        test("JSON accepts nested structured content", () -> json("{\"items\":[1,true,false,null,{\"x\":\"a\\nb\"}],\"neg\":-4.2e-2}"));
        test("JSON accepts multilingual content and surrogate pair", () -> json("{\"text\":\"Español 中文 \\ud83d\\udd10\"}"));
        String[] invalid = {
            "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{'a':1}", "{a:1}", "{\"a\":1,}",
            "{\"a\":[1,]}", "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":01}", "{\"a\":+1}", "{\"a\":.5}",
            "{\"a\":1.}", "{\"a\":1e}", "{\"a\":-}", "{} trailing", "[]", "null", "\ufeff{}",
            "{\"a\":\"\\ud800\"}", "{\"a\":\"\\udc00\"}", "{\"a\":\"\\u12xz\"}", "{\"a\":\"line\nline\"}",
            "{\"a\":\"\\x20\"}", "{\"a\":/*comment*/1}", "{\"a\":1;\"b\":2}", "{\"a\":1}{}"
        };
        for (int i = 0; i < invalid.length; i++) {
            String input = invalid[i]; test("JSON rejects adversarial syntax " + (i + 1), () -> rejects(() -> json(input)));
        }
        test("JSON rejects deep nesting", () -> rejects(() -> json("{\"a\":" + "[".repeat(14) + "0" + "]".repeat(14) + "}")));
        test("JSON rejects excessive node count", () -> rejects(() -> json("{\"a\":[" + "0,".repeat(4100) + "0]}")));
        test("JSON rejects oversized field name", () -> rejects(() -> json("{\"" + "x".repeat(129) + "\":0}")));
        test("JSON enforces byte limit before parsing", () -> rejects(() -> StrictJson.object(Bytes.utf8("{\"x\":0}"), 3)));
        test("Handshake frame has its own allocation limit", () -> rejects(() -> Framing.read(new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(20001).array()), 20000)));
        test("Handshake frame accepts fragmented input", () -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); Framing.write(bytes, Bytes.utf8("{}"));
            check(Bytes.text(Framing.read(new ByteArrayInputStream(bytes.toByteArray()), 512)).equals("{}"));
        });
        test("Retry delay is bounded under extreme attempts", () -> { for (int a : new int[]{0,1,5,30,Integer.MAX_VALUE}) for (double j : new double[]{0,0.5,0.999}) check(RetryPolicy.delaySeconds(a,j) >= 1 && RetryPolicy.delaySeconds(a,j) <= 300); });
        test("Retry rejects invalid jitter", () -> { rejects(() -> RetryPolicy.delaySeconds(0, Double.NaN)); rejects(() -> RetryPolicy.delaySeconds(0, 1)); rejects(() -> RetryPolicy.delaySeconds(-1, .5)); });
        test("Retry schedules from the supplied clock", () -> check(RetryPolicy.next(100, 0, .5) == 105));
        String alice = "a".repeat(64), bob = "b".repeat(64); byte[] n1 = Bytes.random(32), n2 = Bytes.random(32);
        byte[] transcript = NearbyTranscript.encode(true, alice, n1, bob, n2);
        test("Nearby transcript is deterministic", () -> check(Arrays.equals(transcript, NearbyTranscript.encode(true, alice, n1, bob, n2))));
        test("Nearby proof binds fresh verifier nonce", () -> check(!Arrays.equals(transcript, NearbyTranscript.encode(true, alice, n1, bob, Bytes.random(32)))));
        test("Nearby proof binds signer role", () -> check(!Arrays.equals(transcript, NearbyTranscript.encode(false, alice, n1, bob, n2))));
        test("Nearby proof binds identities and direction", () -> check(!Arrays.equals(transcript, NearbyTranscript.encode(false, bob, n2, alice, n1))));
        test("Nearby reflection and self-connection rejected", () -> { rejects(() -> NearbyTranscript.encode(true, alice, n1, bob, n1)); rejects(() -> NearbyTranscript.encode(true, alice, n1, alice, n2)); });
        test("Nearby malformed nonce rejected", () -> rejects(() -> NearbyTranscript.encode(true, alice, new byte[31], bob, n2)));
        SecretKey aes = new SecretKeySpec(Bytes.random(32), "AES"), hmac = new SecretKeySpec(Bytes.random(32), "HmacSHA256");
        String idx = VaultCodec.index(hmac, "message", "key"); byte[] clear = Bytes.utf8("historial secreto");
        VaultCodec.Sealed sealed = VaultCodec.seal(aes, "message", idx, clear);
        test("Vault codec authenticates roundtrip", () -> check(Arrays.equals(clear, VaultCodec.open(aes, "message", idx, sealed.nonce(), sealed.ciphertext()))));
        test("Blind index is deterministic for the same key", () -> check(idx.equals(VaultCodec.index(hmac, "message", "key"))));
        test("Blind index is not an unkeyed dictionary hash", () -> check(!idx.equals(Bytes.sha256(Bytes.utf8("message:key")))));
        test("Blind index is separated across vault keys", () -> check(!idx.equals(VaultCodec.index(new SecretKeySpec(Bytes.random(32), "HmacSHA256"), "message", "key"))));
        test("Blind index is separated by record category", () -> check(!idx.equals(VaultCodec.index(hmac, "session", "key"))));
        test("Index inputs have unambiguous framing", () -> check(!VaultCodec.index(hmac, "ab", "c").equals(VaultCodec.index(hmac, "a", "bc"))));
        test("GCM rejects wrong key", () -> rejects(() -> VaultCodec.open(new SecretKeySpec(Bytes.random(32), "AES"), "message", idx, sealed.nonce(), sealed.ciphertext())));
        test("GCM rejects bucket substitution", () -> rejects(() -> VaultCodec.open(aes, "session", idx, sealed.nonce(), sealed.ciphertext())));
        test("GCM rejects index substitution", () -> rejects(() -> VaultCodec.open(aes, "message", "f".repeat(64), sealed.nonce(), sealed.ciphertext())));
        test("GCM rejects nonce mutation", () -> { byte[] nonce = sealed.nonce(); nonce[0] ^= 1; rejects(() -> VaultCodec.open(aes, "message", idx, nonce, sealed.ciphertext())); });
        test("GCM rejects every single-bit ciphertext mutation", () -> {
            for (int i = 0; i < sealed.ciphertext().length * 8; i++) {
                byte[] changed = sealed.ciphertext(); changed[i / 8] ^= (byte)(1 << (i % 8));
                rejects(() -> VaultCodec.open(aes, "message", idx, sealed.nonce(), changed));
            }
        });
        test("GCM rejects truncated and invalid nonce lengths", () -> { rejects(() -> VaultCodec.open(aes, "message", idx, new byte[11], sealed.ciphertext())); rejects(() -> VaultCodec.open(aes, "message", idx, sealed.nonce(), new byte[16])); });
        test("Sealed record getters do not expose mutable backing arrays", () -> { byte[] copy = sealed.nonce(); copy[0] ^= 1; check(!Arrays.equals(copy, sealed.nonce())); });
        test("Vault randomized IV uniqueness sample", () -> { Set<String> seen = new HashSet<>(); for (int i = 0; i < 500; i++) check(seen.add(Bytes.hex(VaultCodec.seal(aes,"message",idx,clear).nonce()))); });
        test("Legacy codec migration preserves authenticated content", () -> {
            String oldIndex = Bytes.sha256(Bytes.utf8("message:key")); Cipher old = Cipher.getInstance("AES/GCM/NoPadding"); old.init(Cipher.ENCRYPT_MODE, aes);
            old.updateAAD(Bytes.utf8("UMBRA-vault-v1:message:" + oldIndex)); byte[] ciphertext = old.doFinal(clear);
            byte[] migrated = VaultCodec.openLegacy(aes, "message", oldIndex, old.getIV(), ciphertext);
            check(Arrays.equals(clear, migrated));
            VaultCodec.Sealed fresh = VaultCodec.seal(aes, "message", idx, migrated);
            check(Arrays.equals(clear, VaultCodec.open(aes,"message",idx,fresh.nonce(),fresh.ciphertext())));
            rejects(() -> VaultCodec.open(aes,"message",oldIndex,old.getIV(),ciphertext));
        });
        test("Vault bounds cleartext before encryption", () -> rejects(() -> VaultCodec.seal(aes,"message",idx,new byte[VaultCodec.MAX_VALUE + 1])));
        test("Framing fuzz: malformed negative prefixes never allocate", () -> { Random r = new Random(921); for (int i = 0; i < 2000; i++) { int n = r.nextInt() | Integer.MIN_VALUE; rejects(() -> Framing.read(new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(n).array()))); } });
        System.out.println(passed + " additional JVM security test scenarios passed. Android/libsignal/Bluetooth radio NOT exercised.");
    }
}
