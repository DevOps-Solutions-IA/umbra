package app.umbra.content;

import android.content.Context;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only seekable RAM bridge, never a URI or plaintext filesystem temporary. */
final class MemoryDocumentDescriptor implements AutoCloseable {
    private final HandlerThread thread=new HandlerThread("umbra-document-reads");
    private final AtomicBoolean denied=new AtomicBoolean();
    private final byte[] bytes;
    private final Runnable authorization;
    ParcelFileDescriptor descriptor;
    MemoryDocumentDescriptor(byte[] input,Runnable authorization) {
        if(input==null || input.length<1 || input.length>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
        this.authorization=authorization;authorization.run();bytes=input.clone();
    }
    void open(Context context)throws Exception {
        authorization.run();thread.start();
            descriptor=context.getSystemService(StorageManager.class).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,new ProxyFileDescriptorCallback(){
                    @Override public long onGetSize()throws ErrnoException{check();return bytes.length;}
                    @Override public int onRead(long offset,int size,byte[] target)throws ErrnoException {
                        check();if(offset<0 || size<0 || size>target.length)throw new ErrnoException("document read",OsConstants.EINVAL);
                        if(offset>=bytes.length)return 0;
                        int count=Math.min(size,bytes.length-(int)offset);
                        synchronized(bytes){if(denied.get())throw new ErrnoException("document unavailable",OsConstants.EACCES);System.arraycopy(bytes,(int)offset,target,0,count);}
                        try{check();}catch(ErrnoException failure){Arrays.fill(target,0,count,(byte)0);throw failure;}
                        return count;
                    }
                    @Override public void onRelease(){invalidate();wipe();}
                },new Handler(thread.getLooper()));

    }
    private void check()throws ErrnoException {
        if(denied.get())throw new ErrnoException("document unavailable",OsConstants.EACCES);
        try{authorization.run();}catch(RuntimeException unavailable){throw new ErrnoException("document unavailable",OsConstants.EACCES);}
        if(denied.get())throw new ErrnoException("document unavailable",OsConstants.EACCES);
    }
    void invalidate(){denied.set(true);}
    private void wipe(){synchronized(bytes){Arrays.fill(bytes,(byte)0);}}
    @Override public void close()throws Exception {
        invalidate();wipe();
        try{if(descriptor!=null)descriptor.close();}finally{thread.quitSafely();thread.join(2000);}
        if(thread.isAlive())throw new IllegalStateException("Document read worker closure unconfirmed");
    }
}
