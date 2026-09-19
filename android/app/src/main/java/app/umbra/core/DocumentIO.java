package app.umbra.core;

import java.io.*;
import java.util.Arrays;

/** Session-bound document transfers. A provider call already in progress cannot be revoked. */
public final class DocumentIO {
    private static final int CHUNK = 8192;
    private DocumentIO() {}
    public interface Open<T extends Closeable> { T open() throws Exception; }

    public static void write(AccessGate gate, AccessGate.Lease lease, byte[] content,
                             Open<OutputStream> provider) throws Exception {
        gate.check(lease);
        try (OutputStream output = provider.open()) {
            if (output == null) throw new IOException("No output");
            gate.check(lease);
            for (int offset = 0; offset < content.length; offset += CHUNK) {
                gate.check(lease);
                output.write(content, offset, Math.min(CHUNK, content.length - offset));
                gate.check(lease);
            }
            gate.check(lease); output.flush(); gate.check(lease);
        }
    }

    public static byte[] read(AccessGate gate, int maximum, Open<InputStream> provider) throws Exception {
        if (maximum < 1) throw new IllegalArgumentException("Invalid document bound");
        AccessGate.Lease lease = gate.enter();
        try (InputStream input = provider.open(); ClearableBuffer output = new ClearableBuffer()) {
            if (input == null) throw new IOException("No input");
            byte[] buffer = new byte[CHUNK];
            try {
                while (true) {
                    gate.check(lease);
                    int count = input.read(buffer);
                    gate.check(lease);
                    if (count == -1) return output.toByteArray();
                    if (count == 0) throw new IOException("Document provider made no progress");
                    if (count > maximum - output.size())
                        throw new IllegalArgumentException("El archivo supera el tamaño permitido");
                    output.write(buffer, 0, count);
                }
            } finally { Arrays.fill(buffer, (byte) 0); }
        }
    }

    private static final class ClearableBuffer extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
