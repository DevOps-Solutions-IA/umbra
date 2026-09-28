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
    private final Handler callbacks=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private MemoryMediaSource source;
    private volatile State state=State.READY;
    private volatile boolean released;
    private boolean routesRegistered;
    private final Runnable routingTimeout=()->{if(state==State.ROUTING)interrupt();};
    private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
    private final AudioDeviceCallback routes=new AudioDeviceCallback() {
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            for(var device:devices)if(device.getId()==selected.getId())interrupt();
        }
    };
    /** Call off the UI thread after persistent consume; route choice never grants vault access. */
    public RestrictedPlayback(Context context,RestrictedContentService.Session session,AudioDeviceInfo selected)throws Exception {
        this.session=session;this.selected=java.util.Objects.requireNonNull(selected);
        audio=context.getSystemService(AudioManager.class);
        if(session.format()!=RestrictedPayload.Format.AAC_ADTS || !selected.isSink())throw RestrictedPayload.invalid();
        boolean available=false;for(var device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(device.getId()==selected.getId())available=true;
        if(!available)throw RestrictedPayload.invalid();
        try {
            session.decode(bytes->{
                player=new MediaPlayer();
                try {
                    AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build();
                    player.setAudioAttributes(attributes);player.setLooping(false);player.setVolume(0f,0f);
                    source=new MemoryMediaSource(bytes,()->{try{session.check();}catch(Exception failure){throw new ContentException(ContentException.Code.EXPIRED);}});
                    player.setDataSource(source);player.prepare();
                    // Native MediaPlayer has no underlying player/output before setDataSource.
                    // Select only after preparation, still muted and before start/focus.
                    if(!player.setPreferredDevice(selected))throw RestrictedPayload.invalid();
                    if(player.getDuration()<1 || player.getDuration()>10000)throw RestrictedPayload.invalid();
                    player.setOnCompletionListener(ignored->{state=State.COMPLETED;session.close();});
                    player.setOnErrorListener((ignored,what,extra)->{state=State.FAILED;session.close();return true;});
                    player.addOnRoutingChangedListener(route->{
                        try {session.use(()->{
                            AudioDeviceInfo actual=route.getRoutedDevice();
                            if(state==State.ROUTING && actual!=null && actual.getId()==selected.getId()) {
                                callbacks.removeCallbacks(routingTimeout);player.setVolume(1f,1f);state=State.PLAYING;
                            } else if(state==State.PLAYING && (actual==null || actual.getId()!=selected.getId())) {
                                player.setVolume(0f,0f);interrupt();
                            }
                        });} catch(Exception denied){interrupt();}
                    },callbacks);
                    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener(change->{if(change!=AudioManager.AUDIOFOCUS_GAIN)interrupt();},callbacks).build();
                    audio.registerAudioDeviceCallback(routes,callbacks);routesRegistered=true;return this;
                } catch(Exception | Error failure) {release();throw failure;}
            },RestrictedPlayback::release);
        } catch(Exception | Error failure) {state=State.FAILED;session.close();throw failure;}
    }
    public void start()throws Exception {
        if(!started.compareAndSet(false,true) || state!=State.READY || released)throw RestrictedPayload.invalid();
        session.check();
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {interrupt();throw new ContentException(ContentException.Code.CONSENT_REQUIRED);}
        try {session.use(()->{state=State.ROUTING;player.start();callbacks.postDelayed(routingTimeout,1000);});}
        catch(Exception failure){state=State.FAILED;session.close();throw failure;}
    }
    public State state(){return state;}
    private void interrupt(){if(state!=State.COMPLETED && state!=State.CLOSED)state=State.INTERRUPTED;session.close();}
    private synchronized void release() {
        if(released)return;released=true;
        try {if(player!=null)player.release();}
        finally {
            callbacks.removeCallbacks(routingTimeout);
            if(source!=null)source.close();if(routesRegistered)audio.unregisterAudioDeviceCallback(routes);
            if(focus!=null)audio.abandonAudioFocusRequest(focus);
            if(state==State.READY || state==State.ROUTING || state==State.PLAYING)state=State.CLOSED;
        }
    }
    @Override public void close(){session.close();}
}
