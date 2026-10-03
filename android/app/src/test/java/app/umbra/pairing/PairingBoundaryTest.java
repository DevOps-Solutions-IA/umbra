package app.umbra.pairing;

import app.umbra.core.AccessGate;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic protocol-clock boundaries with real libsignal and an explicit
 * transactional memory double. This is not SQLite durability or Android evidence. */
public final class PairingBoundaryTest {
    private static void safeEqual(Object expected,Object actual) {
        assertTrue("Synthetic pairing invariant mismatch (values redacted)",java.util.Objects.deepEquals(expected,actual));
    }

    private static final class Store implements Records {
        final AccessGate gate = new AccessGate();
        Map<String, byte[]> values = new TreeMap<>();
        String failBucket;
        Runnable nextTransaction;
        String clockAdvanceBucket;
        Runnable advanceClockOnPut;
        Store() { gate.unlock(); }
        public byte[] get(String bucket, String key) {
            gate.requireUnlocked(); byte[] value = values.get(bucket + '\0' + key);
            return value == null ? null : value.clone();
        }
        public void put(String bucket, String key, byte[] value) {
            gate.requireUnlocked();
            if (bucket.equals(failBucket)) throw new IllegalStateException("Synthetic storage failure");
            values.put(bucket + '\0' + key, value.clone());
            if (bucket.equals(clockAdvanceBucket) && advanceClockOnPut != null) {
                Runnable advance = advanceClockOnPut; advanceClockOnPut = null; advance.run();
            }
        }
        public void remove(String bucket, String key) { gate.requireUnlocked(); values.remove(bucket + '\0' + key); }
        public List<String> keys(String bucket) {
            gate.requireUnlocked(); List<String> result = new ArrayList<>(); String prefix = bucket + '\0';
            for (String key : values.keySet()) if (key.startsWith(prefix)) result.add(key.substring(prefix.length()));
            return result;
        }
        public Runnable authorization() { var lease = gate.enter(); return () -> gate.check(lease); }
        public <T> T transaction(Work<T> work) throws Exception {
            Runnable hook = nextTransaction; nextTransaction = null; if (hook != null) hook.run();
            Map<String, byte[]> before = new TreeMap<>(values);
            try { return work.run(); } catch (Exception | Error failure) { values = before; throw failure; }
        }
        Map<String, String> snapshot() {
            Map<String, String> result = new TreeMap<>();
            values.forEach((key, value) -> result.put(key, Base64.getEncoder().encodeToString(value)));
            return result;
        }
    }
    private static final class Person {
        final Store db = new Store();
        final Engine engine = new Engine(db);
        final PairingService pairing;
        Person(AtomicLong clock) throws Exception {
            engine.initialize("Synthetic boundary peer"); pairing = new PairingService(db, clock::get);
        }
    }
    private static void code(PairingException.Code expected, org.junit.function.ThrowingRunnable work) {
        safeEqual(expected, assertThrows(PairingException.class, work).code());
    }

    @Test public void expiryIsInclusiveForRequestAcceptAndCompletedReplayWithoutMutation() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now()); long start = clock.get();
        Person a = new Person(clock), b = new Person(clock), c = new Person(clock);
        String invite = a.pairing.createInvitation(60);
        clock.set(start + 59);
        String request = b.pairing.request(invite), ack = a.pairing.accept(request);
        safeEqual(a.engine.id(), b.pairing.complete(ack));
        safeEqual(request, b.pairing.request(invite)); safeEqual(ack, a.pairing.accept(request));
        Map<String, String> aBefore = a.db.snapshot(), bBefore = b.db.snapshot(), cBefore = c.db.snapshot();
        clock.set(start + 60);
        code(PairingException.Code.EXPIRED, () -> c.pairing.request(invite));
        code(PairingException.Code.EXPIRED, () -> b.pairing.request(invite));
        code(PairingException.Code.EXPIRED, () -> a.pairing.accept(request));
        code(PairingException.Code.EXPIRED, () -> b.pairing.complete(ack));
        safeEqual(aBefore, a.db.snapshot()); safeEqual(bBefore, b.db.snapshot()); safeEqual(cBefore, c.db.snapshot());
    }

    @Test public void expiryWhileWaitingForTransactionRejectsRequestAndAcceptance() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now()); long start = clock.get();
        Person a = new Person(clock), b = new Person(clock), c = new Person(clock);
        String invite = a.pairing.createInvitation(60), request = b.pairing.request(invite);
        Map<String, String> aBefore = a.db.snapshot(), cBefore = c.db.snapshot();
        clock.set(start + 59); c.db.nextTransaction = () -> clock.set(start + 60);
        code(PairingException.Code.EXPIRED, () -> c.pairing.request(invite));
        clock.set(start + 59); a.db.nextTransaction = () -> clock.set(start + 60);
        code(PairingException.Code.EXPIRED, () -> a.pairing.accept(request));
        safeEqual(aBefore, a.db.snapshot()); safeEqual(cBefore, c.db.snapshot());
    }

    @Test public void revokeConsumedInvitationSuppressesImmutableAckAndCancellationSuppressesComplete() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now());
        Person a = new Person(clock), b = new Person(clock), c = new Person(clock);
        String invite = a.pairing.createInvitation(600), id = PairingService.invitationId(invite);
        String request = b.pairing.request(invite), other = c.pairing.request(invite), ack = a.pairing.accept(request);
        Map<String, String> consumed = a.db.snapshot();
        code(PairingException.Code.ALREADY_CONSUMED, () -> a.pairing.accept(other));
        safeEqual(consumed, a.db.snapshot());
        a.pairing.revoke(id); assertFalse(a.engine.get("pairing-issued", id).has("ack"));
        Map<String, String> revoked = a.db.snapshot();
        code(PairingException.Code.REVOKED, () -> a.pairing.accept(request));
        code(PairingException.Code.REVOKED, () -> a.pairing.accept(other));
        safeEqual(revoked, a.db.snapshot());
        b.pairing.cancelPending(id); Map<String, String> cancelled = b.db.snapshot();
        code(PairingException.Code.CANCELLED, () -> b.pairing.complete(ack));
        code(PairingException.Code.CANCELLED, () -> b.pairing.request(invite));
        safeEqual(cancelled, b.db.snapshot()); assertTrue(b.engine.contacts().isEmpty());
    }

    @Test public void eachPersistenceFailureRollsBackAllRecordsAndAllowsExactRetry() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now());
        Person a = new Person(clock), b = new Person(clock);
        String invite = a.pairing.createInvitation(600);
        Map<String, String> beforeRequest = b.db.snapshot(); b.db.failBucket = "pairing-pending";
        assertThrows(IllegalStateException.class, () -> b.pairing.request(invite)); b.db.failBucket = null;
        safeEqual(beforeRequest, b.db.snapshot());
        String request = b.pairing.request(invite);
        Map<String, String> beforeAccept = a.db.snapshot(); a.db.failBucket = "pairing-issued";
        assertThrows(IllegalStateException.class, () -> a.pairing.accept(request)); a.db.failBucket = null;
        safeEqual(beforeAccept, a.db.snapshot());
        String ack = a.pairing.accept(request);
        Map<String, String> beforeComplete = b.db.snapshot(); b.db.failBucket = "pairing-pending";
        assertThrows(IllegalStateException.class, () -> b.pairing.complete(ack)); b.db.failBucket = null;
        safeEqual(beforeComplete, b.db.snapshot());
        safeEqual(a.engine.id(), b.pairing.complete(ack));
        Map<String, String> complete = b.db.snapshot();
        safeEqual(a.engine.id(), new PairingService(b.db, clock::get).complete(ack));
        safeEqual(complete, b.db.snapshot());
        safeEqual(ack, new PairingService(a.db, clock::get).accept(request));
    }

    @Test public void secondValidSignatureForSameAckBodyIsReplayNotNewCompletion() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now());
        Person a = new Person(clock), b = new Person(clock);
        String invite = a.pairing.createInvitation(600), request = b.pairing.request(invite), ack = a.pairing.accept(request);
        b.pairing.complete(ack); Map<String, String> before = b.db.snapshot();
        int separator = ack.lastIndexOf('.');
        String encoded = ack.substring("umbra:ack:1:".length(), separator);
        byte[] body = Base64.getUrlDecoder().decode(encoded);
        byte[] signature = new SignalStore(a.db).getIdentityKeyPair().getPrivateKey().calculateSignature(body);
        String resigned = ack.substring(0, separator + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertNotEquals(ack, resigned);
        code(PairingException.Code.REPLAY, () -> b.pairing.complete(resigned));
        safeEqual(before, b.db.snapshot());
    }

    @Test public void validOuterRequestCannotHideInvalidCardOrPrekeySignatures() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now());
        Person a = new Person(clock), b = new Person(clock);
        String invite = a.pairing.createInvitation(600), request = b.pairing.request(invite);
        Map<String, String> before = a.db.snapshot();
        var privateKey = new SignalStore(b.db).getIdentityKeyPair().getPrivateKey();
        for (String target : new String[]{"signature", "signedSig", "kemSig"}) {
            String encoded = request.substring("umbra:request:1:".length(), request.lastIndexOf('.'));
            String[] fields = Bytes.text(Base64.getUrlDecoder().decode(encoded)).split("\n", -1);
            var card = new org.json.JSONObject(Bytes.text(Bytes.unb64(fields[4])));
            if (target.equals("signature")) card.put("signature", Bytes.b64(new byte[64]));
            else {
                var body = new org.json.JSONObject(Bytes.text(Bytes.unb64(card.getString("body"))));
                body.put(target, Bytes.b64(new byte[64]));
                byte[] raw = Bytes.utf8(body.toString());
                card.put("body", Bytes.b64(raw)).put("signature", Bytes.b64(privateKey.calculateSignature(raw)));
            }
            fields[4] = Bytes.b64(Bytes.utf8(card.toString()));
            byte[] raw = Bytes.utf8(String.join("\n", fields));
            String altered = "umbra:request:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(privateKey.calculateSignature(raw));
            code(PairingException.Code.INVALID_SIGNATURE, () -> a.pairing.accept(altered));
            safeEqual(before, a.db.snapshot());
        }
        assertNotNull(a.pairing.accept(request));
    }

    private static void expireAtPut(Person person, String bucket, AtomicLong clock, long expires) {
        person.db.clockAdvanceBucket = bucket; person.db.advanceClockOnPut = () -> clock.set(expires);
    }

    @Test public void requestExpiryAtFinalPersistenceRollsBackPrekeysAndPending() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now()); long start = clock.get();
        Person a = new Person(clock), b = new Person(clock);
        String invite = a.pairing.createInvitation(60);
        clock.set(start + 59); Map<String, String> before = b.db.snapshot();
        expireAtPut(b, "pairing-pending", clock, start + 60);
        code(PairingException.Code.EXPIRED, () -> b.pairing.request(invite));
        safeEqual(before, b.db.snapshot());
    }

    @Test public void acceptanceExpiryAtFinalPersistenceRollsBackContactAndConsumption() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now()); long start = clock.get();
        Person a = new Person(clock), b = new Person(clock);
        String invite = a.pairing.createInvitation(60), request = b.pairing.request(invite);
        clock.set(start + 59); Map<String, String> before = a.db.snapshot();
        expireAtPut(a, "pairing-issued", clock, start + 60);
        code(PairingException.Code.EXPIRED, () -> a.pairing.accept(request));
        safeEqual(before, a.db.snapshot());
    }

    @Test public void completionExpiryAtFinalPersistenceRollsBackContactAndAckHash() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now()); long start = clock.get();
        Person a = new Person(clock), b = new Person(clock);
        String invite = a.pairing.createInvitation(60), request = b.pairing.request(invite), ack = a.pairing.accept(request);
        clock.set(start + 59); Map<String, String> before = b.db.snapshot();
        expireAtPut(b, "pairing-pending", clock, start + 60);
        code(PairingException.Code.EXPIRED, () -> b.pairing.complete(ack));
        safeEqual(before, b.db.snapshot());
    }

    private static String requestWithCard(Person signer, String original, org.json.JSONObject card) {
        String encoded = original.substring("umbra:request:1:".length(), original.lastIndexOf('.'));
        String[] fields = Bytes.text(Base64.getUrlDecoder().decode(encoded)).split("\n", -1);
        fields[4] = Bytes.b64(Bytes.utf8(card.toString()));
        byte[] raw = Bytes.utf8(String.join("\n", fields));
        byte[] signature = new SignalStore(signer.db).getIdentityKeyPair().getPrivateKey().calculateSignature(raw);
        return "umbra:request:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
            + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    @Test public void signedRequestRejectsCompleteValidCardBelongingToAnotherIdentity() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now());
        Person a = new Person(clock), b = new Person(clock), c = new Person(clock);
        String invite = a.pairing.createInvitation(600), original = b.pairing.request(invite);
        // C's entire card is authentic; B signs the containing request with B's real key.
        String substituted = requestWithCard(b, original, c.engine.createCard());
        Map<String, String> before = a.db.snapshot();
        code(PairingException.Code.INVALID_SIGNATURE, () -> a.pairing.accept(substituted));
        safeEqual(before, a.db.snapshot());
        assertTrue(a.engine.contacts().isEmpty());
        assertNotNull(a.pairing.accept(original));
    }

    @Test public void signedRequestRejectsOnlyCardIdentityFieldSubstitution() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now());
        Person a = new Person(clock), b = new Person(clock), c = new Person(clock);
        String invite = a.pairing.createInvitation(600), original = b.pairing.request(invite);
        String encoded = original.substring("umbra:request:1:".length(), original.lastIndexOf('.'));
        String[] fields = Bytes.text(Base64.getUrlDecoder().decode(encoded)).split("\n", -1);
        var card = new org.json.JSONObject(Bytes.text(Bytes.unb64(fields[4])));
        var body = new org.json.JSONObject(Bytes.text(Bytes.unb64(card.getString("body"))));
        body.put("identity", Bytes.b64(new SignalStore(c.db).getIdentityKeyPair().getPublicKey().serialize()));
        // Leave every other card field and its signature unchanged; outer request is freshly valid.
        card.put("body", Bytes.b64(Bytes.utf8(body.toString())));
        String substituted = requestWithCard(b, original, card);
        Map<String, String> before = a.db.snapshot();
        code(PairingException.Code.INVALID_SIGNATURE, () -> a.pairing.accept(substituted));
        safeEqual(before, a.db.snapshot());
        assertTrue(a.engine.contacts().isEmpty());
        assertNotNull(a.pairing.accept(original));
    }

    @Test public void authenticExpiredCodeInvitationDecryptsButIsRejectedAsExpired() throws Exception {
        AtomicLong clock = new AtomicLong(Bytes.now() - 120);
        Person a = new Person(clock);
        String invite = a.pairing.createInvitation(60);
        String[] envelope = invite.substring("umbra:invite:1:".length()).split("\\.", -1);
        byte[] signedBody = Base64.getUrlDecoder().decode(envelope[0]);
        String[] fields = Bytes.text(signedBody).split("\n", -1);
        assertTrue("Expired fixture signature must be authentic", new org.signal.libsignal.protocol.ecc.ECPublicKey(
            new SignalStore(a.db).getIdentityKeyPair().getPublicKey().serialize())
            .verifySignature(signedBody, Base64.getUrlDecoder().decode(envelope[1])));
        char[] humanCode = PairingSecrets.newCode();
        byte[] seed = PairingSecrets.normalize(humanCode), key = PairingSecrets.derive(seed, "UMBRA-PAIR-CODE-INVITE-v1");
        byte[] nonce = Bytes.random(12), plain = Bytes.utf8(invite);
        try {
            // Independent synthetic envelope construction bypasses issuance-time freshness
            // only in the fixture, so production decrypt sees a genuine authenticated stale blob.
            String aad = "UMBRA-PAIR-BLOB-1\nINVITE\n" + fields[1] + "\n"
                + Bytes.sha256(plain) + "\n" + fields[6] + "\n";
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
            cipher.updateAAD(Bytes.utf8(aad)); byte[] encrypted = cipher.doFinal(plain);
            String prefix = aad + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "\n";
            String blob = Bytes.b64(Bytes.utf8(prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted) + "\n"));
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
            cipher.updateAAD(Bytes.utf8(aad)); byte[] decrypted = cipher.doFinal(encrypted);
            try { safeEqual(plain, decrypted); } finally { Arrays.fill(decrypted, (byte) 0); }
            code(PairingException.Code.EXPIRED, () -> PairingSecrets.openInvite(humanCode, blob));
            encrypted[encrypted.length - 1] ^= 1;
            String corrupted = Bytes.b64(Bytes.utf8(prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted) + "\n"));
            code(PairingException.Code.INVALID_FORMAT, () -> PairingSecrets.openInvite(humanCode, corrupted));
        } finally {
            Arrays.fill(humanCode, '\0'); Arrays.fill(seed, (byte) 0); Arrays.fill(key, (byte) 0);
            Arrays.fill(nonce, (byte) 0); Arrays.fill(plain, (byte) 0);
        }
    }
}
