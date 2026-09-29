package app.umbra.content;

import android.content.Context;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.util.concurrent.atomic.AtomicBoolean;

/** Framework MP4 muxer seek/write bridge. Plaintext remains in bounded RAM. */
final class MemoryMuxerDescriptor implements AutoCloseable {
    private final HandlerThread thread=new HandlerThread("umbra-video-muxer");
    private final AtomicBoolean denied=new AtomicBoolean();
    private final BoundedMediaBuffer buffer=new BoundedMediaBuffer();
    private final Runnable authorization;
    private ParcelFileDescriptor descriptor;
    MemoryMuxerDescriptor(Context context,Runnable authorization)throws Exception {
        this.authorization=authorization;authorization.run();thread.start();
        try {
            descriptor=context.getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE,
                new ProxyFileDescriptorCallback(){
                    @Override public long onGetSize()throws ErrnoException{check();return buffer.size();}
                    @Override public int onRead(long offset,int size,byte[] target)throws ErrnoException {
                        check();try{return buffer.read(offset,size,target);}catch(RuntimeException invalid){throw new ErrnoException("media read",OsConstants.EINVAL);}
                    }
                    @Override public int onWrite(long offset,int size,byte[] source)throws ErrnoException {
                        check();try{int count=buffer.write(offset,size,source);check();return count;}
                        catch(RuntimeException invalid){throw new ErrnoException("media write",OsConstants.EFBIG);}
                    }
                    @Override public void onFsync()throws ErrnoException{check();}
                    @Override public void onRelease(){denied.set(true);}
                },new Handler(thread.getLooper()));
        }catch(Exception failure){try{close();}catch(Exception cleanup){RestrictedVideo.markCleanupFailure(authorization);failure.addSuppressed(cleanup);}throw failure;}
    }
    java.io.FileDescriptor descriptor(){authorization.run();if(denied.get())throw RestrictedPayload.invalid();return descriptor.getFileDescriptor();}
    byte[] copy(){authorization.run();if(denied.get())throw RestrictedPayload.invalid();return buffer.copy();}
    private void check()throws ErrnoException {
        if(denied.get())throw new ErrnoException("media unavailable",OsConstants.EACCES);
        try{authorization.run();}catch(RuntimeException failure){throw new ErrnoException("media unavailable",OsConstants.EACCES);}
    }
    @Override public void close()throws Exception {
        denied.set(true);
        try{if(descriptor!=null)descriptor.close();}finally{buffer.close();thread.quitSafely();thread.join(2000);}
        if(thread.isAlive())throw new IllegalStateException("Media muxer worker closure unconfirmed");
    }
}
