# Preserve JNI bindings and store callback interfaces used by libsignal.
-keep class org.signal.** { *; }
-keep interface org.signal.** { *; }
-keep class app.umbra.crypto.SignalStore { *; }
-dontwarn javax.annotation.**

# WebRTC JNI uses Java class/method names and native-thread callbacks from the pinned SDK.
# This keeps only vendor bindings; laboratory adapters are still absent from release sources.
-keep class org.webrtc.** { *; }

# The pinned M150 JNI_OnLoad also enters JNI Zero, outside org.webrtc. Preserve
# only annotated native entry points, following upstream jni_zero/proguard.flags.
# Without this, optimized voice aborts with ClassNotFoundException: JniZero.
-keepclasseswithmembers,includedescriptorclasses,allowaccessmodification,allowoptimization class org.jni_zero.** {
    @org.jni_zero.CalledByNative <methods>;
}
