# Pinned native build investigation

This does **not** enable production voice or automatically replace the Maven pin.
The current Maven allocator follows TURN 300 ALTERNATE-SERVER without local approval.
The patch rejects all such redirects before changing the endpoint; redundancy must
use separately configured local TURN servers. Existing native redirect tests are
changed to assert rejection, unchanged destination and zero allocated candidates.
The native patch and its tests remain UNVERIFIED until the build and real AVD network
regression execute. No cryptographic primitive or DTLS validation is changed.

`bash native/webrtc/build.sh x86_64` uses a fresh `$RUNNER_TEMP` directory, Python 3.12+
and the exact source/depot revisions in the script. Upstream DEPS pins toolchain/SDK
and third-party revisions/hashes. Only remote execution tools and Linux browser ASAN
library downloads are excluded from preparation; no UMBRA suite is excluded.
The build itself disables remote execution and telemetry. Four ABIs build separately
on standard runners. Artifacts include the patch, dependency revisions, license output
and hashes. They are build artifacts, not releases or a claim of bit-for-bit reproducibility.

Before replacing the distribution: review licenses and resolved provenance; compare
common Java classes across ABI outputs; normalize packaging; update the exact inventory;
execute native certificate rejection, bidirectional Engine/HTTPS audio and the real
unauthorized redirect regression with zero alternate traffic. Repeat APK/R8/JNI checks.
Never remove `NativeDistributionPolicy` based only on this compilation succeeding.

`java-generics.patch` corrects an upstream raw `LinkedHashSet` construction to
`LinkedHashSet<>`. The pinned compiler rejects the unchecked conversion; warnings
remain errors. This does not enable video or change codec selection behavior.
