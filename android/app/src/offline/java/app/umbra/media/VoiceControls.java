package app.umbra.media;

/** Offline has neither multimedia implementation nor microphone/network permission. */
public final class VoiceControls implements AutoCloseable {
    public String status() { return "No disponible offline"; }
    public boolean hasSession() { return false; }
    public void show(android.app.Activity activity,app.umbra.crypto.Engine engine,String id,
                     java.util.concurrent.Executor worker,java.util.function.Consumer<android.app.Dialog> track,Runnable changed) {
        throw new SecurityException("Internet voice is unavailable offline");
    }
    @Override public void close() {}
}
