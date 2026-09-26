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
    interface DescriptionPublisher { void publish(int generation,String role,String sdp,String fingerprint) throws Exception; }
    public enum State { NEGOTIATING, ACTIVE, ENDED, FAILED }
    private static boolean initialized;
    private final Object lifecycle=new Object();
    private final Object audioGate=new Object();
    private final Context context;
    private final CallService.MediaLease authorization;
    private final TurnConfiguration turn;
    private final JavaAudioDeviceModule adm;
    private final boolean requireMicrophone;
    private final DescriptionPublisher descriptionPublisher;
    private java.util.function.Supplier<VideoCapturer> syntheticVideo;
    private volatile NativeVideoCapture videoCapture,lastVideoCapture;
    private VideoTrack remoteVideo;
    private volatile VideoSink remoteSink;
    private volatile boolean videoStopped=true, frontCamera=true;
    private volatile String videoChange="", videoStatus="OFF",videoFailureStage="video-authorization";
    private boolean videoSend,videoReceive;
    private volatile long lastVideoFrameNanos,decodedVideoFrames;
    private final VideoSink videoSink=frame -> {
        if(this.cancelled.get() || videoStopped || !videoReceive) return;
        try {
            checkVideoCapture();
            if(this.cancelled.get() || videoStopped) return;
            int width=frame.getBuffer().getWidth(),height=frame.getBuffer().getHeight();
            if(width<1 || height<1 || width>320 || height>320 || (long)width*height>320L*240)
                throw new SecurityException("Decoded video exceeds the consented initial profile");
            lastVideoFrameNanos=android.os.SystemClock.elapsedRealtimeNanos();decodedVideoFrames++;
            VideoSink sink=remoteSink;if(sink!=null)sink.onFrame(frame);
        } catch(Exception invalid) { stopVideoLocally(); }
    };
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean(), disposed=new AtomicBoolean();
    private volatile State state=State.NEGOTIATING;
    private volatile String failureStage="none";
    private volatile String statsDiagnostic="not-received";
    private volatile String negotiationDiagnostic="initializing",pairDiagnostic="unobserved";
    private volatile long receivedAudioPackets;
    private volatile String audioCodec="",videoStats="{}",nativeRelayProtocol="";
    private volatile boolean muted, endDeliveryFailed, mediaAuthorized;
    private PeerConnectionFactory factory;
    private volatile UmbraVoiceProcessor voiceProcessor;
    private volatile boolean requestedModulation;
    private PeerConnection pc;
    private List<RtpTransceiver> mediaTransceivers=List.of();
    private AudioSource source;
    private AudioTrack track;
    private VoiceAudioRoute route;
    private String self,peer,localFingerprint,remoteDigest;
    private int generation;
    private volatile int localDescriptionGeneration;
    private record PendingIce(int generation,IceCandidate candidate) {}
    private final ArrayDeque<PendingIce> pendingLocalIce=new ArrayDeque<>();
    private String publishedLocalSdp="",publishedLocalDigest="";
    private boolean caller,creating,localReady,localSent,remoteApplied,checkingStats;
    private int receivedIce;
    private volatile int candidates;
    private long disconnectedAt,videoNegotiationDeadline;
    private long sessionDeadline,sessionBegan;
    private final long negotiationDeadline=android.os.SystemClock.elapsedRealtime()+30_000;

    public static NativeVoiceSession open(Context context,CallService.MediaLease authorization,TurnConfiguration turn) throws Exception {
        return open(context,authorization,turn,false);
    }
    public static NativeVoiceSession open(Context context,CallService.MediaLease authorization,TurnConfiguration turn,boolean modulated) throws Exception {
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
            NativeVoiceSession voice=new NativeVoiceSession(context,authorization,turn,adm,true,null,null,modulated);
            failure.set(voice::cancelLocally);
            if(initializationFailure.get()) { voice.close(); throw new IllegalStateException("Android audio initialization failed"); }
            return voice;
        } catch(Exception failure) { authorization.close();turn.close();throw failure; }
    }

    /** Package-private injection point; synthetic PCM implementations reside ONLY in androidTest. */
    NativeVoiceSession(Context context,CallService.MediaLease authorization,TurnConfiguration turn,
                       JavaAudioDeviceModule adm,boolean requireMicrophone) throws Exception {
        this(context,authorization,turn,adm,requireMicrophone,null);
    }
    /** Private-CA validation implementation is provided ONLY by the separate test APK. */
    NativeVoiceSession(Context context,CallService.MediaLease authorization,TurnConfiguration turn,
                       JavaAudioDeviceModule adm,boolean requireMicrophone,SSLCertificateVerifier labVerifier) throws Exception {
        this(context,authorization,turn,adm,requireMicrophone,labVerifier,null);
    }
    /** A hostile description publisher can only be supplied by the separate test APK. */
    NativeVoiceSession(Context context,CallService.MediaLease authorization,TurnConfiguration turn,
                       JavaAudioDeviceModule adm,boolean requireMicrophone,SSLCertificateVerifier labVerifier,DescriptionPublisher labPublisher) throws Exception {
        this(context,authorization,turn,adm,requireMicrophone,labVerifier,labPublisher,false);
    }
    NativeVoiceSession(Context context,CallService.MediaLease authorization,TurnConfiguration turn,
                       JavaAudioDeviceModule adm,boolean requireMicrophone,SSLCertificateVerifier labVerifier,DescriptionPublisher labPublisher,boolean modulated) throws Exception {
        if(requireMicrophone && labVerifier!=null)throw new SecurityException("Laboratory trust cannot be used by the production audio entry");
        if(requireMicrophone && labPublisher!=null)throw new SecurityException("Production signaling publisher cannot be replaced");
        this.context=context.getApplicationContext(); this.authorization=Objects.requireNonNull(authorization);
        this.descriptionPublisher=labPublisher==null?authorization::description:labPublisher;
        this.turn=Objects.requireNonNull(turn); this.adm=Objects.requireNonNull(adm); this.requireMicrophone=requireMicrophone;
        try { synchronized(lifecycle) {
            initialize(context); JSONObject row=authorization.snapshot(); turn.check();
            sessionDeadline=row.getLong("deadline");sessionBegan=row.getLong("began");
            self=authorization.localDevice(); caller=self.equals(row.getJSONObject("context").getString("callerDevice"));
            peer=caller?row.getString("selected"):row.getJSONObject("context").getString("callerDevice");
            generation=caller?row.getInt("generation")+1:Math.max(1,row.getInt("generation"));
            if(generation!=1) throw new SecurityException("A new voice endpoint cannot resume an earlier generation");
            authorization.attach(this::invalidate);
            authorization.attachVideoCancellation(this::invalidateVideo);
            if(requireMicrophone) route=new VoiceAudioRoute(context,this::cancelLocally);
            requestedModulation=modulated;voiceProcessor=new UmbraVoiceProcessor(modulated);
            factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).setAudioProcessingFactory(voiceProcessor)
                .setVideoEncoderFactory(new SoftwareVideoEncoderFactory())
                .setVideoDecoderFactory(new SoftwareVideoDecoderFactory()).createPeerConnectionFactory();
            var rtc=turn.nativeConfiguration(authorization.configurationRevision(),NetworkPolicy.RELAY_ONLY,generation);
            rtc.certificate=RtcCertificatePem.generateCertificate();
            var cert=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(
                    rtc.certificate.certificate.getBytes(StandardCharsets.US_ASCII)));
            localFingerprint=Bytes.sha256(cert.getEncoded());
            check();
            var dependencies=PeerConnectionDependencies.builder(this).setSSLCertificateVerifier(labVerifier).createPeerConnectionDependencies();
            pc=factory.createPeerConnection(rtc,dependencies);
            if(pc==null) throw new IllegalStateException("Native media initialization failed");
            // Creating a disabled track alone is NOT sufficient to suppress AudioRecord.
            pc.setAudioRecording(false); pc.setAudioPlayout(false);
            if(caller) pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV));
            refreshTransceivers();
            check(); worker.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);
            watchdog.scheduleWithFixedDelay(()->{
                try {
                    long now=android.os.SystemClock.elapsedRealtime(); turn.check();
                    if(now<sessionBegan || now>=sessionDeadline || (state==State.NEGOTIATING&&now>=negotiationDeadline))
                        throw new SecurityException("Native media deadline expired");
                    if(requireMicrophone&&context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                        throw new SecurityException("Microphone permission revoked");
                } catch(RuntimeException invalid) { if(failureStage.equals("none"))failureStage="local-watchdog";cancelLocally(); }
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
    public String negotiationDiagnostic() { return negotiationDiagnostic+", pair="+pairDiagnostic+", stats="+statsDiagnostic; }
    public long receivedAudioPackets() { return receivedAudioPackets; }
    public String audioCodec() { return audioCodec; }
    public String videoStats() { return videoStats; }
    public String nativeRelayProtocol() { return nativeRelayProtocol; }
    public long videoCaptureLastNanos() {NativeVideoCapture capture=lastVideoCapture;return capture==null?0:capture.lastCaptureNanos;}
    public long videoStopRequestedNanos() {NativeVideoCapture capture=lastVideoCapture;return capture==null?0:capture.stopRequestedNanos;}
    public long videoClosedNanos() {NativeVideoCapture capture=lastVideoCapture;return capture==null?0:capture.closedNanos;}
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
        // Fixed-delay tasks can already be queued when cancellation schedules disposal.
        // They must not overwrite the original certificate/revocation failure.
        if(cancelled.get()) return;
        try {
            JSONObject row=check();
            negotiationDiagnostic="gather="+pc.iceGatheringState()+", connection="+pc.connectionState()+", candidates="+candidates+", sent="+localSent+", remote="+remoteApplied;
            JSONObject video=row.optJSONObject("video");
            if(video!=null && video.getString("state").equals("CONFIRMED") && video.getInt("generation")>generation) beginVideoGeneration(row,video);
            if(row.getInt("generation")>generation) throw new SecurityException("Restart requires a confirmed media change");
            if(!videoStopped && (video==null || !video.getString("state").equals("CONFIRMED"))) stopVideoLocally();
            if(videoCapture!=null && syntheticVideo==null && context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) stopVideoLocally();
            JSONObject remote=row.getInt("generation")==generation?row.getJSONObject("descriptions").optJSONObject(peer):null;
            if(remote!=null && !remoteApplied && !creating) {
                if(row.getInt("generation")!=generation) throw new SecurityException("Wrong voice generation");
                creating=true; remoteDigest=remote.getString("digest");
                pc.setRemoteDescription(new Callback(false,()->{
                    try {
                        check();
                        refreshTransceivers();var transceivers=mediaTransceivers;
                        int expected=videoChange.isEmpty()?1:2;
                        if(transceivers.size()!=expected || transceivers.get(0).getMediaType()!=MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO ||
                            expected==2 && transceivers.get(1).getMediaType()!=MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                            throw new SecurityException("Unapproved media sections");
                        if(!caller) {
                            transceivers.get(0).setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                            if(expected==2 && !transceivers.get(1).setDirection(videoDirection())) throw new SecurityException("Video direction rejected");
                        }
                        remoteApplied=true; creating=false;
                    } catch(Exception invalid) { failureStage="remote-media-section"; fail(); }
                }),new SessionDescription(caller?SessionDescription.Type.ANSWER:SessionDescription.Type.OFFER,remote.getString("sdp")));
            }
            if(!creating && !localReady && (caller || remoteApplied)) {
                creating=true;
                Callback callback=new Callback(true,null);
                if(caller) pc.createOffer(callback,new MediaConstraints()); else pc.createAnswer(callback,new MediaConstraints());
            }
            // Publish an initial TURN candidate, then deliver every later native
            // candidate through the existing authenticated ICE control. Waiting
            // for all interfaces to finish can starve a valid relay connection.
            if(localReady && !localSent && candidates>0) {
                check();
                String sdp=pc.getLocalDescription().description;
                descriptionPublisher.publish(generation,caller?"offer":"answer",sdp,localFingerprint);
                publishedLocalSdp=sdp;publishedLocalDigest=Bytes.sha256(Bytes.utf8(sdp));
                localSent=true;
            }
            if(localSent) while(!pendingLocalIce.isEmpty()) {
                PendingIce item=pendingLocalIce.removeFirst();
                if(item.generation()!=generation)continue;
                IceCandidate candidate=item.candidate();
                // Exact comparison against native-generated SDP, not a parser
                // or a rewrite. Already-announced candidates need no new control.
                if(publishedLocalSdp.contains("a="+candidate.sdp+"\r\n"))continue;
                check();authorization.ice(generation,publishedLocalDigest,candidate.sdpMid,candidate.sdp);
            }
            if(remoteApplied) {
                var ice=row.getJSONArray("ice");
                while(receivedIce<ice.length()) {
                    JSONObject item=ice.getJSONObject(receivedIce++);
                    if(!peer.equals(item.getString("device"))) continue;
                    JSONObject data=item.getJSONObject("data");
                    int index=-1;
                    for(int i=0;i<mediaTransceivers.size();i++) if(Objects.equals(mediaTransceivers.get(i).getMid(),data.getString("mid"))) index=i;
                    if(index<0) throw new SecurityException("Wrong media mid");
                    check();
                    if(!pc.addIceCandidate(new IceCandidate(data.getString("mid"),index,data.getString("candidate")))) throw new SecurityException("Native ICE parser rejected candidate");
                }
            }
            if(localSent && remoteApplied && !checkingStats && pc.connectionState()==PeerConnection.PeerConnectionState.CONNECTED) {
                checkingStats=true; int checkedGeneration=generation;
                pc.getStats(report->post(()->{if(checkedGeneration!=generation) {checkingStats=false;return;} inspect(report);}));
            }
            long now=android.os.SystemClock.elapsedRealtime();
            if(videoStatus.equals("NEGOTIATING") && now>=videoNegotiationDeadline) {failureStage="video-negotiation-timeout";fail();}
            if(state!=State.ACTIVE && now>=negotiationDeadline || disconnectedAt>0 && now-disconnectedAt>=3000) { failureStage="negotiation-timeout"; fail(); }
        } catch(Exception invalid) {
            if(cancelled.get()) return;
            failureStage="authorization-or-signaling"; fail();
        }
    }
    private void inspect(RTCStatsReport report) {
        checkingStats=false;
        String bindingStage="native-authorization";
        try {
            check(); var stats=report.getStatsMap(); boolean validated=false;
            statsDiagnostic="received:"+stats.size();
            for(var stat:stats.values()) if(stat.getType().equals("inbound-rtp") && "audio".equals(stat.getMembers().get("kind"))) {
                Object packets=stat.getMembers().get("packetsReceived");
                if(packets instanceof Number number) receivedAudioPackets=number.longValue();
                var codec=stats.get(stat.getMembers().get("codecId"));
                if(codec!=null) audioCodec=String.valueOf(codec.getMembers().get("mimeType"));
            }
            JSONObject videoCounters=new JSONObject();
            for(var stat:stats.values()) if(Set.of("inbound-rtp","outbound-rtp").contains(stat.getType()) && "video".equals(stat.getMembers().get("kind"))) {
                var codec=stats.get(stat.getMembers().get("codecId"));
                if(codec!=null)videoCounters.put("codec",String.valueOf(codec.getMembers().get("mimeType")));
                for(String field:List.of("framesEncoded","framesSent","framesDecoded","framesReceived","bytesSent","packetsSent","packetsReceived","packetsLost")) {
                    Object value=stat.getMembers().get(field);if(value instanceof Number number)videoCounters.put(field,number.longValue());
                }
            }
            videoStats=videoCounters.toString();
            for(var stat:stats.values()) if(stat.getType().equals("transport") && "connected".equals(stat.getMembers().get("dtlsState"))) {
                var pair=stats.get(stat.getMembers().get("selectedCandidatePairId"));
                if(pair==null) {statsDiagnostic="dtls-connected-pair-missing";continue;}
                var local=stats.get(pair.getMembers().get("localCandidateId"));
                var remote=stats.get(pair.getMembers().get("remoteCandidateId"));
                var cert=stats.get(stat.getMembers().get("remoteCertificateId"));
                if(local==null || remote==null || cert==null) {statsDiagnostic="dtls-connected-local="+(local!=null)+"-remote="+(remote!=null)+"-certificate="+(cert!=null);continue;}
                bindingStage="native-relay-pair";
                for(var entry:List.of(local,remote)) {
                    String type=String.valueOf(entry.getMembers().get("candidateType"));
                    bindingStage+="-"+(Set.of("relay","host","srflx","prflx").contains(type)?type:"unknown");
                }
                pairDiagnostic=bindingStage;
                // Native ICE can discover a peer-reflexive endpoint before the
                // authenticated remote candidate is applied. Upstream's
                // MaybeUpdatePeerReflexiveCandidate upgrades it only on an exact
                // address/protocol/credentials/generation match. Do NOT authorize
                // media from that provisional classification. The unchanged
                // negotiation deadline bounds this wait; active media fails shut.
                if(!mediaAuthorized && "relay".equals(local.getMembers().get("candidateType")) &&
                    "prflx".equals(remote.getMembers().get("candidateType"))) return;
                if(!"relay".equals(local.getMembers().get("candidateType")) || !"relay".equals(remote.getMembers().get("candidateType")))
                    throw new SecurityException("Non-relay native media path");
                String protocol=String.valueOf(local.getMembers().get("relayProtocol"));
                if(!Set.of("udp","tcp","tls").contains(protocol))throw new SecurityException("Unknown native relay transport");
                nativeRelayProtocol=protocol;
                bindingStage="native-certificate-algorithm";
                if(!"sha-256".equals(cert.getMembers().get("fingerprintAlgorithm"))) throw new SecurityException("Unexpected certificate digest algorithm");
                String fingerprint=cert.getMembers().get("fingerprint").toString().replace(":","").toLowerCase(Locale.ROOT);
                bindingStage="native-certificate-binding";
                authorization.verifyRemote(generation,remoteDigest,fingerprint); validated=true;
            }
            if(validated && !mediaAuthorized) {
                check();
                bindingStage="native-audio-source";
                source=factory.createAudioSource(new MediaConstraints());
                track=factory.createAudioTrack("umbra-voice",source);
                track.setEnabled(false);
                bindingStage="native-audio-track";
                if(!mediaTransceivers.get(0).getSender().setTrack(track,false)) throw new IllegalStateException("Native audio sender rejected track");
                check();
                synchronized(audioGate) {
                    if(cancelled.get()) throw new SecurityException("Media cancelled during activation");
                    voiceProcessor.mute(muted);voiceProcessor.authorize(true);
                    mediaAuthorized=true; adm.setMicrophoneMute(muted);
                }
                // JNI can synchronously call error callbacks on another thread. Never hold
                // audioGate across it. Cancellation permanently disables ADM recording and
                // mutes playback, so a late native enable cannot reacquire the microphone.
                bindingStage="native-audio-activation";
                track.setEnabled(!muted); pc.setAudioRecording(true); pc.setAudioPlayout(true);
                synchronized(audioGate) {
                    if(cancelled.get()) throw new SecurityException("Media cancelled during native activation");
                    state=State.ACTIVE;
                } // Authenticated native transport, not evidence of human audible conversation.
            }
            bindingStage="native-video-activation";
            if(validated && !videoChange.isEmpty() && !videoStopped && localSent && remoteApplied) activateVideo();
        } catch(Exception invalid) {
            failureStage=bindingStage.equals("native-video-activation")?videoFailureStage:bindingStage;
            if(bindingStage.equals("native-video-activation") && Set.of("video-capture-initialize","video-capture-start").contains(videoFailureStage)) cameraFailed();
            else fail();
        }
    }
    public String videoStatus() {
        if(videoStopped) return videoStatus;
        if(videoReceive && lastVideoFrameNanos==0 && videoStatus.equals("ACTIVE")) return "WAITING_FOR_FRAME";
        if(videoReceive && lastVideoFrameNanos>0 && android.os.SystemClock.elapsedRealtimeNanos()-lastVideoFrameNanos>3_000_000_000L) return "STALE";
        return videoStatus;
    }
    public long decodedVideoFrames() { return decodedVideoFrames; }
    public long lastVideoFrameNanos() { return lastVideoFrameNanos; }
    public void setRemoteVideoSink(VideoSink sink) { remoteSink=sink; }
    /** Test source lives in the separate instrumentation APK; no fixture is packaged in release. */
    void syntheticVideo(java.util.function.Supplier<VideoCapturer> source) throws Exception {
        check();if(requireMicrophone || !videoChange.isEmpty()) throw new SecurityException("Synthetic source only before isolated test video");
        syntheticVideo=Objects.requireNonNull(source);
    }
    public void video(boolean sending,boolean receiving,boolean accepting,boolean front) throws Exception {
        check();if(state!=State.ACTIVE)throw new SecurityException("Voice transport must be authenticated first");
        if(sending && syntheticVideo==null && context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Obtain camera permission then renew video consent");
        var consent=authorization.reviewVideo(sending,receiving,accepting);
        authorization.video(consent,accepting);frontCamera=front;
    }
    public void rejectVideo() throws Exception { check();authorization.rejectVideo(); }
    /** UI cancellation gate must not wait behind storage or signaling work. */
    public void requestVideoStop() { stopVideoLocally(); }
    public void stopVideo() throws Exception {
        invalidateVideo();
        try { authorization.stopVideo(); } catch(Exception invalid) { cancelLocally();throw invalid; }
    }
    public void switchCamera() throws Exception {
        checkVideo();NativeVideoCapture capture=videoCapture;
        if(capture==null) throw new SecurityException("Camera not running");
        post(()->{try {checkVideo();capture.switchCamera();} catch(Exception invalid) { stopVideoLocally(); }});
    }
    private RtpTransceiver.RtpTransceiverDirection videoDirection() {
        return videoSend?(videoReceive?RtpTransceiver.RtpTransceiverDirection.SEND_RECV:RtpTransceiver.RtpTransceiverDirection.SEND_ONLY):
            (videoReceive?RtpTransceiver.RtpTransceiverDirection.RECV_ONLY:RtpTransceiver.RtpTransceiverDirection.INACTIVE);
    }
    private void beginVideoGeneration(JSONObject row,JSONObject video) throws Exception {
        int next=video.getInt("generation");
        if(!mediaAuthorized || next!=generation+1 || next>4 || creating || row.getInt("generation")>next)
            throw new SecurityException("Invalid video renegotiation state");
        releaseVideo();
        generation=next;videoChange=video.getString("change");
        videoSend=video.getInt(caller?"callerSend":"calleeSend")==1;
        videoReceive=video.getInt(caller?"calleeSend":"callerSend")==1;
        videoStopped=false;videoStatus="NEGOTIATING";lastVideoFrameNanos=0;
        videoNegotiationDeadline=Math.min(sessionDeadline,android.os.SystemClock.elapsedRealtime()+15_000);
        localReady=false;localSent=false;remoteApplied=false;receivedIce=0;remoteDigest=null;
        pendingLocalIce.clear();publishedLocalSdp="";publishedLocalDigest="";
        if(caller) {
            if(mediaTransceivers.size()==1) {
                pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,new RtpTransceiver.RtpTransceiverInit(videoDirection()));
                refreshTransceivers();
            }
            else if(mediaTransceivers.size()!=2 || !mediaTransceivers.get(1).setDirection(videoDirection())) throw new SecurityException("Video transceiver mismatch");
        }
    }
    private void checkVideoCapture() {
        authorization.checkCaptureLease();turn.check();
        long now=android.os.SystemClock.elapsedRealtime();
        if(cancelled.get() || videoStopped || now<sessionBegan || now>=sessionDeadline) throw new SecurityException("Video capture lease ended");
        if(videoSend && syntheticVideo==null && context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Camera permission revoked");
    }
    private void checkVideo() throws Exception {
        JSONObject row=check(),video=row.optJSONObject("video");
        if(videoStopped || video==null || !video.getString("state").equals("CONFIRMED") ||
            !videoChange.equals(video.getString("change")) || generation!=video.getInt("generation")) throw new SecurityException("Video authorization ended");
        if(videoSend && syntheticVideo==null && context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Camera permission revoked");
    }
    private void activateVideo() throws Exception {
        videoFailureStage="video-authorization";checkVideo();
        var transceivers=mediaTransceivers;
        videoFailureStage="video-native-direction";
        if(transceivers.size()!=2 || transceivers.get(1).getCurrentDirection()!=videoDirection()) throw new SecurityException("Negotiated direction differs from consent");
        videoFailureStage="video-remote-track";
        if(videoReceive && remoteVideo==null) {
            var incoming=transceivers.get(1).getReceiver().track();
            if(!(incoming instanceof VideoTrack video)) throw new SecurityException("Missing remote video track");
            remoteVideo=video;remoteVideo.addSink(videoSink);
        }
        if(videoSend && videoCapture==null) {
            videoFailureStage="video-capture-initialize";
            String captureChange=videoChange;
            Runnable captureFailure=()->{if(captureChange.equals(videoChange))cameraFailed();};
            VideoCapturer capturer=syntheticVideo==null?NativeVideoCapture.camera(context,frontCamera,captureFailure):syntheticVideo.get();
            NativeVideoCapture capture=new NativeVideoCapture(context,factory,capturer,()->{
                checkVideoCapture();if(!captureChange.equals(videoChange))throw new SecurityException("Stale camera callback");
            },captureFailure);
            videoCapture=capture;lastVideoCapture=capture;
            checkVideo(); // Disk-backed policy checks must not hold the capture teardown monitor.
            synchronized(capture) {
                capture.requireOpen();
                videoFailureStage="video-attach-track";
                if(!transceivers.get(1).getSender().setTrack(capture.track,false)) throw new SecurityException("Native video track rejected");
                videoFailureStage="video-encoding-parameters";
                RtpParameters parameters=transceivers.get(1).getSender().getParameters();
                for(var encoding:parameters.encodings) { encoding.maxBitrateBps=400_000;encoding.maxFramerate=15; }
                if(!transceivers.get(1).getSender().setParameters(parameters)) throw new SecurityException("Video limits rejected");
                videoFailureStage="video-capture-start";capture.start();
            }
        }
        videoStatus="ACTIVE"; // Decoded image evidence is separate, including last frame freshness.
    }
    private void invalidateVideo() {
        videoStopped=true;videoStatus="OFF";
        NativeVideoCapture capture=videoCapture;
        if(capture!=null) {
            capture.invalidate();
            try {watchdog.execute(()->release(capture::close));}catch(RejectedExecutionException stopped){release(capture::close);}
        }
        String stoppedChange=videoChange;
        post(()->{if(stoppedChange.equals(videoChange))releaseVideo();});
    }
    private void stopVideoLocally() {
        invalidateVideo();String stoppedChange=videoChange;
        post(()->{
            try {
                JSONObject current=authorization.snapshot().optJSONObject("video");
                if(current!=null && stoppedChange.equals(current.getString("change")) && current.getString("state").equals("CONFIRMED")) authorization.stopVideo();
            } catch(Exception invalid) { if(!cancelled.get())fail(); }
        });
    }
    private void cameraFailed() {
        if(videoStopped)return;
        stopVideoLocally();videoStatus="CAMERA_UNAVAILABLE";
    }
    private void refreshTransceivers() {
        // This pinned Java API DISPOSES previous wrappers on every getTransceivers.
        // Keep them stable while sinks/senders are in use; refresh only at SDP changes.
        if(remoteVideo!=null) {remoteVideo.removeSink(videoSink);remoteVideo=null;}
        mediaTransceivers=List.copyOf(pc.getTransceivers());
    }
    private void releaseVideo() {
        videoStopped=true;
        if(remoteVideo!=null) { remoteVideo.removeSink(videoSink);remoteVideo=null; }
        NativeVideoCapture capture=videoCapture;videoCapture=null;
        if(pc!=null && mediaTransceivers.size()==2) mediaTransceivers.get(1).getSender().setTrack(null,false);
        if(capture!=null)capture.close();
    }
    public java.util.List<android.media.AudioDeviceInfo> communicationDevices() throws Exception {
        check(); if(route==null) throw new IllegalStateException("Synthetic endpoint has no physical audio route"); return route.available();
    }
    public void selectCommunicationDevice(int id) throws Exception {
        check(); if(route==null) throw new IllegalStateException("Synthetic endpoint has no physical audio route"); if(voiceProcessor.requested())voiceProcessor.invalidate(); route.select(id);
    }
    /** Local-only control, never called by signaling. Confirmation does not unmute. */
    public void modulation(boolean requested,boolean naturalConfirmed) throws Exception {
        check();
        synchronized(audioGate) {
            if(cancelled.get())throw new SecurityException("Media cancelled during processing change");
            if(!voiceProcessor.request(requested,naturalConfirmed))throw new SecurityException("Natural voice requires explicit confirmation");
            requestedModulation=requested;
        }
    }
    public String modulationStatus() {
        UmbraVoiceProcessor processor=voiceProcessor;
        if(processor==null)return "ERROR_MUTED";
        return switch(processor.status()) {
            case UmbraVoiceProcessor.OFF -> "OFF";
            case UmbraVoiceProcessor.ENABLING -> "ENABLING";
            case UmbraVoiceProcessor.ON -> "ON";
            case UmbraVoiceProcessor.DISABLING -> "DISABLING";
            default -> "ERROR_MUTED";
        };
    }
    public boolean modulationRequested() {return requestedModulation;}
    long processingMetric(int index) {return voiceProcessor.metric(index);}
    void invalidateProcessing() {voiceProcessor.invalidate();}
    public void mute(boolean value) throws Exception {
        check();
        synchronized(audioGate) {
            if(cancelled.get()) throw new SecurityException("Media cancelled during mute change");
            voiceProcessor.mute(value);muted=value; adm.setMicrophoneMute(value);
        }
        post(()->{ try { check(); if(track!=null) track.setEnabled(state==State.ACTIVE&&!value); } catch(Exception invalid) { fail(); } });
    }
    private void invalidate() {
        synchronized(audioGate) {
            if(!cancelled.compareAndSet(false,true)) return;
            mediaAuthorized=false;videoStopped=true;
            UmbraVoiceProcessor processing=voiceProcessor;
            if(processing!=null) {processing.authorize(false);processing.invalidate();}
            NativeVideoCapture capture=videoCapture;
            if(capture!=null) {
                capture.invalidate();
                try {watchdog.execute(()->release(capture::close));}catch(RejectedExecutionException stopped){release(capture::close);}
            }
            remoteSink=null;
            if(state!=State.ENDED) {
                if(failureStage.equals("none"))failureStage="authorization-cancelled";
                state=State.FAILED;
            }
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
        if(failureStage.equals("none"))failureStage="native-operation-failed";
        state=State.FAILED; authorization.close(); invalidate();
    }
    @Override public void close() {
        if(state!=State.FAILED) state=State.ENDED;
        authorization.close(); invalidate();
    }
    private void dispose() { synchronized(lifecycle) {
        if(!disposed.compareAndSet(false,true)) return;
        // Attempt every release even if one vendor callback throws. Failure remains visible.
        release(this::releaseVideo);
        release(()->{if(pc!=null) pc.close();}); release(()->{if(pc!=null) pc.dispose();});
        release(()->{if(track!=null) track.dispose();}); release(()->{if(source!=null) source.dispose();});
        release(()->{if(factory!=null) factory.dispose();});
        release(()->{if(voiceProcessor!=null) voiceProcessor.close();}); release(adm::release);
        release(()->{if(route!=null) route.close();}); release(turn::close);
        watchdog.shutdown();worker.shutdown();
        // An unavailable store must not keep the watchdog alive after native cleanup.
        try { authorization.end(); } catch(Exception unavailable) { endDeliveryFailed=true; }
    } }
    private void release(Runnable operation) {
        try { operation.run(); } catch(RuntimeException failure) { state=State.FAILED; failureStage="resource-cleanup"; }
    }
    private final class Callback implements SdpObserver {
        private final boolean create; private final Runnable applied; private final int expectedGeneration=generation;
        Callback(boolean create,Runnable applied) { this.create=create;this.applied=applied; }
        public void onCreateSuccess(SessionDescription description) { post(()->{
            try { check(); if(expectedGeneration!=generation) throw new SecurityException("Stale SDP callback"); if(!create) throw new IllegalStateException(); localDescriptionGeneration=expectedGeneration;pc.setLocalDescription(new Callback(false,()->{localReady=true;creating=false;}),description); }
            catch(Exception invalid) { fail(); }
        }); }
        public void onSetSuccess() { post(()->{ try { check(); if(expectedGeneration!=generation) throw new SecurityException("Stale SDP callback"); if(applied!=null) applied.run(); } catch(Exception invalid) { fail(); } }); }
        public void onCreateFailure(String ignored) { post(()->{failureStage="create-sdp";fail();}); }
        public void onSetFailure(String ignored) { post(()->{failureStage="apply-sdp";fail();}); }
    }
    public void onSignalingChange(PeerConnection.SignalingState ignored) {}
    public void onIceConnectionChange(PeerConnection.IceConnectionState ignored) {}
    public void onConnectionChange(PeerConnection.PeerConnectionState connection) { post(()->{
        if(connection==PeerConnection.PeerConnectionState.FAILED) { failureStage="native-connection-failed";fail(); }
        else if(connection==PeerConnection.PeerConnectionState.DISCONNECTED) {
            failureStage="native-connection-disconnected";fail(); // New explicit consent/session is required; no automatic ICE/P2P fallback.
        } else if(connection==PeerConnection.PeerConnectionState.CONNECTED) disconnectedAt=0;
    }); }
    public void onIceConnectionReceivingChange(boolean ignored) {}
    public void onIceGatheringChange(PeerConnection.IceGatheringState ignored) {}
    public void onIceCandidate(IceCandidate candidate) {
        if(++candidates>128 || candidate.sdpMid==null || candidate.sdpMid.length()>32 || candidate.sdp==null || candidate.sdp.length()>2048) {
            failureStage="native-candidate-limit";cancelLocally();return;
        }
        int expected=localDescriptionGeneration;
        post(()->{if(expected==generation)pendingLocalIce.addLast(new PendingIce(expected,candidate));});
    }
    public void onIceCandidatesRemoved(IceCandidate[] ignored) {}
    public void onAddStream(MediaStream ignored) {}
    public void onRemoveStream(MediaStream ignored) {}
    public void onDataChannel(DataChannel channel) { post(this::fail); }
    public void onRenegotiationNeeded() {}
}
