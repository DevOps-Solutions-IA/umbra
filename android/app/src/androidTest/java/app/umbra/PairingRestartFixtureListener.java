package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.AccessGate;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Vault;
import app.umbra.pairing.PairingService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import static org.junit.Assert.*;

/** Test APK only: host force-stops a live unlocked process after each committed
 * pairing stage. No death-during-commit, radio or hardware-authentication claim. */
public final class PairingRestartFixtureListener extends RunListener {
    private static final String LOCATOR = "synthetic-pairing-restart-paths";

    private static Context isolated(Context target, File file) {
        return new ContextWrapper(target) {
            @Override public File getDatabasePath(String ignored) { return file; }
            @Override public SQLiteDatabase openOrCreateDatabase(String ignored, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openDatabase(file.getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
            }
        };
    }
    private static byte[] password(String role) { return Bytes.utf8("synthetic pairing restart password " + role); }
    private static void enrollPassword(Vault vault, AccessGate gate, String role) throws Exception {
        byte[] password = password(role);
        try {
            vault.createPassword(password); gate.unlock();
            assertThrows(SecurityException.class, () -> vault.get("meta", "profile"));
            vault.unlock(password);
        } finally { Arrays.fill(password, (byte) 0); }
    }
    private static Vault reopen(Context context, File file, String role) throws Exception {
        assertTrue(file.exists()); AccessGate gate = new AccessGate();
        Vault vault = new Vault(isolated(context, file), gate);
        assertEquals(Vault.State.LOCKED, vault.getVaultState());
        assertThrows(SecurityException.class, () -> vault.get("meta", "profile"));
        gate.unlock();
        assertThrows(SecurityException.class, () -> vault.get("meta", "profile"));
        byte[] password = password(role);
        try { vault.unlock(password); }
        finally { Arrays.fill(password, (byte) 0); }
        assertEquals(Vault.State.UNLOCKED, vault.getVaultState());
        return vault;
    }
    private static void admitTogether(Engine a, Engine b) throws Exception {
        var realm = a.admission().createAdmissionRealm(true);
        var own = a.admission().createAdmissionRequest();
        a.admission().approveAndInstallOwnAdmission(a.admission().reviewAdmissionRequest(own.wire()), true, 3600);
        b.admission().installRealmConfig(realm.encode(), true);
        var request = b.admission().createAdmissionRequest();
        b.admission().installAdmissionCredential(a.admission().approveAdmission(
            a.admission().reviewAdmissionRequest(request.wire()), true, 3600).wire());
    }
    private static void status(String value) {
        Bundle report = new Bundle(); report.putString("pairingRestart", value);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }
    private static void awaitForceStop(String phase, Vault a, Vault b) throws Exception {
        assertEquals(Vault.State.UNLOCKED, a.getVaultState());
        assertEquals(Vault.State.UNLOCKED, b.getVaultState());
        status("READY phase=" + phase + " committed; two unlocked synthetic vaults");
        while (true) Thread.sleep(1000);
    }

    @Override public void testRunStarted(Description description) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".dev") || context.getPackageName().endsWith(".vaultlab"));
        File locator = new File(context.getCacheDir(), LOCATOR);
        String phase = InstrumentationRegistry.getArguments().getString("pairingPhase", "");
        if (phase.equals("invite")) {
            assertFalse(locator.exists());
            DeviceVaultPasswordTest fixture = new DeviceVaultPasswordTest(); fixture.before();
            // Only this fixture's deliberately synthetic placeholder is removed.
            fixture.vault.transaction(() -> { fixture.vault.remove("meta", "identity"); return null; });
            File second = new File(context.getCacheDir(), "synthetic-pairing-restart-b-" + UUID.randomUUID() + ".db");
            AccessGate gate = new AccessGate(); gate.unlock();
            Vault b = new Vault(isolated(context, second), gate);
            Engine first = new Engine(fixture.vault), other = new Engine(b);
            first.initialize("Synthetic restart A"); other.initialize("Synthetic restart B");
            admitTogether(first, other);
            enrollPassword(fixture.vault, fixture.gate, "A"); enrollPassword(b, gate, "B");
            Files.write(locator.toPath(), (fixture.file.getName() + "\n" + second.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            new PairingService(fixture.vault).createInvitation(600);
            assertEquals(1, fixture.vault.keys("pairing-issued").size());
            assertTrue(first.contacts().isEmpty()); assertTrue(other.contacts().isEmpty());
            awaitForceStop(phase, fixture.vault, b);
            return;
        }
        if (!java.util.Set.of("request", "accept", "complete", "verify").contains(phase))
            throw new SecurityException("Specify pairing restart phase");
        String[] names = new String(Files.readAllBytes(locator.toPath()), StandardCharsets.UTF_8).split("\n", -1);
        if (names.length != 3 || !names[2].isEmpty()
                || !names[0].matches("synthetic-password-[0-9a-f-]+\\.db")
                || !names[1].matches("synthetic-pairing-restart-b-[0-9a-f-]+\\.db"))
            throw new SecurityException("Invalid synthetic pairing fixture paths");
        File firstFile = new File(context.getCacheDir(), names[0]), secondFile = new File(context.getCacheDir(), names[1]);
        try (Vault a = reopen(context, firstFile, "A"); Vault b = reopen(context, secondFile, "B")) {
            Engine first = new Engine(a), second = new Engine(b);
            PairingService inviter = new PairingService(a), joiner = new PairingService(b);
            first.admission().requireAdmission(); second.admission().requireAdmission();
            assertEquals(1, a.keys("pairing-issued").size()); String id = a.keys("pairing-issued").get(0);
            var issued = first.get("pairing-issued", id); String invite = issued.getString("invite");
            assertEquals(id, PairingService.invitationId(invite));
            if (phase.equals("request")) {
                assertEquals("ISSUED", issued.getString("state")); assertTrue(b.keys("pairing-pending").isEmpty());
                String request = joiner.request(invite); assertTrue("Request retry changed", request.equals(joiner.request(invite)));
                assertTrue(first.contacts().isEmpty()); assertTrue(second.contacts().isEmpty());
            } else {
                assertEquals(1, b.keys("pairing-pending").size());
                var pending = second.get("pairing-pending", id); String request = pending.getString("request");
                assertTrue("Persisted request changed", request.equals(joiner.request(invite)));
                if (phase.equals("accept")) {
                    assertEquals("ISSUED", issued.getString("state")); assertEquals("PENDING", pending.getString("state"));
                    String ack = inviter.accept(request); assertTrue("ACK retry changed", ack.equals(inviter.accept(request)));
                    assertEquals(1, first.contacts().size()); assertTrue(second.contacts().isEmpty());
                } else {
                    assertEquals("CONSUMED", issued.getString("state")); String ack = issued.getString("ack");
                    assertTrue("Persisted ACK changed", ack.equals(inviter.accept(request)));
                    if (phase.equals("complete")) {
                        assertEquals("PENDING", pending.getString("state")); assertTrue(second.contacts().isEmpty());
                        assertEquals(first.id(), joiner.complete(ack));
                    } else {
                        assertEquals("COMPLETE", pending.getString("state"));
                        assertEquals(Bytes.sha256(Bytes.utf8(ack)), pending.getString("ackHash"));
                        byte[] before = b.get("pairing-pending", id);
                        assertEquals(first.id(), joiner.complete(ack));
                        assertTrue("Completed record changed on retry", Arrays.equals(before, b.get("pairing-pending", id)));
                    }
                    assertEquals(1, first.contacts().size()); assertEquals(1, second.contacts().size());
                    assertEquals(Engine.TrustState.UNVERIFIED, first.trustState(second.id()));
                    assertEquals(Engine.TrustState.UNVERIFIED, second.trustState(first.id()));
                    first.admission().requirePeer(second.id()); second.admission().requirePeer(first.id());
                    assertThrows(SecurityException.class, () -> first.sendText(second.id(), "synthetic denied", 600));
                    assertThrows(SecurityException.class, () -> second.authorizeTransport(first.id()));
                }
            }
            if (!phase.equals("verify")) awaitForceStop(phase, a, b);
        }
        if (phase.equals("verify")) {
            Vault.destroyKey();
            assertTrue(SQLiteDatabase.deleteDatabase(firstFile)); assertTrue(SQLiteDatabase.deleteDatabase(secondFile));
            assertTrue(locator.delete());
            status("PASS four committed stages survived actual force-stop; fresh passwords required; contacts unverified");
        }
    }
}
