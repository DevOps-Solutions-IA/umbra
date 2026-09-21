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
    private void pump(RelayClient relay,Engine engine) throws Exception {
        for(JSONObject q:engine.outbox()) {
            // Match production scheduling. A successful upload is not a request
            // to repost the same immutable envelope on every 100 ms fixture tick.
            if(q.optLong("nextRelay",0)>Bytes.now()) continue;
            var envelope=q.getJSONObject("envelope");
            relay.sendAuthorized(engine,engine.contact(q.getString("peer")).getJSONObject("card"),envelope);
            engine.transported(envelope.getString("id"),true);
        }
        long now=SystemClock.elapsedRealtime();
        if(now<nextPoll) return;
        nextPoll=now+1500; // Respect the unchanged relay quota, including two endpoints behind one host IP.
        JSONArray rows=relay.poll(engine.profile(),0).getJSONArray("messages");
        for(int i=0;i<rows.length();i++) {
            JSONObject envelope=rows.getJSONObject(i); engine.receive(envelope);
            relay.acknowledge(engine.profile(),envelope.getString("id"));
        }
    }
    // Assertions over output generated and parsed by the pinned native library, not an SDP parser.
    private static void auditNativeDescriptions(JSONObject row,String turnUrl,String relayOverride) throws Exception {
        boolean tls=turnUrl.startsWith("turns:");
        String relay=relayOverride.isEmpty()?turnUrl.substring(tls?"turns:".length():"turn:".length(),turnUrl.indexOf(tls?":5349":":3478")):relayOverride;
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
        var original=HttpsURLConnection.getDefaultSSLSocketFactory();
        var cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(Bytes.utf8(configuration.getString("certificate"))));
        var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trust.load(null,null); trust.setCertificateEntry("synthetic-voice",cert);
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var tls=SSLContext.getInstance("TLS"); tls.init(null,managers.getTrustManagers(),null);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory()); // TEST APK only. Hostname verification unchanged.
        NativeVoiceSession voice=null;
        try(var db=new SqliteDeviceRecords("voice-restart",false); var relay=new RelayClient(configuration.getString("base"))) {
            Engine engine=new Engine(db,SystemClock::elapsedRealtime); engine.initialize("Synthetic voice "+(caller?"A":"B"));
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
            long deadline=SystemClock.elapsedRealtime()+70_000; boolean accepted=false;
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
            NativeVoiceSession.initialize(context);
            AtomicInteger decoded=new AtomicInteger(),captured=new AtomicInteger();
            long[] sample={0}; long[] nextFrame={0}; int inputTone=caller?1000:2000,expectedTone=caller?2000:1000;
            var adm=JavaAudioDeviceModule.builder(context).setSampleRate(48000)
                .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                .setAudioBufferCallback((buffer,format,channels,rate,length,time)->{
                    pace(nextFrame,buffer.capacity(),channels,rate);
                    buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN);
                    while(buffer.remaining()>=2*channels) { short value=(short)(12000*Math.sin(2*Math.PI*inputTone*sample[0]++/rate)); for(int c=0;c<channels;c++) buffer.putShort(value); }
                    captured.incrementAndGet(); return System.nanoTime();
                }).setPlaybackSamplesReadyCallback(samples->{
                    byte[] data=samples.getData(); int channels=samples.getChannelCount(),count=data.length/(2*channels); double re=0,im=0,energy=0;
                    for(int i=0;i<count;i++) { int pos=i*2*channels;short value=(short)((data[pos]&255)|(data[pos+1]<<8));double phase=2*Math.PI*expectedTone*i/samples.getSampleRate();re+=value*Math.cos(phase);im+=value*Math.sin(phase);energy+=(double)value*value; }
                    if(count>0 && energy/count>100000 && 2*(re*re+im*im)/(count*energy)>0.55) decoded.incrementAndGet();
                }).createAudioDeviceModule();
            adm.setAudioRecordEnabled(false);
            JSONObject credential=configuration.getJSONObject("turn");
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
            NativeVoiceSession.DescriptionPublisher hostile=configuration.optBoolean("incorrectFingerprint")?
                (generation,role,sdp,fingerprint)->lease.description(generation,role,sdp,Bytes.sha256(Bytes.utf8(fingerprint))):null;
            voice=new NativeVoiceSession(context,lease,turn,adm,false,verifier,hostile);
            AtomicInteger videoCaptured=new AtomicInteger();
            SyntheticVideoCapturer.Decoded videoDecoded=new SyntheticVideoCapturer.Decoded(caller);
            if(withVideo) { voice.syntheticVideo(()->new SyntheticVideoCapturer(caller,videoCaptured));voice.setRemoteVideoSink(videoDecoded); }
            int videoStage=0,videoAudioBaseline=0,videoFrameBaseline=0,videoCaptureBaseline=0;long videoOffAt=0,videoOffRequestedNanos=0;
            boolean videoRequested=false,videoAccepted=false;
            boolean evidence=false,muting=false,mutedEvidence=false,resuming=false,resumedEvidence=false,terminationApplied=false;
            long muteAt=0; int quietBaseline=-1,resumeBaseline=0;
            while(SystemClock.elapsedRealtime()<deadline) {
                pump(relay,engine);
                if(voice.state()==NativeVoiceSession.State.FAILED && !evidence) {
                    if(!expectedRejection) throw new AssertionError("Native authenticated voice failed before audio: "+voice.failureStage()+"; "+voice.negotiationDiagnostic()+"; captured="+captured.get()+", decoded="+decoded.get());
                    if(captured.get()!=0 || decoded.get()!=0 || videoCaptured.get()!=0) throw new AssertionError("Rejected TURN path captured or decoded audio");
                    JSONObject rejected=new JSONObject().put("rejectedBeforeCapture",true);
                    if(configuration.optBoolean("incorrectFingerprint")) {
                        if(!voice.failureStage().equals("native-certificate-binding"))throw new AssertionError("Wrong-fingerprint test did not reach native certificate binding: "+voice.failureStage());
                        rejected.put("reason",voice.failureStage());
                    }
                    write("synthetic-voice-audio.json",rejected);
                    evidence=true;waitFor("synthetic-voice-stop.json",deadline);break;
                }
                if(withVideo && evidence && voice.state()==NativeVoiceSession.State.FAILED && !terminationApplied)
                    throw new AssertionError("Video path failed: "+voice.failureStage()+", video="+voice.videoStatus());
                if(expectedRejection && voice.state()==NativeVoiceSession.State.ACTIVE) throw new AssertionError("Invalid TURN unexpectedly connected");
                if(evidence && !terminationApplied && Files.exists(files.resolve("synthetic-voice-loss.json"))) {
                    String action=read("synthetic-voice-loss.json").optString("action");
                    if(action.equals("trust-loss")) engine.block(peer,true);
                    if(action.equals("device-revoked")) devices.revoke(engine.id());
                    if(action.equals("storage-failure")) { db.failBucket="calls";voice.close(); }
                    if(action.equals("lock")) {
                        engine.calls().cancelLocal();db.gate.lock();db.gate.unlock();
                        try { lease.snapshot();throw new AssertionError("Old media authorization survived lock/unlock"); }
                        catch(SecurityException expected) { /* New unlock cannot restore old consent. */ }
                    }
                    terminationApplied=true;
                }
                if(evidence && terminationApplied && (voice.state()==NativeVoiceSession.State.FAILED || voice.state()==NativeVoiceSession.State.ENDED)) {
                    long terminalObserved=SystemClock.elapsedRealtime();
                    Thread.sleep(1000);
                    if(voice.transmitVoiceAllowed()) throw new AssertionError("Voice revived after termination");
                    // Check the real native ADM callback, not only the adapter state flag.
                    // Wait nominally one second for disposal, then observe for 500 ms.
                    // Measure both intervals and reject excessive scheduling delays. This is synthetic pipeline closure, not a
                    // hardware microphone cancellation-latency measurement.
                    long quietStart=SystemClock.elapsedRealtime();
                    int capturedAtClosure=captured.get();
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
                    write("synthetic-voice-lost.json",new JSONObject().put("failedClosed",true).put("nativeCaptureQuietAfterMillis",quietAfter).put("nativeCaptureObservedMillis",observedFor).put("lateCaptureCallbacks",lateCaptureCallbacks));waitFor("synthetic-voice-stop.json",deadline);break;
                }
                if(!evidence && decoded.get()>=100 && captured.get()>=100 && voice.receivedAudioPackets()>=50 && voice.state()==NativeVoiceSession.State.ACTIVE) {
                    auditNativeDescriptions(engine.calls().session(id),credential.getJSONArray("urls").getString(0),credential.optString("relayAddress"));
                    write("synthetic-voice-audio.json",new JSONObject().put("sdpAddressAudit",true).put("decodedBuffers",decoded.get()).put("capturedBuffers",captured.get()).put("verifiedNativeTransport",true).put("receivedAudioPackets",voice.receivedAudioPackets()).put("codec",voice.audioCodec()).put("nativeRelayProtocol",voice.nativeRelayProtocol())); evidence=true;
                }
                if(evidence && !muting && Files.exists(files.resolve("synthetic-voice-mute.json"))) {
                    voice.mute(true); muting=true;muteAt=SystemClock.elapsedRealtime();
                }
                if(muting && !mutedEvidence) {
                    long age=SystemClock.elapsedRealtime()-muteAt;
                    if(age>=2000 && quietBaseline<0) quietBaseline=decoded.get();
                    if(age>=3200) {
                        if(decoded.get()-quietBaseline>3) throw new AssertionError("Decoded peer tone continued while both endpoints muted");
                        write("synthetic-voice-muted.json",new JSONObject().put("quiet",true));mutedEvidence=true;
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
                        videoAudioBaseline=decoded.get();videoFrameBaseline=videoDecoded.frames.get();
                    }
                    if(videoStage==1 || videoStage==4) {
                        if(caller && !videoRequested) {voice.video(true,true,false,true);videoRequested=true;}
                        JSONObject pending=engine.calls().session(id).optJSONObject("video");
                        if(!caller && !videoAccepted && pending!=null && pending.optString("state").equals("REVIEW")) {
                            // Host-invoked synthetic owner action; production never auto-accepts this proposal.
                            voice.video(true,true,true,true);videoAccepted=true;
                        }
                        if(videoDecoded.frames.get()-videoFrameBaseline>=20 && videoDecoded.phases.get()==3 && decoded.get()-videoAudioBaseline>=50) {
                            auditNativeDescriptions(engine.calls().session(id),credential.getJSONArray("urls").getString(0),credential.optString("relayAddress"));
                            write("synthetic-voice-video-"+(videoStage==1?"active":"resumed")+".json",new JSONObject()
                                .put("decodedRemotePatterns",videoDecoded.frames.get()-videoFrameBaseline).put("distinctPatternPhases",2)
                                .put("decodedAudioDuringVideo",decoded.get()-videoAudioBaseline).put("capturedFrames",videoCaptured.get())
                                .put("generation",engine.calls().session(id).getInt("generation")).put("videoCodec",new JSONObject(voice.videoStats()).getString("codec")).put("sdpAddressAudit",true));
                            videoStage=videoStage==1?2:5;
                        }
                    }
                    if(videoStage==2 && Files.exists(files.resolve("synthetic-voice-video-off.json"))) {
                        videoOffRequestedNanos=SystemClock.elapsedRealtimeNanos();voice.stopVideo();videoOffAt=SystemClock.elapsedRealtime();videoCaptureBaseline=videoCaptured.get();videoAudioBaseline=decoded.get();videoStage=6;
                    }
                    if(videoStage==6 && SystemClock.elapsedRealtime()-videoOffAt>=2000 && decoded.get()-videoAudioBaseline>=50) {
                        int atRest=videoCaptured.get();Thread.sleep(500);
                        if(videoCaptured.get()!=atRest)throw new AssertionError("Video source continued after off");
                        long invalidation=voice.videoStopRequestedNanos()-videoOffRequestedNanos;
                        long lastCallback=Math.max(0,voice.videoCaptureLastNanos()-videoOffRequestedNanos);
                        long closure=voice.videoClosedNanos()-videoOffRequestedNanos;
                        if(invalidation<0 || invalidation>500_000_000L || closure<0 || closure>2_000_000_000L || lastCallback>1_500_000_000L)
                            throw new AssertionError("Video cancellation missed monotonic request bounds");
                        write("synthetic-voice-video-stopped.json",new JSONObject().put("captureStopped",true)
                            .put("requestToInvalidationNanos",invalidation).put("requestToLastCaptureNanos",lastCallback).put("requestToClosedNanos",closure)
                            .put("observedAfterRequestMillis",SystemClock.elapsedRealtime()-videoOffAt)
                            .put("captureCallbacksAfterRequest",atRest-videoCaptureBaseline).put("decodedAudioAfterVideoOff",decoded.get()-videoAudioBaseline));videoStage=3;
                    }
                }
                if(Files.exists(files.resolve("synthetic-voice-stop.json"))) {
                    voice.close(); pump(relay,engine); break;
                }
                Thread.sleep(100);
            }
            if(withVideo && !expectedRejection && videoStage!=5)throw new AssertionError("Missing decoded bidirectional video/off/reactivation evidence; stage="+videoStage+", native="+voice.videoStatus()+", failure="+voice.failureStage()+", sourceFrames="+videoCaptured.get()+", sinkFrames="+voice.decodedVideoFrames()+", validPatterns="+videoDecoded.frames.get()+", phaseMask="+videoDecoded.phases.get()+", counters="+voice.videoStats());
            if(!evidence || (!expectedRejection && !resumedEvidence)) throw new AssertionError("Missing native audio or mute/unmute evidence");
            voice.close(); pump(relay,engine);
            Bundle status=new Bundle(); status.putString("engineVoice",expectedRejection?"PASS invalid TURN rejected before capture":"PASS independent Android Engine/SQLite/Signal/HTTPS + native TURN decoded synthetic peer audio");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
        } finally {
            if(voice!=null) voice.close(); HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
    }
}
