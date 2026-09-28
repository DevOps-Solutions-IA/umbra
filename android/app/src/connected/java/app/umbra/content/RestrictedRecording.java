package app.umbra.content;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Looper;
import app.umbra.core.EmergencyLock;
import app.umbra.crypto.Engine;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Connected-only, foreground caller-owned recording. No service, file, restart or remote trigger. */
public final class RestrictedRecording {
    private RestrictedRecording() {}
    /** Run on a worker after UI consent/permission. Normal stop prepares a note; invalidation discards it. */
    public static RestrictedContentService.Prepared record(Context context,Engine engine,
            RestrictedContentService.Review review,boolean confirmed,AudioDeviceInfo input,
            BooleanSupplier stopRequested)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Recording requires worker");
        var authorization=engine.restricted().preparationAuthorization(review,confirmed);
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        if(input==null || !input.isSource() || stopRequested==null)throw RestrictedPayload.invalid();
        var cancelled=new AtomicBoolean();var closed=new CompletableFuture<Void>();
        var registration=engine.emergency().register(EmergencyLock.Subsystem.MEDIA,()->{cancelled.set(true);return closed;});
        short[] pcm=new short[RestrictedAudio.SAMPLE_RATE*8];AudioRecord recorder=null;
        boolean released=false;
        try {
            Runnable check=()->{
                if(cancelled.get() || Thread.currentThread().isInterrupted() ||
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
                try{authorization.run();}catch(RuntimeException denied){throw denied;}catch(Exception unavailable){throw new ContentException(ContentException.Code.CONSENT_REQUIRED);}
            };
            check.run();
            int minimum=AudioRecord.getMinBufferSize(RestrictedAudio.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<1 || minimum>65536)throw RestrictedPayload.invalid();
            recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RestrictedAudio.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum,4096)).build();
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED || !recorder.setPreferredDevice(input))throw RestrictedPayload.invalid();
            check.run();recorder.startRecording();
            long began=System.nanoTime();int count=0;
            while(count<pcm.length && System.nanoTime()-began<8_000_000_000L && !stopRequested.getAsBoolean()) {
                check.run();AudioDeviceInfo routed=recorder.getRoutedDevice();
                if(routed==null){if(System.nanoTime()-began>500_000_000L)throw RestrictedPayload.invalid();Thread.sleep(5);continue;}
                if(routed.getId()!=input.getId() || recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw RestrictedPayload.invalid();
                int read=recorder.read(pcm,count,Math.min(320,pcm.length-count),AudioRecord.READ_NON_BLOCKING);
                if(read<0)throw RestrictedPayload.invalid();count+=read;check.run();
                if(read==0)Thread.sleep(5);
            }
            recorder.stop();recorder.release();released=true;check.run();
            if(count<1024)throw RestrictedPayload.invalid();
            short[] captured=Arrays.copyOf(pcm,count);
            try{return RestrictedAudio.encode(captured,check);}finally{Arrays.fill(captured,(short)0);}
        } finally {
            try {
                if(recorder!=null && !released)recorder.release();
                released=true;closed.complete(null);registration.close();
            } catch(RuntimeException failure) {
                closed.completeExceptionally(new IllegalStateException("Restricted recording closure failed"));throw failure;
            } finally {Arrays.fill(pcm,(short)0);}
        }
    }
}
