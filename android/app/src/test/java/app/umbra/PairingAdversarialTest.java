package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import app.umbra.pairing.PairingService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real libsignal signatures; serialized test Records reproduce the production transaction contract. */
public class PairingAdversarialTest {
    private static final class SerializedRecords implements Records {
        private final MemoryRecords delegate = new MemoryRecords();
        private final Set<String> buckets = new HashSet<>();
        String failBucket;
        Work<Void> beforeNextTransaction;
        public synchronized byte[] get(String bucket, String key) { return delegate.get(bucket, key); }
        public synchronized void put(String bucket, String key, byte[] data) {
            if (bucket.equals(failBucket)) throw new IllegalStateException("Synthetic storage failure");
            buckets.add(bucket); delegate.put(bucket, key, data);
        }
        public synchronized void remove(String bucket, String key) { delegate.remove(bucket, key); }
        public synchronized List<String> keys(String bucket) { return delegate.keys(bucket); }
        public synchronized <T> T transaction(Work<T> work) throws Exception {
            Work<Void> delayed = beforeNextTransaction; beforeNextTransaction = null;
            if (delayed != null) delayed.run();
            return delegate.transaction(work);
        }
        synchronized Map<String, String> snapshot() {
            Map<String, String> result = new TreeMap<>();
            for (String bucket : buckets) for (String key : keys(bucket))
                result.put(bucket + "/" + key, Base64.getEncoder().encodeToString(get(bucket, key)));
            return result;
        }
    }
    private static final class Person {
        final SerializedRecords records = new SerializedRecords();
        final Engine engine = new Engine(records);
        final PairingService pairing = new PairingService(records);
        Person(String alias) throws Exception { engine.initialize(alias); }
    }
    private interface Operation { void run() throws Exception; }
    private static void reject(Operation operation) throws Exception {
        try { operation.run(); fail("Malformed pairing accepted"); }
        catch (SecurityException | IllegalArgumentException expected) { }
    }
    private static String signedFixture(Person signer, String[] lines) {
        byte[] body = Bytes.utf8(String.join("\n", lines));
        byte[] signature = new SignalStore(signer.records).getIdentityKeyPair().getPrivateKey().calculateSignature(body);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "umbra:invite:1:" + encoder.encodeToString(body) + "." + encoder.encodeToString(signature);
    }
    private static String[] lines(String invite) {
        String encoded = invite.substring("umbra:invite:1:".length()).split("\\.")[0];
        return Bytes.text(Base64.getUrlDecoder().decode(encoded)).split("\n", -1);
    }

    @Test public void acceptStorageFailureRollsBackContactPrekeysAndConsumption() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        String invite = owner.pairing.createInvitation(600), request = requester.pairing.request(invite);
        Map<String, String> before = owner.records.snapshot();
        owner.records.failBucket = "pairing-issued";
        assertThrows(IllegalStateException.class, () -> owner.pairing.accept(request));
        assertEquals(before, owner.records.snapshot());
        assertEquals(0, owner.engine.contacts().size());
        owner.records.failBucket = null;
        String ack = new PairingService(owner.records).accept(request);
        assertEquals(owner.engine.id(), requester.pairing.complete(ack));
        assertEquals(1, owner.engine.contacts().size());
        assertEquals(Engine.TrustState.UNVERIFIED, owner.engine.trustState(requester.engine.id()));
    }

    @Test public void pendingStorageFailureRollsBackGeneratedKeyMaterial() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        String invite = owner.pairing.createInvitation(600);
        Map<String, String> before = requester.records.snapshot();
        requester.records.failBucket = "pairing-pending";
        assertThrows(IllegalStateException.class, () -> requester.pairing.request(invite));
        assertEquals(before, requester.records.snapshot());
        requester.records.failBucket = null;
        assertNotNull(new PairingService(requester.records).request(invite));
    }

    @Test(timeout = 30000) public void eightConcurrentRequestsHaveOnlyOneWinner() throws Exception {
        Person owner = new Person("Owner");
        String invite = owner.pairing.createInvitation(600);
        List<Person> requesters = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Person person = new Person("Requester " + i); requesters.add(person);
            requests.add(person.pairing.request(invite));
        }
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (String request : requests) results.add(workers.submit(() -> {
                start.await();
                try { return new PairingService(owner.records).accept(request); }
                catch (SecurityException expected) { return null; }
            }));
            start.countDown();
            int winners = 0;
            for (int i = 0; i < results.size(); i++) {
                String ack = results.get(i).get(20, TimeUnit.SECONDS);
                if (ack != null) {
                    winners++;
                    assertEquals(ack, new PairingService(owner.records).accept(requests.get(i)));
                    assertEquals(owner.engine.id(), requesters.get(i).pairing.complete(ack));
                }
            }
            assertEquals(1, winners);
            assertEquals(1, owner.engine.contacts().size());
            assertEquals(1, owner.records.keys("prekey").size());
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void correctlySignedExpiredFutureAndNoncanonicalTimestampsAreRejected() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        String invite = owner.pairing.createInvitation(600);
        long now = Bytes.now();
        String[][] times = {
            {"" + (now - 120), "" + (now - 1)},
            {"" + (now + 3600), "" + (now + 4200)},
            {"0" + now, "" + (now + 600)},
            {"+" + now, "" + (now + 600)},
            {"" + now, "" + now},
            {"" + now, "" + (now + 86401)}
        };
        Map<String, String> before = requester.records.snapshot();
        for (String[] pair : times) {
            String[] body = lines(invite); body[5] = pair[0]; body[6] = pair[1];
            reject(() -> requester.pairing.request(signedFixture(owner, body)));
        }
        assertEquals(before, requester.records.snapshot());
    }

    @Test public void signedNoncanonicalLinesAndBoundedMalformedCorpusNeverWrite() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        String invite = owner.pairing.createInvitation(600);
        Map<String, String> before = requester.records.snapshot();
        String[] noFinalNewline = lines(invite);
        reject(() -> requester.pairing.request(signedFixture(owner, Arrays.copyOf(noFinalNewline, noFinalNewline.length - 1))));
        String[] extra = lines(invite); extra[7] = "1\nextra";
        reject(() -> requester.pairing.request(signedFixture(owner, extra)));
        String[] carriage = lines(invite); carriage[0] += "\r";
        reject(() -> requester.pairing.request(signedFixture(owner, carriage)));
        List<String> corpus = new ArrayList<>(Arrays.asList(null, "", invite + "=", invite + "\n",
            "umbra:invite:1:" + "A".repeat(1025), "umbra:invite:1:%.%", invite.replace(":1:", ":999:")));
        Random random = new Random(42);
        for (int i = 0; i < 64; i++) corpus.add(invite.substring(0, random.nextInt(invite.length())));
        for (String value : corpus) reject(() -> requester.pairing.request(value));
        assertEquals(before, requester.records.snapshot());
    }

    private static String shortInvitation(Person owner, long expires) throws Exception {
        String original = owner.pairing.createInvitation(600);
        String id = PairingService.invitationId(original);
        String[] body = lines(original); body[6] = "" + expires;
        String shortInvite = signedFixture(owner, body);
        org.json.JSONObject row = owner.engine.get("pairing-issued", id);
        row.put("invite", shortInvite).put("expires", expires);
        owner.records.put("pairing-issued", id, Bytes.utf8(row.toString()));
        return shortInvite;
    }
    private static WorkDelay untilExpiry(long expires) { return new WorkDelay(expires); }
    private static final class WorkDelay implements Records.Work<Void> {
        private final long expires;
        WorkDelay(long expires) { this.expires = expires; }
        public Void run() throws Exception {
            while (Bytes.now() < expires) Thread.sleep(25);
            return null;
        }
    }

    @Test(timeout = 15000) public void requestExpiryWhileWaitingForTransactionNeverCreatesKeys() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        long expires = Bytes.now() + 3;
        String invite = shortInvitation(owner, expires);
        Map<String, String> before = requester.records.snapshot();
        requester.records.beforeNextTransaction = untilExpiry(expires);
        reject(() -> requester.pairing.request(invite));
        assertEquals(before, requester.records.snapshot());
    }

    @Test(timeout = 15000) public void acceptExpiryWhileWaitingForTransactionNeverConsumesInvitation() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        long expires = Bytes.now() + 3;
        String invite = shortInvitation(owner, expires);
        String request = requester.pairing.request(invite);
        Map<String, String> before = owner.records.snapshot();
        owner.records.beforeNextTransaction = untilExpiry(expires);
        reject(() -> owner.pairing.accept(request));
        assertEquals(before, owner.records.snapshot());
    }

    @Test public void reconstructedServicesPreserveExactTranscriptAndRevocation() throws Exception {
        Person owner = new Person("Owner"), requester = new Person("Requester");
        String invite = owner.pairing.createInvitation(600), request = requester.pairing.request(invite);
        assertEquals(request, new PairingService(requester.records).request(invite));
        String ack = new PairingService(owner.records).accept(request);
        assertEquals(ack, new PairingService(owner.records).accept(request));
        assertEquals(owner.engine.id(), new PairingService(requester.records).complete(ack));
        assertEquals(Engine.TrustState.UNVERIFIED, new Engine(requester.records).trustState(owner.engine.id()));
        new PairingService(owner.records).revoke(PairingService.invitationId(invite));
        reject(() -> new PairingService(owner.records).accept(request));
    }
}
