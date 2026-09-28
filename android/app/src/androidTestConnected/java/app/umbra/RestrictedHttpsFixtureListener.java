package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.content.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.devices.DeviceService;
import app.umbra.lab.SqliteDeviceRecords;
import app.umbra.transport.RelayClient;
import java.nio.file.Files;
import javax.net.ssl.*;
import org.json.JSONObject;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** TEST APK only: two independent Engines/Signal stores in one AVD; real isolated HTTPS. */
public final class RestrictedHttpsFixtureListener extends RunListener {
    private static void send(RelayClient relay,Engine engine)throws Exception {
        for(var row:engine.outbox()) {
            var envelope=row.getJSONObject("envelope");
            relay.sendAuthorized(engine,engine.contact(row.getString("peer")).getJSONObject("card"),envelope);
            relay.sendAuthorized(engine,engine.contact(row.getString("peer")).getJSONObject("card"),envelope);
            engine.transported(envelope.getString("id"),true);
        }
    }
    private static void fetch(RelayClient relay,Engine engine)throws Exception {
        var rows=relay.poll(engine.profile(),0).getJSONArray("messages");
        for(int i=0;i<rows.length();i++) {
            var envelope=rows.getJSONObject(i);engine.receive(envelope);engine.receive(envelope);
            relay.acknowledge(engine.profile(),envelope.getString("id"));
        }
    }
    private static void consume(Engine receiver,String peer,String id)throws Exception {
        if(receiver.restricted().received(peer).size()!=1 || !receiver.messages(peer).isEmpty())throw new AssertionError("Note visibility/duplicate violation");
        var session=receiver.restricted().open(receiver.restricted().reviewOpen(id),true);
        try {
            var observed=SyntheticRestrictedAudio.observe(session);
            if(observed.samples()<16000 || observed.samples()>=24000 || observed.rms()<1000 || observed.rms()>10000 ||
                observed.targetEnergy()<=100*observed.otherEnergy() || observed.tailFraction()<=0.6)throw new AssertionError("HTTPS note native decode mismatch");
        }finally{session.close();session.closure().toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS);}
        try {receiver.restricted().open(receiver.restricted().reviewOpen(id),true);throw new AssertionError("Consumed HTTPS note reopened");}
        catch(ContentException denied){if(denied.code()!=ContentException.Code.CONSUMED)throw denied;}
    }
    private static void consumeDocument(Engine receiver,String peer,String id,int color)throws Exception {
        if(receiver.restricted().received(peer).size()!=2 || !receiver.messages(peer).isEmpty())throw new AssertionError("Document visibility/duplicate violation");
        var session=receiver.restricted().open(receiver.restricted().reviewOpen(id),true);
        SyntheticDocuments.observe(session,color);
        try{receiver.restricted().open(receiver.restricted().reviewOpen(id),true);throw new AssertionError("Consumed HTTPS document reopened");}
        catch(ContentException denied){if(denied.code()!=ContentException.Code.CONSUMED)throw denied;}
    }
    @Override public void testRunStarted(Description description)throws Exception {
        var files=InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir().toPath();
        var path=files.resolve("synthetic-restricted-https.json");
        var config=new JSONObject(Bytes.text(Files.readAllBytes(path)));Files.delete(path);
        var cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(Bytes.utf8(config.getString("certificate"))));
        var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("synthetic-restricted",cert);
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);
        var original=HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory()); // Test process only; hostname verification unchanged.
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            var a=new Engine(ar);var b=new Engine(br);a.initialize("Synthetic note A");b.initialize("Synthetic note B");
            AdmissionLab.provision(a,files,"synthetic-note-a",config.getString("realm"));
            AdmissionLab.provision(b,files,"synthetic-note-b",config.getString("realm"));
            String base=config.getString("base");
            a.connectivity().vaultUnlocked();a.connectivity().connect(base,true);
            b.connectivity().vaultUnlocked();b.connectivity().connect(base,true);
            try(var ra=new RelayClient(base,()->true,a.admission());var rb=new RelayClient(base,()->true,b.admission())) {
                ra.register(a.profile(),config.getJSONArray("invitations").getString(0));a.updateRelay(base,true);
                rb.register(b.profile(),config.getJSONArray("invitations").getString(1));b.updateRelay(base,true);
                a.importCard(b.createCard());b.importCard(a.createCard());
                a.verify(b.id(),Bytes.safetyCode(a.id(),b.id()));b.verify(a.id(),Bytes.safetyCode(a.id(),b.id()));
                var ad=new DeviceService(ar);var bd=new DeviceService(br);ad.migrate();bd.migrate();
                String al=ad.roster(a.id()),bl=bd.roster(b.id());ad.apply(bl);bd.apply(al);
                var ac=ad.reviewRoster(bl);ad.approveRoster(ac,ac.fingerprint(),true);
                var bc=bd.reviewRoster(al);bd.approveRoster(bc,bc.fingerprint(),true);
                var aConsent=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
                String ai=a.restricted().send(aConsent,SyntheticRestrictedAudio.sanitizedTone(a,aConsent),true);
                var bConsent=b.restricted().reviewSend(a.id(),RestrictedPayload.Mode.ONCE,600,30);
                String bi=b.restricted().send(bConsent,SyntheticRestrictedAudio.sanitizedTone(b,bConsent),true);
                send(ra,a);fetch(rb,b);send(rb,b);fetch(ra,a);send(ra,a);fetch(rb,b);
                consume(b,a.id(),ai);consume(a,b.id(),bi);
                var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
                var ap=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
                var bp=b.restricted().reviewSend(a.id(),RestrictedPayload.Mode.ONCE,600,30);
                String aDocument=a.restricted().send(ap,SyntheticDocuments.prepare(context,a,ap,android.graphics.Color.RED),true);
                String bDocument=b.restricted().send(bp,SyntheticDocuments.prepare(context,b,bp,android.graphics.Color.BLUE),true);
                send(ra,a);fetch(rb,b);send(rb,b);fetch(ra,a);send(ra,a);fetch(rb,b);
                consumeDocument(b,a.id(),aDocument,android.graphics.Color.RED);
                consumeDocument(a,b.id(),bDocument,android.graphics.Color.BLUE);
                a.connectivity().disconnect();
                try {ra.poll(a.profile(),0);throw new AssertionError("Disconnected note transport reopened");}
                catch(java.io.IOException|SecurityException denied){ /* Explicit gate rejection, never a success substitute. */ }
                var receipt=new android.os.Bundle();receipt.putString("restrictedHttps","PASS real HTTPS admission Signal AAC and isolated PDF both directions and consumption");
                InstrumentationRegistry.getInstrumentation().sendStatus(0,receipt);
            }
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
}
