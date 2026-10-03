package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.admission.AdmissionService;
import app.umbra.core.AccessGate;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Vault;
import app.umbra.pairing.PairingService;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real encrypted Vault, SQLite, Argon2 and libsignal, using only the existing
 * isolated lab AndroidKeyStore fixture. Reopen is not process death or hardware
 * authentication evidence; no Bluetooth or network transport is exercised. */
@RunWith(AndroidJUnit4.class)
public final class DevicePairingPersistenceTest {
    private static void safeEqual(Object expected,Object actual) {
        assertTrue("Synthetic pairing invariant mismatch (values redacted)",java.util.Objects.deepEquals(expected,actual));
    }

    private DeviceVaultPasswordTest keys;
    private final List<Person> people = new ArrayList<>();

    @Before public void before() throws Exception {
        keys = new DeviceVaultPasswordTest();
        keys.before();
        keys.vault.close();
        keys.vault = null;
    }

    @After public void after() throws Exception {
        try {
            for (Person person : people) {
                try { if (person.vault != null) person.vault.close(); }
                finally { SQLiteDatabase.deleteDatabase(person.file); }
            }
        } finally { if (keys != null) keys.after(); }
    }

    private final class Person {
        final String alias;
        final File file;
        final Context context;
        Vault vault;
        Engine engine;
        PairingService pairing;
        Person(String alias) throws Exception {
            this.alias = alias;
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            file = new File(target.getCacheDir(), "synthetic-pairing-" + UUID.randomUUID() + ".db");
            context = new ContextWrapper(target) {
                @Override public File getDatabasePath(String ignored) { return file; }
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                        SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                    return SQLiteDatabase.openDatabase(file.getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
                }
            };
            people.add(this);
            AccessGate gate = new AccessGate(); gate.unlock();
            vault = new Vault(context, gate);
            engine = new Engine(vault); engine.initialize(alias);
            byte[] password = password();
            try { vault.createPassword(password); }
            finally { Arrays.fill(password, (byte) 0); }
            reopen();
        }
        byte[] password() { return Bytes.utf8("synthetic pairing password " + alias); }
        void reopen() throws Exception {
            Runnable old = vault.getVaultState() == Vault.State.UNLOCKED ? vault.authorization() : null;
            vault.close();
            AccessGate gate = new AccessGate(); gate.unlock();
            vault = new Vault(context, gate);
            safeEqual(Vault.State.LOCKED, vault.getVaultState());
            assertThrows(SecurityException.class, () -> vault.get("meta", "profile"));
            byte[] password = password();
            try { vault.unlock(password); }
            finally { Arrays.fill(password, (byte) 0); }
            if (old != null) assertThrows(SecurityException.class, old::run);
            engine = new Engine(vault); pairing = new PairingService(vault);
        }
    }

    private void reopenBoth(Person a, Person b) throws Exception { a.reopen(); b.reopen(); }

    private void admitTogether(Person a, Person b) throws Exception {
        var realm = a.engine.admission().createAdmissionRealm(true);
        var own = a.engine.admission().createAdmissionRequest();
        a.engine.admission().approveAndInstallOwnAdmission(
            a.engine.admission().reviewAdmissionRequest(own.wire()), true, 3600);
        b.engine.admission().installRealmConfig(realm.encode(), true);
        var request = b.engine.admission().createAdmissionRequest();
        b.engine.admission().installAdmissionCredential(a.engine.admission().approveAdmission(
            a.engine.admission().reviewAdmissionRequest(request.wire()), true, 3600).wire());
        reopenBoth(a, b);
    }

    private void exchangeAcrossReopens(Person a, Person b) throws Exception {
        String invite = a.pairing.createInvitation(3600);
        reopenBoth(a, b);
        String request = b.pairing.request(invite);
        reopenBoth(a, b);
        safeEqual(request, b.pairing.request(invite));
        String ack = a.pairing.accept(request);
        reopenBoth(a, b);
        safeEqual(ack, a.pairing.accept(request));
        safeEqual(a.engine.id(), b.pairing.complete(ack));
        reopenBoth(a, b);
        safeEqual(a.engine.id(), b.pairing.complete(ack));
        safeEqual(1, a.engine.contacts().size());
        safeEqual(1, b.engine.contacts().size());
        safeEqual(Engine.TrustState.UNVERIFIED, a.engine.trustState(b.engine.id()));
        safeEqual(Engine.TrustState.UNVERIFIED, b.engine.trustState(a.engine.id()));
        assertThrows(SecurityException.class, () -> a.engine.sendText(b.engine.id(), "synthetic denied", 600));
        assertThrows(SecurityException.class, () -> b.engine.authorizeTransport(a.engine.id()));
    }

    @Test public void filePickerReturnCannotReviveAuthorizationAndPendingTranscriptSurvives() throws Exception {
        Person a=new Person("A"),b=new Person("B");
        var product=new app.umbra.pairing.PairingProduct(a.vault);
        var step=product.createPairing();Runnable before=a.vault.authorization();
        var access=a.vault.access();
        var ticket=access.beginExternal(app.umbra.access.AccessSnapshot.ExternalAction.DOCUMENT_PICKER);
        access.background();access.foreground();
        assertThrows(SecurityException.class,before::run);
        safeEqual("REQUIRES_USER_ACTION",access.externalReturned(ticket,false).state().name());
        assertThrows(SecurityException.class,()->new app.umbra.pairing.PairingProduct(a.vault).pairingStatus(step.snapshot().id()));
        a.reopen();
        assertThrows(SecurityException.class,before::run);
        product=new app.umbra.pairing.PairingProduct(a.vault);
        safeEqual("INVITE_CREATED",product.pairingStatus(step.snapshot().id()).phase().name());
        var joiner=new app.umbra.pairing.PairingProduct(b.vault);
        var request=joiner.importFile(step.delivery().payload());
        var ack=product.importFile(request.delivery().payload());
        joiner.importFile(ack.delivery().payload());
        safeEqual(Engine.TrustState.UNVERIFIED,a.engine.trustState(b.engine.id()));
        safeEqual(Engine.TrustState.UNVERIFIED,b.engine.trustState(a.engine.id()));
    }

    @Test public void sameRealmPairingAndImmutableRetriesSurviveEveryVaultReopen() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        admitTogether(a, b);
        exchangeAcrossReopens(a, b);
        a.engine.admission().requirePeer(b.engine.id());
        b.engine.admission().requirePeer(a.engine.id());
        String code = Bytes.safetyCode(a.engine.id(), b.engine.id());
        a.engine.verify(b.engine.id(), code); b.engine.verify(a.engine.id(), code);
        reopenBoth(a, b);
        String id = a.engine.sendText(b.engine.id(), "synthetic persisted pairing", 600);
        b.engine.receive(a.engine.get("outbox", id).getJSONObject("envelope"));
        safeEqual("synthetic persisted pairing", b.engine.messages(a.engine.id()).get(0).getString("text"));
    }

    @Test public void unconfiguredPairingPersistsButHumanVerificationDoesNotGrantAdmission() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        exchangeAcrossReopens(a, b);
        String code = Bytes.safetyCode(a.engine.id(), b.engine.id());
        a.engine.verify(b.engine.id(), code); b.engine.verify(a.engine.id(), code);
        reopenBoth(a, b);
        for (Person person : new Person[]{a, b}) {
            safeEqual(AdmissionService.State.UNCONFIGURED, person.engine.admission().getAdmissionState());
            assertTrue(person.vault.keys("admission-peers").isEmpty());
            assertThrows(SecurityException.class, person.engine.admission()::requireAdmission);
            assertThrows(SecurityException.class, person.engine::authorizeTransportSelf);
        }
        safeEqual(Engine.TrustState.VERIFIED, a.engine.trustState(b.engine.id()));
        assertThrows(SecurityException.class, () -> a.engine.sendText(b.engine.id(), "synthetic denied", 600));
        assertThrows(SecurityException.class, () -> b.engine.authorizeTransport(a.engine.id()));
    }

    private static List<String> encryptedRows(Vault vault) {
        List<String> rows = new ArrayList<>();
        try (var cursor = vault.getReadableDatabase().rawQuery(
                "SELECT bucket,k,hex(nonce),hex(value) FROM records ORDER BY bucket,k", null)) {
            while (cursor.moveToNext()) rows.add(cursor.getString(0) + ":" + cursor.getString(1)
                + ":" + cursor.getString(2) + ":" + cursor.getString(3));
        }
        return rows;
    }

    @Test public void sqliteFailureAtConsumeRollsBackContactCredentialAndPrekeysAcrossReopen() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        admitTogether(a, b);
        String invite = a.pairing.createInvitation(3600);
        reopenBoth(a, b);
        String request = b.pairing.request(invite);
        reopenBoth(a, b);
        List<String> before = encryptedRows(a.vault);
        a.vault.getWritableDatabase().execSQL("CREATE TRIGGER reject_pairing_consume BEFORE INSERT ON records "
            + "WHEN NEW.bucket='pairing-issued' BEGIN SELECT RAISE(ABORT,'synthetic pairing storage failure'); END");
        try { assertThrows(IllegalStateException.class, () -> a.pairing.accept(request)); }
        finally { a.vault.getWritableDatabase().execSQL("DROP TRIGGER reject_pairing_consume"); }
        reopenBoth(a, b);
        safeEqual(before, encryptedRows(a.vault));
        assertTrue(a.engine.contacts().isEmpty());
        assertTrue(a.vault.keys("admission-peers").isEmpty());
        safeEqual("ISSUED", a.engine.get("pairing-issued", PairingService.invitationId(invite)).getString("state"));
        String ack = a.pairing.accept(request);
        reopenBoth(a, b);
        safeEqual(ack, a.pairing.accept(request));
        safeEqual(a.engine.id(), b.pairing.complete(ack));
    }
    @Test public void productQrAndCodeUseEncryptedVaultAndBoundedRealCodecs() throws Exception {
        Person a=new Person("A"),b=new Person("B");admitTogether(a,b);
        var owner=new app.umbra.pairing.PairingProduct(a.vault);
        var created=owner.createPairing();String invite=created.delivery().payload();
        var qr=app.umbra.pairing.PairingQrCodec.render(invite,512);byte[] plane=new byte[512*512];
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)plane[y*512+x]=qr.get(x,y)?0:(byte)255;
        try {safeEqual(invite,app.umbra.pairing.PairingQrCodec.decodeLuminance(plane,512,512));}
        catch(app.umbra.pairing.PairingException originalFailure) {
            // Diagnostic only: alternate readers can never satisfy this assertion.
            try {
                var diagnostic=new android.os.Bundle();
                diagnostic.putInt("pairingQrWidth",512);diagnostic.putInt("pairingQrHeight",512);
                diagnostic.putInt("pairingQrPayloadCharacters",invite.length());
                diagnostic.putString("pairingQrDefault",qrDiagnostic(plane,invite,null));
                diagnostic.putString("pairingQrTryHarder",qrDiagnostic(plane,invite,com.google.zxing.DecodeHintType.TRY_HARDER));
                diagnostic.putString("pairingQrPureBarcode",qrDiagnostic(plane,invite,com.google.zxing.DecodeHintType.PURE_BARCODE));
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,diagnostic);
            } catch(RuntimeException diagnosticUnavailable) {
                originalFailure.addSuppressed(new IllegalStateException("Fixed QR failure diagnostic unavailable"));
            }
            throw originalFailure;
        }
        char[] code=app.umbra.pairing.PairingSecrets.newCode();
        try {
            String blob=app.umbra.pairing.PairingSecrets.sealInvite(code,invite);
            safeEqual(invite,app.umbra.pairing.PairingSecrets.openInvite(code,blob));
            var request=new app.umbra.pairing.PairingProduct(b.vault).importFile(invite);
            reopenBoth(a,b);
            var ack=new app.umbra.pairing.PairingProduct(a.vault).importFile(request.delivery().payload());
            reopenBoth(a,b);
            var complete=new app.umbra.pairing.PairingProduct(b.vault).importFile(ack.delivery().payload());
            safeEqual(app.umbra.pairing.PairingSnapshot.Phase.COMPLETE,complete.snapshot().phase());
            assertTrue(complete.snapshot().verificationRequired());
            safeEqual(Engine.TrustState.UNVERIFIED,b.engine.trustState(a.engine.id()));
        } finally {Arrays.fill(code,'\0');Arrays.fill(plane,(byte)0);}
    }

    private static String qrDiagnostic(byte[] plane,String expected,com.google.zxing.DecodeHintType extra) {
        try {
            var hints=new java.util.EnumMap<com.google.zxing.DecodeHintType,Object>(com.google.zxing.DecodeHintType.class);
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,java.util.List.of(com.google.zxing.BarcodeFormat.QR_CODE));
            if(extra!=null)hints.put(extra,Boolean.TRUE);
            var source=new com.google.zxing.PlanarYUVLuminanceSource(plane,512,512,0,0,512,512,false);
            var bitmap=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));
            String decoded=new com.google.zxing.qrcode.QRCodeReader().decode(bitmap,hints).getText();
            return expected.equals(decoded)?"SUCCESS":"OTHER";
        } catch(com.google.zxing.NotFoundException failure) {return "NotFound";}
        catch(com.google.zxing.FormatException failure) {return "Format";}
        catch(com.google.zxing.ChecksumException failure) {return "Checksum";}
        catch(RuntimeException failure) {return "OTHER";}
    }

}
