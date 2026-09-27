package app.umbra.media;

/** Offline has neither multimedia implementation nor microphone/network permission. */
public final class VoiceControls implements AutoCloseable {
    public String status() { return "No disponible offline"; }
    public boolean hasSession() { return false; }
    public void show(android.app.Activity activity,app.umbra.crypto.Engine engine,String id,
                     java.util.concurrent.Executor worker,java.util.function.Consumer<android.app.Dialog> track,Runnable changed) {
        throw new SecurityException("Internet voice is unavailable offline");
    }
    /** Same shape as the connected snapshot so shared UI compiles; offline never has one. */
    public record Snapshot(String callId,String state,String modulationStatus,boolean modulationRequested,boolean muted,
                           String videoStatus,long lastCaptureNanos,long closedNanos) {}
    public Snapshot snapshot() { return null; }
    public void setMuted(boolean value) { throw unavailable(); }
    public void requestModulation() { throw unavailable(); }
    public long modeEpoch() { return 0; }
    public void useNaturalVoice(long reviewedEpoch) { throw unavailable(); }
    public void chooseAudioOutput(android.app.Activity activity,java.util.function.Consumer<android.app.Dialog> track) { throw unavailable(); }
    public void showRemoteVideo(android.app.Activity activity,java.util.function.Consumer<android.app.Dialog> track) { throw unavailable(); }
    public void switchCamera(android.app.Activity activity,java.util.concurrent.Executor worker) { throw unavailable(); }
    public void stopVideo() { throw unavailable(); }
    public void answerVideo(android.app.Activity activity,java.util.concurrent.Executor worker,boolean accepting,int choice,Runnable changed) { throw unavailable(); }
    private static SecurityException unavailable() { return new SecurityException("Internet media is unavailable offline"); }
    @Override public void close() {}
}
