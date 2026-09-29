package app.umbra.content;

import android.content.Context;
import java.util.Arrays;

/** Test APK only. Patterns enter real AVC encoder; observations follow real remote decoder. */
public final class SyntheticRestrictedVideo {
    private SyntheticRestrictedVideo() {}
    public static void stage(String value) {
        var status=new android.os.Bundle();status.putString("restrictedVideoStage",value);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    /** Real kernel cap and filesystem query, without starting a camera or muxer. */
    public static void memoryDescriptorBounds(android.content.Context context,Runnable authorization)throws Exception {
        try(var memory=new MemoryMuxerDescriptor(context,authorization)) {
            android.system.Os.fstatvfs(memory.descriptor());
            org.junit.Assert.assertThrows(android.system.ErrnoException.class,
                ()->android.system.Os.pwrite(memory.descriptor(),new byte[]{1},0,1,MemoryMuxerDescriptor.WORK_LIMIT));
            org.junit.Assert.assertEquals(MemoryMuxerDescriptor.WORK_LIMIT,android.system.Os.fstat(memory.descriptor()).st_size);
        }
        stage("MEMORY_MUXER_KERNEL_BOUND_CONFIRMED");
        // Diagnose the previous destination without invoking the crashing native muxer.
        var thread=new android.os.HandlerThread("synthetic-proxy-filesystem");thread.start();
        try(var proxy=context.getSystemService(android.os.storage.StorageManager.class).openProxyFileDescriptor(
                android.os.ParcelFileDescriptor.MODE_READ_WRITE,new android.os.ProxyFileDescriptorCallback(){
                    @Override public long onGetSize(){return 0;}
                    @Override public int onRead(long offset,int size,byte[] data){return 0;}
                    @Override public int onWrite(long offset,int size,byte[] data){return size;}
                    @Override public void onRelease(){}
                },new android.os.Handler(thread.getLooper()))) {
            String capability;
            try {android.system.Os.fstatvfs(proxy.getFileDescriptor());capability="SUPPORTED";}
            catch(android.system.ErrnoException unavailable){capability="ERRNO_"+unavailable.errno;}
            var report=new android.os.Bundle();report.putString("previousProxyFilesystemQuery",capability);
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
        }finally{thread.quitSafely();thread.join(2000);if(thread.isAlive())throw new AssertionError("Synthetic proxy closure unconfirmed");}
    }
    public static byte[] clip(Context context,Runnable check)throws Exception {
        try(var frames=new RestrictedVideo.Frames(64,48)) {
            for(int i=0;i<10;i++) {
                byte[] frame=new byte[64*48*3/2];
                Arrays.fill(frame,0,64*48,(byte)(40+i*18));Arrays.fill(frame,64*48,frame.length,(byte)128);
                frames.add(frame,i*100000L);
            }
            short[] samples=new short[16000];
            for(int i=0;i<samples.length;i++)samples[i]=(short)(6000*Math.sin(2*Math.PI*440*i/16000.0));
            byte[] audio=null;
            stage("SYNTHETIC_AVC_ENCODE_ENTER");
            try(var track=RestrictedVideo.encode(frames,check)) {
                stage("SYNTHETIC_AVC_ENCODE_DONE");
                audio=RestrictedAudio.encodeBytes(samples,check);stage("SYNTHETIC_AAC_ENCODE_DONE");
                byte[] result=RestrictedVideo.mux(context,track,audio,check);stage("SYNTHETIC_MUX_DONE");return result;
            }finally{Arrays.fill(samples,(short)0);if(audio!=null)Arrays.fill(audio,(byte)0);}
        }
    }
    public record Observation(int frames,int firstLuma,int lastLuma,long lastTime,int audioSamples,double rms) {}
    public static Observation observe(RestrictedContentService.Session session)throws Exception {
        if(session.format()!=RestrictedPayload.Format.AVC_MP4)throw RestrictedPayload.invalid();
        Runnable check=()->{try{session.check();}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw RestrictedPayload.invalid();}};
        return session.decode(bytes->{
            try(var frames=RestrictedVideo.decode(bytes,check)) {
                byte[] audio=RestrictedVideo.extractAudio(bytes,check);if(audio==null)throw RestrictedPayload.invalid();
                short[] pcm=null;
                try {
                    pcm=RestrictedAudio.decode(audio,check);double energy=0;for(short value:pcm)energy+=(double)value*value;
                    return new Observation(frames.pixels.size(),frames.pixels.get(0)[100]&255,
                        frames.pixels.get(frames.pixels.size()-1)[100]&255,frames.times.get(frames.times.size()-1),pcm.length,Math.sqrt(energy/pcm.length));
                }finally{Arrays.fill(audio,(byte)0);if(pcm!=null)Arrays.fill(pcm,(short)0);}
            }
        },ignored->{});
    }
}
