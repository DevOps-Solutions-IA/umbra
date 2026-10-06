package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.connectivity.*;
import app.umbra.crypto.Engine;
import app.umbra.core.Bytes;
import app.umbra.lab.SqliteDeviceRecords;
import app.umbra.transport.RelayClient;
import android.os.SystemClock;
import org.json.JSONObject;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import java.nio.file.*;
import javax.net.ssl.*;

/** Explicit host-driven synthetic lab. Never packaged in release. No sensor acquisition. */
public final class PrivateStartupFixtureListener extends RunListener {
    private Path files;
    private void require(boolean ok,String failure) { if(!ok) throw new AssertionError(failure); }
    private void publish(Path receipt,JSONObject value) throws Exception {
        Path temporary=receipt.resolveSibling(receipt.getFileName()+".tmp");
        Files.write(temporary,Bytes.utf8(value.toString()));
        Files.move(temporary,receipt,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void checkpoint(String stage) throws Exception {
        Path receipt=files.resolve("synthetic-startup-"+stage+".json"),go=files.resolve("synthetic-startup-"+stage+"-go");
        publish(receipt,new JSONObject().put("stage",stage).put("monotonicMillis",SystemClock.elapsedRealtime()));
        long deadline=SystemClock.elapsedRealtime()+45_000;
        while(!Files.exists(go)) {if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("Startup host barrier: "+stage);Thread.sleep(50);}
        Files.delete(go);
    }
    private void denied(Engine engine,String origin) throws Exception {
        try(var ignored=new RelayClient(origin,()->true,engine.admission())) {throw new AssertionError("Disconnected client created");}
        catch(SecurityException expected) { /* Gate must reject before opening a connection or resolving DNS. */ }
        require(!engine.connectivity().isNetworkSessionAllowed(),"Network grant survived");
    }
    private void awaitDefaultNetwork(android.content.Context context,Engine engine,String origin,
                                     StartupNetworkReadiness.Stage stage) throws Exception {
        var manager=context.getSystemService(android.net.ConnectivityManager.class);
        StartupNetworkReadiness.await(stage,SystemClock::elapsedRealtime,
            ()->{require(manager!=null,"Lab network observation unavailable");return manager.getActiveNetwork()!=null;},
            ()->denied(engine,origin),Thread::sleep,receipt->{
                JSONObject value=new JSONObject().put("stage",receipt.stage().name())
                    .put("elapsedMillis",receipt.elapsedMillis())
                    .put("defaultNetworkPresent",receipt.defaultNetworkPresent())
                    .put("outcome",receipt.outcome().name());
                publish(files.resolve("synthetic-startup-readiness-"+receipt.stage().name()+".json"),value);
                android.os.Bundle status=new android.os.Bundle();
                status.putString("networkReadinessStage",receipt.stage().name());
                status.putLong("networkReadinessElapsedMillis",receipt.elapsedMillis());
                status.putBoolean("networkReadinessDefaultPresent",receipt.defaultNetworkPresent());
                status.putString("networkReadinessOutcome",receipt.outcome().name());
                InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
            });
    }
    @Override public void testRunStarted(Description description) throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();files=context.getFilesDir().toPath();
        String phase=InstrumentationRegistry.getArguments().getString("startupPhase","");
        if(phase.equals("verify")) {
            try(var records=new SqliteDeviceRecords("startup-restart",true)) {
                Engine engine=new Engine(records);
                require(engine.initialized(),"Identity missing after force-stop");
                require(engine.connectivity().getConnectivityState()==ConnectivityService.State.LOCKED_PRIVATE,"Process restored online state");
                denied(engine,"https://startup.umbra.test");checkpoint("restarted");
            }
            return;
        }
        require(phase.equals("prepare"),"Unknown startup fixture phase");
        JSONObject config=new JSONObject(Bytes.text(Files.readAllBytes(files.resolve("synthetic-startup-config.json"))));
        String base=config.getString("base"),trap="https://startup.umbra.test";
        // These Records are synthetic lab SQLite, not a production Keystore fallback.
        var records=new SqliteDeviceRecords("startup-restart",false);
        Engine engine=new Engine(records);engine.initialize("Synthetic startup sender");
        require(engine.connectivity().getConnectivityState()==ConnectivityService.State.LOCKED_PRIVATE,"Initial state");
        denied(engine,trap);checkpoint("cold");
        AdmissionLab.provision(engine,files,"synthetic-startup-admission",config.getString("realm"));
        engine.setOnline(true); // Legacy persisted preference must never restore consent.
        engine.connectivity().vaultUnlocked();denied(engine,trap);checkpoint("unlocked");
        if(!app.umbra.calls.CallPlatform.ENABLED) {
            try {engine.connectivity().connect(base,true);throw new AssertionError("Offline edition connected");}
            catch(SecurityException expected) { /* Exact flavor policy. */ }
            var nearby=engine.connectivity().startNearby(true);nearby.checkNearby();nearby.close();
            require(!engine.connectivity().isNearbySessionAllowed(),"Nearby cleanup");
            checkpoint("offline");
        } else {
            var original=HttpsURLConnection.getDefaultSSLSocketFactory();
            var cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(Bytes.utf8(config.getString("certificate"))));
            var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("synthetic-startup",cert);
            var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
            var tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            try(var receiverRecords=new SqliteDeviceRecords()) {
                Engine receiver=new Engine(receiverRecords);receiver.initialize("Synthetic startup receiver");
                AdmissionLab.provision(receiver,files,"synthetic-startup-peer",config.getString("realm"));
                receiver.connectivity().vaultUnlocked();
                awaitDefaultNetwork(context,engine,trap,StartupNetworkReadiness.Stage.INITIAL);
                AndroidConnectivity.connect(context,engine.connectivity(),base,true);
                receiver.connectivity().connect(base,true);
                try(var relay=new RelayClient(base,()->true,engine.admission())) {
                    relay.register(engine.profile(),config.getJSONArray("invitations").getString(0));
                    relay.register(receiver.profile(),config.getJSONArray("invitations").getString(1));
                    engine.importCard(receiver.createCard());receiver.importCard(engine.createCard());
                    String safety=Bytes.safetyCode(engine.id(),receiver.id());engine.verify(receiver.id(),safety);receiver.verify(engine.id(),safety);
                    engine.sendText(receiver.id(),"synthetic private startup acceptance",600);
                    JSONObject envelope=engine.outbox().get(0).getJSONObject("envelope");
                    relay.sendAuthorized(engine,engine.contact(receiver.id()).getJSONObject("card"),envelope);
                    var messages=relay.poll(receiver.profile(),0).getJSONArray("messages");require(messages.length()==1,"HTTPS delivery");
                    JSONObject received=messages.getJSONObject(0);receiver.receive(received);relay.acknowledge(receiver.profile(),received.getString("id"));
                    require(receiver.messages(engine.id()).size()==1,"Signal plaintext delivered locally");
                    require(relay.poll(receiver.profile(),0).getJSONArray("messages").length()==0,"ACK persisted");
                    var left=new app.umbra.devices.DeviceService(records);var right=new app.umbra.devices.DeviceService(receiverRecords);left.migrate();right.migrate();
                    var roster=left.reviewRoster(right.roster(receiver.id()));left.approveRoster(roster,roster.fingerprint(),true);
                    engine.calls().reviewInvite(receiver.id(),app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY);
                }
                receiver.connectivity().disconnect();receiver.connectivity().connect(trap,true);
                try(var probe=new RelayClient(trap,()->true,receiver.admission())) {
                    try {probe.publicRealm();throw new AssertionError("NXDOMAIN trap answered HTTPS");}
                    catch(java.io.IOException expected) {require(receiver.connectivity().getConnectivityState()==ConnectivityService.State.OFFLINE_ERROR,"DNS failure did not close grant");}
                }
                checkpoint("online");engine.connectivity().disconnect();denied(engine,trap);
                try {engine.calls().reviewInvite(receiver.id(),app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY);throw new AssertionError("Disconnected call consent");}
                catch(SecurityException expected) { /* No TURN/media can receive a consent. */ }
                checkpoint("disconnected");
                AndroidConnectivity.connect(context,engine.connectivity(),base,true);checkpoint("loss-ready");
                long deadline=SystemClock.elapsedRealtime()+15_000;
                while(engine.connectivity().isNetworkSessionAllowed()) {if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("Network loss not observed");Thread.sleep(50);}
                checkpoint("network-lost");denied(engine,trap);
                // svc wifi enable is asynchronous. Observe OS readiness, without
                // retrying connect or performing DNS/I/O, inside the host's 45s barrier.
                awaitDefaultNetwork(context,engine,trap,StartupNetworkReadiness.Stage.RECOVERY);
                AndroidConnectivity.connect(context,engine.connectivity(),base,true);records.gate.lock();
                require(engine.connectivity().getConnectivityState()==ConnectivityService.State.LOCKED_PRIVATE,"Vault lock left online");checkpoint("locked");
                records.gate.unlock();engine.connectivity().vaultUnlocked();AndroidConnectivity.connect(context,engine.connectivity(),base,true);
            } finally {HttpsURLConnection.setDefaultSSLSocketFactory(original);}
        }
        if("true".equals(InstrumentationRegistry.getArguments().getString("emergency","false"))) {
            var requested=engine.emergencyLock();
            long until=SystemClock.elapsedRealtime()+6000;
            while(engine.emergency().status().state()==app.umbra.core.EmergencyLock.State.CLOSING && SystemClock.elapsedRealtime()<until)Thread.sleep(10);
            var result=engine.emergency().status();
            require(result.state()==app.umbra.core.EmergencyLock.State.CLOSED,"Emergency did not confirm closure before force-stop");
            denied(engine,trap);
            try {records.gate.unlock();throw new AssertionError("Legacy unlock survived emergency");}
            catch(SecurityException expected) { /* New authentication ticket is mandatory. */ }
            publish(files.resolve("synthetic-startup-emergency-result.json"),new JSONObject()
                .put("requestedNanos",requested.requestedNanos()).put("invalidatedNanos",requested.invalidatedNanos())
                .put("confirmedNanos",result.finishedNanos()).put("state",result.state().name()));
            checkpoint("emergency-closed");
        }
        // Deliberately left open until the host proves process death. No synthetic close/reopen claim.
        checkpoint("kill-ready");Thread.sleep(45_000);throw new AssertionError("Host failed to terminate fixture");
    }
}
