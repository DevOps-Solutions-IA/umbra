package app.umbra;

import app.umbra.core.*;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.devices.*;
import app.umbra.transport.RelayClient;
import java.util.*;
import org.json.*;

/** Three independent real Signal clients and an isolated HTTPS relay; JVM storage is synthetic. */
final class DeviceRelayIntegration {
    private static final class Store implements Records {
        final MemoryRecords memory = new MemoryRecords(); final AccessGate gate = new AccessGate();
        Store() { gate.unlock(); }
        public synchronized byte[] get(String b,String k) { gate.requireUnlocked(); return memory.get(b,k); }
        public synchronized void put(String b,String k,byte[] v) { gate.requireUnlocked(); memory.put(b,k,v); }
        public synchronized void remove(String b,String k) { gate.requireUnlocked(); memory.remove(b,k); }
        public synchronized List<String> keys(String b) { gate.requireUnlocked(); return memory.keys(b); }
        public synchronized <T> T transaction(Work<T> w) throws Exception {
            var lease = gate.enter(); return memory.transaction(() -> { T value = w.run(); gate.check(lease); return value; });
        }
        public Runnable authorization() { var lease=gate.enter(); return () -> gate.check(lease); }
    }
    private static final class Device {
        final Store db = new Store(); final Engine e = new Engine(db); final DeviceService d = new DeviceService(db);
        Device(String name) throws Exception { e.initialize(name); }
    }
    private static void check(boolean value,String label) { if(!value) throw new AssertionError(label); System.out.println("PASS device HTTPS: " + label); }
    private static void pair(Device a,Device b) throws Exception {
        a.e.importCard(b.e.createCard()); b.e.importCard(a.e.createCard());
        a.e.verify(b.e.id(),Bytes.safetyCode(a.e.id(),b.e.id())); b.e.verify(a.e.id(),Bytes.safetyCode(a.e.id(),b.e.id()));
    }
    private static void approve(Device viewer,Device owner,Device... members) throws Exception {
        String roster=owner.d.roster(owner.e.id()); viewer.d.apply(roster);
        for(Device member:members) viewer.e.importCard(member.e.createCard());
        var consent=viewer.d.reviewRoster(roster); viewer.d.approveRoster(consent,consent.fingerprint(),true);
    }
    private static void upload(RelayClient relay,Device sender) throws Exception {
        for(JSONObject row:sender.e.outbox()) {
            JSONObject envelope=row.getJSONObject("envelope");
            relay.sendAuthorized(sender.e,sender.e.contact(row.getString("peer")).getJSONObject("card"),envelope);
            sender.e.transported(envelope.getString("id"),true);
        }
    }
    private static void download(RelayClient relay,Device receiver) throws Exception {
        JSONObject page=relay.poll(receiver.e.profile(),0);
        JSONArray messages=page.getJSONArray("messages");
        for(int i=0;i<messages.length();i++) {
            JSONObject envelope=messages.getJSONObject(i); receiver.e.receive(envelope); receiver.e.receive(envelope);
            relay.acknowledge(receiver.e.profile(),envelope.getString("id"));
        }
    }
    private static void httpReject(RelayIntegrationTest.Operation op) throws Exception {
        try { op.run(); throw new AssertionError("Revoked capability accepted"); }
        catch(java.io.IOException expected) { check(expected.getMessage().contains("HTTP 401)"),"revoked relay capability rejected"); }
    }
    static void run(String base,String[] invitations) throws Exception {
        Device a1=new Device("Synthetic A1"),a2=new Device("Synthetic A2"),b1=new Device("Synthetic B1");
        try(RelayClient relay=new RelayClient(base)) {
            int i=2; for(Device d:List.of(a1,a2,b1)) relay.register(d.e.profile(),invitations[i++]);
            pair(a1,b1); a1.e.sendText(b1.e.id(),"synthetic before linking",600); upload(relay,a1); download(relay,b1); upload(relay,b1); download(relay,a1);
            check(a1.e.outbox().isEmpty(),"A1/B1 verified messaging and ACK before linking");
            a1.d.migrate(); b1.d.migrate();
            String challenge=a1.d.challenge(a2.d.publicKey(),600);
            String response=a2.d.respond(a2.d.reviewChallenge(challenge),true);
            String approval=a1.d.approve(a1.d.reviewResponse(response),true); a2.d.complete(approval);
            JSONObject grant=a2.d.relayDelegation(); relay.delegateDeviceRevocation(a2.e.profile(),grant.getString("token"));
            a2.e.sendDeviceDelegation(); upload(relay,a2); download(relay,a1); upload(relay,a1); download(relay,a2);
            check(a1.e.messages(a2.e.id()).isEmpty(),"deletion capability travels only in Signal ciphertext, not visible chat");
            approve(b1,a1,a2); pair(a2,b1); approve(a1,b1); approve(a2,b1);
            String logical=b1.e.sendIdentityText(a1.e.id(),"synthetic multidestination",600); upload(relay,b1);
            download(relay,a1); upload(relay,a1); download(relay,b1);
            check(b1.e.outbox().size()==1,"A2 disconnected while A1 acknowledges independently");
            JSONObject delayed=new JSONObject(b1.e.outbox().get(0).getJSONObject("envelope").toString());
            Engine restarted=new Engine(b1.db);
            check(delayed.toString().equals(restarted.outbox().get(0).getJSONObject("envelope").toString()),"immutable pending ciphertext across Engine recreation");
            download(relay,a2); upload(relay,a2); download(relay,b1);
            check(a2.e.messages(b1.e.id()).get(0).getString("logicalId").equals(logical),"same encrypted logical ID, independent A1/A2 envelopes");
            a2.e.sendIdentityText(b1.e.id(),"synthetic A2 reply",600); upload(relay,a2); download(relay,b1); upload(relay,b1); download(relay,a2);
            check(b1.e.messages(a2.e.id()).stream().filter(m -> !m.optBoolean("outgoing")).count()==1,"A2/B1 independent real Signal session");
            var location=b1.e.locations();
            String point=location.manual(location.review(a1.e.id(),app.umbra.location.LocationPayload.Mode.MANUAL,120,false),true,12.345678,45.678912);
            for(JSONObject queued:b1.e.outbox()) {
                String wire=queued.getJSONObject("envelope").toString();
                check(!wire.contains("LOCATION_") && !wire.contains("123456780") && !wire.contains("location"),"location and type absent from relay plaintext");
            }
            upload(relay,b1); download(relay,a1); download(relay,a2); upload(relay,a1); upload(relay,a2); download(relay,b1);
            for(Device target:List.of(a1,a2)) {
                JSONObject incoming=target.e.locations().received(b1.e.id()).get(0);
                check(incoming.getJSONObject("payload").getString("session").equals(point) && incoming.getJSONObject("lastPoint").getLong("latE7")==123456780,
                    "manual point without Android permission via verified HTTPS and independent Signal session");
            }
            check(b1.e.outbox().isEmpty(),"location per-device receipts clear delivery queue");
            String live=location.start(location.review(a1.e.id(),app.umbra.location.LocationPayload.Mode.ZONE,900,true),true);
            location.publish(live,12.345678,45.678912,5,Bytes.now(),"ANDROID_FINE"); upload(relay,b1); download(relay,a1); upload(relay,a1); download(relay,b1);
            location.stop(live); upload(relay,b1); download(relay,a1); download(relay,a2); upload(relay,a1); upload(relay,a2); download(relay,b1);
            check(a1.e.locations().received(b1.e.id()).stream().anyMatch(r -> r.optString("state").equals("STOPPED")),"encrypted live START UPDATE STOP persisted through real relay");
            var calls=b1.e.calls();
            String call=calls.invite(calls.reviewInvite(a1.e.id(),app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),true);
            upload(relay,b1); download(relay,a1); download(relay,a2);
            a1.e.calls().accept(a1.e.calls().reviewAccept(call,app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),true);
            a2.e.calls().accept(a2.e.calls().reviewAccept(call,app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),true);
            upload(relay,a1); upload(relay,a2); download(relay,b1); upload(relay,b1); download(relay,a1); download(relay,a2);
            check(calls.session(call).getString("selected").equals(a1.e.id()),"call first authenticated acceptance selected exactly one device");
            check(a1.e.calls().session(call).getString("state").equals("SELECTED") && a2.e.calls().session(call).getString("state").equals("NOT_SELECTED"),"callee needs SELECT and losing device has no media authority");
            String offer="v=0\r\ns=synthetic signaling offer; no media executed\r\nt=0 0\r\n";
            String answer="v=0\r\ns=synthetic signaling answer; no media executed\r\nt=0 0\r\n";
            calls.description(call,1,"offer",offer,Bytes.sha256(Bytes.utf8("synthetic caller certificate")));
            upload(relay,b1); download(relay,a1);
            a1.e.calls().description(call,1,"answer",answer,Bytes.sha256(Bytes.utf8("synthetic callee certificate")));
            upload(relay,a1); download(relay,b1);
            calls.ice(call,1,Bytes.sha256(Bytes.utf8(offer)),"0","synthetic opaque ICE control; no ICE agent");
            for(JSONObject q:b1.e.outbox()) if(q.has("callSession")) {
                String wire=q.getJSONObject("envelope").toString(); check(!wire.contains(call)&&!wire.contains("CALL_")&&!wire.contains("synthetic"),"call identifiers and negotiation absent from relay plaintext");
            }
            upload(relay,b1); download(relay,a1);
            check(a1.e.calls().session(call).getString("state").equals("NEGOTIATING"),"authenticated signaling negotiation, not ACTIVE media");
            calls.end(call); upload(relay,b1); download(relay,a1); upload(relay,a1); download(relay,b1);
            check(a1.e.calls().session(call).getString("state").equals("ENDED"),"encrypted END persisted through HTTPS");
            String old=a1.d.roster(a1.e.id()); b1.e.sendIdentityText(a1.e.id(),"synthetic in transit",600); upload(relay,b1);
            String revoked=a1.d.revoke(a2.e.id());
            a1.e.sendDeviceRoster(b1.e.id()); upload(relay,a1); download(relay,b1); a2.d.apply(revoked);
            List<JSONObject> revocations=a1.d.pendingRelayRevocations(); check(revocations.size()==1,"durable delegated revocation queued");
            JSONObject r=revocations.get(0); relay.revokeDevice(r.getString("box"),r.getString("token"));
            relay.revokeDevice(r.getString("box"),r.getString("token")); a1.d.relayRevoked(r.getString("device"),r.getString("box"));
            httpReject(() -> relay.poll(a2.e.profile(),0));
            httpReject(() -> relay.send(b1.e.contact(a2.e.id()).getJSONObject("card"),delayed));
            try { b1.d.apply(old); throw new AssertionError("Old roster accepted"); } catch(SecurityException expected) { check(true,"stale membership cannot reactivate A2"); }
            try { a1.d.approve(a1.d.reviewResponse(response),true); throw new AssertionError("Replay accepted"); } catch(SecurityException expected) { check(true,"consumed ceremony cannot reactivate A2"); }
            b1.e.sendIdentityText(a1.e.id(),"synthetic after revocation",600);
            for(JSONObject row:b1.e.outbox()) check(!row.getString("peer").equals(a2.e.id()),"no new or queued delivery to revoked A2");
            upload(relay,b1); download(relay,a1); upload(relay,a1); download(relay,b1);
            check(a1.d.pendingRelayRevocations().isEmpty(),"relay revocation confirmation committed");
        }
    }
}
