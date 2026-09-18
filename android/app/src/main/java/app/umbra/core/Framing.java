package app.umbra.core;

import java.io.*;

/** One bounded JSON frame per RFCOMM record; no Java object deserialization. */
public final class Framing {
    public static final int MAX_FRAME = 1_000_000;
    private Framing() {}
    public static byte[] read(InputStream input) throws IOException {
        return read(input, MAX_FRAME);
    }
    public static byte[] read(InputStream input, int maximum) throws IOException {
        if (maximum < 1 || maximum > MAX_FRAME) throw new IllegalArgumentException("Invalid frame bound");
        DataInputStream stream = new DataInputStream(input);
        int length = stream.readInt();
        if (length < 1 || length > maximum) throw new IOException("Invalid frame length");
        byte[] frame = new byte[length]; stream.readFully(frame); return frame;
    }
    public static void write(OutputStream output, byte[] frame) throws IOException {
        if (frame.length < 1 || frame.length > MAX_FRAME) throw new IOException("Invalid frame length");
        DataOutputStream stream = new DataOutputStream(output);
        synchronized (output) { stream.writeInt(frame.length); stream.write(frame); stream.flush(); }
    }
}
