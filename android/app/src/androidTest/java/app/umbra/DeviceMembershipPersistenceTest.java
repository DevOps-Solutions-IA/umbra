package app.umbra;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.devices.DeviceService;
import app.umbra.lab.SqliteDeviceRecords;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android SQLite + packaged Signal JNI. No production Keystore or physical transport claim. */
@RunWith(AndroidJUnit4.class)
public class DeviceMembershipPersistenceTest {
    private static void pair(Engine a,Engine b) throws Exception {
        a.importCard(b.createCard()); b.importCard(a.createCard());
        a.verify(b.id(),Bytes.safetyCode(a.id(),b.id())); b.verify(a.id(),Bytes.safetyCode(a.id(),b.id()));
    }
    @Test public void threeIdentitiesPersistIndependentSessionsAndRevocationAcrossReopen() throws Exception {
        try(var ar=new SqliteDeviceRecords();var nr=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),n=new Engine(nr),b=new Engine(br);
            a.initialize("Synthetic A1"); n.initialize("Synthetic A2"); b.initialize("Synthetic B1"); pair(a,b);
            DeviceService ad=new DeviceService(ar),nd=new DeviceService(nr),bd=new DeviceService(br);
            ad.migrate(); bd.migrate(); String challenge=ad.challenge(nd.publicKey(),600);
            String response=nd.respond(nd.reviewChallenge(challenge),true),approval=ad.approve(ad.reviewResponse(response),true);
            nr.reopen(); assertEquals(a.id(),new DeviceService(nr).complete(approval));
            String roster=ad.roster(a.id()); bd.apply(roster); b.importCard(n.createCard());
            var consent=bd.reviewRoster(roster); bd.approveRoster(consent,consent.fingerprint(),true);
            pair(n,b); String bRoster=bd.roster(b.id()); nd.apply(bRoster);
            var bConsent=nd.reviewRoster(bRoster); nd.approveRoster(bConsent,bConsent.fingerprint(),true);
            String logical=b.sendIdentityText(a.id(),"synthetic durable fanout",600);
            br.reopen(); b=new Engine(br);
            for(JSONObject delivery:b.outbox()) {
                JSONObject envelope=delivery.getJSONObject("envelope");
                if(delivery.getString("peer").equals(a.id())) a.receive(envelope); else n.receive(envelope);
            }
            nr.reopen(); assertEquals(logical,new Engine(nr).messages(b.id()).get(0).getString("logicalId"));
            String revoked=ad.revoke(n.id()); bd.apply(revoked); nd.apply(revoked);
            ar.reopen(); br.reopen(); nr.reopen();
            Engine restartedB=new Engine(br),restartedN=new Engine(nr);
            assertThrows(SecurityException.class,() -> restartedB.sendText(restartedN.id(),"blocked",600));
            assertThrows(SecurityException.class,() -> new DeviceService(br).apply(roster));
            assertThrows(SecurityException.class,() -> new DeviceService(ar).challenge(new DeviceService(nr).publicKey(),600));
            String revokedId=n.id();
            assertTrue(restartedB.outbox().stream().noneMatch(row -> row.optString("peer").equals(revokedId)));
        }
    }
    @Test public void diskFailureAndOldUnlockConsentCannotAuthorizeDevice() throws Exception {
        try(var ar=new SqliteDeviceRecords();var nr=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),n=new Engine(nr); a.initialize("Synthetic A1"); n.initialize("Synthetic A2");
            DeviceService ad=new DeviceService(ar),nd=new DeviceService(nr); ad.migrate();
            String response=nd.respond(nd.reviewChallenge(ad.challenge(nd.publicKey(),600)),true);
            var old=ad.reviewResponse(response); ar.gate.lock(); ar.gate.unlock();
            assertThrows(SecurityException.class,() -> ad.approve(old,true));
            String before=ad.roster(a.id()); ar.failBucket="device-issued";
            assertThrows(IllegalStateException.class,() -> ad.approve(ad.reviewResponse(response),true));
            ar.failBucket=null; ar.reopen(); assertEquals(before,ad.roster(a.id())); assertNull(a.contact(n.id()));
            String approval=ad.approve(ad.reviewResponse(response),true); nd.complete(approval);
            ad.revoke(n.id()); ar.reopen();
            assertThrows(SecurityException.class,() -> ad.approve(ad.reviewResponse(response),true));
        }
    }
}
