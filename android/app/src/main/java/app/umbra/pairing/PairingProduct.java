package app.umbra.pairing;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.admission.AdmissionException;
import app.umbra.transport.RelayClient;
import java.util.Arrays;
import org.json.JSONObject;

/** Single product coordinator for file, QR and opaque online courier. All methods run on a worker. */
public final class PairingProduct {
    public static final long PRODUCT_TTL=600;
    private final Records db;private final Engine engine;private final PairingService pairing;
    public PairingProduct(Records records) {db=records;engine=new Engine(db);pairing=new PairingService(db);}
    /** Explicit export material; deliberately absent from snapshots and diagnostic strings. */
    public static final class Delivery {
        private final String value;private Delivery(String v){value=v;}
        public String payload(){return value;}
        @Override public String toString(){return "PairingDelivery[redacted]";}
    }
    public record Step(PairingSnapshot snapshot,Delivery delivery) {}
    public static final class HumanCode implements AutoCloseable {
        private final String id;private final char[] code;
        private HumanCode(String id,char[] code){this.id=id;this.code=code;}
        public String id(){return id;}
        public synchronized char[] display(){return code.clone();}
        public synchronized void close(){Arrays.fill(code,'\0');}
        @Override public String toString(){return "PairingHumanCode[redacted]";}
    }
    private JSONObject row(String bucket,String id)throws Exception{return engine.get(bucket,id);}
    private void put(String bucket,String id,JSONObject value){
        if(bucket.equals("pairing-product") && !value.has("expires")) {
            try {JSONObject source=row("pairing-issued",id);if(source==null)source=row("pairing-pending",id);
                if(source==null)throw failure(PairingException.Code.STATE_MISMATCH);value.put("expires",source.getLong("expires"));
            } catch(RuntimeException e){throw e;}catch(Exception e){throw failure(PairingException.Code.STATE_MISMATCH);}
        } db.put(bucket,id,Bytes.utf8(value.toString()));
    }
    private void pruneProducts()throws Exception {
        for(String bucket:new String[]{"pairing-product","pairing-code-claims"})
            for(String id:db.keys(bucket))if(row(bucket,id).getLong("expires")<=Bytes.now())db.remove(bucket,id);
    }
    private void requireCapacity(String bucket,String retryId)throws Exception {
        if(retryId!=null && row(bucket,retryId)!=null)return;
        int live=0;for(String id:db.keys(bucket))if(row(bucket,id).getLong("expires")>Bytes.now())live++;
        if(live>=32)throw failure(PairingException.Code.CAPACITY_REACHED);
    }
    public java.util.List<PairingSnapshot> pendingPairings()throws Exception {
        return local(()->{java.util.List<PairingSnapshot> out=new java.util.ArrayList<>();
            for(String bucket:new String[]{"pairing-issued","pairing-pending"})for(String id:db.keys(bucket))out.add(status(id));
            return java.util.List.copyOf(out);});
    }
    private Runnable lease(){try{return db.authorization();}catch(SecurityException e){throw failure(PairingException.Code.VAULT_LOCKED);}}
    private void check(Runnable lease){try{lease.run();}catch(SecurityException e){throw failure(PairingException.Code.VAULT_LOCKED);}}
    private <T>T local(Records.Work<T> work) throws Exception {
        Runnable lease=lease();try{return db.transaction(()->{check(lease);T value=work.run();check(lease);return value;});}
        catch(PairingException e){throw e;}catch(AdmissionException e){throw failure(e.code()==AdmissionException.Code.AUTHORITY_MISMATCH?PairingException.Code.AUTHORITY_MISMATCH:PairingException.Code.ADMISSION_INVALID);}
        catch(SecurityException e){check(lease);throw failure(PairingException.Code.STATE_MISMATCH);}
        catch(IllegalArgumentException | org.json.JSONException e){throw failure(PairingException.Code.INVALID_FORMAT);}
        catch(Exception e){throw failure(PairingException.Code.UNAVAILABLE);}
    }
    private static PairingException failure(PairingException.Code c){return new PairingException(c);}
    public Step createPairing() throws Exception {return createPairing(lease());}
    private Step createPairing(Runnable captured) throws Exception {
        return local(()->{check(captured);pruneProducts();requireCapacity("pairing-issued",null);String invite=pairing.createInvitation(PRODUCT_TTL);String id=PairingService.invitationId(invite);return new Step(status(id),new Delivery(invite));});
    }
    /** Explicit file continuation after fresh authorization; never restores the previous export lease. */
    public Step resumeFile(String id)throws Exception {
        return local(()->{PairingSnapshot snapshot=status(PairingService.token(id));
            if(snapshot.failure()!=null)throw failure(snapshot.failure());
            if(snapshot.phase()==PairingSnapshot.Phase.COMPLETE)return new Step(snapshot,null);
            JSONObject record=row(snapshot.role()==PairingSnapshot.Role.INVITER?"pairing-issued":"pairing-pending",id);
            String field=snapshot.role()==PairingSnapshot.Role.JOINER?"request":
                snapshot.phase()==PairingSnapshot.Phase.ACK_CREATED?"ack":"invite";
            return new Step(snapshot,new Delivery(record.getString(field)));});
    }
    /** File import dispatches the whole signed protocol; presentation never assembles cryptographic steps. */
    public Step importFile(String payload) throws Exception {
        if(payload==null)throw failure(PairingException.Code.INVALID_FORMAT);
        if(payload.length()>24000)throw failure(PairingException.Code.PAYLOAD_TOO_LARGE);
        return local(()->{
            String result,id;
            if(payload.startsWith("umbra:invite:1:")) {
                id=PairingService.invitationId(payload);rejectCancelled(id);requireCapacity("pairing-pending",id);result=pairing.request(payload);
            } else if(payload.startsWith("umbra:request:1:")) {
                result=pairing.accept(payload);id=ackId(result);
            } else if(payload.startsWith("umbra:ack:1:")) {
                id=ackId(payload);rejectCancelled(id);pairing.complete(payload);return new Step(status(id),null);
            } else throw failure(PairingException.Code.INVALID_FORMAT);
            return new Step(status(id),new Delivery(result));
        });
    }
    private static String ackId(String ack) {
        // Identifier only; complete()/accept() are the mandatory signature validators before commit.
        try {
            String body=Bytes.text(java.util.Base64.getUrlDecoder().decode(ack.substring("umbra:ack:1:".length(),ack.indexOf('.'))));
            return PairingService.token(body.split("\n",-1)[1]);
        } catch(Exception e){throw failure(PairingException.Code.INVALID_FORMAT);}
    }
    private void rejectCancelled(String id)throws Exception {
        JSONObject p=row("pairing-product",id);if(p!=null && p.optBoolean("cancelled"))throw failure(PairingException.Code.CANCELLED);
    }
    public PairingSnapshot pairingStatus(String id)throws Exception{return local(()->status(PairingService.token(id)));}
    private PairingSnapshot status(String id)throws Exception {
        JSONObject issued=row("pairing-issued",id),pending=row("pairing-pending",id),p=row("pairing-product",id);
        if(issued==null && pending==null)throw failure(PairingException.Code.STATE_MISMATCH);
        boolean inviter=issued!=null;JSONObject r=inviter?issued:pending;
        long remaining=Math.max(0,r.getLong("expires")-Bytes.now());String peer=null;
        var phase=inviter?PairingSnapshot.Phase.INVITE_CREATED:PairingSnapshot.Phase.REQUEST_CREATED;
        var next=inviter?PairingSnapshot.NextAction.SHARE_INVITATION:PairingSnapshot.NextAction.DELIVER_REQUEST;
        PairingException.Code error=null;boolean verifiedRequired=false;
        if(r.getString("state").equals("CONSUMED")){phase=PairingSnapshot.Phase.ACK_CREATED;next=PairingSnapshot.NextAction.DELIVER_ACK;verifiedRequired=true;peer=r.optString("peer",null);}
        if(r.getString("state").equals("COMPLETE")){phase=PairingSnapshot.Phase.COMPLETE;next=PairingSnapshot.NextAction.VERIFY_IDENTITY;verifiedRequired=true;peer=r.getString("peer");}
        if(p!=null && p.optBoolean("published") && phase==PairingSnapshot.Phase.INVITE_CREATED)next=PairingSnapshot.NextAction.WAITING_FOR_PEER;
        if(p!=null && p.optBoolean("submitted") && phase==PairingSnapshot.Phase.REQUEST_CREATED)next=PairingSnapshot.NextAction.WAITING_FOR_PEER;
        if(remaining==0){phase=PairingSnapshot.Phase.EXPIRED;next=PairingSnapshot.NextAction.NONE;error=PairingException.Code.EXPIRED;}
        if(r.getString("state").equals("REVOKED")){phase=PairingSnapshot.Phase.REVOKED;next=PairingSnapshot.NextAction.NONE;error=PairingException.Code.REVOKED;}
        if(p!=null && p.optBoolean("cancelled")){phase=PairingSnapshot.Phase.CANCELLED;next=PairingSnapshot.NextAction.NONE;error=PairingException.Code.CANCELLED;}
        if(peer!=null)verifiedRequired=engine.trustState(peer)!=Engine.TrustState.VERIFIED;
        return new PairingSnapshot(id,inviter?PairingSnapshot.Role.INVITER:PairingSnapshot.Role.JOINER,phase,next,remaining,error,peer,verifiedRequired);
    }
    public void cancelPairing(String id)throws Exception {
        local(()->{PairingService.token(id);status(id);JSONObject p=row("pairing-product",id);if(p==null)p=new JSONObject();p.put("cancelled",true);put("pairing-product",id,p);
            if(row("pairing-issued",id)!=null)pairing.revoke(id);else pairing.cancelPending(id);return null;});
    }
    /** Caller explicitly elected online pairing. Client enforces network/admission lease before any I/O. */
    private Step createQrInviteInternal(RelayClient relay)throws Exception {
        Runnable captured=lease();Step step=createPairing(captured);publish(step.snapshot().id(),relay,null,captured);return local(()->{check(captured);return new Step(status(step.snapshot().id()),step.delivery());});
    }
    private HumanCode createHumanCodeInternal(RelayClient relay)throws Exception {
        Runnable captured=lease();Step step=createPairing(captured);char[] code=PairingSecrets.newCode();
        try{
            local(()->{check(captured);JSONObject p=new JSONObject().put("humanCode",new String(code));put("pairing-product",step.snapshot().id(),p);return null;});
            publish(step.snapshot().id(),relay,code,captured);return new HumanCode(step.snapshot().id(),code);
        }
        catch(Exception e){Arrays.fill(code,'\0');throw e;}
    }
    private void publish(String id,RelayClient relay,char[] code,Runnable captured)throws Exception {
        check(captured);
        JSONObject publish=local(()->{
            check(captured);rejectCancelled(id);JSONObject issued=row("pairing-issued",id);if(issued==null)throw failure(PairingException.Code.WRONG_ROLE);
            String invite=issued.getString("invite");PairingService.validateInvitation(invite);
            JSONObject p=row("pairing-product",id);if(p==null)p=new JSONObject();
            if(!p.has("publish")){
                JSONObject body=new JSONObject().put("id",id).put("owner_token",issued.getString("revoke")).put("request_token",PairingService.consumeToken(invite)).put("expires",PairingService.expires(invite));
                if(code!=null)body.put("code_locator",PairingSecrets.codeLocator(code)).put("code_read_token",PairingSecrets.codeCapability(code)).put("invite_blob",PairingSecrets.sealInvite(code,invite));
                p.put("publish",body);put("pairing-product",id,p);
            }return p.getJSONObject("publish");
        });
        check(captured);relay.publishPairingRendezvous(engine.profile().getString("box"),engine.profile().getString("read"),publish);check(captured);
        local(()->{check(captured);rejectCancelled(id);JSONObject p=row("pairing-product",id);p.put("published",true);put("pairing-product",id,p);return null;});
    }
    private Step acceptQrInternal(String invite,RelayClient relay)throws Exception {
        Runnable captured=lease();check(captured);PairingQrCodec.decode(invite);Step step=local(()->{check(captured);return importFile(invite);});submit(step.snapshot().id(),relay,captured);return local(()->{check(captured);return new Step(status(step.snapshot().id()),null);});
    }
    private Step acceptHumanCodeInternal(char[] code,RelayClient relay)throws Exception {
        Runnable captured=lease();check(captured);String locator=PairingSecrets.codeLocator(code),capability=PairingSecrets.codeCapability(code);
        String claim=local(()->{check(captured);pruneProducts();
            for(String ownId:db.keys("pairing-product")) {
                JSONObject own=row("pairing-product",ownId),published=own.optJSONObject("publish");
                if(published!=null && locator.equals(published.optString("code_locator")) && row("pairing-issued",ownId)!=null)
                    throw failure(PairingException.Code.SELF_PAIRING);
            }
            JSONObject old=row("pairing-code-claims",locator);if(old!=null)return old.getString("claim");
            if(db.keys("pairing-code-claims").size()>=32)throw failure(PairingException.Code.CAPACITY_REACHED);
            String id=Bytes.token();put("pairing-code-claims",locator,new JSONObject().put("claim",id).put("expires",Bytes.now()+PRODUCT_TTL));return id;});
        check(captured);JSONObject fetched=relay.claimPairingCode(locator,capability,claim);check(captured);
        String invite=PairingSecrets.openInvite(code,fetched.getString("invite_blob"));check(captured);
        Step step=local(()->{check(captured);return importFile(invite);});submit(step.snapshot().id(),relay,captured);return local(()->{check(captured);return new Step(status(step.snapshot().id()),null);});
    }
    private void submit(String id,RelayClient relay,Runnable captured)throws Exception {
        check(captured);
        JSONObject body=local(()->{check(captured);rejectCancelled(id);JSONObject pending=row("pairing-pending",id);if(pending==null)throw failure(PairingException.Code.WRONG_ROLE);
            String invite=pending.getString("invite");PairingService.validateInvitation(invite);JSONObject p=row("pairing-product",id);if(p==null)p=new JSONObject();
            if(!p.has("submit"))p.put("submit",new JSONObject().put("request_id",Bytes.token()).put("ack_token",Bytes.token()).put("request_blob",PairingSecrets.sealTranscript(PairingSecrets.Direction.REQUEST,invite,pending.getString("request"))));
            put("pairing-product",id,p);return p.getJSONObject("submit");});
        String capability=local(()->PairingService.consumeToken(row("pairing-pending",id).getString("invite")));
        check(captured);relay.submitPairingRequest(id,capability,body);check(captured);
        local(()->{check(captured);rejectCancelled(id);JSONObject p=row("pairing-product",id);p.put("submitted",true);put("pairing-product",id,p);return null;});
    }
    /** Explicit redisplay from the encrypted vault only; never diagnostic/snapshot data. */
    public HumanCode humanCode(String id)throws Exception {
        return local(()->{PairingSnapshot snapshot=status(PairingService.token(id));
            if(snapshot.failure()!=null)throw failure(snapshot.failure());
            JSONObject p=row("pairing-product",id);
            if(p==null || !p.has("humanCode"))throw failure(PairingException.Code.STATE_MISMATCH);
            return new HumanCode(id,p.getString("humanCode").toCharArray());});
    }
    /** Explicit retry preserves the original ciphertext and requires a new current network authorization. */
    public PairingSnapshot retryPairing(String id,RelayClient relay)throws Exception {
        return online(()->{Runnable captured=lease();PairingSnapshot snapshot=pairingStatus(id);
            if(snapshot.failure()!=null)throw failure(snapshot.failure());
            if(snapshot.role()==PairingSnapshot.Role.INVITER) {
                JSONObject p=local(()->{check(captured);return row("pairing-product",id);});
                char[] code=p!=null && p.has("humanCode")?p.getString("humanCode").toCharArray():null;
                try{publish(id,relay,code,captured);}finally{if(code!=null)Arrays.fill(code,'\0');}
            }else submit(id,relay,captured);
            check(captured);return pairingStatus(id);
        });
    }
    /** One bounded exchange step, never background polling or implicit network consent. */
    private PairingSnapshot advancePairingInternal(String id,RelayClient relay)throws Exception {
        Runnable captured=lease();check(captured);PairingSnapshot initial=pairingStatus(id);
        if(initial.failure()!=null)throw failure(initial.failure());
        JSONObject issued=local(()->row("pairing-issued",id));
        if(issued!=null) {
            String invite=issued.getString("invite"),owner=issued.getString("revoke");
            JSONObject saved=local(()->row("pairing-product",id));
            if(saved!=null && saved.has("selected")){check(captured);relay.selectPairingAck(id,owner,saved.getJSONObject("selected"));check(captured);return pairingStatus(id);}
            check(captured);var requests=relay.pairingRequests(id,owner).getJSONArray("requests");check(captured);
            if(requests.length()>8)throw failure(PairingException.Code.PAYLOAD_TOO_LARGE);
            for(int i=0;i<requests.length();i++){
                JSONObject candidate=requests.getJSONObject(i);String request;
                try{request=PairingSecrets.openTranscript(PairingSecrets.Direction.REQUEST,invite,candidate.getString("request_blob"));}
                catch(PairingException invalid){PairingService.validateInvitation(invite);continue;}
                // Admission or storage failures remain actionable, never swallowed as malformed ciphertext.
                JSONObject selection;
                try {selection=local(()->{check(captured);rejectCancelled(id);JSONObject current=row("pairing-product",id);
                    if(current!=null && current.has("selected"))return current.getJSONObject("selected");
                    String ack=pairing.accept(request);
                    JSONObject body=new JSONObject().put("request_hash",candidate.getString("request_hash")).put("ack_blob",PairingSecrets.sealTranscript(PairingSecrets.Direction.ACK,invite,ack));
                    JSONObject p=row("pairing-product",id);if(p==null)p=new JSONObject();p.put("selected",body);put("pairing-product",id,p);return body;});}
                catch(PairingException invalid){
                    switch(invalid.code()) {
                        case INVALID_FORMAT, INVALID_SIGNATURE, WRONG_INVITATION -> {continue;}
                        default -> throw invalid;
                    }
                }
                check(captured);relay.selectPairingAck(id,owner,selection);check(captured);break;
            }
        } else {
            JSONObject p=local(()->row("pairing-product",id));if(p==null || !p.has("submit"))throw failure(PairingException.Code.STATE_MISMATCH);
            JSONObject submit=p.getJSONObject("submit");check(captured);
            JSONObject received=relay.pairingAck(id,Bytes.sha256(Bytes.utf8(submit.getString("request_id"))),submit.getString("ack_token"));check(captured);
            if(received.has("ack_blob")){
                String invite=local(()->row("pairing-pending",id).getString("invite"));String ack=PairingSecrets.openTranscript(PairingSecrets.Direction.ACK,invite,received.getString("ack_blob"));
                local(()->{check(captured);return importFile(ack);});
            }
        }return pairingStatus(id);
    }
    public void revokeOnline(String id,RelayClient relay)throws Exception {
        Runnable captured=lease();check(captured);String owner=local(()->{JSONObject r=row("pairing-issued",id);if(r==null)throw failure(PairingException.Code.WRONG_ROLE);return r.getString("revoke");});
        cancelPairing(id);check(captured);relay.revokePairingRendezvous(id,owner);check(captured);
    }
    public Step createQrInvite(RelayClient relay)throws Exception {return online(()->createQrInviteInternal(relay));}

    public HumanCode createHumanCode(RelayClient relay)throws Exception {return online(()->createHumanCodeInternal(relay));}

    public Step acceptQr(String invite,RelayClient relay)throws Exception {return online(()->acceptQrInternal(invite,relay));}

    public Step acceptHumanCode(char[] code,RelayClient relay)throws Exception {return online(()->acceptHumanCodeInternal(code,relay));}

    public PairingSnapshot advancePairing(String id,RelayClient relay)throws Exception {return online(()->advancePairingInternal(id,relay));}

    private <T>T online(Records.Work<T> work)throws Exception {
        try{return work.run();}catch(PairingException e){throw e;}
        catch(AdmissionException e){throw failure(e.code()==AdmissionException.Code.AUTHORITY_MISMATCH?PairingException.Code.AUTHORITY_MISMATCH:PairingException.Code.ADMISSION_INVALID);}
        catch(Exception e){throw failure(PairingException.Code.UNAVAILABLE);}
    }

}
