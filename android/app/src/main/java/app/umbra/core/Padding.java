package app.umbra.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Random 1 KiB buckets hide exact content length, not traffic volume or timing. */
public final class Padding {
    public static final int MAX_CLEAR = 600_000;
    private Padding() {}
    public static byte[] pad(byte[] clear) {
        if (clear.length < 1 || clear.length > MAX_CLEAR) throw new IllegalArgumentException("Invalid message length");
        int size = ((clear.length + 4 + 1023) / 1024) * 1024;
        byte[] padded = Bytes.random(size);
        ByteBuffer.wrap(padded).putInt(clear.length).put(clear); return padded;
    }
    public static byte[] unpad(byte[] padded) {
        if (padded.length < 1024 || padded.length % 1024 != 0 || padded.length > MAX_CLEAR + 1027)
            throw new IllegalArgumentException("Invalid padding");
        int length = ByteBuffer.wrap(padded).getInt();
        if (length < 1 || length > MAX_CLEAR || length > padded.length - 4)
            throw new IllegalArgumentException("Invalid plaintext length");
        return Arrays.copyOfRange(padded, 4, 4 + length);
    }
}
