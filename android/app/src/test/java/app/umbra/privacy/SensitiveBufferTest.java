package app.umbra.privacy;
import org.junit.Test;
import static org.junit.Assert.*;
public class SensitiveBufferTest {
    @Test public void ownershipIsSingleTransferAndOriginalIsNotModified() {
        char[] original={'a','b'};
        var buffer=new SensitiveBuffer(original);char[] owned=buffer.take();
        assertNotSame(original,owned);assertArrayEquals(original,owned);
        assertThrows(IllegalStateException.class,buffer::take);buffer.close();
        java.util.Arrays.fill(owned,'\0');assertArrayEquals(new char[]{'a','b'},original);
        assertEquals("SensitiveBuffer[redacted]",buffer.toString());
    }
    @Test public void closedAndOversizedInputsFailClosed() {
        var buffer=new SensitiveBuffer(new char[]{'a'});buffer.close();buffer.close();
        assertThrows(IllegalStateException.class,buffer::take);
        assertThrows(PrivacyException.class,()->new SensitiveBuffer(new char[SensitiveBuffer.MAX_CHARS+1]));
    }
}
