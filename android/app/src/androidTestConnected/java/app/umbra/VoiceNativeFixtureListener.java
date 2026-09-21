package app.umbra;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.calls.CallPayload.NetworkPolicy;
import app.umbra.media.TurnConfiguration;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** Native subsystem probe ONLY, not Engine/HTTPS acceptance. Synthetic PCM and no AudioRecord. */
public final class VoiceNativeFixtureListener extends RunListener {
    private static final String REVISION = "a".repeat(64);
    private static void pace(long[] next,int bytes,int channels,int rate) {
        long now=System.nanoTime(); if(next[0]==0 || now-next[0]>30_000_000L) next[0]=now;
        long wait=next[0]-now; if(wait>0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
        next[0]+=1_000_000_000L*bytes/(2L*channels*rate);
    }
    @Override public void testRunStarted(Description ignored) throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!BuildConfig.DEBUG || !context.getPackageName().endsWith(".dev")) throw new SecurityException("Lab only");
        var file = context.getFileStreamPath("synthetic-voice-turn.json").toPath();
        if (Files.size(file)>4096) throw new SecurityException("Oversized synthetic credentials");
        JSONObject credentials = new JSONObject(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(file);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false).setInjectableLogger((m,s,t) -> {}, Logging.Severity.LS_NONE)
                .createInitializationOptions());
        try (Endpoint a = new Endpoint(credentials.getJSONObject("a"), 1000, 2000); Endpoint b = new Endpoint(credentials.getJSONObject("b"), 2000, 1000)) {
            SessionDescription offer = a.local(true);
            b.remote(offer);
            SessionDescription answer = b.local(false);
            a.remote(answer);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while ((a.detected.get() < 100 || b.detected.get() < 100) && System.nanoTime() < deadline) Thread.sleep(50);
            if (a.detected.get() < 100 || b.detected.get() < 100) throw new AssertionError("Bidirectional decoded synthetic tones missing: " + a.detected + "/" + b.detected);
            a.checkTransport(b.fingerprint); b.checkTransport(a.fingerprint);
            if (a.captured.get()<100 || b.captured.get()<100) throw new AssertionError("Synthetic input not consumed");
            Bundle status = new Bundle();
            status.putString("nativeVoice", "PASS two native PeerConnections; bidirectional decoded synthetic tones; relay pairs; DTLS fingerprints. Subsystem probe, no Engine/HTTPS acceptance.");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
        }
        // Deliberate adversarial input ONLY in tests; production never rewrites SDP.
        // Keep native-generated ICE/SDP intact except the authenticated DTLS fingerprint.
        try (Endpoint a = new Endpoint(credentials.getJSONObject("a"),1000,2000);
             Endpoint b = new Endpoint(credentials.getJSONObject("b"),2000,1000)) {
            SessionDescription offer=a.local(true);
            String expected="a=fingerprint:sha-256 "+colonFingerprint(a.fingerprint);
            var impostor=RtcCertificatePem.generateCertificate();
            var certificate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(
                new java.io.ByteArrayInputStream(impostor.certificate.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            String substituted="a=fingerprint:sha-256 "+colonFingerprint(app.umbra.core.Bytes.sha256(certificate.getEncoded()));
            if(offer.description.indexOf(expected)<0 || offer.description.indexOf(expected)!=offer.description.lastIndexOf(expected))
                throw new AssertionError("Expected exactly one generated fingerprint for mutation");
            b.remote(new SessionDescription(offer.type,offer.description.replace(expected,substituted)));
            a.remote(b.local(false));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(b.pc.connectionState()!=PeerConnection.PeerConnectionState.FAILED && System.nanoTime()<deadline) Thread.sleep(50);
            if(b.pc.connectionState()!=PeerConnection.PeerConnectionState.FAILED || a.detected.get()!=0 || b.detected.get()!=0)
                throw new AssertionError("Native DTLS failed to reject substituted certificate before decoded audio");
            Bundle status=new Bundle();status.putString("nativeCertificateRejection","PASS native DTLS rejected a certificate inconsistent with remote SDP; zero decoded tones");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
        }
    }
    private static String colonFingerprint(String hex) {
        StringJoiner joined=new StringJoiner(":");
        for(int i=0;i<hex.length();i+=2) joined.add(hex.substring(i,i+2).toUpperCase(Locale.ROOT));
        return joined.toString();
    }

    private static final class Endpoint implements AutoCloseable, PeerConnection.Observer {
        final AtomicInteger detected = new AtomicInteger(), captured = new AtomicInteger(), candidates = new AtomicInteger();
        final JavaAudioDeviceModule adm;
        final PeerConnectionFactory factory;
        final PeerConnection pc;
        final AudioSource source;
        final AudioTrack track;
        final TurnConfiguration turn;
        final String fingerprint;
        final List<Integer> iceErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch gathered = new CountDownLatch(1);
        long sample; long[] nextFrame={0};
        Endpoint(JSONObject credentials, int sendTone, int receiveTone) throws Exception {
            var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            adm = JavaAudioDeviceModule.builder(context).setSampleRate(48000)
                    .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                    .setAudioBufferCallback((buffer, format, channels, rate, length, time) -> {
                        pace(nextFrame,buffer.capacity(),channels,rate);
                        buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN);
                        while (buffer.remaining() >= 2 * channels) {
                            short value = (short)(12000 * Math.sin(2 * Math.PI * sendTone * sample++ / rate));
                            for (int c=0; c<channels; c++) buffer.putShort(value);
                        }
                        captured.incrementAndGet(); return System.nanoTime();
                    }).setPlaybackSamplesReadyCallback(samples -> {
                        byte[] data = samples.getData(); int channels = samples.getChannelCount();
                        int count = data.length / (2 * channels); double re=0, im=0, energy=0;
                        for (int i=0; i<count; i++) {
                            int pos=i*2*channels; short v=(short)((data[pos]&255)|(data[pos+1]<<8));
                            double phase = 2*Math.PI*receiveTone*i/samples.getSampleRate();
                            re+=v*Math.cos(phase); im+=v*Math.sin(phase); energy+=(double)v*v;
                        }
                        if(count>0 && energy/count>100_000 && 2*(re*re+im*im)/(count*energy)>0.55) detected.incrementAndGet();
                    }).createAudioDeviceModule();
            adm.setAudioRecordEnabled(false); // MUST precede factory/source/PeerConnection creation.
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
            long remaining = Math.min(180_000, (credentials.getLong("expires") - System.currentTimeMillis()/1000)*1000);
            turn = new TurnConfiguration(List.of(credentials.getJSONArray("urls").getString(0)), REVISION,
                    credentials.getString("username"), credentials.getString("password"), remaining, android.os.SystemClock::elapsedRealtime);
            var rtc = turn.nativeConfiguration(REVISION, NetworkPolicy.RELAY_ONLY, 1);
            rtc.certificate = RtcCertificatePem.generateCertificate();
            var cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(
                    new java.io.ByteArrayInputStream(rtc.certificate.certificate.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            fingerprint = app.umbra.core.Bytes.sha256(cert.getEncoded());
            pc = factory.createPeerConnection(rtc, this);
            if(pc==null) throw new AssertionError("Native PeerConnection creation failed");
            MediaConstraints constraints = new MediaConstraints();
            for(String key:List.of("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter"))
                constraints.optional.add(new MediaConstraints.KeyValuePair(key,"false"));
            source = factory.createAudioSource(constraints);
            track = factory.createAudioTrack("synthetic-audio", source);
            pc.addTrack(track, List.of("synthetic-stream"));
        }
        SessionDescription local(boolean offer) throws Exception {
            Await created = new Await();
            if(offer) pc.createOffer(created, new MediaConstraints()); else pc.createAnswer(created, new MediaConstraints());
            created.waitFor(); Await applied = new Await(); pc.setLocalDescription(applied, created.description); applied.waitFor();
            // Snapshot once a real relay candidate is available. COMPLETE can precede
            // Android's first network callback, or wait on an unusable IPv6 interface.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while(candidates.get()==0 && System.nanoTime()<deadline) Thread.sleep(50);
            SessionDescription result=pc.getLocalDescription();
            // Inspection assertion, not a replacement parser. Native WebRTC generates/parses SDP.
            int count=0;
            for(String line:result.description.split("\r\n")) if(line.startsWith("a=candidate:")) {
                if(!line.contains(" typ relay ") || line.contains(" raddr ") && !line.contains(" raddr 0.0.0.0 ")) throw new AssertionError("Non-private native candidate");
                count++;
            }
            if(count==0) throw new AssertionError("No real TURN allocation/candidate; native error codes=" + iceErrors + "; callbacks=" + candidates + "; audio=" + result.description.contains("m=audio") + "; sendrecv=" + result.description.contains("a=sendrecv") + "; chars=" + result.description.length() + "; ice=" + pc.iceConnectionState());
            return result;
        }
        void remote(SessionDescription description) throws Exception {
            Await applied = new Await(); pc.setRemoteDescription(applied,description); applied.waitFor();
        }
        void checkTransport(String expectedFingerprint) throws Exception {
            CompletableFuture<RTCStatsReport> pending = new CompletableFuture<>(); pc.getStats(pending::complete);
            var stats = pending.get(5,TimeUnit.SECONDS).getStatsMap(); boolean verified=false;
            for(var stat:stats.values()) if(stat.getType().equals("transport") && "connected".equals(stat.getMembers().get("dtlsState"))) {
                var pair = stats.get(stat.getMembers().get("selectedCandidatePairId"));
                var local = stats.get(pair.getMembers().get("localCandidateId"));
                var remote = stats.get(pair.getMembers().get("remoteCandidateId"));
                if(!"relay".equals(local.getMembers().get("candidateType")) || !"relay".equals(remote.getMembers().get("candidateType"))) throw new AssertionError("Direct selected pair");
                var certificate = stats.get(stat.getMembers().get("remoteCertificateId"));
                String actual = certificate.getMembers().get("fingerprint").toString().replace(":", "").toLowerCase(Locale.ROOT);
                if(!actual.equals(expectedFingerprint)) throw new AssertionError("Effective remote certificate mismatch");
                verified=true;
            }
            if(!verified) throw new AssertionError("No authenticated native transport stats");
        }
        @Override public void close() { pc.close(); pc.dispose(); track.dispose(); source.dispose(); factory.dispose(); adm.release(); turn.close(); }
        public void onSignalingChange(PeerConnection.SignalingState state) {}
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
        public void onIceConnectionReceivingChange(boolean receiving) {}
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) { if(state==PeerConnection.IceGatheringState.COMPLETE) gathered.countDown(); }
        public void onIceCandidate(IceCandidate candidate) { candidates.incrementAndGet(); }
        public void onIceCandidateError(IceCandidateErrorEvent event) { iceErrors.add(event.errorCode); }
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        public void onAddStream(MediaStream stream) {}
        public void onRemoveStream(MediaStream stream) {}
        public void onDataChannel(DataChannel channel) { throw new AssertionError("Unexpected data channel"); }
        public void onRenegotiationNeeded() {}
    }
    private static final class Await implements SdpObserver {
        final CountDownLatch done = new CountDownLatch(1); SessionDescription description; String failure;
        public void onCreateSuccess(SessionDescription value) { description=value; done.countDown(); }
        public void onSetSuccess() { done.countDown(); }
        public void onCreateFailure(String ignored) { failure="Native SDP creation rejected"; done.countDown(); }
        public void onSetFailure(String ignored) { failure="Native SDP parser/application rejected"; done.countDown(); }
        void waitFor() throws Exception { if(!done.await(10,TimeUnit.SECONDS)) throw new AssertionError("Native SDP timeout"); if(failure!=null) throw new AssertionError(failure); }
    }
}
