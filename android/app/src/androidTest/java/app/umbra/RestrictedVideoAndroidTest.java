package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.content.*;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Codec/preparation acceptance; not surface playback, audible output or two physical peers. */
public final class RestrictedVideoAndroidTest {
    @Test public void syntheticAvcAacReencodedSignalDecodedThenConsumeSurvivesReopen()throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            SyntheticRestrictedVideo.memoryDescriptorBounds(context,ar.authorization());
            byte[] input=SyntheticRestrictedVideo.clip(context,ar.authorization()),original=input.clone();
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);String id;
            SyntheticRestrictedVideo.stage("PREPARATION_ENTER");
            try(var prepared=RestrictedVideo.prepare(context,a,review,input,true)) {
                SyntheticRestrictedVideo.stage("PREPARATION_DONE");
                assertArrayEquals(original,input);id=a.restricted().send(review,prepared,true);
            }finally{Arrays.fill(input,(byte)0);Arrays.fill(original,(byte)0);}
            for(var row:a.outbox()){b.receive(row.getJSONObject("envelope"));b.receive(row.getJSONObject("envelope"));}
            assertEquals(1,b.restricted().received(a.id()).size());assertTrue(b.messages(a.id()).isEmpty());
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);
            try {
                SyntheticRestrictedVideo.stage("RECIPIENT_DECODE_ENTER");
                var observed=SyntheticRestrictedVideo.observe(session);SyntheticRestrictedVideo.stage("RECIPIENT_DECODE_DONE");
                assertEquals(10,observed.frames());assertEquals(900000,observed.lastTime());
                assertTrue(observed.firstLuma()>=30 && observed.firstLuma()<=50);
                assertTrue(observed.lastLuma()>=190 && observed.lastLuma()<=212);
                assertTrue(observed.audioSamples()>=16000 && observed.audioSamples()<=26000);
                assertTrue(observed.rms()>2000 && observed.rms()<6000);
                var status=new android.os.Bundle();status.putString("restrictedVideoDecoded",
                    "frames="+observed.frames()+",firstY="+observed.firstLuma()+",lastY="+observed.lastLuma()+",audioSamples="+observed.audioSamples());
                InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
            }finally{session.close();}
            session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);br.reopen();
            Engine reopened=new Engine(br);assertTrue(reopened.restricted().status(id).consumed());
            assertEquals(ContentException.Code.CONSUMED,assertThrows(ContentException.class,
                ()->reopened.restricted().open(reopened.restricted().reviewOpen(id),true)).code());
        }
    }
    @Test public void malformedDeniedConsentAndOldLeaseCannotCreateVideoDelivery()throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            byte[] clip=SyntheticRestrictedVideo.clip(context,ar.authorization(),false);
            try {
                for(int length:new int[]{1,8,23}) {
                    byte[] prefix=Arrays.copyOf(clip,length);
                    assertThrows("MP4 prefix="+length,ContentException.class,
                            ()->RestrictedVideo.prepare(context,a,a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),prefix,true));
                }
                for(int boxLength:new int[]{0,15,Integer.MAX_VALUE,-1}) {
                    byte[] mutation=clip.clone();java.nio.ByteBuffer.wrap(mutation).putInt(boxLength);
                    assertThrows("MP4 ftyp length="+boxLength,ContentException.class,
                            ()->RestrictedVideo.prepare(context,a,a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),mutation,true));
                }
                int ftypLength=java.nio.ByteBuffer.wrap(clip).getInt();
                assertTrue(ftypLength>=24 && ftypLength<clip.length);
                byte[] headerOnly=Arrays.copyOf(clip,ftypLength);
                try {
                    try(var unexpected=RestrictedVideo.prepare(context,a,a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),headerOnly,true)) {
                        fail("MP4 with ftyp only accepted");
                    }
                } catch(java.io.IOException | ContentException expected) {
                    // MediaExtractor input rejection only; unexpected runtime failures propagate.
                }
                try(var prepared=RestrictedVideo.prepare(context,a,a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),clip,true)){assertNotNull(prepared);}
            } finally {Arrays.fill(clip,(byte)0);}
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            assertThrows(ContentException.class,()->RestrictedVideo.prepare(context,a,review,new byte[32],true));
            assertThrows(ContentException.class,()->RestrictedVideo.prepare(context,a,review,new byte[RestrictedPayload.MAX_BYTES+1],true));
            assertEquals(ContentException.Code.CONSENT_REQUIRED,assertThrows(ContentException.class,
                ()->RestrictedVideo.prepare(context,a,review,new byte[32],false)).code());
            ar.gate.lock();ar.gate.unlock();
            assertThrows(SecurityException.class,()->RestrictedVideo.prepare(context,a,review,new byte[32],true));
            assertTrue(a.outbox().isEmpty());
        }
    }

    /** Silent synthetic video: no camera, microphone or audible output on a phone. */
    @Test public void nativeFilePlaybackDeliversChangingFramesAndCannotReplay()throws Exception {
        exercisePlayback(false);
    }
    @Test public void lockAfterNativeVideoFramesClosesSurfaceAndRejectsOldSession()throws Exception {
        exercisePlayback(true);
    }
    private static void exercisePlayback(boolean lock)throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            byte[] input=SyntheticRestrictedVideo.clip(context,ar.authorization(),false);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);String id;
            try(var prepared=RestrictedVideo.prepare(context,a,review,input,true)) {
                id=a.restricted().send(review,prepared,true);
            }finally{Arrays.fill(input,(byte)0);}
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);
            try(var sink=new FrameSink();var playback=new RestrictedPlayback(context,session,null,sink)) {
                playback.start();
                long deadline=System.nanoTime()+2_000_000_000L;
                while((sink.frames.get()<2 || playback.firstVideoFrameNanos()==0) && System.nanoTime()<deadline)Thread.sleep(5);
                assertTrue("No positive native presentation",sink.frames.get()>=2);
                assertTrue("Native rendering callback not delivered before cancellation",playback.firstVideoFrameNanos()>0);
                long requested=System.nanoTime();
                if(lock)br.gate.lock();
                session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
                long closed=System.nanoTime();int count=sink.frames.get();
                assertNull("Native frame sink failed",sink.failure.get());
                assertTrue("Surface ownership not released",sink.closed.get());
                if(lock) {
                    assertTrue("Local closure exceeded bound",closed-requested<1_000_000_000L);
                    br.gate.unlock();assertThrows(Exception.class,playback::start);
                }else {
                    assertEquals(RestrictedPlayback.State.COMPLETED,playback.state());
                    assertTrue("Missing changing decoded frames",count>=3 && sink.maximum.get()-sink.minimum.get()>100);
                    assertThrows(Exception.class,playback::start);
                }
                Thread.sleep(200);assertEquals("Callbacks continued after closure",count,sink.frames.get());
                assertTrue(sink.lastFrame.get()<=closed);
                var report=new android.os.Bundle();report.putString("restrictedPlayback",
                    "lock="+lock+",frames="+count+",minY="+sink.minimum.get()+",maxY="+sink.maximum.get()+
                    ",requested="+requested+",lastFrame="+sink.lastFrame.get()+",closed="+closed+",observationNanos=200000000");
                InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
            }finally{session.close();}
            br.reopen();Engine restarted=new Engine(br);
            assertEquals(ContentException.Code.CONSUMED,assertThrows(ContentException.class,
                ()->restarted.restricted().open(restarted.restricted().reviewOpen(id),true)).code());
        }
    }
    private static final class FrameSink implements RestrictedPlayback.VideoOutput {
        final android.os.HandlerThread thread=new android.os.HandlerThread("synthetic-video-sink");
        final android.media.ImageReader reader=android.media.ImageReader.newInstance(64,48,android.graphics.ImageFormat.YUV_420_888,3);
        final java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger minimum=new java.util.concurrent.atomic.AtomicInteger(255),maximum=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong lastFrame=new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        FrameSink(){
            thread.start();reader.setOnImageAvailableListener(source->{
                synchronized(this) {
                    if(closed.get())return;
                    try(var frame=source.acquireLatestImage()) {
                        if(frame==null)return;
                        var plane=frame.getPlanes()[0];var data=plane.getBuffer();
                        int y=data.get(data.position()+20*plane.getRowStride()+20*plane.getPixelStride())&255;
                        minimum.accumulateAndGet(y,Math::min);maximum.accumulateAndGet(y,Math::max);
                        frames.incrementAndGet();lastFrame.set(System.nanoTime());
                    }catch(Throwable error){failure.compareAndSet(null,error);}
                }
            },new android.os.Handler(thread.getLooper()));
        }
        @Override public android.view.Surface surface(){return reader.getSurface();}
        @Override public void close()throws Exception {
            synchronized(this){if(!closed.compareAndSet(false,true))return;reader.setOnImageAvailableListener(null,null);reader.close();}
            thread.quitSafely();thread.join(1000);if(thread.isAlive())throw new IllegalStateException("Synthetic sink closure unconfirmed");
        }
    }
}
