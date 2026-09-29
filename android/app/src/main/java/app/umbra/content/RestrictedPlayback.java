package app.umbra.content;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;

/** One locally consented playback. No seek, replay, file, URI, MediaSession or export API. */
public final class RestrictedPlayback implements AutoCloseable {
    public enum State { READY, ROUTING, PLAYING, COMPLETED, INTERRUPTED, FAILED, CLOSED }
    private final RestrictedContentService.Session session;
    private final AudioManager audio;
    private final AudioDeviceInfo selected;
    /** UI owns presentation and must protect it before construction and clear on close.
     * Ownership transfers to this playback; cleanup runs off the UI thread. */
    public interface VideoOutput extends AutoCloseable {
        android.view.Surface surface();
        @Override void close() throws Exception;
    }
    private final VideoOutput video;
    private boolean hasAudio=true;
    private final java.util.concurrent.atomic.AtomicLong firstVideoFrame=new java.util.concurrent.atomic.AtomicLong();
    private final Handler callbacks=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private MemoryMediaSource source;
    private final java.util.concurrent.atomic.AtomicReference<State> state=new java.util.concurrent.atomic.AtomicReference<>(State.READY);
    private volatile boolean released;
    private boolean routesRegistered;
    private final Runnable routingTimeout=()->{if(state.get()==State.ROUTING)interrupt();};
    private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
    private final AudioDeviceCallback routes=new AudioDeviceCallback() {
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            for(var device:devices)if(selected!=null && device.getId()==selected.getId())interrupt();
        }
    };
    /** Call off the UI thread after persistent consume; route choice never grants vault access. */
    public RestrictedPlayback(Context context,RestrictedContentService.Session session,AudioDeviceInfo selected)throws Exception {
        this(context,session,selected,null);
    }
    /** File-video only, no camera/WebRTC. No UI or external viewer is created here.
     * For a silent clip selected may be null; an audio track always requires an explicit route. */
    public RestrictedPlayback(Context context,RestrictedContentService.Session session,AudioDeviceInfo selected,VideoOutput video)throws Exception {
        this.session=java.util.Objects.requireNonNull(session);this.selected=selected;this.video=video;
        audio=context.getSystemService(AudioManager.class);
        try {
            if((video==null && session.format()!=RestrictedPayload.Format.AAC_ADTS) ||
                    (video!=null && session.format()!=RestrictedPayload.Format.AVC_MP4))throw RestrictedPayload.invalid();
            session.decode(bytes->{
                try {
                    if(video!=null) {
                        hasAudio=RestrictedVideo.playbackAudio(bytes,()->{try{session.check();}catch(Exception denied){throw RestrictedPayload.invalid();}});
                        if(video.surface()==null || !video.surface().isValid())throw RestrictedPayload.invalid();
                    }
                    if(hasAudio) {
                        if(selected==null || !selected.isSink())throw RestrictedPayload.invalid();
                        boolean available=false;for(var device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(device.getId()==selected.getId())available=true;
                        if(!available)throw RestrictedPayload.invalid();
                    }
                    player=new MediaPlayer();
                    AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build();
                    player.setAudioAttributes(attributes);player.setLooping(false);player.setVolume(0f,0f);
                    if(video!=null)player.setSurface(video.surface());
                    source=new MemoryMediaSource(bytes,()->{try{session.check();}catch(Exception failure){throw new ContentException(ContentException.Code.EXPIRED);}});
                    player.setDataSource(source);player.prepare();
                    // Native MediaPlayer has no underlying player/output before setDataSource.
                    // Select only after preparation, still muted and before start/focus.
                    if(hasAudio && !player.setPreferredDevice(selected))throw RestrictedPayload.invalid();
                    if(player.getDuration()<1 || player.getDuration()>(video==null?10000:3000))throw RestrictedPayload.invalid();
                    if(video!=null) {
                        RestrictedVideo.dimensions(player.getVideoWidth(),player.getVideoHeight());
                        player.setOnInfoListener((ignored,what,extra)->{
                            if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                try {session.use(()->{
                                    firstVideoFrame.compareAndSet(0,System.nanoTime());
                                    if(!hasAudio && state.compareAndSet(State.ROUTING,State.PLAYING))callbacks.removeCallbacks(routingTimeout);
                                });}catch(Exception denied){interrupt();}
                            }
                            return false;
                        });
                    }
                    player.setOnCompletionListener(ignored->{terminal(State.COMPLETED);session.close();});
                    player.setOnErrorListener((ignored,what,extra)->{terminal(State.FAILED);session.close();return true;});
                    if(hasAudio)player.addOnRoutingChangedListener(route->{
                        try {session.use(()->{
                            AudioDeviceInfo actual=route.getRoutedDevice();
                            if(state.get()==State.ROUTING && actual!=null && actual.getId()==selected.getId()) {
                                if(state.compareAndSet(State.ROUTING,State.PLAYING)){callbacks.removeCallbacks(routingTimeout);player.setVolume(1f,1f);}
                            } else if(state.get()==State.PLAYING && (actual==null || actual.getId()!=selected.getId())) {
                                player.setVolume(0f,0f);interrupt();
                            }
                        });} catch(Exception denied){interrupt();}
                    },callbacks);
                    if(hasAudio)focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener(change->{if(change!=AudioManager.AUDIOFOCUS_GAIN)interrupt();},callbacks).build();
                    if(hasAudio){audio.registerAudioDeviceCallback(routes,callbacks);routesRegistered=true;}return this;
                } catch(Exception | Error failure) {
                    try {release();}catch(RuntimeException cleanupFailure){session.cleanupFailed();}
                    throw failure;
                }
            },RestrictedPlayback::release);
        } catch(Exception | Error failure) {
            terminal(State.FAILED);
            try{release();}catch(RuntimeException cleanupFailure){session.cleanupFailed();}
            session.close();throw failure;
        }
    }
    public void start()throws Exception {
        if(!started.compareAndSet(false,true) || state.get()!=State.READY || released)throw RestrictedPayload.invalid();
        session.check();
        if(hasAudio && audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {interrupt();throw new ContentException(ContentException.Code.CONSENT_REQUIRED);}
        try {session.use(()->{if(!state.compareAndSet(State.READY,State.ROUTING))throw RestrictedPayload.invalid();player.start();callbacks.postDelayed(routingTimeout,1000);});}
        catch(Exception failure){terminal(State.FAILED);session.close();throw failure;}
    }
    public State state(){return state.get();}
    /** Zero means no native rendering-start callback; never an inferred visible frame. */
    public long firstVideoFrameNanos(){return firstVideoFrame.get();}
    private void terminal(State outcome){state.updateAndGet(previous->switch(previous){
        case READY,ROUTING,PLAYING -> outcome; default -> previous;
    });}
    private void interrupt(){terminal(State.INTERRUPTED);session.close();}
    private synchronized void release() {
        if(released)return;released=true;
        boolean failed=false;
        try {if(player!=null)player.release();}catch(RuntimeException failure){failed=true;}
        try {if(video!=null)video.close();}catch(Exception failure){failed=true;}
        try {callbacks.removeCallbacks(routingTimeout);}catch(RuntimeException failure){failed=true;}
        try {if(source!=null)source.close();}catch(RuntimeException failure){failed=true;}
        try {if(routesRegistered)audio.unregisterAudioDeviceCallback(routes);}catch(RuntimeException failure){failed=true;}
        try {if(focus!=null)audio.abandonAudioFocusRequest(focus);}catch(RuntimeException failure){failed=true;}
        terminal(failed?State.FAILED:State.CLOSED);
        if(failed)throw new IllegalStateException("Restricted playback closure failed");
    }
    @Override public void close(){session.close();}
}
