package app.umbra.content;

import android.content.Context;
import java.util.Arrays;

/** Test APK only. Patterns enter real AVC encoder; observations follow real remote decoder. */
public final class SyntheticRestrictedVideo {
    private SyntheticRestrictedVideo() {}
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
            try(var track=RestrictedVideo.encode(frames,check)) {
                audio=RestrictedAudio.encodeBytes(samples,check);return RestrictedVideo.mux(context,track,audio,check);
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
