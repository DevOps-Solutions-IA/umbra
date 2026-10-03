package app.umbra.media;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.BuildConfig;
import app.umbra.calls.CallPayload.NetworkPolicy;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.devices.DeviceService;
import app.umbra.lab.SqliteDeviceRecords;
import app.umbra.transport.RelayClient;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.json.*;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.webrtc.audio.JavaAudioDeviceModule;

/** Two AVD host-controlled synthetic owners. Real Engine, SQLite, HTTPS, Signal and native media. */
public final class VoiceEngineFixtureListener extends RunListener {
    private static final String REVISION="a".repeat(64);
    private Path files;
    private JSONObject read(String name) throws Exception {
        Path file=files.resolve(name); if(Files.size(file)>32000) throw new SecurityException("Oversized lab fixture");
        return new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
    }
    private void write(String name,JSONObject value) throws Exception {
        Path temp=files.resolve(name+".tmp"); Files.write(temp,Bytes.utf8(value.toString()));
        Files.move(temp,files.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void waitFor(String name,long deadline) throws Exception {
        while(!Files.isRegularFile(files.resolve(name))) {
            if(SystemClock.elapsedRealtime()>=deadline) throw new AssertionError("Host consent exchange timed out"); Thread.sleep(50);
        }
    }
    private long nextPoll;
    private int expiredDeliveriesRejected;
    private VideoStopDeliveryGate videoStopGate;
    /** Deliberately bypass the client gate in this test to verify backend default denial from Android. */
    private static void unadmittedRelayDenied(String base) throws Exception {
        for(String path:new String[]{"/v1/boxes","/v1/turn/credentials"}) {
            HttpsURLConnection connection=(HttpsURLConnection)new java.net.URL(base+path).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
                try(var output=connection.getOutputStream()) { output.write(Bytes.utf8("{}")); }
                if(connection.getResponseCode()!=403)throw new AssertionError("Unadmitted private relay request was not denied");
                if(connection.getErrorStream()!=null)connection.getErrorStream().close();
            } finally { connection.disconnect(); }
        }
        // TURN URL has no production issuer in this version: this tests default-deny routing,
        // not successful production TURN issuance or a replacement for the real lab provider.
    }
    private void pump(RelayClient relay,Engine engine) throws Exception { pump(relay,engine,null,null,false,Long.MAX_VALUE); }
    private void pump(RelayClient relay,Engine engine,String callId,NativeVoiceSession voice,boolean expiryExpected,long credentialExpiry) throws Exception {
        if(videoStopGate!=null && !videoStopGate.released() && Files.exists(files.resolve("synthetic-voice-video-stop-release.json"))) {
            JSONObject release=read("synthetic-voice-video-stop-release.json");
            videoStopGate.release(release.getBoolean("release"),release.getInt("generation"),release.getString("stopNonce"),stopIds(release.getJSONArray("peerStopIds")));
        }
        for(JSONObject q:engine.outbox()) {
            // Keep the same queued ciphertext untransported until both synthetic
            // owners have made their independently measured local stop request.
            if(videoStopGate!=null) {
                videoStopGate.requireAnnounced(q.optString("callType"),q.optString("callSession"),q.optInt("callGeneration"),q.getJSONObject("envelope").getString("id"));
                if(videoStopGate.defer(q.optString("callType"),q.optString("callSession"),q.optInt("callGeneration")))continue;
            }
            // Match production scheduling. A successful upload is not a request
            // to repost the same immutable envelope on every 100 ms fixture tick.
            if(q.optLong("nextRelay",0)>Bytes.now()) continue;
            var envelope=q.getJSONObject("envelope");
            try { relay.sendAuthorized(engine,engine.contact(q.getString("peer")).getJSONObject("card"),envelope); }
            catch(SecurityException rejected) {
                // Cancellation can occur after outbox enumeration and before the transport guard.
                // Assert this exact expected expiry rejection; never mark it sent, retry it, or
                // accept identity/storage/other-session errors as successful cancellation evidence.
                ExpiredDeliveryAssertion.check(rejected,expiryExpected && Bytes.now()>=credentialExpiry,voice!=null &&
                    (voice.state()==NativeVoiceSession.State.FAILED || voice.state()==NativeVoiceSession.State.ENDED),
                    callId,q.optString("callSession"));
                expiredDeliveriesRejected++;
                return; // Native closure must still pass the measured callback-quiescence checks.
            }
            engine.transported(envelope.getString("id"),true);
        }
        long now=SystemClock.elapsedRealtime();
        if(now<nextPoll) return;
        nextPoll=now+1500; // Respect the unchanged relay quota, including two endpoints behind one host IP.
        JSONArray rows=relay.poll(engine.profile(),0).getJSONArray("messages");
        for(int i=0;i<rows.length();i++) {
            JSONObject envelope=rows.getJSONObject(i); engine.receive(envelope);
            if(videoStopGate!=null)videoStopGate.received(envelope.getString("id"));
            relay.acknowledge(engine.profile(),envelope.getString("id"));
        }
    }
    private static List<String> stopIds(JSONArray values) throws Exception {
        if(values.length()<1 || values.length()>128)throw new SecurityException("Invalid synthetic stop envelope count");
        var ids=new ArrayList<String>();
        for(int i=0;i<values.length();i++)ids.add(values.getString(i));
        return ids;
    }
    private void videoStopIssued(Engine engine) throws Exception {
        var stopEnvelopeIds=new ArrayList<String>();
        for(JSONObject queued:engine.outbox()) {
            if(!videoStopGate.matchesStop(queued.optString("callType"),queued.optString("callSession"),queued.optInt("callGeneration")))continue;
            stopEnvelopeIds.add(queued.getJSONObject("envelope").getString("id"));
        }
        videoStopGate.issued(stopEnvelopeIds);
        write("synthetic-voice-video-stop-issued.json",new JSONObject().put("issued",true)
            .put("generation",videoStopGate.generation()).put("stopNonce",videoStopGate.nonce())
            .put("stopEnvelopeIds",new JSONArray(videoStopGate.stopEnvelopeIds())));
    }
    private void assertBlockedPeer(Engine engine,String peer) throws Exception {
        if(!engine.contact(peer).optBoolean("blocked"))throw new AssertionError("Trust-loss action did not persist");
        try {engine.authorizeTransport(peer);throw new AssertionError("Blocked peer retained transport authorization");}
        catch(SecurityException expected) { /* Explicit rejection assertion before I/O, not an ignored pump failure. */ }
    }
    private void finishTransport(RelayClient relay,Engine engine,NativeVoiceSession voice,boolean localLockApplied,
                                 boolean localTrustRemoved,String peer,JSONObject savedProfile) throws Exception {
        if(localTrustRemoved) {
            assertBlockedPeer(engine,peer);
            if(voice.transmitVoiceAllowed() || (voice.state()!=NativeVoiceSession.State.FAILED && voice.state()!=NativeVoiceSession.State.ENDED))
                throw new AssertionError("Trust loss did not close native media");
            return; // No final send/fetch/apply for a deliberately blocked peer.
        }
        if(!localLockApplied) { pump(relay,engine); return; }
        if(engine.connectivity().getConnectivityState()!=app.umbra.connectivity.ConnectivityService.State.LOCKED_PRIVATE ||
                voice.transmitVoiceAllowed() || (voice.state()!=NativeVoiceSession.State.FAILED && voice.state()!=NativeVoiceSession.State.ENDED))
            throw new AssertionError("Lock did not revoke connectivity and native media");
        // The synthetic gate was unlocked again above to prove old grants stay dead.
        // Evaluate local records separately: the required failure must be in RelayClient.
        JSONObject profile=savedProfile;
        try { relay.poll(profile,0);throw new AssertionError("Old relay resumed after lock/unlock"); }
        catch(SecurityException expected) { /* Exact negative transport assertion; no reauthorization or I/O. */ }
    }
    // Assertions over output generated and parsed by the pinned native library, not an SDP parser.
    private static void auditNativeDescriptions(JSONObject row,String turnUrl,String relayOverride) throws Exception {
        boolean tls=turnUrl.startsWith("turns:");
        String relay=relayOverride.isEmpty()?turnUrl.substring(tls?"turns:".length():"turn:".length(),turnUrl.indexOf(tls?":5349":":3478")):relayOverride;
        var incremental=row.getJSONArray("ice");
        for(int i=0;i<incremental.length();i++) {
            String candidate=incremental.getJSONObject(i).getJSONObject("data").getString("candidate");
            if(!candidate.contains(" typ relay ") || !candidate.contains(" "+relay+" ") ||
                (candidate.contains(" raddr ")&&!candidate.contains(" raddr 0.0.0.0 ")) ||
                (candidate.contains(" rport ")&&!candidate.contains(" rport 0 ")))
                throw new AssertionError("Incremental native ICE exposed a direct or related address");
        }
        JSONObject descriptions=row.getJSONObject("descriptions");
        if(descriptions.length()!=2) throw new AssertionError("Missing both authenticated native descriptions");
        for(var keys=descriptions.keys();keys.hasNext();) {
            String key=keys.next();
            String sdp=descriptions.getJSONObject(key).getString("sdp");int candidates=0;
            for(String line:sdp.split("\\r?\\n")) {
                if(line.startsWith("c=") && !line.equals("c=IN IP4 "+relay) && !line.equals("c=IN IP4 0.0.0.0"))
                    throw new AssertionError("Native SDP exposed non-relay connection address");
                if(line.startsWith("a=candidate:")) {
                    candidates++;
                    if(!line.contains(" typ relay ") || !line.contains(" "+relay+" ") ||
                        (line.contains(" raddr ")&&!line.contains(" raddr 0.0.0.0 ")) ||
                        (line.contains(" rport ")&&!line.contains(" rport 0 ")))
                        throw new AssertionError("Native SDP candidate leaked a direct or related address");
                }
            }
            if(candidates==0 || sdp.contains("10.0.2.")) throw new AssertionError("Native SDP address audit failed");
        }
    }
    private static void pace(long[] next,int bytes,int channels,int rate) {
        long now=System.nanoTime(); if(next[0]==0 || now-next[0]>30_000_000L) next[0]=now;
        long wait=next[0]-now; if(wait>0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
        next[0]+=1_000_000_000L*bytes/(2L*channels*rate);
    }
    @Override public void testRunStarted(Description ignored) throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(!(BuildConfig.DEBUG && context.getPackageName().equals("app.umbra.privatechat.dev"))
                && !context.getPackageName().equals("app.umbra.privatechat.medialab")) throw new SecurityException("Lab only");
        files=context.getFilesDir().toPath(); JSONObject configuration=read("synthetic-voice-engine.json");
        Files.delete(files.resolve("synthetic-voice-engine.json"));
        boolean caller=configuration.getString("role").equals("A");
        boolean expectedRejection=configuration.optBoolean("expectedRejection");
        boolean withVideo=configuration.optBoolean("video");
        boolean modulation=configuration.optBoolean("modulation");
        boolean initialModulation=configuration.optBoolean("initialModulation");
        boolean oneWay=configuration.optBoolean("receiveOnlyCallee");
        var original=HttpsURLConnection.getDefaultSSLSocketFactory();
        var cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(Bytes.utf8(configuration.getString("certificate"))));
        var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trust.load(null,null); trust.setCertificateEntry("synthetic-voice",cert);
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var tls=SSLContext.getInstance("TLS"); tls.init(null,managers.getTrustManagers(),null);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory()); // TEST APK only. Hostname verification unchanged.
        NativeVoiceSession voice=null;
        try(var db=new SqliteDeviceRecords("voice-restart",false)) {
            Engine engine=new Engine(db,SystemClock::elapsedRealtime); engine.initialize("Synthetic voice "+(caller?"A":"B"));
            unadmittedRelayDenied(configuration.getString("base"));
            app.umbra.AdmissionLab.provision(engine,files,"synthetic-admission",configuration.getString("admissionRealm"));
            engine.connectivity().vaultUnlocked(); engine.connectivity().connect(configuration.getString("base"),true);
            try(var relay=new RelayClient(configuration.getString("base"),() -> true,engine.admission())) {
            relay.register(engine.profile(),configuration.getString("invitation")); engine.updateRelay(configuration.getString("base"),true);
            DeviceService devices=new DeviceService(db); devices.migrate();
            write("synthetic-voice-public.json",new JSONObject().put("identity",engine.id()).put("card",engine.createCard()).put("roster",devices.roster(engine.id())));
            waitFor("synthetic-voice-peer.json",SystemClock.elapsedRealtime()+40_000);
            JSONObject other=read("synthetic-voice-peer.json"); Files.delete(files.resolve("synthetic-voice-peer.json"));
            String peer=engine.importCard(other.getJSONObject("card"));
            engine.verify(peer,Bytes.safetyCode(engine.id(),peer)); devices.apply(other.getString("roster"));
            var roster=devices.reviewRoster(other.getString("roster")); devices.approveRoster(roster,roster.fingerprint(),true);
            write("synthetic-voice-ready.json",new JSONObject().put("ready",true));
            waitFor("synthetic-voice-start.json",SystemClock.elapsedRealtime()+20_000);
            String id=caller?engine.calls().invite(engine.calls().reviewInvite(peer,NetworkPolicy.RELAY_ONLY),true):null;
            // Voice's 70 s includes setup/mute. Video adds two authenticated negotiations:
            // 25 s each accommodates measured ~19 s under 128 kbit/80 ms/2% loss.
            // This is a TOTAL test budget, not a relaxed capture/cancellation bound.
            // Product invitation (60 s) and call lifetime (180 s) remain enforced.
            long fixtureStarted=SystemClock.elapsedRealtime();
            long deadline=fixtureStarted+(modulation?140_000:withVideo?120_000:70_000); boolean accepted=false;
            while(SystemClock.elapsedRealtime()<deadline) {
                pump(relay,engine);
                if(id==null) for(JSONObject row:engine.calls().sessions()) {
                    if(!peer.equals(row.getJSONObject("context").getString("callerDevice"))) throw new AssertionError("Unexpected peer");
                    id=row.getJSONObject("context").getString("callId");
                }
                if(id!=null) {
                    JSONObject row=engine.calls().session(id); String state=row.getString("state");
                    if(!caller && state.equals("INCOMING") && !accepted) {
                        // Explicit synthetic owner action in this invoked fixture, not production auto-accept.
                        engine.calls().accept(engine.calls().reviewAccept(id,NetworkPolicy.RELAY_ONLY),true); accepted=true;
                    }
                    if(state.equals("SELECTED") || state.equals("NEGOTIATING")) break;
                }
                Thread.sleep(100);
            }
            if(id==null) throw new AssertionError("No authenticated call invitation");
            var consent=engine.calls().reviewMedia(id,REVISION);
            var lease=engine.calls().prepareMedia(consent,true);
            write("synthetic-voice-turn-ready.json",new JSONObject().put("selectedAndConsented",true));
            // Keep delivering SELECT to the other endpoint while the host waits
            // for both Engines. No ADM/PeerConnection exists before this barrier.
            while(!Files.exists(files.resolve("synthetic-voice-turn.json"))) {
                if(SystemClock.elapsedRealtime()>=deadline)throw new AssertionError("TURN provisioning deadline");
                lease.snapshot();pump(relay,engine);Thread.sleep(100);
            }
            JSONObject credential=read("synthetic-voice-turn.json");
            Files.delete(files.resolve("synthetic-voice-turn.json"));
            lease.snapshot();
            NativeVoiceSession.initialize(context);
            AtomicInteger decoded=new AtomicInteger(),captured=new AtomicInteger(),modified=new AtomicInteger(),loud=new AtomicInteger(),playbackSamples=new AtomicInteger(),playbackRate=new AtomicInteger();
            var processingObservation=new java.util.concurrent.atomic.AtomicReference<DecodedAudioWindow>();
            var muteObservation=new java.util.concurrent.atomic.AtomicReference<DecodedAudioWindow>();
            AtomicInteger videoCaptured=new AtomicInteger();
            var lastAudioCaptureNanos=new java.util.concurrent.atomic.AtomicLong();
            var lastVideoCaptureNanos=new java.util.concurrent.atomic.AtomicLong();
            SyntheticVideoCapturer.Decoded videoDecoded=new SyntheticVideoCapturer.Decoded(caller);
            long[] sample={0}; long[] nextFrame={0}; int inputTone=caller?1000:2000,expectedTone=caller?2000:1000;
            var adm=JavaAudioDeviceModule.builder(context).setSampleRate(48000)
                .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                .setAudioBufferCallback((buffer,format,channels,rate,length,time)->{
                    pace(nextFrame,buffer.capacity(),channels,rate);
                    buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN);
                    while(buffer.remaining()>=2*channels) { short value=(short)(12000*Math.sin(2*Math.PI*inputTone*sample[0]++/rate)); for(int c=0;c<channels;c++) buffer.putShort(value); }
                    captured.incrementAndGet();long timestamp=System.nanoTime();lastAudioCaptureNanos.set(timestamp);return timestamp;
                }).setPlaybackSamplesReadyCallback(samples->{
                    playbackSamples.set(samples.getData().length/(2*samples.getChannelCount()));playbackRate.set(samples.getSampleRate());
                    byte[] data=samples.getData(); int channels=samples.getChannelCount(),count=data.length/(2*channels); double re=0,im=0,energy=0;
                    for(int i=0;i<count;i++) { int pos=i*2*channels;short value=(short)((data[pos]&255)|(data[pos+1]<<8));double phase=2*Math.PI*expectedTone*i/samples.getSampleRate();re+=value*Math.cos(phase);im+=value*Math.sin(phase);energy+=(double)value*value; }
                    if(count>0 && energy/count>100000 && 2*(re*re+im*im)/(count*energy)>0.55) decoded.incrementAndGet();
                    if(count>0 && energy/count>100000) {
                        loud.incrementAndGet();double[] bands=new double[2];
                        for(int band=0;band<2;++band) {
                            double br=0,bi=0;int frequency=expectedTone+(band==0?-100:100);
                            for(int i=0;i<count;i++) {int pos=i*2*channels;short value=(short)((data[pos]&255)|(data[pos+1]<<8));double phase=2*Math.PI*frequency*i/samples.getSampleRate();br+=value*Math.cos(phase);bi+=value*Math.sin(phase);}
                            bands[band]=2*(br*br+bi*bi)/(count*energy);
                        }
                        if(bands[0]>0.12 && bands[1]>0.12 && bands[0]+bands[1]>0.55 && 2*(re*re+im*im)/(count*energy)<0.10)modified.incrementAndGet();
                    }
                    long observedAt=SystemClock.elapsedRealtime();
                    DecodedAudioWindow processingSample=processingObservation.get();
                    if(processingSample!=null)processingSample.sample(observedAt,decoded.get(),modified.get(),loud.get(),videoDecoded.frames.get());
                    DecodedAudioWindow muteSample=muteObservation.get();
                    if(muteSample!=null)muteSample.sample(observedAt,decoded.get(),modified.get(),loud.get(),videoDecoded.frames.get());
                }).createAudioDeviceModule();
            adm.setAudioRecordEnabled(false);
            long remaining=Math.min(180000,(credential.getLong("expires")-Bytes.now())*1000);
            var turn=new TurnConfiguration(List.of(credential.getJSONArray("urls").getString(0)),REVISION,
                credential.getString("username"),credential.getString("password"),remaining,SystemClock::elapsedRealtime,credential.optString("hostname",""));
            org.webrtc.SSLCertificateVerifier verifier=null;
            if(credential.has("certificate")) {
                var ca=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(Bytes.utf8(credential.getString("certificate"))));
                var store=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());store.load(null,null);store.setCertificateEntry("synthetic-turn-root",ca);
                var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
                X509TrustManager manager=Arrays.stream(factory.getTrustManagers()).filter(m->m instanceof X509TrustManager).map(m->(X509TrustManager)m).findFirst().orElseThrow();
                verifier=encoded->{
                    try {
                        var leaf=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(encoded));
                        leaf.checkValidity();ca.checkValidity();manager.checkServerTrusted(new java.security.cert.X509Certificate[]{leaf,ca},"RSA");return true;
                    } catch(Exception rejected) {return false;}
                }; // Native SSLPostConnectionCheck independently verifies host/IP identity; SECURE remains configured.
            }
            var nativeWorker=new java.util.concurrent.atomic.AtomicReference<Thread>();
            NativeVoiceSession.DescriptionPublisher hostile=(generation,role,sdp,fingerprint)->{
                nativeWorker.set(Thread.currentThread());
                lease.description(generation,role,sdp,configuration.optBoolean("incorrectFingerprint")?Bytes.sha256(Bytes.utf8(fingerprint)):fingerprint);
            };
            voice=new NativeVoiceSession(context,lease,turn,adm,false,verifier,hostile,caller&&initialModulation);
            if(withVideo) { voice.syntheticVideo(()->new SyntheticVideoCapturer(caller,videoCaptured,lastVideoCaptureNanos));voice.setRemoteVideoSink(videoDecoded); }
            int videoStage=0,videoAudioBaseline=0,videoFrameBaseline=0,videoCaptureBaseline=0;long videoOffAt=0,videoOffRequestedNanos=0;
            boolean videoRequested=false,videoAccepted=false;
            boolean evidence=false,muting=false,mutedEvidence=false,resuming=false,resumedEvidence=false,terminationApplied=false,localLockApplied=false,localTrustRemoved=false;
            boolean initialProcessingEvidence=false,initialNatural=false;
            boolean emergencyApplied=false;JSONObject savedProfile=engine.profile();
            boolean cameraDeniedChecked=false,cameraDeniedEvidence=false;int cameraDeniedBaseline=0;
            int processingStep=0;
            long processingAt=0;String processingExpected="",processingDiagnostic="";
            int resumeBaseline=0;
            while(SystemClock.elapsedRealtime()<deadline) {
                boolean expiryExpected=evidence &&
                    Files.exists(files.resolve("synthetic-voice-loss.json")) &&
                    read("synthetic-voice-loss.json").optString("action").equals("credential-expiry");
                if(localLockApplied) {
                    var connectivity=engine.connectivity().getConnectivityState();
                    if(engine.connectivity().isNetworkSessionAllowed() ||
                            connectivity!=app.umbra.connectivity.ConnectivityService.State.LOCKED_PRIVATE &&
                            !(emergencyApplied && connectivity==app.umbra.connectivity.ConnectivityService.State.DISCONNECTING))
                        throw new AssertionError("Connectivity restored during native closure");
                } else if(localTrustRemoved)assertBlockedPeer(engine,peer);
                else pump(relay,engine,id,voice,expiryExpected,credential.getLong("expires"));
                if(voice.state()==NativeVoiceSession.State.FAILED && !evidence) {
                    if(!expectedRejection) throw new AssertionError("Native authenticated voice failed before audio: "+voice.failureStage()+"; "+voice.negotiationDiagnostic()+"; captured="+captured.get()+", decoded="+decoded.get());
                    if(captured.get()!=0 || decoded.get()!=0 || videoCaptured.get()!=0) throw new AssertionError("Rejected TURN path captured or decoded audio");
                    String terminalReason=voice.failureStage();
                    // Observe actual scheduled teardown/callbacks after the terminal transition.
                    // Cancellation must not rewrite a certificate rejection as a generic tick error.
                    Thread.sleep(350);
                    if(voice.state()!=NativeVoiceSession.State.FAILED || !terminalReason.equals(voice.failureStage()) ||
                        captured.get()!=0 || decoded.get()!=0 || videoCaptured.get()!=0)
                        throw new AssertionError("Terminal rejection changed during late-callback observation");
                    JSONObject rejected=new JSONObject().put("rejectedBeforeCapture",true);
                    if(configuration.optBoolean("incorrectFingerprint")) {
                        if(!java.util.Set.of("native-certificate-binding","native-connection-failed","native-connection-disconnected","authorization-cancelled").contains(voice.failureStage()))throw new AssertionError("Wrong-fingerprint test did not reach certificate rejection or native peer closure: "+voice.failureStage()+"; "+voice.negotiationDiagnostic());
                        rejected.put("reason",voice.failureStage());
                    }
                    write("synthetic-voice-audio.json",rejected);
                    evidence=true;waitFor("synthetic-voice-stop.json",deadline);break;
                }
                if(expectedRejection && voice.state()==NativeVoiceSession.State.ACTIVE) throw new AssertionError("Invalid TURN unexpectedly connected");
                if(configuration.optBoolean("cameraDenied") && evidence && !cameraDeniedEvidence) {
                    if(!cameraDeniedChecked) {
                        try {voice.video(true,true,false,true);throw new AssertionError("Direct API accepted denied CAMERA permission");}
                        catch(SecurityException expected) { /* No camera or video request may be created. */ }
                        if(engine.calls().session(id).has("video") || voice.state()!=NativeVoiceSession.State.ACTIVE)
                            throw new AssertionError("Camera denial altered the authorized voice session");
                        cameraDeniedChecked=true;cameraDeniedBaseline=decoded.get();
                    } else if(decoded.get()-cameraDeniedBaseline>=50) {
                        write("synthetic-voice-camera-denied.json",new JSONObject().put("deniedWithoutVideoState",true).put("decodedAudioAfterDenial",decoded.get()-cameraDeniedBaseline));
                        cameraDeniedEvidence=true;
                    }
                }
                if(evidence && !terminationApplied && Files.exists(files.resolve("synthetic-voice-loss.json"))) {
                    String action=read("synthetic-voice-loss.json").optString("action");
                    if(action.equals("trust-loss")) {engine.block(peer,true);localTrustRemoved=true;assertBlockedPeer(engine,peer);}
                    if(action.equals("device-revoked")) devices.revoke(engine.id());
                    if(action.equals("storage-failure")) { db.failBucket="calls";voice.close(); }
                    if(action.equals("emergency-lock")) {
                        localLockApplied=true;emergencyApplied=true;engine.emergencyLock();
                        try { lease.snapshot();throw new AssertionError("Old media authorization survived emergency"); }
                        catch(SecurityException expected) { /* Coordinator denied before asynchronous native closure. */ }
                    }
                    if(action.equals("lock")) {
                        localLockApplied=true;engine.calls().cancelLocal();db.gate.lock();db.gate.unlock();
                        try { lease.snapshot();throw new AssertionError("Old media authorization survived lock/unlock"); }
                        catch(SecurityException expected) { /* New unlock cannot restore old consent. */ }
                    }
                    terminationApplied=true;
                }
                if(localLockApplied && voice.state()!=NativeVoiceSession.State.FAILED && voice.state()!=NativeVoiceSession.State.ENDED) {
                    // Authorization is already dead. Do not read SQLite/SDP or run any
                    // old consent while asynchronous native resources are still closing.
                    if(emergencyApplied && engine.emergency().status().state()==app.umbra.core.EmergencyLock.State.INCOMPLETE)
                        throw new AssertionError("Emergency resources did not confirm closure");
                    Thread.sleep(10);continue;
                }
                if(withVideo && evidence && voice.state()==NativeVoiceSession.State.FAILED && !terminationApplied)
                    throw new AssertionError("Video path failed: "+voice.failureStage()+", video="+voice.videoStatus());
                if(evidence && terminationApplied && (voice.state()==NativeVoiceSession.State.FAILED || voice.state()==NativeVoiceSession.State.ENDED)) {
                    long terminalObserved=SystemClock.elapsedRealtime();
                    Thread.sleep(1000);
                    if(voice.transmitVoiceAllowed()) throw new AssertionError("Voice revived after termination");
                    // Check the real native ADM callback, not only the adapter state flag.
                    // Wait nominally one second for disposal, then observe for 500 ms.
                    // Measure both intervals and reject excessive scheduling delays. This is synthetic pipeline closure, not a
                    // hardware microphone cancellation-latency measurement.
                    long quietStart=SystemClock.elapsedRealtime();
                    int capturedAtClosure=captured.get();int videoAtClosure=videoCaptured.get();
                    Thread.sleep(500);
                    long quietAfter=quietStart-terminalObserved;
                    long observedFor=SystemClock.elapsedRealtime()-quietStart;
                    if(quietAfter<1000 || quietAfter>2000 || observedFor<500 || observedFor>1500)
                        throw new AssertionError("Native capture closure observation missed its timing bounds");
                    int lateCaptureCallbacks=captured.get()-capturedAtClosure;
                    if(lateCaptureCallbacks!=0) throw new AssertionError("Native audio capture callbacks survived cancellation");
                    if("calls".equals(db.failBucket)) {
                        if(!voice.endDeliveryFailed()) throw new AssertionError("Synthetic storage failure was not exercised");
                        db.failBucket=null;db.reopen();
                        for(JSONObject row:new Engine(db,SystemClock::elapsedRealtime).calls().sessions())
                            if(!app.umbra.calls.CallPayload.TERMINAL.contains(row.getString("state"))) throw new AssertionError("SQLite rollback revived voice");
                    }
                    JSONObject stopped=new JSONObject().put("failedClosed",true).put("nativeCaptureQuietAfterMillis",quietAfter).put("nativeCaptureObservedMillis",observedFor).put("lateCaptureCallbacks",lateCaptureCallbacks).put("expiredDeliveriesRejected",expiredDeliveriesRejected);
                    if(emergencyApplied) {
                        if(videoCaptured.get()!=videoAtClosure)throw new AssertionError("Camera callbacks survived emergency");
                        var closure=engine.emergency().status();
                        if(closure.state()!=app.umbra.core.EmergencyLock.State.CLOSED)throw new AssertionError("Emergency closure unconfirmed: "+closure.state());
                        stopped.put("emergencyState",closure.state().name()).put("requestedNanos",closure.requestedNanos())
                            .put("invalidatedNanos",closure.invalidatedNanos()).put("confirmedNanos",closure.finishedNanos())
                            .put("lateVideoCallbacks",videoCaptured.get()-videoAtClosure)
                            .put("lastAudioCaptureNanos",lastAudioCaptureNanos.get()).put("lastVideoCaptureNanos",lastVideoCaptureNanos.get());
                    }
                    write("synthetic-voice-lost.json",stopped);waitFor("synthetic-voice-stop.json",deadline);break;
                }
                if(initialModulation && !initialProcessingEvidence && voice.state()==NativeVoiceSession.State.ACTIVE && (caller?decoded.get():modified.get())>=100) {
                    if(!caller && decoded.get()!=0)throw new AssertionError("Natural voice escaped initial MODULATED selection");
                    if(caller && !voice.modulationStatus().equals("ON"))throw new AssertionError("Initial mode not effective: "+voice.modulationStatus()+", faults="+voice.processingMetric(4)+", processed="+voice.processingMetric(1)+", modulated="+voice.processingMetric(2));
                    write("synthetic-voice-initial-processing.json",new JSONObject().put("natural",decoded.get()).put("modified",modified.get()).put("effective",voice.modulationStatus()));
                    initialProcessingEvidence=true;
                }
                if(initialModulation && initialProcessingEvidence && !initialNatural && Files.exists(files.resolve("synthetic-voice-initial-natural.json"))) {
                    if(caller)voice.modulation(false,true); // Explicit synthetic owner consent from host.
                    initialNatural=true;
                }
                if(!evidence && (!initialModulation || initialNatural) && decoded.get()>=100 && captured.get()>=100 && voice.receivedAudioPackets()>=50 && voice.state()==NativeVoiceSession.State.ACTIVE) {
                    auditNativeDescriptions(engine.calls().session(id),credential.getJSONArray("urls").getString(0),credential.optString("relayAddress"));
                    write("synthetic-voice-audio.json",new JSONObject().put("sdpAddressAudit",true).put("decodedBuffers",decoded.get()).put("capturedBuffers",captured.get()).put("verifiedNativeTransport",true).put("receivedAudioPackets",voice.receivedAudioPackets()).put("codec",voice.audioCodec()).put("nativeRelayProtocol",voice.nativeRelayProtocol())); evidence=true;
                }
                if(evidence && !muting && Files.exists(files.resolve("synthetic-voice-mute.json"))) {
                    voice.mute(true); muting=true;
                    write("synthetic-voice-mute-applied.json",new JSONObject().put("applied",true).put("elapsedMillis",SystemClock.elapsedRealtime()));
                }
                if(muting && !mutedEvidence && Files.exists(files.resolve("synthetic-voice-mute-observe.json"))) {
                    // Host releases this barrier only after BOTH native mute calls returned.
                    if(muteObservation.get()==null)muteObservation.set(new DecodedAudioWindow(SystemClock.elapsedRealtime(),2000,3500,1200,2500));
                    DecodedAudioWindow.Result observation=muteObservation.get().result();
                    if(observation!=null) {
                        if(!observation.failure().isEmpty())throw new AssertionError(observation.failure());
                        int tones=observation.natural();
                        if(tones>3) throw new AssertionError("Decoded peer tone continued after both native mute confirmations: tones="+tones+", observedMillis="+observation.observedMillis());
                        write("synthetic-voice-muted.json",new JSONObject().put("quiet",true).put("observedMillis",observation.observedMillis()).put("decodedTones",tones));mutedEvidence=true;
                        muteObservation.set(null);
                    }
                }
                if(mutedEvidence && !resuming && Files.exists(files.resolve("synthetic-voice-resume.json"))) {
                    voice.mute(false);resuming=true;resumeBaseline=decoded.get();
                }
                if(resuming && !resumedEvidence && decoded.get()-resumeBaseline>=50) {
                    write("synthetic-voice-resumed.json",new JSONObject().put("decodedAfterUnmute",decoded.get()-resumeBaseline));resumedEvidence=true;
                }
                if(withVideo && resumedEvidence) {
                    if((videoStage==0 && Files.exists(files.resolve("synthetic-voice-video-start.json"))) ||
                        (videoStage==3 && Files.exists(files.resolve("synthetic-voice-video-resume.json")))) {
                        videoStage=videoStage==0?1:4;videoRequested=false;videoAccepted=false;
                        videoAudioBaseline=decoded.get();videoFrameBaseline=videoDecoded.frames.get();videoCaptureBaseline=videoCaptured.get();
                    }
                    if(videoStage==1 || videoStage==4) {
                        if(caller && !videoRequested) {voice.video(true,!oneWay,false,true);videoRequested=true;}
                        JSONObject pending=engine.calls().session(id).optJSONObject("video");
                        if(!caller && !videoAccepted && pending!=null && pending.optString("state").equals("REVIEW")) {
                            // Host-invoked synthetic owner action; production never auto-accepts this proposal.
                            voice.video(!oneWay,true,true,true);videoAccepted=true;
                        }
                        boolean framesReady=oneWay && caller?
                            videoCaptured.get()-videoCaptureBaseline>=20 && videoDecoded.frames.get()==0:
                            videoDecoded.frames.get()-videoFrameBaseline>=20 && videoDecoded.phases.get()==3;
                        if(oneWay && !caller && videoCaptured.get()!=0)throw new AssertionError("Receive-only consent captured a local frame");
                        if(framesReady && voice.videoStatus().equals("ACTIVE") && decoded.get()-videoAudioBaseline>=50) {
                            auditNativeDescriptions(engine.calls().session(id),credential.getJSONArray("urls").getString(0),credential.optString("relayAddress"));
                            if(videoStage==1)videoStopGate=new VideoStopDeliveryGate(id,engine.calls().session(id).getInt("generation"),UUID.randomUUID().toString());
                            write("synthetic-voice-video-"+(videoStage==1?"active":"resumed")+".json",new JSONObject()
                                .put("decodedRemotePatterns",videoDecoded.frames.get()-videoFrameBaseline).put("distinctPatternPhases",oneWay&&caller?0:2)
                                .put("sendPermitted",!oneWay||caller).put("receivePermitted",!oneWay||!caller)
                                .put("decodedAudioDuringVideo",decoded.get()-videoAudioBaseline).put("capturedFrames",videoCaptured.get())
                                .put("fixtureElapsedMillis",SystemClock.elapsedRealtime()-fixtureStarted)
                                .put("generation",engine.calls().session(id).getInt("generation")).put("videoCodec",new JSONObject(voice.videoStats()).getString("codec")).put("sdpAddressAudit",true));
                            videoStage=videoStage==1?2:5;
                        }
                    }
                    if(videoStage==2 && Files.exists(files.resolve("synthetic-voice-video-off.json"))) {
                        Bundle beforeStop=new Bundle();beforeStop.putString("videoBeforeStop",voice.state().name()+":"+voice.failureStage()+":"+voice.videoStatus());
                        InstrumentationRegistry.getInstrumentation().sendStatus(0,beforeStop);
                        if(configuration.optBoolean("stopVideoRace")) {
                            final NativeVoiceSession activeVoice=voice;
                            var entered=new java.util.concurrent.CountDownLatch(1);
                            var resume=new java.util.concurrent.CountDownLatch(1);
                            db.beforeTransaction=()->{
                                if(Thread.currentThread()!=nativeWorker.get() || !activeVoice.videoActivationStage().equals("video-authorization"))return;
                                db.beforeTransaction=null;entered.countDown();
                                try {if(!resume.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Video race release missing");}
                                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                            };
                            try {
                                if(!entered.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Native activation rendezvous not reached");
                                videoOffRequestedNanos=SystemClock.elapsedRealtimeNanos();voice.stopVideo();
                                // Exercise multiple authorized same-change stop controls in the
                                // native race fixture without resetting the first request clock.
                                voice.stopVideo();videoStopIssued(engine);
                            } finally {db.beforeTransaction=null;resume.countDown();}
                            Thread.sleep(350);
                            if(voice.state()!=NativeVoiceSession.State.ACTIVE)throw new AssertionError("Video-only cancellation terminated authorized audio: "+voice.failureStage());
                        } else {videoOffRequestedNanos=SystemClock.elapsedRealtimeNanos();voice.stopVideo();videoStopIssued(engine);}
                        videoOffAt=SystemClock.elapsedRealtime();videoCaptureBaseline=videoCaptured.get();videoAudioBaseline=decoded.get();videoStage=6;
                    }
                    if(videoStage==6 && videoStopGate.peerStopApplied() && SystemClock.elapsedRealtime()-videoOffAt>=2000 && decoded.get()-videoAudioBaseline>=50) {
                        int atRest=videoCaptured.get();Thread.sleep(500);
                        if(videoCaptured.get()!=atRest)throw new AssertionError("Video source continued after off");
                        long invalidation=voice.videoStopRequestedNanos()-videoOffRequestedNanos;
                        long lastCallback=Math.max(0,voice.videoCaptureLastNanos()-videoOffRequestedNanos);
                        long closure=voice.videoClosedNanos()-videoOffRequestedNanos;
                        if(!(oneWay&&!caller) && !VideoStopDeliveryGate.withinBounds(videoOffRequestedNanos,
                                voice.videoStopRequestedNanos(),voice.videoCaptureLastNanos(),voice.videoClosedNanos())) {
                            Bundle timing=new Bundle();
                            timing.putString("videoStopFailure","LOCAL_REQUEST_BOUNDS");
                            timing.putLong("videoStopInvalidationDeltaNanos",invalidation);
                            timing.putLong("videoStopLastCaptureDeltaNanos",lastCallback);
                            timing.putLong("videoStopClosureDeltaNanos",closure);
                            timing.putBoolean("videoStopReleaseReceived",videoStopGate.released());
                            InstrumentationRegistry.getInstrumentation().sendStatus(0,timing);
                            throw new AssertionError("Video cancellation missed monotonic request bounds");
                        }
                        if(oneWay&&!caller) {
                            if(atRest!=0 || voice.videoClosedNanos()!=0)throw new AssertionError("Receive-only endpoint instantiated a camera source");
                            invalidation=0;lastCallback=0;closure=0;
                        }
                        write("synthetic-voice-video-stopped.json",new JSONObject().put("captureStopped",true).put("captureWasAuthorized",!oneWay||caller)
                            .put("requestToInvalidationNanos",invalidation).put("requestToLastCaptureNanos",lastCallback).put("requestToClosedNanos",closure)
                            .put("observedAfterRequestMillis",SystemClock.elapsedRealtime()-videoOffAt)
                            .put("captureCallbacksAfterRequest",atRest-videoCaptureBaseline).put("decodedAudioAfterVideoOff",decoded.get()-videoAudioBaseline));videoStage=3;
                    }
                }
                if(modulation) {
                    String diagnostic=voice.state()+":"+voice.modulationStatus();
                    if(!diagnostic.equals(processingDiagnostic)) {
                        processingDiagnostic=diagnostic;
                        JSONObject detail=new JSONObject().put("state",voice.state().name()).put("effective",voice.modulationStatus()).put("step",processingStep)
                            .put("failureStage",voice.failureStage());
                        try {detail.put("faults",voice.processingMetric(4)).put("maxBlockNanos",voice.processingMetric(8)).put("processed",voice.processingMetric(1));}
                        catch(IllegalStateException disposed) {detail.put("processorDisposed",true);}
                        write("synthetic-voice-processing-diagnostic.json",detail);
                    }
                }
                if(modulation && resumedEvidence && (!withVideo || videoStage==5)) {
                    String command="synthetic-voice-processing-"+processingStep+".json";
                    if(processingAt==0 && !Files.exists(files.resolve("synthetic-voice-processing-applied-"+processingStep+".json")) && Files.exists(files.resolve(command))) {
                        JSONObject action=read(command);processingExpected=action.getString("expected");
                        if(caller) {
                            switch(action.getString("action")) {
                                case "on", "retry" -> voice.modulation(true,false);
                                case "off" -> voice.modulation(false,true);
                                case "unconfirmed-off" -> {
                                    try {voice.modulation(false,false);throw new AssertionError("Natural voice bypassed confirmation");}
                                    catch(SecurityException expected) { /* Required local confirmation. */ }
                                }
                                case "mute" -> voice.mute(true);
                                case "unmute" -> voice.mute(false);
                                case "fault" -> voice.invalidateProcessing();
                                default -> throw new AssertionError("Unknown synthetic processing action");
                            }
                        }
                        write("synthetic-voice-processing-applied-"+processingStep+".json",new JSONObject().put("step",processingStep).put("applied",true).put("elapsedMillis",SystemClock.elapsedRealtime()));
                    }
                    if(processingAt==0 && Files.exists(files.resolve("synthetic-voice-processing-observe-"+processingStep+".json"))) {
                        if(!Files.exists(files.resolve("synthetic-voice-processing-applied-"+processingStep+".json")))throw new AssertionError("Processing observation preceded local action");
                        processingAt=SystemClock.elapsedRealtime();
                        processingObservation.set(new DecodedAudioWindow(processingAt,1200,2500,2000,3500));
                    }
                    if(processingAt!=0) {
                        DecodedAudioWindow.Result observation=processingObservation.get().result();
                        if(observation!=null) {
                            if(!observation.failure().isEmpty())throw new AssertionError(observation.failure());
                            int natural=observation.natural(),changed=observation.modified(),energy=observation.loud();
                            String expected=caller?"natural":processingExpected;
                            if(expected.equals("natural") && (natural<40 || changed>3) ||
                               expected.equals("modified") && (changed<40 || natural>3) ||
                               expected.equals("quiet") && (natural>3 || changed>3 || energy>3))
                                throw new AssertionError("Remote processing mismatch step="+processingStep+", expected="+expected+", natural="+natural+", modified="+changed+", loud="+energy+", effective="+voice.modulationStatus());
                            if(withVideo && observation.video()<5)throw new AssertionError("Video stopped during local modulation");
                            JSONObject result=new JSONObject().put("step",processingStep).put("natural",natural).put("modified",changed).put("loud",energy)
                                .put("settleMillis",observation.settleMillis()).put("observedMillis",observation.observedMillis()).put("effective",voice.modulationStatus())
                                .put("videoFrames",observation.video()).put("playbackSamplesPerCallback",playbackSamples.get()).put("playbackRate",playbackRate.get()).put("processPssKiB",android.os.Debug.getPss()).put("nativeHeapAllocatedBytes",android.os.Debug.getNativeHeapAllocatedSize());
                            JSONArray metrics=new JSONArray();for(int index=0;index<34;index++)metrics.put(voice.processingMetric(index));result.put("metrics",metrics);
                            write("synthetic-voice-processing-result-"+processingStep+".json",result);processingStep++;processingAt=0;processingObservation.set(null);
                        }
                    }
                }
                if(!expectedRejection && voice.state()==NativeVoiceSession.State.ACTIVE) {
                    try {
                        JSONObject current=engine.calls().session(id);
                        if(current.getJSONObject("descriptions").length()==2)
                            auditNativeDescriptions(current,credential.getJSONArray("urls").getString(0),credential.optString("relayAddress"));
                    } catch(SecurityException cancelled) {
                        if(voice.state()==NativeVoiceSession.State.ACTIVE)throw cancelled;
                        // A concurrent termination invalidated the snapshot. The next
                        // iteration still requires the scenario's native closure evidence.
                    }
                }
                if(Files.exists(files.resolve("synthetic-voice-stop.json"))) {
                    voice.close(); finishTransport(relay,engine,voice,localLockApplied,localTrustRemoved,peer,savedProfile); break;
                }
                Thread.sleep(100);
            }
            if(withVideo && !expectedRejection && videoStage!=5)throw new AssertionError("Missing decoded bidirectional video/off/reactivation evidence; stage="+videoStage+", native="+voice.videoStatus()+", failure="+voice.failureStage()+", sourceFrames="+videoCaptured.get()+", sinkFrames="+voice.decodedVideoFrames()+", validPatterns="+videoDecoded.frames.get()+", phaseMask="+videoDecoded.phases.get()+", counters="+voice.videoStats());
            if(modulation && processingStep!=10)throw new AssertionError("Incomplete remote modulation sequence");
            if(!evidence || (!expectedRejection && !resumedEvidence)) throw new AssertionError("Missing native audio or mute/unmute evidence");
            voice.close(); finishTransport(relay,engine,voice,localLockApplied,localTrustRemoved,peer,savedProfile);
            if(configuration.optBoolean("admissionRevocationCheck")) {
                // Media is already closed. Test actual AVD -> HTTPS authorization independently
                // of local revocation knowledge; never confuse DevicePolicy revocation with this.
                engine.admission().requireAdmission();
                write("synthetic-voice-admission-ready.json",new JSONObject().put("ready",true));
                waitFor("synthetic-voice-admission-change.json",SystemClock.elapsedRealtime()+20_000);
                JSONObject change=read("synthetic-voice-admission-change.json");
                boolean denied=false;
                if(caller) {
                    engine.admission().requireAdmission(); // Still locally ADMITTED.
                    try { relay.poll(engine.profile(),0); }
                    catch(java.io.IOException failure) {
                        if(!"Servidor rechazó la operación (HTTP 403)".equals(failure.getMessage()))throw failure;
                        denied=true;
                    }
                    if(!denied)throw new AssertionError("Relay accepted revoked admission before local synchronization");
                    engine.admission().applyRevocation(change.getString("revocation"));
                    if(engine.admission().getAdmissionState()!=app.umbra.admission.AdmissionService.State.REVOKED)
                        throw new AssertionError("Revocation not persisted locally");
                } else {
                    if(!change.getBoolean("unaffected"))throw new AssertionError("Unexpected admission fixture command");
                    relay.poll(engine.profile(),0);
                    engine.admission().requireAdmission();
                }
                write("synthetic-voice-admission-result.json",new JSONObject().put("relayDeniedBeforeLocalSync",denied)
                    .put("state",engine.admission().getAdmissionState().name()));
            }
            Bundle status=new Bundle(); status.putString("engineVoice",expectedRejection?"PASS invalid TURN rejected before capture":"PASS independent Android Engine/SQLite/Signal/HTTPS + native TURN decoded synthetic peer audio");
            if(localLockApplied) status.putString("privateStartupLock","PASS old relay rejected; no reconnect");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
            }
        } finally {
            if(voice!=null) voice.close(); HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
    }
}
