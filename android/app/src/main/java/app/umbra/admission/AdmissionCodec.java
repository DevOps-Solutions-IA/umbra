package app.umbra.admission;

import app.umbra.core.Bytes;
import java.util.Base64;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

/** Canonical public transcripts. Cryptographic operations are the pinned BC implementation. */
final class AdmissionCodec {
    static final int MAX_WIRE = 4096;
    private AdmissionCodec() {}
    static SecurityException invalid() { return new SecurityException("Admission unavailable"); }
    static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static byte[] decode(String value, int length) {
        if (value == null || value.length() > MAX_WIRE || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(value);
            if ((length >= 0 && raw.length != length) || !encode(raw).equals(value)) throw invalid();
            return raw;
        } catch (IllegalArgumentException e) { throw invalid(); }
    }
    static String random() { return Bytes.token(); }
    static String token(String value) { decode(value,32); return value; }
    static String publicKey(String value) {
        byte[] raw = decode(value,32);
        if (!Ed25519.validatePublicKeyFull(raw,0)) throw invalid();
        return value;
    }
    static String signalKey(String value) {
        byte[] raw = decode(value,33);
        if (raw[0] != 5) throw invalid();
        return value;
    }
    static long number(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,11}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw invalid(); }
    }
    static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid();
        return value;
    }
    static byte[] publicFromSeed(byte[] seed) {
        if (seed == null || seed.length != 32) throw invalid();
        byte[] result = new byte[32]; Ed25519.generatePublicKey(seed,0,result,0); return result;
    }
    static byte[] body(String type, String... fields) {
        StringBuilder out = new StringBuilder("UMBRA-ADMISSION-").append(type).append("-1\n");
        for (String field : fields) {
            if (field == null || !field.matches("[A-Za-z0-9_:/.-]{1,512}")) throw invalid();
            out.append(field).append('\n');
        }
        byte[] raw = Bytes.utf8(out.toString());
        if (raw.length > 2048) throw invalid();
        return raw;
    }
    static String sign(String type, byte[] seed, String... fields) {
        if (seed == null || seed.length != 32) throw invalid();
        byte[] data = body(type,fields), signature = new byte[64];
        Ed25519.sign(seed,0,data,0,data.length,signature,0);
        return "umbra:admission:"+type+":1:"+encode(data)+"."+encode(signature);
    }
    static String[] fields(String type, String wire, int count) {
        if (wire == null || wire.length() > MAX_WIRE) throw invalid();
        String prefix = "umbra:admission:"+type+":1:";
        if (!wire.startsWith(prefix)) throw invalid();
        String[] parts = wire.substring(prefix.length()).split("\\.",-1);
        if (parts.length != 2) throw invalid();
        decode(parts[1],64);
        byte[] raw = decode(parts[0],-1);
        String[] lines = Bytes.text(raw).split("\n",-1);
        if (lines.length != count+2 || !lines[0].equals("UMBRA-ADMISSION-"+type+"-1") || !lines[count+1].isEmpty()) throw invalid();
        String[] fields = java.util.Arrays.copyOfRange(lines,1,count+1);
        if (!Bytes.equal(raw,body(type,fields))) throw invalid();
        return fields;
    }
    static void verify(String type, String wire, String publicKey, String... fields) {
        byte[] key = decode(publicKey(publicKey),32), data = body(type,fields);
        byte[] sig = decode(wire.substring(wire.lastIndexOf('.')+1),64);
        if (!Ed25519.verify(sig,0,key,0,data,0,data.length)) throw invalid();
    }
    static void interval(long start,long end,long maximum) {
        if (start <= 0 || end <= start || end-start > maximum || end > 999999999999L) throw invalid();
    }
}
