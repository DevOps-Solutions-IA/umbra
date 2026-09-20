package app.umbra;

import app.umbra.core.AccessGate;
import app.umbra.core.DocumentIO;
import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Real streams with deterministic provider delays; not Android provider or hardware coverage. */
public class DocumentIOTest {
    private static AccessGate unlocked() { AccessGate gate = new AccessGate(); gate.unlock(); return gate; }
    @Test public void exportRejectsReauthenticationDuringProviderOpen() throws Exception {
        AccessGate gate = unlocked(); AccessGate.Lease lease = gate.enter();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        assertThrows(AccessGate.LockedException.class, () -> DocumentIO.write(gate, lease, new byte[16384], () -> {
            gate.lock(); gate.unlock(); return target;
        }));
        assertEquals(0, target.size());
    }
    @Test public void exportRejectsStaleLeaseBeforeOpeningProvider() throws Exception {
        AccessGate gate = unlocked(); AccessGate.Lease lease = gate.enter(); gate.lock(); gate.unlock();
        assertThrows(AccessGate.LockedException.class, () -> DocumentIO.write(gate, lease, new byte[1], () -> {
            fail("Stale export must not open or truncate a document"); return null;
        }));
    }
    @Test public void exportStopsAfterLockDuringWriteAndClosesStream() throws Exception {
        AccessGate gate = unlocked();
        class LockingStream extends ByteArrayOutputStream {
            boolean closed;
            @Override public synchronized void write(byte[] data, int offset, int size) {
                super.write(data, offset, size); gate.lock(); gate.unlock();
            }
            @Override public void close() { closed = true; }
        }
        LockingStream target = new LockingStream();
        assertThrows(AccessGate.LockedException.class, () -> DocumentIO.write(gate, gate.enter(), new byte[20000], () -> target));
        assertEquals(8192, target.size()); assertTrue(target.closed);
    }
    @Test public void exportsExactContentAcrossChunks() throws Exception {
        AccessGate gate = unlocked(); byte[] content = new byte[20000]; Arrays.fill(content, (byte) 37);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        DocumentIO.write(gate, gate.enter(), content, () -> target);
        assertArrayEquals(content, target.toByteArray());
    }
    @Test public void readRejectsReauthenticationDuringOpenWithoutReading() throws Exception {
        AccessGate gate = unlocked();
        assertThrows(AccessGate.LockedException.class, () -> DocumentIO.read(gate, 100, () -> {
            gate.lock(); gate.unlock();
            return new InputStream() { @Override public int read() { fail("Stale read"); return -1; } };
        }));
    }
    @Test public void readRejectsReauthenticationAtEndOfStream() throws Exception {
        AccessGate gate = unlocked();
        assertThrows(AccessGate.LockedException.class, () -> DocumentIO.read(gate, 100, () -> new InputStream() {
            @Override public int read() { gate.lock(); gate.unlock(); return -1; }
        }));
    }
    @Test public void readEnforcesExactLimit() throws Exception {
        AccessGate gate = unlocked(); byte[] content = new byte[8193]; Arrays.fill(content, (byte) 9);
        assertArrayEquals(content, DocumentIO.read(gate, content.length, () -> new ByteArrayInputStream(content)));
        assertThrows(IllegalArgumentException.class, () -> DocumentIO.read(gate, content.length - 1, () -> new ByteArrayInputStream(content)));
    }
    @Test public void readRejectsNonProgressAndClosesProvider() throws Exception {
        AccessGate gate = unlocked();
        class StalledStream extends InputStream {
            boolean closed;
            @Override public int read() { return 0; }
            @Override public int read(byte[] data) { return 0; }
            @Override public void close() { closed = true; }
        }
        StalledStream source = new StalledStream();
        assertThrows(IOException.class, () -> DocumentIO.read(gate, 100, () -> source));
        assertTrue(source.closed);
    }
}
