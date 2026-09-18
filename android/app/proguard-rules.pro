# Preserve JNI bindings and store callback interfaces used by libsignal.
-keep class org.signal.** { *; }
-keep interface org.signal.** { *; }
-keep class app.umbra.crypto.SignalStore { *; }
-dontwarn javax.annotation.**
