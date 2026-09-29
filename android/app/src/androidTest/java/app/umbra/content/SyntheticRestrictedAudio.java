package app.umbra.content;

import java.util.Arrays;

/** Synthetic source/PCM observations only in androidTest, never production or physical microphones. */
public final class SyntheticRestrictedAudio {
    private SyntheticRestrictedAudio() {}
    public static RestrictedContentService.Prepared prepareRaw(byte[] bytes,Runnable authorization)throws Exception {
        return RestrictedAudio.prepare(bytes,authorization);
    }
    public static RestrictedContentService.Prepared tone(Runnable authorization)throws Exception {
        short[] pcm=new short[RestrictedAudio.SAMPLE_RATE];
        for(int i=0;i<pcm.length;i++)pcm[i]=(short)(8000*Math.sin(2*Math.PI*(i>=pcm.length-640?1320:440)*i/RestrictedAudio.SAMPLE_RATE));
        try{return RestrictedAudio.encode(pcm,authorization);}finally{Arrays.fill(pcm,(short)0);}
    }
    public static RestrictedContentService.Prepared sanitizedTone(app.umbra.crypto.Engine engine,
            RestrictedContentService.Review review)throws Exception {
        var grant=engine.restricted().preparationAuthorization(review,true);
        Runnable authorization=()->{try{grant.run();}catch(Exception denied){throw new SecurityException("Synthetic authorization expired");}};
        return sanitizedTone(authorization,bytes->RestrictedAudio.prepare(engine,review,bytes,true));
    }
    private interface Preparation {RestrictedContentService.Prepared run(byte[] bytes)throws Exception;}
    /** The delivered bytes are the sanitizer result, not the original synthetic ADTS. */
    public static RestrictedContentService.Prepared sanitizedTone(Runnable authorization)throws Exception {
        return sanitizedTone(authorization,bytes->RestrictedAudio.prepare(bytes,authorization));
    }
    private static RestrictedContentService.Prepared sanitizedTone(Runnable authorization,Preparation preparation)throws Exception {
        short[] pcm=new short[RestrictedAudio.SAMPLE_RATE];byte[] original=null;
        for(int i=0;i<pcm.length;i++)pcm[i]=(short)(8000*Math.sin(2*Math.PI*(i>=pcm.length-640?1320:440)*i/RestrictedAudio.SAMPLE_RATE));
        try {
            original=RestrictedAudio.encodeBytes(pcm,authorization);
            byte[] unchanged=original.clone();
            try {
                var prepared=preparation.run(original);
                try {org.junit.Assert.assertArrayEquals(unchanged,original);return prepared;}
                catch(RuntimeException | Error failure){prepared.close();throw failure;}
            } finally {Arrays.fill(unchanged,(byte)0);}
        } finally {Arrays.fill(pcm,(short)0);if(original!=null)Arrays.fill(original,(byte)0);}
    }
    public record Observation(int samples,double rms,double targetEnergy,double otherEnergy,int encodedFrames,double tailFraction,int markerStart,double markerFraction) {}
    public static RestrictedContentService.Prepared silence(Runnable authorization)throws Exception {
        short[] pcm=new short[RestrictedAudio.SAMPLE_RATE];
        try{return RestrictedAudio.encode(pcm,authorization);}finally{Arrays.fill(pcm,(short)0);}
    }
    public record CaptureObservation(int samples,int peak,double rms) {}
    /** Statistics only; no raw PCM escapes or persists. */
    public static CaptureObservation captureStatistics(RestrictedContentService.Session session)throws Exception {
        return session.decode(bytes->{
            short[] pcm=RestrictedAudio.decode(bytes,()->{try{session.check();}catch(Exception denied){throw new SecurityException("Synthetic capture cancelled");}});
            try {
                double energy=0;int peak=0;for(short sample:pcm){energy+=(double)sample*sample;peak=Math.max(peak,Math.abs((int)sample));}
                if(pcm.length<1024)throw new AssertionError("No native captured samples");
                return new CaptureObservation(pcm.length,peak,Math.sqrt(energy/pcm.length));
            }finally{Arrays.fill(pcm,(short)0);}
        },ignored->{});
    }
    public static Observation observe(RestrictedContentService.Session session)throws Exception {
        return session.decode(bytes->{
            byte[] original=bytes.clone();
            try(var prepared=RestrictedAudio.prepare(bytes,()->{try{session.check();}catch(Exception denied){throw new SecurityException("Synthetic preparation cancelled");}})) {
                org.junit.Assert.assertNotNull(prepared);org.junit.Assert.assertArrayEquals(original,bytes);
            } finally {Arrays.fill(original,(byte)0);}
            int frames=0;
            var extractor=new android.media.MediaExtractor();
            try(var source=new MemoryMediaSource(bytes,()->{})) {
                extractor.setDataSource(source);extractor.selectTrack(0);
                while(extractor.getSampleTime()>=0){frames++;if(!extractor.advance())break;}
            } finally {extractor.release();}
            short[] pcm=RestrictedAudio.decode(bytes,()->{try{session.check();}catch(Exception denied){throw new SecurityException("Synthetic decode cancelled");}});
            try {
                double energy=0,target=0,other=0;int count=0;
                // Lossy AAC priming is excluded by a fixed interior window, not by searching for success.
                for(int start=4096;start+1600<=pcm.length-2048;start+=1600) {
                    double tr=0,ti=0,or=0,oi=0;
                    for(int i=0;i<1600;i++) {
                        double sample=pcm[start+i];energy+=sample*sample;count++;
                        tr+=sample*Math.cos(2*Math.PI*440*i/16000);ti+=sample*Math.sin(2*Math.PI*440*i/16000);
                        or+=sample*Math.cos(2*Math.PI*1000*i/16000);oi+=sample*Math.sin(2*Math.PI*1000*i/16000);
                    }
                    target+=tr*tr+ti*ti;other+=or*or+oi*oi;
                }
                if(count==0)throw new AssertionError("No decoded observation window");
                double tailFraction=0;
                for(int start=Math.max(0,pcm.length-4096);start+512<=pcm.length;start+=128) {
                    double re=0,im=0,total=0;
                    for(int i=0;i<512;i++){double sample=pcm[start+i];total+=sample*sample;re+=sample*Math.cos(2*Math.PI*1320*i/16000);im+=sample*Math.sin(2*Math.PI*1320*i/16000);}
                    if(total>512*1000.0*1000.0)tailFraction=Math.max(tailFraction,2*(re*re+im*im)/(512*total));
                }
                // Diagnostic only: find synthetic end marker across the decoded note, keeping
                // the original bounded-tail acceptance unchanged until cause is demonstrated.
                int markerStart=-1;double markerFraction=0;
                for(int start=0;start+512<=pcm.length;start+=128) {
                    double re=0,im=0,total=0;
                    for(int i=0;i<512;i++){double sample=pcm[start+i];total+=sample*sample;re+=sample*Math.cos(2*Math.PI*1320*i/16000);im+=sample*Math.sin(2*Math.PI*1320*i/16000);}
                    if(total>512*1000.0*1000.0) {
                        double fraction=2*(re*re+im*im)/(512*total);
                        if(fraction>markerFraction){markerFraction=fraction;markerStart=start;}
                    }
                }
                return new Observation(pcm.length,Math.sqrt(energy/count),target,other,frames,tailFraction,markerStart,markerFraction);
            }finally{Arrays.fill(pcm,(short)0);}
        },ignored->{});
    }
}
