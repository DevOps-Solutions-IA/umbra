# Physical PDF/AAC corrections — checkpoint, master v4 remains open

Base published before these changes: `a8e6f0d31380cdc632d385074aa92b729ee01dfc`. Tested worktree includes the pending video adapter; the APK hashes below identify actual installed bytes, not a claim of clean-commit acceptance.

## Reproduction and correction

PDF: physical attempts 05–07 failed valid preparation. Fixed diagnostic from 07: `PDF_WAIT_CONNECTED_RESULT_DEAD_CANCELLED`. The isolated parser connected, supplied a result, and died; `onBindingDied` called cancel, racing the executor observing result and Binder-death futures. Binding death from this intentionally single-job process is not user/lease cancellation. The callback no longer cancels. Success still requires an authenticated result AND actual Binder death, current authorization, and the unchanged 12-second bound; explicit/emergency cancellation remains terminal. Attempt 08 passes both previously failing PDF cases; malformed/page-limit/recovery and invalid-consent cases remain enabled.

AAC: the pinned AOSP `c2.android.aac.encoder` and decoder are reported by the phone. Before correction the sanitized one-second synthetic note decoded to 24576 samples (24 access units); the final marker was at sample 19456, outside the fixed last-4096 window. This is not absent content or an OEM codec substitution. The application was appending its explicit two-frame lookahead drain AND retaining newer C2 EOS flush padding, compounding on decode/re-encode. Output is now capped to frame-aligned input plus the existing two-frame drain, while still consuming/validating native output to EOS. No signal-level trimming, assertion reduction or timeout change. Android source comparison supports the native EOS flush difference; this is not a claim of a byte-identical vendor source build.

Source: https://android.googlesource.com/platform/frameworks/av/+/master/media/codec2/components/aac/C2SoftAacEnc.cpp (EOS flush calls AAC with negative input count). Existing FDK delay/profile remains documented in ADR-restricted-audio.

The unchanged end-marker, RMS, frequency, duration, Signal delivery, duplicate, SQLite consume/reopen assertions pass in attempt 09. No microphone, camera, GPS, audible playback or personal data used.

## Physical evidence

| Attempt | Result | App APK SHA-256 | Test APK SHA-256 |
|---|---|---|---|
| 07 | 5 PASS / 3 FAIL | `d25abc1af330350a7bfea4dfec523d35772ed087028b37553b8a1e41cb7a33f0` | `65c938ce331a6c704de750a201c04d432b4f7632d83865eb8052cd4c42569f9e` |
| 08 | 7 PASS / 1 FAIL | `dc4ce0b97bcc9a4cb180ad082ec7d17c55175682849a0ffe5f7ae6ec495154ca` | `65c938ce331a6c704de750a201c04d432b4f7632d83865eb8052cd4c42569f9e` |
| 09 | 8 PASS / 0 FAIL | `e7fac2a406333365ab3a47600c023d3429d34a70ea07e85073e795f6ee44c4ac` | `65c938ce331a6c704de750a201c04d432b4f7632d83865eb8052cd4c42569f9e` |

Command: activate `.venv`; JDK 21; Gradle 8.13 / fixed project dependencies, `assembleOfflineDebug assembleOfflineDebugAndroidTest` (exit 0), then `scripts/run_physical_tests.py --safe --execute --update-owned --flavor offline` with explicit privately stored Windows ADB target/localhost port 5038. Runner exit 1 for 07/08, exit 0 for 09. Every result retained locally; no serial published.

Xiaomi Android 16/API36, ARM64, patch 2026-05-01. A fresh non-authenticated synthetic key reports TEE and passes AES-GCM/tamper, then its own alias is removed. This does NOT validate authenticated production Vault/biometric protection. App and test applicationIds are isolated `.offline.dev`; APK policy and Signal JNI checks pass before installation. No INTERNET, ACCESS_NETWORK_STATE, microphone or camera permissions.

## Preservation and limits

Full recoverable bundle plus pending patch and sanitized physical receipts exported to Windows `Downloads/UMBRA_RESPALDOS_CODEX/umbra-security-content-a8e6f0d-physical-fixes-checkpoint.tar.gz`, SHA-256 `5d9aba3083db50ea0007297fe1e0065224302ffffa12e17424f449da56f692be`. Isolated clone, `git fsck --full`, patch applicability/application and exact diff verified. Earlier backups preserved.

This is not full A/B/C acceptance. New commit CI, connected/R8 physical cases, video presentation, lifecycle matrix and final Claude API freeze remain pending. One phone is not two physical peers. No Claude production UI changed.
