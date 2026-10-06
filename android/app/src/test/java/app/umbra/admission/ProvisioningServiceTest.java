package app.umbra.admission;

import org.junit.Test;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.connectivity.ConnectivityService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** JVM synthetic storage/epochs with real Signal identity and admission APIs. Not Android
 * SQLite, encrypted Vault, Keystore, radio, network or physical authentication evidence. */
public class ProvisioningServiceTest {
    private static final String ORIGIN="https://relay.example:443";
    private static final class Memory implements Records {
        Map<String,byte[]> rows=new LinkedHashMap<>(); boolean open=true,failCommit;
        long epoch; int depth; String failPut; Runnable profileWrite;
        void lock(){open=false;epoch++;} void unlock(){open=true;epoch++;}
        void check(){if(!open)throw new SecurityException("Synthetic locked session");}
        public byte[] get(String b,String k){check();byte[] v=rows.get(b+":"+k);return v==null?null:v.clone();}
        public void put(String b,String k,byte[] v){check();String key=b+":"+k;
            if(key.equals(failPut))throw new IllegalStateException("Synthetic storage write failure");
            if(key.equals("meta:profile") && profileWrite!=null)profileWrite.run();
            rows.put(key,v.clone());}
        public void remove(String b,String k){check();rows.remove(b+":"+k);}
        public List<String> keys(String b){check();return rows.keySet().stream().filter(k->k.startsWith(b+":")).map(k->k.substring(b.length()+1)).toList();}
        public Runnable authorization(){check();long captured=epoch;return ()->{check();if(captured!=epoch)throw new SecurityException("Synthetic stale lease");};}
        public synchronized <T>T transaction(Work<T> work)throws Exception{
            check();Map<String,byte[]> before=new LinkedHashMap<>(rows);boolean outer=depth++==0;long captured=epoch;
            try{T result=work.run();check();if(captured!=epoch)throw new SecurityException("Synthetic commit epoch changed");
                if(outer && failCommit)throw new IllegalStateException("Synthetic commit failure");return result;
            }catch(Exception|Error failure){rows=before;throw failure;}finally{depth--;}
        }
    }
    private static final class Device {
        final Memory records=new Memory(); final Engine engine=new Engine(records);
        final AdmissionService admission=engine.admission(); final ProvisioningService provisioning=admission.provisioning();
        Device()throws Exception{engine.initialize("Synthetic provisioning device");}
    }
    private static RealmConfig realm()throws Exception{return new Device().admission.createAdmissionRealm(true);}
    private static void rejected(Records.Work<?> work){assertThrows(SecurityException.class,work::run);}
    private static void install(Device device,RealmConfig realm)throws Exception{
        device.provisioning.install(device.provisioning.review(ORIGIN,realm.encode()),true);
    }
    private static void changeProfile(Device device,String field,Object value)throws Exception{
        device.records.transaction(()->{JSONObject profile=device.engine.profile();profile.put(field,value);
            device.records.put("meta","profile",Bytes.utf8(profile.toString()));return null;});
    }
    @Test public void configuredDoesNotAdmitRegisterUnlockOrConnect()throws Exception{
        Device device=new Device();RealmConfig realm=realm();JSONObject before=device.engine.profile();
        assertFalse(device.provisioning.status().configured());install(device,realm);
        var status=device.provisioning.status();assertTrue(status.configured());assertEquals(ORIGIN,status.exactOrigin());assertEquals(realm,status.realm());
        assertEquals(AdmissionService.State.NOT_ADMITTED,status.admissionState());assertFalse(status.registered());
        assertFalse(device.engine.profile().getBoolean("online"));
        assertEquals(ConnectivityService.State.LOCKED_PRIVATE,device.engine.connectivity().getConnectivityState());
        assertFalse(device.engine.connectivity().isNearbySessionAllowed());
        for(java.util.Iterator<String> keys=before.keys();keys.hasNext();){
            String key=keys.next();if(!key.equals("relay"))assertEquals(before.get(key),device.engine.profile().get(key));
        }
        assertTrue(device.records.keys("trusted").isEmpty());assertNull(device.records.get("admission","pending"));
    }
    @Test public void explicitSourceConfirmationAndOwningServiceAreRequired()throws Exception{
        Device device=new Device();RealmConfig realm=realm();var review=device.provisioning.review(ORIGIN,realm.encode());
        rejected(()->{device.provisioning.install(review,false);return null;});
        rejected(()->{device.provisioning.install(null,true);return null;});
        Device another=new Device();rejected(()->{another.provisioning.install(review,true);return null;});
        ProvisioningService foreignService=new ProvisioningService(device.records,device.admission);
        rejected(()->{foreignService.install(review,true);return null;});
        assertNull(device.records.get("admission","realm"));assertEquals("",device.engine.profile().getString("relay"));
        assertSame(device.provisioning,device.admission.provisioning());
        device.admission.provisioning().install(review,true);assertTrue(device.provisioning.status().configured());
    }
    @Test public void lockAndReopenDoNotRenewReviewOrExposeMetadata()throws Exception{
        Device device=new Device();var review=device.provisioning.review(ORIGIN,realm().encode());
        device.records.lock();rejected(()->device.provisioning.status());
        rejected(()->device.provisioning.review("invalid","invalid"));
        device.records.unlock();rejected(()->{device.provisioning.install(review,true);return null;});
        rejected(review::exactOrigin);rejected(review::realm);
        assertNull(device.records.get("admission","realm"));assertEquals("",device.engine.profile().getString("relay"));
    }
    @Test public void exactTupleRepeatPreservesPendingRequestAndRegistrationFlags()throws Exception{
        Device device=new Device();RealmConfig realm=realm();install(device,realm);
        var pending=device.admission.createAdmissionRequest();changeProfile(device,"registered",true);
        byte[] profile=device.records.get("meta","profile"),seed=device.records.get("admission-secret","device");
        install(device,realm);assertArrayEquals(profile,device.records.get("meta","profile"));
        assertArrayEquals(seed,device.records.get("admission-secret","device"));assertEquals(pending,device.admission.pendingRequest());
        assertEquals(AdmissionService.State.REQUEST_PENDING,device.provisioning.status().admissionState());assertTrue(device.provisioning.status().registered());
    }
    @Test public void authorityRealmAndOriginChangesCannotReplaceBinding()throws Exception{
        Device device=new Device();RealmConfig pinned=realm(),other=realm();install(device,pinned);
        byte[] profile=device.records.get("meta","profile"),stored=device.records.get("admission","realm");
        rejected(()->device.provisioning.review(ORIGIN,other.encode()));
        RealmConfig otherId=new RealmConfig(other.realmId(),pinned.authorityPublicKey(),pinned.authorityKeyId());
        rejected(()->device.provisioning.review(ORIGIN,otherId.encode()));
        rejected(()->device.provisioning.review("https://relay.example",pinned.encode()));
        rejected(()->device.provisioning.review("https://RELAY.example:443",pinned.encode()));
        assertArrayEquals(profile,device.records.get("meta","profile"));assertArrayEquals(stored,device.records.get("admission","realm"));
    }
    @Test public void bindingIsRecheckedAfterReview()throws Exception{
        Device device=new Device();RealmConfig a=realm(),b=realm();var review=device.provisioning.review(ORIGIN,a.encode());
        install(device,b);rejected(()->{device.provisioning.install(review,true);return null;});
        assertEquals(b,device.admission.getRealmInfo());
    }
    @Test public void existingRealmCanBeCompletedOnlyWithSameAuthority()throws Exception{
        Device device=new Device();RealmConfig realm=device.admission.createAdmissionRealm(true);
        assertFalse(device.provisioning.status().configured());
        rejected(()->device.provisioning.review(ORIGIN,realm().encode()));
        install(device,realm);assertTrue(device.admission.isAdmissionAuthority());assertTrue(device.provisioning.status().configured());
    }
    @Test public void exactHttpsOriginRejectsNormalizationAndOtherFormats()throws Exception{
        Device device=new Device();RealmConfig realm=realm();
        for(String input:new String[]{"", " https://relay.example", "https://relay.example/", "https://relay.example ",
                "http://relay.example", "https://user@relay.example", "https://relay.example/path", "https://relay.example?q=1",
                "https://relay.example#fragment", "https://relay.example:0", "https://relay.example:65536", "https://"})
            rejected(()->device.provisioning.review(input,realm.encode()));
        rejected(()->device.provisioning.review(null,realm.encode()));
        rejected(()->device.provisioning.review(ORIGIN,"umbra:realm:2:invalid"));
        assertNull(device.records.get("admission","realm"));
    }
    @Test public void diskWriteAndCommitFailuresRollbackWholeConfiguration()throws Exception{
        for(boolean commit:new boolean[]{false,true}){
            Device device=new Device();RealmConfig realm=realm();var review=device.provisioning.review(ORIGIN,realm.encode());
            byte[] profile=device.records.get("meta","profile");
            if(commit)device.records.failCommit=true;else device.records.failPut="meta:profile";
            assertThrows(IllegalStateException.class,()->device.provisioning.install(review,true));
            assertArrayEquals(profile,device.records.get("meta","profile"));assertNull(device.records.get("admission","realm"));
            assertNull(device.records.get("admission-secret","device"));
            device.records.failCommit=false;device.records.failPut=null;device.provisioning.install(review,true);
            assertTrue(device.provisioning.status().configured());
        }
    }
    @Test public void lockDuringWriteRollsBackAndReviewCannotBeReused()throws Exception{
        Device device=new Device();var review=device.provisioning.review(ORIGIN,realm().encode());byte[] profile=device.records.get("meta","profile");
        device.records.profileWrite=device.records::lock;rejected(()->{device.provisioning.install(review,true);return null;});
        device.records.profileWrite=null;device.records.unlock();
        assertArrayEquals(profile,device.records.get("meta","profile"));assertNull(device.records.get("admission","realm"));
        assertNull(device.records.get("admission-secret","device"));rejected(()->{device.provisioning.install(review,true);return null;});
    }
    @Test public void nearbyConsentBlocksImportWithoutBeingStopped()throws Exception{
        Device device=new Device();RealmConfig realm=realm();var review=device.provisioning.review(ORIGIN,realm.encode());
        device.engine.connectivity().vaultUnlocked();var lease=device.engine.connectivity().startNearby(true);
        try{rejected(()->{device.provisioning.install(review,true);return null;});
            assertTrue(device.engine.connectivity().isNearbySessionAllowed());assertNull(device.records.get("admission","realm"));
        }finally{lease.close();}
        device.provisioning.install(review,true);assertEquals(ConnectivityService.State.UNLOCKED_OFFLINE,device.engine.connectivity().getConnectivityState());
    }
    @Test public void admissionAndRegistrationAreIndependentSnapshotFacts()throws Exception{
        Device device=new Device();RealmConfig realm=device.admission.createAdmissionRealm(true);install(device,realm);
        var pending=device.admission.createAdmissionRequest();var review=device.admission.reviewAdmissionRequest(pending.wire());
        device.admission.approveAndInstallOwnAdmission(review,true,3600);
        assertEquals(AdmissionService.State.ADMITTED,device.provisioning.status().admissionState());assertFalse(device.provisioning.status().registered());
        changeProfile(device,"registered",true);assertTrue(device.provisioning.status().registered());
        var credential=device.records.get("admission","credential");install(device,realm);
        assertArrayEquals(credential,device.records.get("admission","credential"));
    }
    @Test public void activeOnlineConsentBlocksImportWithoutDisconnecting()throws Exception{
        Device device=new Device();RealmConfig realm=device.admission.createAdmissionRealm(true);install(device,realm);
        var request=device.admission.createAdmissionRequest();device.admission.approveAndInstallOwnAdmission(device.admission.reviewAdmissionRequest(request.wire()),true,3600);
        var review=device.provisioning.review(ORIGIN,realm.encode());
        // Explicit connected-edition service, still the same storage/admission. No sockets.
        ConnectivityService online=device.admission.connectivity();online.vaultUnlocked();
        if(online.canConnect()){
            online.connect(ORIGIN,true);
            try{rejected(()->{device.provisioning.install(review,true);return null;});assertEquals(ConnectivityService.State.CONNECTED,online.getConnectivityState());}
            finally{online.disconnect();}
        }else{
            var failure=assertThrows(app.umbra.connectivity.ConnectivityException.class,()->online.connect(ORIGIN,true));
            assertEquals(app.umbra.connectivity.ConnectivityException.Code.EDITION,failure.code());
            device.provisioning.install(review,true);
            assertEquals(ConnectivityService.State.UNLOCKED_OFFLINE,online.getConnectivityState());
        }
    }
    @Test public void corruptMetadataIsRejectedWithoutRepair()throws Exception{
        for(String field:new String[]{"id","registered","online","relay"}){
            Device device=new Device();RealmConfig realm=realm();changeProfile(device,field,"corrupt");
            byte[] profile=device.records.get("meta","profile");
            rejected(()->device.provisioning.review(ORIGIN,realm.encode()));
            assertArrayEquals(profile,device.records.get("meta","profile"));assertNull(device.records.get("admission","realm"));
        }
        Device device=new Device();RealmConfig realm=realm();install(device,realm);
        device.records.put("admission","realm",Bytes.utf8("corrupt"));
        rejected(()->device.provisioning.status());assertEquals("corrupt",Bytes.text(device.records.get("admission","realm")));
    }
    @Test public void missingIdentityOrInvalidRegistrationCannotProvision()throws Exception{
        Device device=new Device();RealmConfig realm=realm();device.records.remove("meta","identity");
        assertThrows(IllegalStateException.class,()->device.provisioning.review(ORIGIN,realm.encode()));assertNull(device.records.get("admission","realm"));
        Device invalid=new Device();invalid.records.put("meta","registration",Bytes.utf8("0"));
        rejected(()->invalid.provisioning.review(ORIGIN,realm.encode()));assertNull(invalid.records.get("admission","realm"));
    }
    @Test public void malformedJsonRejectsWithoutRepairOrDiagnosticDisclosure()throws Exception{
        Device device=new Device();RealmConfig realm=realm();
        byte[] corrupt=Bytes.utf8("{\"read\":\"synthetic-private-value\",broken}");
        device.records.put("meta","profile",corrupt);
        ProvisioningException failure=assertThrows(ProvisioningException.class,()->device.provisioning.review(ORIGIN,realm.encode()));
        assertEquals(ProvisioningException.Code.INVALID_CONFIGURATION,failure.code());
        assertFalse(failure.toString().contains("synthetic-private-value"));assertNull(failure.getCause());
        assertArrayEquals(corrupt,device.records.get("meta","profile"));assertNull(device.records.get("admission","realm"));
        rejected(()->device.provisioning.status());
    }
    @Test public void corruptNumericMetadataRejectsEveryOperationWithoutDisclosureOrRepair()throws Exception{
        Device device=new Device();RealmConfig realm=realm();var review=device.provisioning.review(ORIGIN,realm.encode());
        byte[] profile=device.records.get("meta","profile"),corrupt=Bytes.utf8("synthetic-private-sentinel");
        device.records.put("meta","registration",corrupt);
        for(Records.Work<?> operation:new Records.Work<?>[]{device.provisioning::status,
                ()->device.provisioning.review(ORIGIN,realm.encode()),()->{device.provisioning.install(review,true);return null;}}){
            ProvisioningException failure=assertThrows(ProvisioningException.class,operation::run);
            assertEquals(ProvisioningException.Code.INVALID_CONFIGURATION,failure.code());
            assertFalse(failure.toString().contains("synthetic-private-sentinel"));assertNull(failure.getCause());
            assertArrayEquals(corrupt,device.records.get("meta","registration"));
            assertArrayEquals(profile,device.records.get("meta","profile"));assertNull(device.records.get("admission","realm"));
            assertNull(device.records.get("admission-secret","device"));
        }
    }
    @Test public void diagnosticStringsDoNotExposeOriginKeysOrProfile()throws Exception{
        Device device=new Device();RealmConfig realm=realm();install(device,realm);
        var review=device.provisioning.review(ORIGIN,realm.encode());
        for(Object value:new Object[]{device.provisioning,review,device.provisioning.status()}){
            assertFalse(value.toString().contains(ORIGIN));assertFalse(value.toString().contains(realm.authorityPublicKey()));
            assertFalse(value.toString().contains(device.engine.profile().getString("read")));
        }
    }
}
