package app.umbra.calls;
/** Compile-time connected policy; no runtime override in the offline APK. */
public final class CallPlatform {
    private CallPlatform() {}
    public static final boolean ENABLED = true;
}
