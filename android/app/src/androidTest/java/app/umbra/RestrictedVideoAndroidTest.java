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
}
