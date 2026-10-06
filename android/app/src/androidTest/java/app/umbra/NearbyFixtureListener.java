package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import android.bluetooth.*;
import android.os.Bundle;
import android.os.SystemClock;
import app.umbra.core.Bytes;
import app.umbra.content.*;
import app.umbra.crypto.Engine;
import app.umbra.transport.BluetoothLink;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit two-device fixture: real RFCOMM + libsignal, isolated test-only SQLite storage. */
public final class NearbyFixtureListener extends RunListener {
    private Bundle arguments;
    private final Object recordsLock = new Object();
    private Engine engine;
    private boolean delayIncoming;
    private final ArrayDeque<JSONObject> incomingDuringDuplicate=new ArrayDeque<>();
    /** Test network scheduling only: apply ACKs after BOTH authenticated writes. */
    private AutoCloseable duplicateWindow() {
        synchronized(recordsLock) {
            require(!delayIncoming && incomingDuringDuplicate.isEmpty(),"Overlapping duplicate windows");
            delayIncoming=true;
        }
        return ()->{
            synchronized(recordsLock) {
                delayIncoming=false;
                try {while(!incomingDuringDuplicate.isEmpty())engine.receive(incomingDuringDuplicate.removeFirst());}
                catch(Exception failure){receiveFailure=failure;throw failure;}
                finally{incomingDuringDuplicate.clear();}
            }
        };
    }
    private BluetoothLink link;
    private volatile Throwable receiveFailure;
    private volatile boolean helloReceived, authenticated, closedOrRejected, admissionDenied;
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private void status(String key, String value) { Bundle b = new Bundle(); b.putString(key, value); InstrumentationRegistry.getInstrumentation().sendStatus(0, b); }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition, long milliseconds, String failure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (!condition.ready()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError(failure);
            Thread.sleep(100);
        }
    }
    @Override public void testRunStarted(Description description) throws Exception {
        arguments = InstrumentationRegistry.getArguments();
        File approval = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "nearby-synthetic-approval");
        app.umbra.lab.SqliteDeviceRecords fixtureRecords=null;
        try {
            require(BuildConfig.DEBUG, "Only synthetic debug APKs may run this fixture");
            require(!InstrumentationRegistry.getInstrumentation().getTargetContext().getDatabasePath("umbra.db").exists(), "Refuse existing vault data");
            Files.deleteIfExists(approval.toPath());
            String role = arguments.getString("role", "");
            require(role.equals("listener") || role.equals("dialer"), "Specify listener or dialer role");
            boolean dialer = role.equals("dialer");
            boolean negative="true".equals(arguments.getString("unadmittedDialer","false"));
            BluetoothAdapter adapter = InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
            require(adapter != null && adapter.isEnabled(), "Bluetooth adapter must be enabled");
            require(!adapter.isDiscovering(), "Leave Settings discovery before RFCOMM enrollment");
            BluetoothDevice device = adapter.getRemoteDevice(arguments.getString("address", ""));
            require(device.getBondState() == BluetoothDevice.BOND_BONDED, "Pair the synthetic devices in Android first");
            boolean emergency="true".equals(arguments.getString("emergency","false"));
            // PDF preparation requires the same cancellation coordinator as production.
            // Both paths use the existing disposable SQLite lab adapter, never a Vault fallback.
            fixtureRecords=new app.umbra.lab.SqliteDeviceRecords();
            app.umbra.data.Records records=fixtureRecords;
            engine = new Engine(records); engine.initialize("Synthetic " + role);
            if(negative && dialer) engine.admission().installRealmConfig(arguments.getString("admissionRealm",""),true);
            else AdmissionLab.provision(engine,approval.getParentFile().toPath(),"synthetic-admission",arguments.getString("admissionRealm",""));
            new app.umbra.devices.DeviceService(records).migrate();
            engine.connectivity().vaultUnlocked();
            link = new BluetoothLink(InstrumentationRegistry.getInstrumentation().getTargetContext(), new BluetoothLink.Listener() {
                public String ownId() throws Exception { synchronized (recordsLock) { return engine.id(); } }
                public JSONObject ownCard() throws Exception { synchronized (recordsLock) { return engine.createCard(); } }
                public String acceptCard(JSONObject card) throws Exception {
                    synchronized (recordsLock) {
                        try { return engine.importCard(card); }
                        catch (Exception failure) {
                            // Fixed categories only: never emit card content, capabilities or exception text.
                            NearbyFixtureListener.this.status("nearbyCardImportFailure",
                                failure instanceof SecurityException ? "SECURITY" : failure instanceof IllegalStateException ? "STATE" : "OTHER");
                            throw failure;
                        }
                    }
                }
                public byte[] prove(boolean d, String peer, byte[] a, byte[] b) throws Exception {
                    synchronized (recordsLock) {
                        try { return engine.proveNearby(d, peer, a, b); }
                        catch(SecurityException denied) {
                            if(negative && dialer && engine.admission().getAdmissionState()==app.umbra.admission.AdmissionService.State.NOT_ADMITTED) {
                                admissionDenied=true; NearbyFixtureListener.this.status("nearbyAdmissionDenied","NOT_ADMITTED");
                            }
                            throw denied;
                        }
                    }
                }
                public void verify(boolean d, String peer, byte[] a, byte[] b, byte[] proof, boolean enrolling) throws Exception {
                    synchronized (recordsLock) { engine.verifyNearby(d, peer, a, b, proof, enrolling); }
                }
                public void authorizeSend(String peer) throws Exception { synchronized (recordsLock) { engine.authorizeTransport(peer); } }
                public void authorizeEnvelope(String peer, JSONObject envelope) throws Exception { synchronized(recordsLock) { engine.authorizeEnvelope(envelope); } }
                public void receive(String peer, JSONObject envelope) throws Exception {
                    synchronized (recordsLock) {
                        try {
                            if(delayIncoming) {
                                require(incomingDuringDuplicate.size()<8,"Synthetic ingress delay capacity exceeded");
                                incomingDuringDuplicate.addLast(new JSONObject(envelope.toString()));
                            } else engine.receive(envelope);
                        }
                        catch (Exception failure) { receiveFailure = failure; throw failure; }
                    }
                }
                public void stage(BluetoothLink.Stage stage) {
                    if(stage==BluetoothLink.Stage.HELLO_RECEIVED) helloReceived=true;
                    if(stage==BluetoothLink.Stage.AUTHENTICATED) authenticated=true;
                    NearbyFixtureListener.this.status("nearbyHandshakeStage",stage.name());
                }
                public void status(String text) {
                    // Test-only, fixed vocabulary. Never echo transport text or payloads.
                    String stage=switch(text) {
                        case "Bluetooth: esperando vinculación explícita", "Bluetooth: esperando contacto verificado" -> "LISTENING";
                        case "Bluetooth: escucha finalizada" -> "LISTEN_ENDED";
                        case "Bluetooth: conexión fallida" -> "CONNECT_FAILED";
                        case "Bluetooth: enlace cerrado por tiempo límite" -> "TIMED_OUT";
                        case "Bluetooth: enlace cerrado o paquete no aceptado" -> "CLOSED_OR_REJECTED";
                        case "Clave del dispositivo comprobada · verifica el código del contacto", "Bluetooth: contacto verificado conectado" -> "CONNECTED";
                        default -> "UNCLASSIFIED";
                    };
                    if(stage.equals("CLOSED_OR_REJECTED")) closedOrRejected=true;
                    NearbyFixtureListener.this.status("nearbyTransportStage",stage);
                }
            }, engine.connectivity().startNearby(true));
            if (dialer) link.connect(device, true); else link.listen(true);
            status("nearbyStage", "listening-or-connecting");
            if(negative) {
                await(() -> helloReceived && closedOrRejected,30000,"Unadmitted RFCOMM rejection not observed after hello exchange");
                require(!authenticated && link.connectedPeer()==null,"Unadmitted peer authenticated");
                if(dialer) require(admissionDenied,"Missing admission-specific proof rejection");
                status("nearbyResult","PASS: actual RFCOMM hello exchange rejected before admission authentication");
                return;
            }
            await(() -> link.connectedPeer() != null, 30000, "RFCOMM enrollment handshake failed");
            String peer = link.connectedPeer(), code;
            synchronized (recordsLock) { code = Bytes.safetyCode(engine.id(), peer); }
            // Public synthetic fingerprint: host compares both codes before granting either approval.
            status("syntheticSafetyCode", code);
            await(() -> approval.exists() && code.equals(new String(Files.readAllBytes(approval.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim()),
                60000, "Host comparison/approval not received");
            Files.delete(approval.toPath());
            synchronized (recordsLock) {
                engine.verify(peer, code);
            }
            status("nearbyStage", "verified");
            await(() -> approval.exists(), 30000, "Host transfer barrier not released");
            Files.delete(approval.toPath());
            synchronized (recordsLock) {
                engine.sendDeviceRoster(peer);
                engine.sendText(peer, "Synthetic " + role + " text", 3600);
                engine.sendFile(peer, "synthetic.bin", Bytes.utf8("Synthetic " + role + " attachment"), 3600);
            }
            Set<String> sent = new HashSet<>(); boolean locationSent=false, imageSent=false, imageConsumed=false, noteSent=false, noteConsumed=false, documentSent=false, documentConsumed=false, videoSent=false, videoConsumed=false, drainedAnnounced=false;long emergencyActiveNanos=0;
            long deadline = SystemClock.elapsedRealtime() + 45000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (receiveFailure != null) throw new AssertionError("Incoming processing failed", receiveFailure);
                List<JSONObject> queue;
                synchronized (recordsLock) {
                    if(!locationSent && engine.get("device-roster",peer)!=null) {
                        var consent=engine.locations().review(peer,app.umbra.location.LocationPayload.Mode.MANUAL,120,false);
                        engine.locations().manual(consent,true,12.345678,45.678912); locationSent=true;
                    }
                    if(locationSent && !imageSent) {
                        android.graphics.Bitmap pixels=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);
                        pixels.eraseColor(dialer?0xff336699:0xff993366);
                        byte[] original;
                        try {var output=new java.io.ByteArrayOutputStream();require(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output),"Synthetic PNG encoding failed");original=output.toByteArray();}
                        finally{pixels.recycle();}
                        try {
                            var consent=engine.restricted().reviewSend(peer,RestrictedPayload.Mode.ONCE,600,30);
                            engine.restricted().send(consent,RestrictedImages.prepare(engine,consent,original,true),true);imageSent=true;
                        }finally{Arrays.fill(original,(byte)0);}
                    }
                    if(imageSent && !noteSent) {
                        var consent=engine.restricted().reviewSend(peer,RestrictedPayload.Mode.ONCE,600,30);
                        engine.restricted().send(consent,SyntheticRestrictedAudio.sanitizedTone(engine,consent),true);noteSent=true;
                    }
                    queue = engine.outbox();
                }
                if(noteSent && !documentSent) {
                    RestrictedContentService.Review consent;
                    synchronized(recordsLock){consent=engine.restricted().reviewSend(peer,RestrictedPayload.Mode.ONCE,600,30);}
                    // No Records/SQLite/coordination monitor across isolated parser IPC.
                    try(var prepared=SyntheticDocuments.prepare(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                            engine,consent,dialer?android.graphics.Color.RED:android.graphics.Color.BLUE)) {
                        synchronized(recordsLock){engine.restricted().send(consent,prepared,true);documentSent=true;}
                    }
                }
                if(documentSent && !videoSent) {
                    RestrictedContentService.Review consent;
                    synchronized(recordsLock){consent=engine.restricted().reviewSend(peer,RestrictedPayload.Mode.ONCE,600,30);}
                    try(var prepared=SyntheticRestrictedVideo.prepare(InstrumentationRegistry.getInstrumentation().getTargetContext(),engine,consent)) {
                        synchronized(recordsLock){engine.restricted().send(consent,prepared,true);videoSent=true;}
                    }
                }
                for (JSONObject queued : queue) {
                    JSONObject envelope = queued.getJSONObject("envelope");
                    String id = envelope.getString("id");
                    // A duplicate may regenerate an already transported receipt with the same ID.
                    // Send it again; a historical sent set is not the current outbox.
                    if (!sent.add(id) && !queued.optBoolean("receipt")) continue;
                    if (!queued.optBoolean("receipt") && !queued.has("locationSession")) {
                        // A fast ACK legitimately removes restricted ciphertext from outbox.
                        // Hold inbound application delivery (bounded, encrypted only), NOT the
                        // Records monitor or socket writer, until both copies crossed RFCOMM.
                        try(var delayed=duplicateWindow()) {
                            link.sendAsync(peer, envelope).get(15, TimeUnit.SECONDS);
                            Thread.sleep(100); // Existing bounded write retirement interval.
                            link.sendAsync(peer, new JSONObject(envelope.toString())).get(15, TimeUnit.SECONDS);
                        }
                    } else link.sendAsync(peer,envelope).get(15,TimeUnit.SECONDS);
                    synchronized (recordsLock) { engine.transported(id, false); }
                }
                boolean complete;
                synchronized (recordsLock) {
                    List<JSONObject> messages = engine.messages(peer);
                    long incoming = messages.stream().filter(m -> !m.optBoolean("outgoing")).count();
                    long delivered = messages.stream().filter(m -> m.optBoolean("outgoing") && "Entregado".equals(m.optString("status"))).count();
                    require(incoming <= 2, "Duplicate displayed more than once");
                    var restricted=engine.restricted().received(peer);
                    var formats=new HashSet<RestrictedPayload.Format>();
                    for(var object:restricted) {
                        require(Set.of(RestrictedPayload.Format.PNG,RestrictedPayload.Format.AAC_ADTS,RestrictedPayload.Format.PDF_PAGES,RestrictedPayload.Format.AVC_MP4).contains(object.format()),
                            "Unexpected restricted RFCOMM format");
                        require(formats.add(object.format()),"Restricted RFCOMM duplicate created another object");
                    }
                    for(var object:restricted) {
                    if(object.format()==RestrictedPayload.Format.PNG && !imageConsumed) {
                        var session=engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);
                        android.graphics.Bitmap output=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);
                        try(var decoder=new RestrictedImages.Decoder(session)) {
                            decoder.render(new android.graphics.Canvas(output),new android.graphics.Rect(0,0,16,16));
                            require(output.getPixel(8,8)==(dialer?0xff993366:0xff336699),"Restricted RFCOMM peer image mismatch");
                        }finally{output.recycle();}
                        session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
                        try{engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);throw new AssertionError("Consumed RFCOMM image reopened");}
                        catch(ContentException expected){require(expected.code()==ContentException.Code.CONSUMED,"Unexpected restricted rejection");}
                        imageConsumed=true;
                    }
                    if(object.format()==RestrictedPayload.Format.AAC_ADTS && !noteConsumed) {
                        var session=engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);
                        try {
                            var observed=SyntheticRestrictedAudio.observe(session);
                            require(observed.samples()>=16000 && observed.samples()<24000,"Restricted RFCOMM note duration mismatch");
                            require(observed.rms()>1000 && observed.rms()<10000 && observed.targetEnergy()>100*observed.otherEnergy()
                                && observed.tailFraction()>0.6,"Restricted RFCOMM decoded note mismatch");
                        }finally{session.close();}
                        session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
                        try{engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);throw new AssertionError("Consumed RFCOMM note reopened");}
                        catch(ContentException expected){require(expected.code()==ContentException.Code.CONSUMED,"Unexpected restricted rejection");}
                        noteConsumed=true;
                    }
                    if(object.format()==RestrictedPayload.Format.PDF_PAGES && !documentConsumed) {
                        var session=engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);
                        SyntheticDocuments.observe(session,dialer?android.graphics.Color.BLUE:android.graphics.Color.RED);
                        try{engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);throw new AssertionError("Consumed RFCOMM document reopened");}
                        catch(ContentException expected){require(expected.code()==ContentException.Code.CONSUMED,"Unexpected restricted rejection");}
                        documentConsumed=true;
                    }
                    if(object.format()==RestrictedPayload.Format.AVC_MP4 && !videoConsumed) {
                        SyntheticRestrictedVideo.observeAndClose(engine.restricted().open(engine.restricted().reviewOpen(object.id()),true));
                        try{engine.restricted().open(engine.restricted().reviewOpen(object.id()),true);throw new AssertionError("Consumed RFCOMM video reopened");}
                        catch(ContentException expected){require(expected.code()==ContentException.Code.CONSUMED,"Unexpected restricted rejection");}
                        videoConsumed=true;
                    }
                    if((object.format()==RestrictedPayload.Format.PNG && imageConsumed) || (object.format()==RestrictedPayload.Format.AAC_ADTS && noteConsumed)
                            || (object.format()==RestrictedPayload.Format.PDF_PAGES && documentConsumed) || (object.format()==RestrictedPayload.Format.AVC_MP4 && videoConsumed))
                        require(engine.restricted().status(object.id()).consumed(),"Duplicate reset restricted consumption");
                    }
                    complete = formats.size()==4 && videoSent && videoConsumed && imageSent && imageConsumed && noteSent && noteConsumed && documentSent && documentConsumed && incoming == 2 && delivered == 2 && engine.get("device-roster", peer) != null && locationSent &&
                        engine.locations().received(peer).size()==1 && engine.outbox().isEmpty();
                    if(complete) require(engine.locations().received(peer).get(0).getJSONObject("lastPoint").getLong("latE7")==123456780,"Location content mismatch");
                    if (complete) {
                        String other = dialer ? "listener" : "dialer";
                        for (JSONObject m : messages) if (!m.optBoolean("outgoing")) {
                            if ("text".equals(m.getString("kind")))
                                require(("Synthetic " + other + " text").equals(m.getString("text")), "Text mismatch");
                            else require(Arrays.equals(Bytes.utf8("Synthetic " + other + " attachment"),
                                Bytes.unb64(m.getString("data"))), "Attachment mismatch");
                        }
                    }
                }
                if (complete && !drainedAnnounced) {
                    if(emergency) {
                        require(link.connectedPeer()!=null,"Missing active authenticated RFCOMM before emergency barrier");
                        emergencyActiveNanos=System.nanoTime();
                    }
                    status("nearbyStage", "drained"); drainedAnnounced=true;
                }
                // Drained is a historical snapshot: a duplicate can regenerate an ACK
                // after it. Finish this writer's pump before allowing either socket to
                // close. All payloads and duplicate writes already completed above;
                // only redundant receipt arrivals can remain after both writers stop.
                if (complete && approval.exists() && "quiesce".equals(new String(Files.readAllBytes(approval.toPath()),java.nio.charset.StandardCharsets.UTF_8))) {
                    Files.delete(approval.toPath());
                    status("nearbyStage", "quiescent");
                    await(() -> approval.exists() && "finish".equals(new String(Files.readAllBytes(approval.toPath()),java.nio.charset.StandardCharsets.UTF_8)),
                        Math.max(0,deadline-SystemClock.elapsedRealtime()), "Host quiescent barrier not released");
                    Files.delete(approval.toPath());
                    if(receiveFailure!=null) throw new AssertionError("Incoming processing failed during quiescence",receiveFailure);
                    if(emergency) {
                        require(emergencyActiveNanos>0,"Missing positive RFCOMM evidence");
                        var requested=engine.emergencyLock();
                        await(()->engine.emergency().status().state()!=app.umbra.core.EmergencyLock.State.CLOSING,6000,"Emergency closure deadline");
                        var result=engine.emergency().status();
                        require(result.state()==app.umbra.core.EmergencyLock.State.CLOSED,"Nearby emergency closure incomplete");
                        require(link.connectedPeer()==null,"Nearby socket remained connected");
                        long observation=SystemClock.elapsedRealtime();Thread.sleep(500);
                        require(link.connectedPeer()==null && !engine.connectivity().isNearbySessionAllowed(),"Nearby resumed after emergency");
                        status("nearbyEmergency","PASS active="+emergencyActiveNanos+",request="+requested.requestedNanos()+",invalidated="+requested.invalidatedNanos()+
                            ",confirmed="+result.finishedNanos()+",observationMillis="+(SystemClock.elapsedRealtime()-observation));
                    }
                    status("nearbyResult", "PASS: RFCOMM, challenge, host verification, authenticated device roster, bidirectional text/attachment, encrypted location, restricted PNG, native AAC, isolated PDF and AVC decoded/consumed, duplicate, receipts");
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("RFCOMM delivery and receipt deadline expired");
        } catch (Exception | AssertionError failure) {
            status("nearbyResult", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            if (link != null) link.close();
            if (fixtureRecords!=null) fixtureRecords.close();
            approval.delete();
        }
    }
}
