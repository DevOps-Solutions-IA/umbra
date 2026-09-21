# Preserve JNI bindings and store callback interfaces used by libsignal.
-keep class org.signal.** { *; }
-keep interface org.signal.** { *; }
-keep class app.umbra.crypto.SignalStore { *; }
-dontwarn javax.annotation.**

# WebRTC JNI uses Java class/method names and native-thread callbacks from the pinned SDK.
# This keeps only vendor bindings; laboratory adapters are still absent from release sources.
-keep class org.webrtc.** { *; }
