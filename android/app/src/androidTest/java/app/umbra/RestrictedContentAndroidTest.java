package app.umbra;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import app.umbra.content.*;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Signal + AES-GCM + SQLite + Android image codec; in-process delivery, not HTTPS/RFCOMM. */
public class RestrictedContentAndroidTest {
    private byte[] png() {
        Bitmap bitmap=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff336699);
        try {var output=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));return output.toByteArray();}
        finally {bitmap.recycle();}
    }
    @Test public void syntheticImageDecodedAfterSignalThenCannotReopenAfterSqliteReopen() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            byte[] original=png(),copy=original.clone();
            String id=a.restricted().send(a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),RestrictedImages.prepare(original,ar.authorization()),true);
            assertArrayEquals(copy,original);
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            assertEquals(1,b.restricted().received(a.id()).size());
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);Bitmap output=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);
            try(var decoder=new RestrictedImages.Decoder(session)) {
                decoder.render(new Canvas(output),new Rect(0,0,24,16));
                assertEquals(0xff336699,output.getPixel(8,8));
                assertEquals(ContentException.Code.CAPACITY,assertThrows(ContentException.class,
                    ()->new RestrictedImages.Decoder(session)).code());
            }finally{output.recycle();}
            session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
            br.reopen();var reopened=new Engine(br);
            assertThrows(ContentException.class,()->reopened.restricted().open(reopened.restricted().reviewOpen(id),true));
            assertTrue(reopened.restricted().status(id).consumed());
            assertTrue(reopened.messages(a.id()).isEmpty());
        }
    }
    @Test public void sqliteConsumeFailureDoesNotDeliverDecoderSession() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            String id=a.restricted().send(a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),RestrictedImages.prepare(png(),ar.authorization()),true);
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            br.failBucket="restricted-state";assertThrows(IllegalStateException.class,()->b.restricted().open(b.restricted().reviewOpen(id),true));br.failBucket=null;
            assertFalse(b.restricted().status(id).consumed());
            try(var session=b.restricted().open(b.restricted().reviewOpen(id),true)){session.check();}
        }
    }
    @Test public void lockAfterPositiveRenderClosesDecoderAndRejectsLateFrame() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            String id=a.restricted().send(a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),RestrictedImages.prepare(png(),ar.authorization()),true);
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);Bitmap output=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);
            try(var decoder=new RestrictedImages.Decoder(session)) {
                Canvas canvas=new Canvas(output);Rect target=new Rect(0,0,24,16);
                decoder.render(canvas,target);assertEquals(0xff336699,output.getPixel(8,8));
                long requested=System.nanoTime();br.gate.lock();long invalidated=System.nanoTime();
                session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);long closed=System.nanoTime();
                assertTrue(closed>=invalidated);assertTrue(invalidated>=requested);
                assertThrows(SecurityException.class,()->decoder.render(canvas,target));
                Thread.sleep(100);assertThrows(SecurityException.class,()->decoder.render(canvas,target));
                var receipt=new android.os.Bundle();receipt.putString("restrictedLockNanos",requested+","+invalidated+","+closed);
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,receipt);
                br.gate.unlock();assertThrows(SecurityException.class,()->decoder.render(canvas,target));
            } finally {output.recycle();}
        }
    }
    @Test public void syntheticNoteEncodedSanitizedSignalDecodedAndConsumed() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            String id=a.restricted().send(a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),
                SyntheticRestrictedAudio.tone(ar.authorization()),true);
            for(var row:a.outbox()){b.receive(row.getJSONObject("envelope"));b.receive(row.getJSONObject("envelope"));}
            assertTrue(b.messages(a.id()).isEmpty());assertEquals(1,b.restricted().received(a.id()).size());
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);
            try {
                var observed=SyntheticRestrictedAudio.observe(session);
                var receipt=new android.os.Bundle();receipt.putString("restrictedAac", "decodedSamples="+observed.samples()+",encodedFrames="+observed.encodedFrames()+",tailFraction="+observed.tailFraction()+",rms="+observed.rms());
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,receipt);
                assertTrue("Decoded note shorter than input: "+observed.samples(),observed.samples()>=16000);
                assertTrue("Decoded note exceeded bounded priming: "+observed.samples(),observed.samples()<24000);
                assertTrue("Decoded note RMS outside profile",observed.rms()>1000 && observed.rms()<10000);
                assertTrue("Decoded note did not preserve synthetic frequency",observed.targetEnergy()>100*observed.otherEnergy());
                assertTrue("Final input marker was lost by codec drain",observed.tailFraction()>0.6);
            }finally{session.close();}
            session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);br.reopen();
            var restarted=new Engine(br);
            assertEquals(ContentException.Code.CONSUMED,assertThrows(ContentException.class,
                ()->restarted.restricted().open(restarted.restricted().reviewOpen(id),true)).code());
        }
    }
    @Test public void notePreparationRejectsMalformedAndExpiredAuthorizationWithoutRecording() throws Exception {
        assertThrows(Exception.class,()->RestrictedAudio.prepare(new byte[]{1,2,3,4},()->{}));
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(false);
        assertThrows(SecurityException.class,()->SyntheticRestrictedAudio.tone(()->{if(!allowed.get())throw new SecurityException("Synthetic locked");}));
        allowed.set(true);
        try(var encoded=SyntheticRestrictedAudio.tone(()->{if(!allowed.get())throw new SecurityException("Synthetic locked");})) {
            assertNotNull(encoded);allowed.set(false);
            assertThrows(SecurityException.class,()->RestrictedAudio.prepare(new byte[]{1,2},()->{if(!allowed.get())throw new SecurityException("Synthetic locked");}));
        }
    }
}
