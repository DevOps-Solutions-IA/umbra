package app.umbra.content;

import java.util.Arrays;

/** Bounded random-access muxer destination; no filesystem plaintext or external handle. */
final class BoundedMediaBuffer implements AutoCloseable {
    private byte[] bytes=new byte[RestrictedPayload.MAX_BYTES];
    private int length;
    private long remaining=8L*RestrictedPayload.MAX_BYTES;
    synchronized int size(){check();return length;}
    private void check(){if(bytes==null)throw RestrictedPayload.invalid();}
    synchronized int write(long offset,int count,byte[] source) {
        check();
        if(offset<0 || offset>bytes.length || count<0 || count>source.length || count>bytes.length-offset || count>remaining)
            throw RestrictedPayload.invalid();
        System.arraycopy(source,0,bytes,(int)offset,count);remaining-=count;length=Math.max(length,(int)offset+count);return count;
    }
    synchronized int read(long offset,int count,byte[] target) {
        check();if(offset<0 || count<0 || count>target.length)throw RestrictedPayload.invalid();
        if(offset>=length)return 0;int copied=Math.min(count,length-(int)offset);System.arraycopy(bytes,(int)offset,target,0,copied);return copied;
    }
    synchronized byte[] copy(){check();if(length==0)throw RestrictedPayload.invalid();return Arrays.copyOf(bytes,length);}
    @Override public synchronized void close(){if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;length=0;}
}
