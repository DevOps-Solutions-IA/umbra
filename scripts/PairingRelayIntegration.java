package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.pairing.*;
import app.umbra.transport.RelayClient;
import java.nio.file.*;
import java.util.Arrays;
import org.json.*;

/** Production pairing, Signal and HTTPS. MemoryRecords is not Android vault persistence. */
final class PairingRelayIntegration {
    private static void check(boolean value,String label) {
        if(!value)throw new AssertionError(label);
        System.out.println("PASS pairing HTTPS: "+label);
    }
    private static void denyUnverified(Engine from,Engine to)throws Exception {
        try {from.sendText(to.id(),"synthetic forbidden before verification",600);}
        catch(SecurityException expected) {check(from.outbox().isEmpty(),"unverified sender cannot queue plaintext or ciphertext");return;}
        throw new AssertionError("Unverified pairing allowed message");
    }
    private static String syntheticQr(String invite) {
        var matrix=PairingQrCodec.render(invite,1024);
        byte[] pixels=new byte[matrix.getWidth()*matrix.getHeight()];
        for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)
            pixels[y*matrix.getWidth()+x]=(byte)(matrix.get(x,y)?0:255);
        String decoded=PairingQrCodec.decodeLuminance(pixels,matrix.getWidth(),matrix.getHeight());
        check(invite.equals(decoded),"synthetic QR pixels decode exact signed invite (not camera)");
        return decoded;
    }
    private static void deliver(Engine from,Engine to,RelayClient fromRelay,RelayClient toRelay)throws Exception {
        for(JSONObject queued:from.outbox()) {
            JSONObject envelope=queued.getJSONObject("envelope");
            fromRelay.sendAuthorized(from,from.contact(to.id()).getJSONObject("card"),envelope);
            fromRelay.sendAuthorized(from,from.contact(to.id()).getJSONObject("card"),envelope);
            from.transported(envelope.getString("id"),true);
        }
        JSONObject page=toRelay.poll(to.profile(),0);
        check(!page.getBoolean("more"),"bounded synthetic delivery fits one page");
        JSONArray messages=page.getJSONArray("messages");
        for(int i=0;i<messages.length();i++) {
            JSONObject envelope=messages.getJSONObject(i);
            to.receive(envelope);to.receive(envelope);
            toRelay.acknowledge(to.profile(),envelope.getString("id"));
        }
    }
    static void run(String base,String[] invitations,Path exchange)throws Exception {
        String realm=Files.readString(exchange.resolve("admission-realm"));
        for(int mode=0;mode<2;mode++) {
            String label=mode==0?"QR":"human code";
            MemoryRecords aStore=new MemoryRecords(),bStore=new MemoryRecords();
            Engine a=new Engine(aStore),b=new Engine(bStore);
            a.initialize("Synthetic pairing inviter "+mode);b.initialize("Synthetic pairing joiner "+mode);
            AdmissionLab.provision(a,exchange,"admission-pairing-"+mode+"-a",realm);
            AdmissionLab.provision(b,exchange,"admission-pairing-"+mode+"-b",realm);
            a.connectivity().vaultUnlocked();a.connectivity().connect(base,true);
            b.connectivity().vaultUnlocked();b.connectivity().connect(base,true);
            PairingProduct owner=new PairingProduct(aStore),joiner=new PairingProduct(bStore);
            try(RelayClient ca=new RelayClient(base,()->true,a.admission());
                RelayClient cb=new RelayClient(base,()->true,b.admission())) {
                ca.register(a.profile(),invitations[8+mode*2]);cb.register(b.profile(),invitations[9+mode*2]);
                String id;
                if(mode==0) {
                    PairingProduct.Step created=owner.createQrInvite(ca);id=created.snapshot().id();
                    String invite=syntheticQr(created.delivery().payload());
                    // Possession of request capability may enqueue opaque garbage, never a valid contact.
                    cb.submitPairingRequest(id,PairingService.consumeToken(invite),new JSONObject()
                            .put("request_id",Bytes.token()).put("ack_token",Bytes.token())
                            .put("request_blob",Bytes.b64(new byte[40])));
                    check(owner.advancePairing(id,ca).phase()==PairingSnapshot.Phase.INVITE_CREATED,
                            "invalid first ciphertext candidate does not consume invitation");
                    check(a.contact(b.id())==null,"invalid candidate creates no contact");
                    PairingProduct.Step accepted=joiner.acceptQr(invite,cb);
                    String request=joiner.resumeFile(id).delivery().payload();
                    check(accepted.delivery()==null && accepted.snapshot().nextAction()==PairingSnapshot.NextAction.WAITING_FOR_PEER,"QR online submission exposes waiting state without manual delivery");
                    joiner.acceptQr(invite,cb);
                    check(request.equals(joiner.resumeFile(id).delivery().payload()),"QR request retry keeps signed transcript");
                } else {
                    try(PairingProduct.HumanCode code=owner.createHumanCode(ca)) {
                        id=code.id();char[] display=code.display();
                        try {
                            try(PairingProduct.HumanCode restored=owner.humanCode(id)) {
                                char[] again=restored.display();
                                try {check(Arrays.equals(display,again),"human code redisplay preserves original code");}
                                finally {Arrays.fill(again,'\0');}
                            }
                            PairingProduct.Step accepted=joiner.acceptHumanCode(display,cb);
                            String request=joiner.resumeFile(id).delivery().payload();
                            check(accepted.delivery()==null && accepted.snapshot().nextAction()==PairingSnapshot.NextAction.WAITING_FOR_PEER,"human code submission exposes waiting state without manual delivery");
                            joiner.acceptHumanCode(display,cb);
                            check(request.equals(joiner.resumeFile(id).delivery().payload()),"human code claim and encrypted request exact retry");
                        } finally {Arrays.fill(display,'\0');}
                    }
                }
                check(owner.retryPairing(id,ca).nextAction()==PairingSnapshot.NextAction.WAITING_FOR_PEER,label+" owner publish exact retry");
                check(joiner.retryPairing(id,cb).nextAction()==PairingSnapshot.NextAction.WAITING_FOR_PEER,label+" joiner ciphertext exact retry");
                check(a.contact(b.id())==null && b.contact(a.id())==null,label+" queued request is not a committed contact");
                check(owner.advancePairing(id,ca).phase()==PairingSnapshot.Phase.ACK_CREATED,label+" inviter validates and commits signed request");
                check(owner.advancePairing(id,ca).phase()==PairingSnapshot.Phase.ACK_CREATED,label+" encrypted ack selection exact retry");
                check(joiner.advancePairing(id,cb).phase()==PairingSnapshot.Phase.COMPLETE,label+" joiner validates signed ack");
                check(joiner.advancePairing(id,cb).phase()==PairingSnapshot.Phase.COMPLETE,label+" ack delivery exact retry");
                check(a.trustState(b.id())==Engine.TrustState.UNVERIFIED && b.trustState(a.id())==Engine.TrustState.UNVERIFIED,label+" both contacts remain UNVERIFIED");
                denyUnverified(a,b);denyUnverified(b,a);
            }
            // Recreate Engine and both RelayClients while keeping only synthetic local Records.
            {
                a=new Engine(aStore);b=new Engine(bStore);
                check(a.trustState(b.id())==Engine.TrustState.UNVERIFIED && b.trustState(a.id())==Engine.TrustState.UNVERIFIED,label+" Engine recreation preserves unverified trust (MemoryRecords)");
                String safety=Bytes.safetyCode(a.id(),b.id());
                check(safety.equals(Bytes.safetyCode(b.id(),a.id())),label+" symmetric out-of-band verification fingerprint");
                a.verify(b.id(),safety);b.verify(a.id(),safety);
                a.connectivity().vaultUnlocked();a.connectivity().connect(base,true);
                b.connectivity().vaultUnlocked();b.connectivity().connect(base,true);
                try(RelayClient ca=new RelayClient(base,()->true,a.admission());
                    RelayClient cb=new RelayClient(base,()->true,b.admission())) {
                a.sendText(b.id(),"synthetic pairing message A to B",600);
                deliver(a,b,ca,cb);deliver(b,a,cb,ca);
                check(a.outbox().isEmpty(),label+" A to B authenticated receipt clears sender queue");
                b.sendText(a.id(),"synthetic pairing message B to A",600);
                deliver(b,a,cb,ca);deliver(a,b,ca,cb);
                check(b.outbox().isEmpty(),label+" B to A authenticated receipt clears sender queue");
                check(a.messages(b.id()).size()==2 && b.messages(a.id()).size()==2,label+" bidirectional Signal messages persist once despite retries");
                check(new Engine(aStore).trustState(b.id())==Engine.TrustState.VERIFIED &&
                        new Engine(bStore).trustState(a.id())==Engine.TrustState.VERIFIED,label+" verified trust survives Engine recreation (not process death)");
                if(mode==1) {
                    int pendingBefore=joiner.pendingPairings().size(),contactsBefore=b.contacts().size();
                    try(PairingProduct.HumanCode cancelled=owner.createHumanCode(ca)) {
                        char[] display=cancelled.display();
                        try {
                            owner.revokeOnline(cancelled.id(),ca);
                            check(owner.pairingStatus(cancelled.id()).phase()==PairingSnapshot.Phase.CANCELLED,
                                    "online cancellation leaves terminal owner snapshot");
                            try {
                                joiner.acceptHumanCode(display,cb);
                                throw new AssertionError("Revoked human code accepted");
                            } catch(PairingException denied) {
                                check(denied.code()==PairingException.Code.UNAVAILABLE,
                                        "revoked human code returns typed UNAVAILABLE");
                            }
                            check(joiner.pendingPairings().size()==pendingBefore && b.contacts().size()==contactsBefore &&
                                    b.trustState(a.id())==Engine.TrustState.VERIFIED,
                                    "revoked code creates no pending/contact and preserves existing trust");
                            check(cb.publicRealm().equals(realm),"relay remains healthy after revoked code rejection");
                        } finally {Arrays.fill(display,'\0');}
                    }
                }
                }
            }
        }
        System.out.println("Pairing product QR/code real HTTPS + libsignal passed; synthetic MemoryRecords and QR image, no Android Keystore or camera.");
    }
}
