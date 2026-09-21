package app.umbra.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import app.umbra.calls.CallService;
import app.umbra.calls.CallPayload.NetworkPolicy;
import app.umbra.core.Bytes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** A single selected, consented voice endpoint. Signal remains the only signaling authority.
 * No background lifetime, automatic resume, arbitrary remote TURN URLs or direct ICE mode.
 */
public final class NativeVoiceSession implements AutoCloseable, PeerConnection.Observer {
    public enum State { NEGOTIATING, ACTIVE, ENDED, FAILED }
    private static boolean initialized;
    private final Object lifecycle=new Object();
    private final Object audioGate=new Object();
    private final Context context;
    private final CallService.MediaLease authorization;
    private final TurnConfiguration turn;
    private final JavaAudioDeviceModule adm;
    private final boolean requireMicrophone;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean(), disposed=new AtomicBoolean();
    private volatile State state=State.NEGOTIATING;
    private volatile String failureStage="none";
    private volatile long receivedAudioPackets;
    private volatile String audioCodec="";
    private volatile boolean muted, endDeliveryFailed, mediaAuthorized;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioSource source;
    private AudioTrack track;
    private VoiceAudioRoute route;
    private String self,peer,localFingerprint,remoteDigest;
    private int generation;
    private boolean caller,creating,localReady,localSent,remoteApplied,checkingStats;
    private int receivedIce;
    private volatile int candidates;
    private long disconnectedAt;
    private long sessionDeadline,sessionBegan;
    private final long negotiationDeadline=android.os.SystemClock.elapsedRealtime()+30_000;

    public static NativeVoiceSession open(Context context,CallService.MediaLease authorization,TurnConfiguration turn) throws Exception {
        try {
            authorization.snapshot(); turn.check();
            NativeDistributionPolicy.requireAuthorizedTurnDestinations();
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission required; obtain fresh consent after the permission dialog");
            initialize(context);
            AtomicBoolean initializationFailure=new AtomicBoolean();
            var failure=new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> initializationFailure.set(true));
            JavaAudioDeviceModule adm=JavaAudioDeviceModule.builder(context)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    public void onWebRtcAudioRecordInitError(String ignored) { failure.get().run(); }
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code,String ignored) { failure.get().run(); }
                    public void onWebRtcAudioRecordError(String ignored) { failure.get().run(); }
                }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    public void onWebRtcAudioTrackInitError(String ignored) { failure.get().run(); }
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code,String ignored) { failure.get().run(); }
                    public void onWebRtcAudioTrackError(String ignored) { failure.get().run(); }
                }).createAudioDeviceModule();
            NativeVoiceSession voice=new NativeVoiceSession(context,authorization,turn,adm,true);
            failure.set(voice::cancelLocally);
            if(initializationFailure.get()) { voice.close(); throw new IllegalStateException("Android audio initialization failed"); }
            return voice;
        } catch(Exception failure) { authorization.close();turn.close();throw failure; }
    }

    /** Package-private injection point; synthetic PCM implementations reside ONLY in androidTest. */
    NativeVoiceSession(Context context,CallService.MediaLease authorization,TurnConfiguration turn,
                       JavaAudioDeviceModule adm,boolean requireMicrophone) throws Exception {
        this.context=context.getApplicationContext(); this.authorization=Objects.requireNonNull(authorization);
        this.turn=Objects.requireNonNull(turn); this.adm=Objects.requireNonNull(adm); this.requireMicrophone=requireMicrophone;
        try { synchronized(lifecycle) {
            initialize(context); JSONObject row=authorization.snapshot(); turn.check();
            sessionDeadline=row.getLong("deadline");sessionBegan=row.getLong("began");
            self=authorization.localDevice(); caller=self.equals(row.getJSONObject("context").getString("callerDevice"));
            peer=caller?row.getString("selected"):row.getJSONObject("context").getString("callerDevice");
            generation=caller?row.getInt("generation")+1:Math.max(1,row.getInt("generation"));
            if(generation!=1) throw new SecurityException("A new voice endpoint cannot resume an earlier generation");
            authorization.attach(this::invalidate);
            if(requireMicrophone) route=new VoiceAudioRoute(context,this::cancelLocally);
            factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
            var rtc=turn.nativeConfiguration(authorization.configurationRevision(),NetworkPolicy.RELAY_ONLY,generation);
            rtc.certificate=RtcCertificatePem.generateCertificate();
            var cert=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(
                    rtc.certificate.certificate.getBytes(StandardCharsets.US_ASCII)));
            localFingerprint=Bytes.sha256(cert.getEncoded());
            check(); pc=factory.createPeerConnection(rtc,this);
            if(pc==null) throw new IllegalStateException("Native media initialization failed");
            // Creating a disabled track alone is NOT sufficient to suppress AudioRecord.
            pc.setAudioRecording(false); pc.setAudioPlayout(false);
            if(caller) pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV));
            check(); worker.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);
            watchdog.scheduleWithFixedDelay(()->{
                try {
                    long now=android.os.SystemClock.elapsedRealtime(); turn.check();
                    if(now<sessionBegan || now>=sessionDeadline || (state==State.NEGOTIATING&&now>=negotiationDeadline))
                        throw new SecurityException("Native media deadline expired");
                    if(requireMicrophone&&context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                        throw new SecurityException("Microphone permission revoked");
                } catch(RuntimeException invalid) { cancelLocally(); }
            },0,100,TimeUnit.MILLISECONDS);
        } } catch(Exception failure) { invalidate(); dispose(); throw failure; }
    }
    static synchronized void initialize(Context context) {
        if(initialized) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false).setInjectableLogger((message,severity,tag)->{},Logging.Severity.LS_NONE)
                .createInitializationOptions());
        initialized=true;
    }
    public State state() { return state; }
    public String failureStage() { return failureStage; }
    public long receivedAudioPackets() { return receivedAudioPackets; }
    public String audioCodec() { return audioCodec; }
    boolean transmitVoiceAllowed() { return mediaAuthorized && !muted && !cancelled.get(); }
    public boolean endDeliveryFailed() { return endDeliveryFailed; }
    private JSONObject check() throws Exception {
        if(cancelled.get()) throw new SecurityException("Media cancelled");
        turn.check();
        if(requireMicrophone && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Microphone permission revoked");
        return authorization.snapshot();
    }
    private void post(Runnable operation) {
        if(cancelled.get()) return;
        try { worker.execute(()->{ if(!cancelled.get()) operation.run(); }); }
        catch(RejectedExecutionException stopped) { if(!cancelled.get()) fail(); }
    }
    private void tick() {
        try {
            JSONObject row=check();
            if(row.getInt("generation")>generation) throw new SecurityException("Restart requires a new local voice session");
            JSONObject remote=row.getJSONObject("descriptions").optJSONObject(peer);
            if(remote!=null && !remoteApplied && !creating) {
                if(row.getInt("generation")!=generation) throw new SecurityException("Wrong voice generation");
                creating=true; remoteDigest=remote.getString("digest");
                pc.setRemoteDescription(new Callback(false,()->{
                    try {
                        check();
                        var transceivers=pc.getTransceivers();
                        if(transceivers.size()!=1 || transceivers.get(0).getMediaType()!=MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                            throw new SecurityException("Only one audio media section is supported");
                        if(!caller) transceivers.get(0).setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                        remoteApplied=true; creating=false;
                    } catch(Exception invalid) { failureStage="remote-media-section"; fail(); }
                }),new SessionDescription(caller?SessionDescription.Type.ANSWER:SessionDescription.Type.OFFER,remote.getString("sdp")));
            }
            if(!creating && !localReady && (caller || remoteApplied)) {
                creating=true;
                Callback callback=new Callback(true,null);
                if(caller) pc.createOffer(callback,new MediaConstraints()); else pc.createAnswer(callback,new MediaConstraints());
            }
            if(localReady && !localSent && candidates>0) {
                check();
                String sdp=pc.getLocalDescription().description;
                authorization.description(generation,caller?"offer":"answer",sdp,localFingerprint);
                localSent=true;
            }
            if(remoteApplied) {
                var ice=row.getJSONArray("ice");
                while(receivedIce<ice.length()) {
                    JSONObject item=ice.getJSONObject(receivedIce++);
                    if(!peer.equals(item.getString("device"))) continue;
                    JSONObject data=item.getJSONObject("data");
                    String mid=pc.getTransceivers().get(0).getMid();
                    if(!Objects.equals(mid,data.getString("mid"))) throw new SecurityException("Wrong audio mid");
                    check();
                    if(!pc.addIceCandidate(new IceCandidate(mid,0,data.getString("candidate")))) throw new SecurityException("Native ICE parser rejected candidate");
                }
            }
            if(localSent && remoteApplied && !checkingStats && pc.connectionState()==PeerConnection.PeerConnectionState.CONNECTED) {
                checkingStats=true; pc.getStats(report->post(()->inspect(report)));
            }
            long now=android.os.SystemClock.elapsedRealtime();
            if(state!=State.ACTIVE && now>=negotiationDeadline || disconnectedAt>0 && now-disconnectedAt>=3000) { failureStage="negotiation-timeout"; fail(); }
        } catch(Exception invalid) { failureStage="authorization-or-signaling"; fail(); }
    }
    private void inspect(RTCStatsReport report) {
        checkingStats=false;
        try {
            check(); var stats=report.getStatsMap(); boolean validated=false;
            for(var stat:stats.values()) if(stat.getType().equals("inbound-rtp") && "audio".equals(stat.getMembers().get("kind"))) {
                Object packets=stat.getMembers().get("packetsReceived");
                if(packets instanceof Number number) receivedAudioPackets=number.longValue();
                var codec=stats.get(stat.getMembers().get("codecId"));
                if(codec!=null) audioCodec=String.valueOf(codec.getMembers().get("mimeType"));
            }
            for(var stat:stats.values()) if(stat.getType().equals("transport") && "connected".equals(stat.getMembers().get("dtlsState"))) {
                var pair=stats.get(stat.getMembers().get("selectedCandidatePairId"));
                if(pair==null) continue;
                var local=stats.get(pair.getMembers().get("localCandidateId"));
                var remote=stats.get(pair.getMembers().get("remoteCandidateId"));
                var cert=stats.get(stat.getMembers().get("remoteCertificateId"));
                if(local==null || remote==null || cert==null) continue;
                if(!"relay".equals(local.getMembers().get("candidateType")) || !"relay".equals(remote.getMembers().get("candidateType")))
                    throw new SecurityException("Non-relay native media path");
                if(!"sha-256".equals(cert.getMembers().get("fingerprintAlgorithm"))) throw new SecurityException("Unexpected certificate digest algorithm");
                String fingerprint=cert.getMembers().get("fingerprint").toString().replace(":","").toLowerCase(Locale.ROOT);
                authorization.verifyRemote(generation,remoteDigest,fingerprint); validated=true;
            }
            if(validated && state!=State.ACTIVE) {
                check();
                source=factory.createAudioSource(new MediaConstraints());
                track=factory.createAudioTrack("umbra-voice",source);
                track.setEnabled(false);
                if(!pc.getTransceivers().get(0).getSender().setTrack(track,false)) throw new IllegalStateException("Native audio sender rejected track");
                check();
                synchronized(audioGate) {
                    if(cancelled.get()) throw new SecurityException("Media cancelled during activation");
                    mediaAuthorized=true; adm.setMicrophoneMute(muted);
                }
                // JNI can synchronously call error callbacks on another thread. Never hold
                // audioGate across it. Cancellation permanently disables ADM recording and
                // mutes playback, so a late native enable cannot reacquire the microphone.
                track.setEnabled(!muted); pc.setAudioRecording(true); pc.setAudioPlayout(true);
                synchronized(audioGate) {
                    if(cancelled.get()) throw new SecurityException("Media cancelled during native activation");
                    state=State.ACTIVE;
                } // Authenticated native transport, not evidence of human audible conversation.
            }
        } catch(Exception invalid) { failureStage="native-binding"; fail(); }
    }
    public java.util.List<android.media.AudioDeviceInfo> communicationDevices() throws Exception {
        check(); if(route==null) throw new IllegalStateException("Synthetic endpoint has no physical audio route"); return route.available();
    }
    public void selectCommunicationDevice(int id) throws Exception {
        check(); if(route==null) throw new IllegalStateException("Synthetic endpoint has no physical audio route"); route.select(id);
    }
    public void mute(boolean value) throws Exception {
        check();
        synchronized(audioGate) {
            if(cancelled.get()) throw new SecurityException("Media cancelled during mute change");
            muted=value; adm.setMicrophoneMute(value);
        }
        post(()->{ try { check(); if(track!=null) track.setEnabled(state==State.ACTIVE&&!value); } catch(Exception invalid) { fail(); } });
    }
    private void invalidate() {
        synchronized(audioGate) {
            if(!cancelled.compareAndSet(false,true)) return;
            mediaAuthorized=false;
            if(state!=State.ENDED) state=State.FAILED;
            adm.setMicrophoneMute(true); adm.setSpeakerMute(true); adm.setAudioRecordEnabled(false);
            // Volatile ADM flags stop hardware capture independently of a stalled database worker.
        }
        try { watchdog.execute(()->{ synchronized(lifecycle) {
            if(!disposed.get()&&pc!=null) release(pc::close);
        } }); } catch(RejectedExecutionException stopped) { /* Disposal already owns the connection. */ }
        try { worker.execute(this::dispose); } catch(RejectedExecutionException stopped) { dispose(); }
    }
    public void cancelLocally() { authorization.close(); invalidate(); }
    private void fail() {
        state=State.FAILED; authorization.close(); invalidate();
    }
    @Override public void close() {
        if(state!=State.FAILED) state=State.ENDED;
        authorization.close(); invalidate();
    }
    private void dispose() { synchronized(lifecycle) {
        if(!disposed.compareAndSet(false,true)) return;
        // Attempt every release even if one vendor callback throws. Failure remains visible.
        release(()->{if(pc!=null) pc.close();}); release(()->{if(pc!=null) pc.dispose();});
        release(()->{if(track!=null) track.dispose();}); release(()->{if(source!=null) source.dispose();});
        release(()->{if(factory!=null) factory.dispose();}); release(adm::release);
        release(()->{if(route!=null) route.close();}); release(turn::close);
        watchdog.shutdown();worker.shutdown();
        // An unavailable store must not keep the watchdog alive after native cleanup.
        try { authorization.end(); } catch(Exception unavailable) { endDeliveryFailed=true; }
    } }
    private void release(Runnable operation) {
        try { operation.run(); } catch(RuntimeException failure) { state=State.FAILED; failureStage="resource-cleanup"; }
    }
    private final class Callback implements SdpObserver {
        private final boolean create; private final Runnable applied;
        Callback(boolean create,Runnable applied) { this.create=create;this.applied=applied; }
        public void onCreateSuccess(SessionDescription description) { post(()->{
            try { check(); if(!create) throw new IllegalStateException(); pc.setLocalDescription(new Callback(false,()->{localReady=true;creating=false;}),description); }
            catch(Exception invalid) { fail(); }
        }); }
        public void onSetSuccess() { post(()->{ try { check(); if(applied!=null) applied.run(); } catch(Exception invalid) { fail(); } }); }
        public void onCreateFailure(String ignored) { post(()->{failureStage="create-sdp";fail();}); }
        public void onSetFailure(String ignored) { post(()->{failureStage="apply-sdp";fail();}); }
    }
    public void onSignalingChange(PeerConnection.SignalingState ignored) {}
    public void onIceConnectionChange(PeerConnection.IceConnectionState ignored) {}
    public void onConnectionChange(PeerConnection.PeerConnectionState connection) { post(()->{
        if(connection==PeerConnection.PeerConnectionState.FAILED) fail();
        else if(connection==PeerConnection.PeerConnectionState.DISCONNECTED) {
            fail(); // New explicit consent/session is required; no automatic ICE/P2P fallback.
        } else if(connection==PeerConnection.PeerConnectionState.CONNECTED) disconnectedAt=0;
    }); }
    public void onIceConnectionReceivingChange(boolean ignored) {}
    public void onIceGatheringChange(PeerConnection.IceGatheringState ignored) {}
    public void onIceCandidate(IceCandidate candidate) { candidates++; }
    public void onIceCandidatesRemoved(IceCandidate[] ignored) {}
    public void onAddStream(MediaStream ignored) {}
    public void onRemoveStream(MediaStream ignored) {}
    public void onDataChannel(DataChannel channel) { post(this::fail); }
    public void onRenegotiationNeeded() {}
}
