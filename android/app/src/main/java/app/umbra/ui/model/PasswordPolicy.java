package app.umbra.ui.model;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Presentation-side helper for the personal password contract (docs/VAULT_PASSWORD.md): UTF-8 bytes,
 * 12–1024 bytes, no trim or normalization. This only gives early feedback and produces the caller-owned
 * byte[]; the domain re-validates and decides. Nothing here stores, logs or compares against a secret.
 */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static final int MIN_BYTES = 12, MAX_BYTES = 1024;
    /** Process-local auto-lock choices; the domain maximum is 240000 ms (no five-minute option in v1). */
    public static final long[] AUTO_LOCK_MILLIS = {60_000, 120_000, 240_000};
    public static final String[] AUTO_LOCK_LABELS = {"1 min", "2 min", "4 min"};
    public static final int DEFAULT_AUTO_LOCK_INDEX = 2;

    public enum Problem {
        OK(null),
        EMPTY("Escribe la contraseña."),
        TOO_SHORT("Muy corta: mínimo " + MIN_BYTES + "."),
        TOO_LONG("Muy larga: máximo " + MAX_BYTES + " bytes."),
        INVALID_CHARACTER("Carácter no válido."),
        MISMATCH("No coinciden.");
        public final String message;
        Problem(String message) { this.message = message; }
    }

    /** UTF-8 length in bytes without building a String; -1 for an unpaired surrogate. */
    public static int utf8Length(CharSequence text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) bytes += 1;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return -1;
                bytes += 4; i++;
            } else if (Character.isLowSurrogate(c)) return -1;
            else bytes += 3;
        }
        return bytes;
    }

    /** Checks a single entry (unlock) against the byte bounds. */
    public static Problem check(CharSequence password) {
        if (password == null || password.length() == 0) return Problem.EMPTY;
        int length = utf8Length(password);
        if (length < 0) return Problem.INVALID_CHARACTER;
        if (length < MIN_BYTES) return Problem.TOO_SHORT;
        if (length > MAX_BYTES) return Problem.TOO_LONG;
        return Problem.OK;
    }

    /** Checks a new password and its confirmation, character by character (no String copies). */
    public static Problem checkNew(CharSequence password, CharSequence confirmation) {
        Problem single = check(password);
        if (single != Problem.OK) return single;
        return sameChars(password, confirmation) ? Problem.OK : Problem.MISMATCH;
    }

    public static boolean sameChars(CharSequence a, CharSequence b) {
        if (a == null || b == null) return false;
        int diff = a.length() ^ b.length();
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    /**
     * Exact UTF-8 bytes of the text as typed (no trim, no normalization). The returned array is owned by
     * the caller, who must erase it after the domain call returns or fails. Intermediate buffers are wiped.
     */
    public static byte[] encode(CharSequence password) throws CharacterCodingException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer buffer = encoder.encode(CharBuffer.wrap(password));
        byte[] backing = buffer.array();
        try {
            byte[] exact = new byte[buffer.remaining()];
            buffer.get(exact);
            return exact;
        } finally { Arrays.fill(backing, (byte) 0); }
    }

    public static void erase(byte[] secret) { if (secret != null) Arrays.fill(secret, (byte) 0); }

    public static int autoLockIndex(long millis) {
        for (int i = 0; i < AUTO_LOCK_MILLIS.length; i++) if (AUTO_LOCK_MILLIS[i] == millis) return i;
        return DEFAULT_AUTO_LOCK_INDEX;
    }
}
