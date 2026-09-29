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
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            String id=a.restricted().send(review,RestrictedImages.prepare(a,review,original,true),true);
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
    @Test public void encryptedVaultDatabaseAndWalDoNotPersistSyntheticContentOrObjectKeys()throws Exception {
        // Actual production Vault/SQLite encryption and password profile, with owned
        // AndroidKeyStore no-auth fixture keys only. This is not hardware-auth proof.
        var fixture=new DeviceVaultPasswordTest();
        byte[] source=null,encodedObjectKey=null;
        byte[] ordinary=app.umbra.core.Bytes.utf8("SYNTHETIC-VAULT-RETENTION-MARKER-ONLY-29-09-2026");
        try {
            fixture.before();
            fixture.vault.transaction(()->{
                fixture.vault.remove("meta","identity");fixture.vault.remove("session","ratchet");return null;
            });
            try(var senderRecords=new SqliteDeviceRecords()) {
                Engine a=new Engine(senderRecords),b=new Engine(fixture.vault);
                a.initialize("Synthetic retention sender");b.initialize("Synthetic retention receiver");
                AdmissionFixture.enroll(a);AdmissionFixture.enroll(b);
                a.importCard(b.createCard());b.importCard(a.createCard());
                a.verify(b.id(),app.umbra.core.Bytes.safetyCode(a.id(),b.id()));
                b.verify(a.id(),app.umbra.core.Bytes.safetyCode(a.id(),b.id()));
                fixture.vault.createPassword(app.umbra.core.Bytes.utf8("synthetic password alpha"));
                fixture.gate.unlock();fixture.vault.unlock(app.umbra.core.Bytes.utf8("synthetic password alpha"));
                var database=fixture.vault.getWritableDatabase();assertTrue(database.enableWriteAheadLogging());
                try(var setting=database.rawQuery("PRAGMA wal_autocheckpoint=0",null)){assertTrue(setting.moveToFirst());}
                // A known plaintext ordinary record checks the Vault layer independently
                // of restricted content's inner object encryption (avoid a tautology).
                b.sendText(a.id(),app.umbra.core.Bytes.text(ordinary),600);
                assertEquals(app.umbra.core.Bytes.text(ordinary),b.messages(a.id()).get(0).getString("text"));
                source=png();byte[] unchanged=source.clone();
                var consent=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
                String id=a.restricted().send(consent,RestrictedImages.prepare(a,consent,source,true),true);
                assertArrayEquals(unchanged,source);java.util.Arrays.fill(unchanged,(byte)0);
                for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
                byte[] object=fixture.vault.get("restricted-object",id);
                try {
                    var parsed=new org.json.JSONObject(app.umbra.core.Bytes.text(object));
                    encodedObjectKey=app.umbra.core.Bytes.utf8(parsed.getString("key"));
                } finally {java.util.Arrays.fill(object,(byte)0);}
                byte[][] markers={source,ordinary,encodedObjectKey};
                assertTrue("WAL evidence must actually exist before scanning",new java.io.File(fixture.file+"-wal").length()>0);
                int scans=scanOwnedVaultFiles(fixture.file,markers);
                var session=b.restricted().open(b.restricted().reviewOpen(id),true);
                Bitmap output=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);
                try(var decoder=new RestrictedImages.Decoder(session)) {
                    decoder.render(new Canvas(output),new Rect(0,0,24,16));assertEquals(0xff336699,output.getPixel(8,8));
                } finally {output.recycle();session.close();}
                session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
                assertTrue(b.restricted().status(id).consumed());assertNull(fixture.vault.get("restricted-object",id));
                scans+=scanOwnedVaultFiles(fixture.file,markers);
                fixture.vault.close();scans+=scanOwnedVaultFiles(fixture.file,markers);
                fixture.gate.unlock();fixture.vault.unlock(app.umbra.core.Bytes.utf8("synthetic password alpha"));
                Engine reopened=new Engine(fixture.vault);assertTrue(reopened.restricted().status(id).consumed());
                assertEquals(ContentException.Code.CONSUMED,assertThrows(ContentException.class,
                    ()->reopened.restricted().open(reopened.restricted().reviewOpen(id),true)).code());
                scans+=scanOwnedVaultFiles(fixture.file,markers);
                var report=new android.os.Bundle();report.putString("restrictedEncryptedRetention",
                    "PASS ownedDatabaseAndSidecarsScanned="+scans+",positiveDecoded=true,passwordVault=true,fixtureNoAuthKeys=true");
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
            }
        } finally {
            if(source!=null)java.util.Arrays.fill(source,(byte)0);
            if(encodedObjectKey!=null)java.util.Arrays.fill(encodedObjectKey,(byte)0);
            java.util.Arrays.fill(ordinary,(byte)0);fixture.after();
        }
    }
    private static int scanOwnedVaultFiles(java.io.File database,byte[][] markers)throws Exception {
        int scanned=0;
        for(String suffix:new String[]{"","-wal","-shm","-journal"}) {
            java.io.File file=new java.io.File(database.getPath()+suffix);
            if(!file.exists())continue;
            assertTrue("Fixture storage exceeded bounded scan",file.length()<=16L*1024*1024);
            byte[] stored=java.nio.file.Files.readAllBytes(file.toPath());
            try {
                for(byte[] marker:markers) {
                    assertTrue(marker.length>=32);boolean found=false;
                    for(int offset=0;offset<=stored.length-marker.length && !found;offset++) {
                        if(stored[offset]!=marker[0])continue;
                        int n=1;while(n<marker.length && stored[offset+n]==marker[n])n++;
                        found=n==marker.length;
                    }
                    assertFalse("Synthetic plaintext/object key persisted in owned Vault storage",found);
                }
            } finally {java.util.Arrays.fill(stored,(byte)0);}
            scanned++;
        }
        assertTrue("No actual Vault file scanned",scanned>0);return scanned;
    }

    @Test public void sqliteConsumeFailureDoesNotDeliverDecoderSession() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            String id=a.restricted().send(review,RestrictedImages.prepare(a,review,png(),true),true);
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            br.failBucket="restricted-state";assertThrows(IllegalStateException.class,()->b.restricted().open(b.restricted().reviewOpen(id),true));br.failBucket=null;
            assertFalse(b.restricted().status(id).consumed());
            try(var session=b.restricted().open(b.restricted().reviewOpen(id),true)){session.check();}
        }
    }
    @Test public void lockAfterPositiveRenderClosesDecoderAndRejectsLateFrame() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            String id=a.restricted().send(review,RestrictedImages.prepare(a,review,png(),true),true);
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
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            String id=a.restricted().send(review,SyntheticRestrictedAudio.sanitizedTone(a,review),true);
            for(var row:a.outbox()){b.receive(row.getJSONObject("envelope"));b.receive(row.getJSONObject("envelope"));}
            assertTrue(b.messages(a.id()).isEmpty());assertEquals(1,b.restricted().received(a.id()).size());
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);
            try {
                var observed=SyntheticRestrictedAudio.observe(session);
                var receipt=new android.os.Bundle();receipt.putString("restrictedAac", "decodedSamples="+observed.samples()+",encodedFrames="+observed.encodedFrames()+",tailFraction="+observed.tailFraction()+",rms="+observed.rms()+",markerStart="+observed.markerStart()+",markerFraction="+observed.markerFraction());
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
        assertThrows(Exception.class,()->SyntheticRestrictedAudio.prepareRaw(new byte[]{1,2,3,4},()->{}));
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(false);
        assertThrows(SecurityException.class,()->SyntheticRestrictedAudio.tone(()->{if(!allowed.get())throw new SecurityException("Synthetic locked");}));
        allowed.set(true);
        try(var encoded=SyntheticRestrictedAudio.tone(()->{if(!allowed.get())throw new SecurityException("Synthetic locked");})) {
            assertNotNull(encoded);allowed.set(false);
            assertThrows(SecurityException.class,()->SyntheticRestrictedAudio.prepareRaw(new byte[]{1,2},()->{if(!allowed.get())throw new SecurityException("Synthetic locked");}));
        }
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var initial=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            try(var old=SyntheticRestrictedAudio.sanitizedTone(a,initial)) {
                ar.gate.lock();old.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);ar.gate.unlock();
                var fresh=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
                assertThrows(SecurityException.class,()->a.restricted().send(fresh,old,true));
                assertTrue(a.outbox().isEmpty());
            }
        }
    }
    @Test public void nativeNotePlaybackConfirmsRouteThenLockClosesWithoutReplay() throws Exception {
        exerciseRoutedPlayback(false);
    }
    @Test public void nativeNoteFocusLossClosesAndNeverResumes()throws Exception {
        exerciseRoutedPlayback(true);
    }
    private static void exerciseRoutedPlayback(boolean focusLoss)throws Exception {
        var instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        // Existing locked Activity is only a foreground host for Android audio-focus rules.
        // No product UI is changed or unlocked; synthetic Records remain test-APK-only.
        var intent=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(intent);
        try(var host=androidx.test.core.app.ActivityScenario.launch(intent);
                var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            String id=a.restricted().send(a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30),
                SyntheticRestrictedAudio.tone(ar.authorization()),true);
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            var audio=context.getSystemService(android.media.AudioManager.class);
            android.media.AudioDeviceInfo selected=null;
            for(var device:audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
                if(device.getType()==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){selected=device;break;}
            assertNotNull("Disposable AVD synthetic output required",selected);
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);
            android.media.AudioFocusRequest competing=null;
            try(var player=new RestrictedPlayback(context,session,selected)) {
                player.start();long until=System.nanoTime()+2_000_000_000L;
                while(player.state()==RestrictedPlayback.State.ROUTING && System.nanoTime()<until)Thread.sleep(10);
                assertEquals("Actual route confirmation before cancellation required",RestrictedPlayback.State.PLAYING,player.state());
                long requested=System.nanoTime();
                if(focusLoss) {
                    competing=new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                        .setOnAudioFocusChangeListener(change->{},new android.os.Handler(android.os.Looper.getMainLooper())).build();
                    assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(competing));
                }else br.gate.lock();
                long invalidated=System.nanoTime();
                session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);long closed=System.nanoTime();
                var outcome=player.state();
                assertTrue("Resource closure must leave a terminal outcome",outcome==RestrictedPlayback.State.CLOSED || outcome==RestrictedPlayback.State.INTERRUPTED);
                if(competing!=null){audio.abandonAudioFocusRequest(competing);competing=null;}
                Thread.sleep(100);assertEquals("Late callback changed terminal outcome",outcome,player.state());
                if(!focusLoss)br.gate.unlock();
                assertThrows(SecurityException.class,player::start);
                assertEquals(ContentException.Code.CONSUMED,assertThrows(ContentException.class,
                    ()->b.restricted().open(b.restricted().reviewOpen(id),true)).code());
                var status=new android.os.Bundle();status.putString("restrictedPlaybackLockNanos",requested+","+invalidated+","+closed);
                instrumentation.sendStatus(0,status);
            } finally {if(competing!=null)audio.abandonAudioFocusRequest(competing);session.close();}
        }
    }
}
