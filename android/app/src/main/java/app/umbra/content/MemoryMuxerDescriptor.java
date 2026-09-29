package app.umbra.content;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.util.Arrays;

/** Anonymous, kernel-growth-bounded RAM descriptor compatible with framework muxer statfs.
 * No pathname, external URI, plaintext disk file or JNI dependency. */
final class MemoryMuxerDescriptor implements AutoCloseable {
    // Linux UAPI fcntl/memfd constants, identical on all four supported Android ABIs.
    // Android exposes the syscalls since API30, but not these seal constants in OsConstants.
    private static final int MFD_ALLOW_SEALING=2,F_ADD_SEALS=1033,F_GET_SEALS=1034;
    private static final int F_SEAL_SEAL=1,F_SEAL_GROW=4;
    // Includes framework preallocation slack; final content limit remains 256 KiB.
    static final int WORK_LIMIT=8*RestrictedPayload.MAX_BYTES;
    private final Runnable authorization;
    private FileDescriptor descriptor;
    MemoryMuxerDescriptor(Context context,Runnable authorization)throws Exception {
        this.authorization=authorization;authorization.run();
        try {
            descriptor=Os.memfd_create("umbra-video",OsConstants.MFD_CLOEXEC|MFD_ALLOW_SEALING);
            Os.ftruncate(descriptor,WORK_LIMIT);
            Os.fcntlInt(descriptor,F_ADD_SEALS,F_SEAL_GROW|F_SEAL_SEAL);
            if((Os.fcntlInt(descriptor,F_GET_SEALS,0)&(F_SEAL_GROW|F_SEAL_SEAL))!=(F_SEAL_GROW|F_SEAL_SEAL))throw RestrictedPayload.invalid();
            // Framework MPEG4Writer calls fpathconf/statfs at start. Do not pass an
            // app FUSE proxy that cannot implement the filesystem query.
            Os.fstatvfs(descriptor);authorization.run();
        }catch(Exception failure){try{close();}catch(Exception cleanup){RestrictedVideo.markCleanupFailure(authorization);failure.addSuppressed(cleanup);}throw failure;}
    }
    synchronized FileDescriptor descriptor(){authorization.run();if(descriptor==null)throw RestrictedPayload.invalid();return descriptor;}
    synchronized byte[] copy()throws Exception {
        authorization.run();if(descriptor==null)throw RestrictedPayload.invalid();
        // The framework must trim its own preallocation on successful stop. Never
        // guess file length by stripping zeros or parse/rewrite MP4 ourselves.
        long length=Os.fstat(descriptor).st_size;
        if(length<1 || length>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
        byte[] result=new byte[(int)length];boolean success=false;
        try {
            int position=0;
            while(position<result.length) {
                authorization.run();int count=Os.pread(descriptor,result,position,Math.min(8192,result.length-position),position);
                if(count<=0)throw RestrictedPayload.invalid();position+=count;
            }
            authorization.run();success=true;return result;
        }finally{if(!success)Arrays.fill(result,(byte)0);}
    }
    @Override public synchronized void close()throws Exception {
        if(descriptor==null)return;FileDescriptor owned=descriptor;descriptor=null;
        try {
            long length=Os.fstat(owned).st_size;
            if(length<0 || length>WORK_LIMIT)throw RestrictedPayload.invalid();
            byte[] zero=new byte[8192];long position=0;
            while(position<length) {
                int count=Os.pwrite(owned,zero,0,(int)Math.min(zero.length,length-position),position);
                if(count<=0)throw RestrictedPayload.invalid();position+=count;
            }
        }finally{Os.close(owned);}
    }
}
