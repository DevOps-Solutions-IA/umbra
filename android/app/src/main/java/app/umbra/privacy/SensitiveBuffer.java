package app.umbra.privacy;

import java.util.Arrays;
/** Bounded owned input; no String conversion, serialisation or diagnostic content. */
public final class SensitiveBuffer implements AutoCloseable {
    public static final int MAX_CHARS=1024;
    private char[] value;
    public SensitiveBuffer(char[] source) {
        if(source==null || source.length>MAX_CHARS) throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);
        value=source.clone();
    }
    /** A single ownership transfer; consumer must wipe its returned buffer after use. */
    public synchronized char[] take() {
        if(value==null) throw new IllegalStateException("Sensitive input unavailable");
        char[] result=value;value=null;return result;
    }
    @Override public synchronized void close() { if(value!=null)Arrays.fill(value,'\0');value=null; }
    @Override public String toString() { return "SensitiveBuffer[redacted]"; }
}
