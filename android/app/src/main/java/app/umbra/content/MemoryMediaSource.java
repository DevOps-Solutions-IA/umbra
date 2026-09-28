package app.umbra.content;

import android.media.MediaDataSource;
import java.io.IOException;

/** Borrowed, bounded RAM owned by preparation or the restricted session; no files or URI. */
final class MemoryMediaSource extends MediaDataSource {
    private byte[] bytes;
    private final Runnable authorization;
    private long remaining=32L*1024*1024;
    MemoryMediaSource(byte[] bytes,Runnable authorization) {
        if(bytes==null || bytes.length==0 || bytes.length>4*1024*1024)throw RestrictedPayload.invalid();
        this.bytes=bytes;this.authorization=authorization;
    }
    @Override public synchronized int readAt(long position,byte[] target,int offset,int size)throws IOException {
        authorization.run();
        if(bytes==null || position<0 || offset<0 || size<0 || offset>target.length-size)throw new IOException("Media source unavailable");
        if(size==0)return 0;
        if(position>=bytes.length)return -1;
        int count=(int)Math.min(size,bytes.length-position);
        if(count>remaining)throw new IOException("Media read budget exceeded");
        remaining-=count;System.arraycopy(bytes,(int)position,target,offset,count);authorization.run();return count;
    }
    @Override public synchronized long getSize()throws IOException {
        authorization.run();if(bytes==null)throw new IOException("Media source closed");return bytes.length;
    }
    @Override public synchronized void close(){bytes=null;}
}
