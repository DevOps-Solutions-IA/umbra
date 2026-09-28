package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.devices.DeviceService;
import app.umbra.transport.RelayClient;
import app.umbra.admission.AdmissionService;
import java.nio.file.*;
import org.json.JSONObject;

/** Separate A1/A2/B1 acceptance with real Signal and HTTPS; synthetic JVM records. */
final class AdmissionRelayIntegration {
    private static void check(boolean value,String label) {
        if(!value) throw new AssertionError(label); System.out.println("PASS admission HTTPS: "+label);
    }
    static void run(String base,String[] invitations,Path exchange)throws Exception {
        MemoryRecords[] records={new MemoryRecords(),new MemoryRecords(),new MemoryRecords()};
        Engine[] e={new Engine(records[0]),new Engine(records[1]),new Engine(records[2])};
        String realm=Files.readString(exchange.resolve("admission-realm"));
        for(int i=0;i<3;i++) { e[i].initialize("Synthetic admission "+i); e[i].admission().installRealmConfig(realm,true); }
        for(int i:new int[]{0,2}) AdmissionLab.provision(e[i],exchange,"admission-acceptance-"+i,realm);
        DeviceService a1=new DeviceService(records[0]),a2=new DeviceService(records[1]); a1.migrate();
        String challenge=a1.challenge(a2.publicKey(),600);
        a2.complete(a1.approve(a1.reviewResponse(a2.respond(a2.reviewChallenge(challenge),true)),true));
        check(e[1].admission().getAdmissionState()==AdmissionService.State.NOT_ADMITTED,"A2 linked without inheriting admission");
        try(RelayClient notAdmitted=new RelayClient(base,()->true,e[1].admission())) {
            notAdmitted.register(e[1].profile(),invitations[6]); throw new AssertionError("Unadmitted relay accepted");
        } catch(SecurityException expected) { check(true,"A2 private API denied before independent approval"); }
        AdmissionLab.provision(e[1],exchange,"admission-acceptance-1",realm);
        // Public authority evidence is explicitly delivered, separately from roster linking.
        e[0].admission().installPeerCredential(e[1].admission().requireAdmission().wire(),e[1].id());
        e[1].importCard(e[2].createCard());e[2].importCard(e[1].createCard());
        String safety=Bytes.safetyCode(e[1].id(),e[2].id()); e[1].verify(e[2].id(),safety);e[2].verify(e[1].id(),safety);
        for(Engine engine:e) { engine.connectivity().vaultUnlocked(); engine.connectivity().connect(base,true); }
        try(RelayClient c1=new RelayClient(base,()->true,e[0].admission());
            RelayClient c2=new RelayClient(base,()->true,e[1].admission());
            RelayClient cb=new RelayClient(base,()->true,e[2].admission())) {
            c1.register(e[0].profile(),invitations[5]);c2.register(e[1].profile(),invitations[6]);cb.register(e[2].profile(),invitations[7]);
            Path temporary=exchange.resolve("membership-revoke.tmp"), request=exchange.resolve("membership-revoke.json");
            Files.writeString(temporary,new JSONObject().put("credential",e[0].admission().requireAdmission().wire()).toString());
            Files.move(temporary,request,StandardCopyOption.ATOMIC_MOVE);
            Path result=exchange.resolve("membership-revoked.json");long deadline=System.nanoTime()+15_000_000_000L;
            while(!Files.exists(result)) { if(System.nanoTime()>=deadline)throw new AssertionError("Administrative revocation deadline");Thread.sleep(50); }
            String revoked=new JSONObject(Files.readString(result)).getString("revocation");
            // Server has already applied it; local old credential still present must not authorize HTTP.
            try { c1.poll(e[0].profile(),0);throw new AssertionError("Revoked remote credential accepted"); }
            catch(java.io.IOException expected) { check(expected.getMessage().contains("HTTP 403)"),"relay denies revoked A1 before local synchronization"); }
            for(Engine device:e)device.admission().applyRevocation(revoked);
            check(e[0].admission().getAdmissionState()==AdmissionService.State.REVOKED,"A1 revocation applied locally");
            for(int i:new int[]{1,2})check(e[i].admission().getAdmissionState()==AdmissionService.State.ADMITTED,"other device remains admitted "+i);
            c2.poll(e[1].profile(),0);cb.poll(e[2].profile(),0);
            e[1].sendText(e[2].id(),"synthetic after A1 admission revocation",600);
            JSONObject envelope=e[1].outbox().get(0).getJSONObject("envelope");
            c2.sendAuthorized(e[1],e[1].contact(e[2].id()).getJSONObject("card"),envelope);
            JSONObject received=cb.poll(e[2].profile(),0).getJSONArray("messages").getJSONObject(0);
            e[2].receive(received);cb.acknowledge(e[2].profile(),received.getString("id"));
            check(e[2].messages(e[1].id()).get(0).getString("text").equals("synthetic after A1 admission revocation"),"A2/B1 real Signal message after A1 revocation");
            check(new Engine(records[0]).admission().getAdmissionState()==AdmissionService.State.REVOKED,"Engine recreation does not undo revocation (not Android process death)");
        }
    }
}
