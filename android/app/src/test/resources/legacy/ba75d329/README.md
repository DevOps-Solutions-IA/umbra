# Exact historical parser fixture (test resources only)

Source repository: DevOps-Solutions-IA/umbra, MIT (see repository LICENSE).
Commit: ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1.
Path: android/app/src/main/java/app/umbra/protocol/Wire.java.
Git blob: ea27fa8482c33302ecb755315cdb142d17377129.
SHA-256: ea9d801633b605f9b2cef27cb544683f9d26b80370f4f1463e75b75a76e1f845.

Wire.java.txt is a byte-for-byte `git show` extraction. The JVM test verifies its
hash, compiles this parser in a temporary directory using the active pinned JDK,
and launches an isolated Java process whose first classpath entry is that directory.
Current unchanged dependency APIs support compilation; the legacy Wire parser
itself is exact. There is no runtime Git fetch, network call, production class,
Android package or claim of an entire historical APK executed.

The current sender Engine produces synthetic restricted and text Signal messages;
the test decrypts them using real libsignal and feeds their actual wire content to
the historical parser. Text must pass; restricted must fail with the historical
unsupported-kind rejection, before it could become an ordinary attachment.
