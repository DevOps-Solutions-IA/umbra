package app.umbra.media;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground voice focus and explicit communication-device choice, API31+. No SCO transport hack. */
final class VoiceAudioRoute implements AutoCloseable {
    private final AudioManager manager;
    private final int previousMode;
    private final AudioDeviceInfo previousDevice;
    private final AudioFocusRequest focus;
    private final Runnable stop;
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile int selected=-1;
    private final AudioManager.OnCommunicationDeviceChangedListener listener;
    private boolean focusGranted,listenerAdded;
    VoiceAudioRoute(Context context,Runnable stop) {
        this.stop=stop; manager=context.getSystemService(AudioManager.class);
        if(manager==null) throw new IllegalStateException("Audio service unavailable");
        previousMode=manager.getMode(); previousDevice=manager.getCommunicationDevice();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change->{if(change!=AudioManager.AUDIOFOCUS_GAIN&&!closed.get())stop.run();},new Handler(Looper.getMainLooper()))
            .build();
        listener=device->{if(!closed.get()&&selected!=-1&&(device==null||device.getId()!=selected))stop.run();};
        try {
            // target35+ also requires a top app/foreground service; no hidden-service workaround.
            focusGranted=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if(!focusGranted) throw new SecurityException("Foreground audio focus unavailable");
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            AudioDeviceInfo current=manager.getCommunicationDevice();
            if(current!=null) selected=current.getId();
            manager.addOnCommunicationDeviceChangedListener(context.getMainExecutor(),listener); listenerAdded=true;
        } catch(RuntimeException failure) { close(); throw failure; }
    }
    List<AudioDeviceInfo> available() {
        if(closed.get()) throw new SecurityException("Audio route closed");
        return List.copyOf(manager.getAvailableCommunicationDevices());
    }
    void select(int id) {
        AudioDeviceInfo device=available().stream().filter(value->value.getId()==id).findFirst()
            .orElseThrow(()->new SecurityException("Communication device unavailable"));
        int previous=selected; selected=id;
        if(!manager.setCommunicationDevice(device)) { selected=previous;stop.run();throw new IllegalStateException("Audio route selection failed"); }
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        if(listenerAdded) manager.removeOnCommunicationDeviceChangedListener(listener);
        if(focusGranted) {
            manager.clearCommunicationDevice();
            if(previousDevice!=null && manager.getAvailableCommunicationDevices().stream().anyMatch(d->d.getId()==previousDevice.getId()))
                manager.setCommunicationDevice(previousDevice);
            manager.setMode(previousMode); manager.abandonAudioFocusRequest(focus);
        }
    }
}
