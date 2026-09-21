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
            var envelope=q.getJSONObject("envelope");
            relay.sendAuthorized(engine,engine.contact(q.getString("peer")).getJSONObject("card"),envelope);
            engine.transported(envelope.getString("id"),true);
        }
        long now=SystemClock.elapsedRealtime();
        if(now<nextPoll) return;
        nextPoll=now+1000; // Respect the unchanged relay quota, including two endpoints behind one host IP.
        JSONArray rows=relay.poll(engine.profile(),0).getJSONArray("messages");
        for(int i=0;i<rows.length();i++) {
            JSONObject envelope=rows.getJSONObject(i); engine.receive(envelope);
            relay.acknowledge(engine.profile(),envelope.getString("id"));
        }
    }
    // Assertions over output generated and parsed by the pinned native library, not an SDP parser.
    private static void auditNativeDescriptions(JSONObject row,String turnUrl) throws Exception {
        String relay=turnUrl.substring("turn:".length(),turnUrl.indexOf(":3478"));
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
        if(!BuildConfig.DEBUG || !context.getPackageName().endsWith(".dev")) throw new SecurityException("Lab only");
        files=context.getFilesDir().toPath(); JSONObject configuration=read("synthetic-voice-engine.json");
        Files.delete(files.resolve("synthetic-voice-engine.json"));
        boolean caller=configuration.getString("role").equals("A");
        boolean expectedRejection=configuration.optBoolean("expectedRejection");
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
                credential.getString("username"),credential.getString("password"),remaining,SystemClock::elapsedRealtime);
            voice=new NativeVoiceSession(context,lease,turn,adm,false);
            boolean evidence=false,muting=false,mutedEvidence=false,resuming=false,resumedEvidence=false,terminationApplied=false;
            long muteAt=0; int quietBaseline=-1,resumeBaseline=0;
            while(SystemClock.elapsedRealtime()<deadline) {
                pump(relay,engine);
                if(voice.state()==NativeVoiceSession.State.FAILED && !evidence) {
                    if(!expectedRejection) throw new AssertionError("Native authenticated voice failed before audio: "+voice.failureStage());
                    if(captured.get()!=0 || decoded.get()!=0) throw new AssertionError("Rejected TURN path captured or decoded audio");
                    write("synthetic-voice-audio.json",new JSONObject().put("rejectedBeforeCapture",true));
                    evidence=true;waitFor("synthetic-voice-stop.json",deadline);break;
                }
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
                    Thread.sleep(1000);
                    if(voice.transmitVoiceAllowed()) throw new AssertionError("Voice revived after termination");
                    if("calls".equals(db.failBucket)) {
                        if(!voice.endDeliveryFailed()) throw new AssertionError("Synthetic storage failure was not exercised");
                        db.failBucket=null;db.reopen();
                        for(JSONObject row:new Engine(db,SystemClock::elapsedRealtime).calls().sessions())
                            if(!app.umbra.calls.CallPayload.TERMINAL.contains(row.getString("state"))) throw new AssertionError("SQLite rollback revived voice");
                    }
                    write("synthetic-voice-lost.json",new JSONObject().put("failedClosed",true));waitFor("synthetic-voice-stop.json",deadline);break;
                }
                if(!evidence && decoded.get()>=100 && captured.get()>=100 && voice.receivedAudioPackets()>=50 && voice.state()==NativeVoiceSession.State.ACTIVE) {
                    auditNativeDescriptions(engine.calls().session(id),credential.getJSONArray("urls").getString(0));
                    write("synthetic-voice-audio.json",new JSONObject().put("sdpAddressAudit",true).put("decodedBuffers",decoded.get()).put("capturedBuffers",captured.get()).put("verifiedNativeTransport",true).put("receivedAudioPackets",voice.receivedAudioPackets()).put("codec",voice.audioCodec())); evidence=true;
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
                if(Files.exists(files.resolve("synthetic-voice-stop.json"))) {
                    voice.close(); pump(relay,engine); break;
                }
                Thread.sleep(100);
            }
            if(!evidence || (!expectedRejection && !resumedEvidence)) throw new AssertionError("Missing native audio or mute/unmute evidence");
            voice.close(); pump(relay,engine);
            Bundle status=new Bundle(); status.putString("engineVoice",expectedRejection?"PASS invalid TURN rejected before capture":"PASS independent Android Engine/SQLite/Signal/HTTPS + native TURN decoded synthetic peer audio");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
        } finally {
            if(voice!=null) voice.close(); HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
    }
}
