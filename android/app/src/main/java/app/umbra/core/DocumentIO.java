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
        try (Transfer transfer=new Transfer(gate)) {
            OutputStream output=transfer.attach(provider.open());
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
        try (Transfer transfer=new Transfer(gate); ClearableBuffer output = new ClearableBuffer()) {
            InputStream input=transfer.attach(provider.open());
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

    /** Covers provider.open too: an uninterruptible provider remains INCOMPLETE, not falsely closed. */
    private static final class Transfer implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicReference<Closeable> stream=new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.CompletableFuture<Void> finished=new java.util.concurrent.CompletableFuture<>();
        private final EmergencyLock.Registration registration;
        Transfer(AccessGate gate) {
            registration=gate.emergency().register(EmergencyLock.Subsystem.DOCUMENTS,()->{
                cancelled.set(true);
                try { closeStream(); } catch(Exception failure) { finished.completeExceptionally(new IOException("Document closure failed")); }
                return finished;
            });
        }
        <T extends Closeable> T attach(T value) throws IOException {
            stream.set(value); if(cancelled.get()) { closeStream();throw new IOException("Document cancelled"); } return value;
        }
        private IOException closeFailure;
        private synchronized void closeStream() throws IOException {
            // Serialize normal completion with the cancellation close. Taking the reference
            // alone does not prove the provider has closed, and must not hide its late failure.
            if(closeFailure!=null)throw closeFailure;
            Closeable value=stream.getAndSet(null);
            try {if(value!=null)value.close();}
            catch(IOException | RuntimeException failure) {
                closeFailure=new IOException("Document closure failed");throw closeFailure;
            }
        }
        @Override public void close() throws IOException {
            try { closeStream();finished.complete(null); }
            catch(IOException failure) {finished.completeExceptionally(new IOException("Document closure failed"));throw failure;}
            finally {if(!finished.isCompletedExceptionally())registration.close();}
        }
    }

    private static final class ClearableBuffer extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
