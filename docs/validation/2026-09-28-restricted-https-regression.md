# Restricted content HTTPS integration / source inventory regression

Parent: 86b2a5e04ca4c8e4d6706dd32f151d5ff782897b.
Verify run 36439675974 on earlier 65c551d failed in job 108986877550 before
HTTPS execution: javac could not resolve app.umbra.content. The integration
launcher manually enumerates pure JVM sources; it had not added Engine's new
ContentException, RestrictedPayload and RestrictedContentService dependencies.
This is a regression in this branch, not a Maven/network error. Failed log retained.

Correction adds exactly those three domain sources, leaving Android codecs in
the Android laboratory, and adds RestrictedRelayIntegration to the existing real
HTTPS acceptance. This script fixture creates a small synthetic PNG with JDK
ImageIO and calls the package-internal preparation constructor. It does not
replace or claim to execute Android sanitization/rendering or production capture.
It is not packaged into any APK.

Executed with activated Python venv, JDK 21 and exact Gradle dependency classpath:
- python scripts/test_relay_integration.py: exit 0 after source repair.
- python scripts/test_relay_integration.py --classpath-file
  android/app/build/integration/classpath.txt: exit 0 with new regression.

Existing 50 top-level HTTPS checks plus existing device/admission subscenarios
passed. New restricted assertions cover immutable duplicate delivery, authenticated
Signal descriptor, exclusion from ordinary history/export, durable consumed state
against duplicated Signal ingress, ACK removal and ciphertext immutability.
The fixture encoding check is setup, not an additional product behavior test.
Remote relay uses real isolated SQLite and verified localhost TLS; Engines use
memory transactions. This is not Android vault durability, actual process death,
RFCOMM, photo display or hardware acceptance.

Separate previous R8 correction eeef875 was verified by run 36440644808:
privacy debug and R8 both SUCCESS, connected and offline. Checkout
0b953c67b8499858f9afcd9d874dcd50c9a5dc80. Those results do not validate later code.
Artifacts (GitHub-reported archive SHA-256):
- 10978456739 R8: b4595b59fb800a49175521e3a6130331ec96b1a2d1f6673cbfbedb8c13f9ac58
- 10977911903 debug: e91b97a5606658bc1d42f563139c434592611a45fa88de9029bbce7a4304b66e

No UI changes. Master A/B/C remains open; no media hardware or full acceptance claim.
