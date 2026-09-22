package app.umbra.media;

import android.content.Context;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.webrtc.*;

/** TEST APK only: changing grayscale peer markers before the native VP8 encoder. */
final class SyntheticVideoCapturer implements VideoCapturer {
    private final boolean caller;
    private final AtomicInteger captured;
    private CapturerObserver observer;
    private ScheduledExecutorService worker;
    private int sequence;
    SyntheticVideoCapturer(boolean caller,AtomicInteger captured) {this.caller=caller;this.captured=captured;}
    public void initialize(SurfaceTextureHelper ignored,Context context,CapturerObserver observer) {this.observer=observer;}
    public synchronized void startCapture(int width,int height,int fps) {
        if(worker!=null || width!=320 || height!=240 || fps!=15)throw new AssertionError("Unexpected synthetic capture configuration");
        worker=Executors.newSingleThreadScheduledExecutor();observer.onCapturerStarted(true);
        worker.scheduleAtFixedRate(()->{
            JavaI420Buffer buffer=JavaI420Buffer.allocate(320,240);
            int phase=(sequence++/5)%2;ByteBuffer luminance=buffer.getDataY();
            for(int y=0;y<240;y++) for(int x=0;x<320;x++) {
                int value=x<160?(caller?48:192):(phase==0?80:160);
                luminance.put(y*buffer.getStrideY()+x,(byte)value);
            }
            fill(buffer.getDataU(),128);fill(buffer.getDataV(),128);
            VideoFrame frame=new VideoFrame(buffer,0,System.nanoTime());
            try {captured.incrementAndGet();observer.onFrameCaptured(frame);} finally {frame.release();}
        },0,1_000_000_000L/15,TimeUnit.NANOSECONDS);
    }
    private static void fill(ByteBuffer buffer,int value) {while(buffer.hasRemaining())buffer.put((byte)value);buffer.rewind();}
    public synchronized void stopCapture() throws InterruptedException {
        if(worker==null)return;worker.shutdownNow();
        if(!worker.awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("Synthetic capture did not stop");
        worker=null;observer.onCapturerStopped();
    }
    public void changeCaptureFormat(int width,int height,int fps) {throw new AssertionError("Unconsented source change");}
    public void dispose() {try{stopCapture();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    public boolean isScreencast() {return false;}

    static final class Decoded implements VideoSink {
        private final int expected;final AtomicInteger frames=new AtomicInteger(),phases=new AtomicInteger();
        private long lastTimestamp;
        Decoded(boolean caller) {expected=caller?192:48;}
        public synchronized void onFrame(VideoFrame frame) {
            var buffer=frame.getBuffer().toI420();
            try {
                if(frame.getRotation()!=0 || buffer.getWidth()<160 || buffer.getWidth()>320 || buffer.getHeight()<120 || buffer.getHeight()>240 || frame.getTimestampNs()<=lastTimestamp)return;
                double marker=average(buffer,buffer.getWidth()/8,buffer.getHeight()/3),phase=average(buffer,buffer.getWidth()*11/16,buffer.getHeight()/3);
                if(Math.abs(marker-expected)>15)return; // Opposite endpoint marker, never local preview.
                int bit=Math.abs(phase-80)<15?1:Math.abs(phase-160)<15?2:0;
                if(bit==0)return;
                phases.set(phases.get()|bit);frames.incrementAndGet();lastTimestamp=frame.getTimestampNs();
            } finally {buffer.release();}
        }
        private static double average(VideoFrame.I420Buffer b,int x,int y) {
            ByteBuffer luminance=b.getDataY();long sum=0;for(int row=y;row<y+20;row++)for(int col=x;col<x+20;col++)sum+=luminance.get(row*b.getStrideY()+col)&255;
            return sum/400.0;
        }
    }
}
