# Dependency review — 2026-09-19 UTC

VERIFIED — Public package coordinates only sent to OSV/PyPI; no source, identities or credentials uploaded. Metadata preserved in `osv-initial.json`, `osv-details.json`, `osv-updated.json`, `python-release-metadata.json`.

Updates are minimum fixed releases for returned advisories, not a general latest-version upgrade:

| Package | Before | After | Reason |
|---|---|---|---|
| anyio | 4.13.0 | 4.14.2 | GHSA-5p39-cfhj-2xmp, GHSA-82r6-8w77-94w6 |
| click | 8.1.8 | 8.3.3 | PYSEC-2026-2132 / CVE-2026-7246 |
| pytest | 9.0.2 | 9.0.3 | GHSA-6w46-j5rx-g56g |
| starlette | 0.50.0 | 1.3.1 | Five distinct GHSAs returned, latest fixed floor 1.3.1 |
| fastapi | 0.128.2 | 0.133.0 | First release adding compatibility with Starlette 1.x; 0.132 still caps <1.0 |

VERIFIED — Primary release notes consulted:
- https://github.com/agronholm/anyio/releases/tag/4.14.2
- https://github.com/pallets/click/releases/tag/8.3.3
- https://github.com/pytest-dev/pytest/releases/tag/9.0.3
- https://github.com/Kludex/starlette/releases/tag/1.3.1
- https://github.com/fastapi/fastapi/releases/tag/0.133.0
- https://github.com/Kludex/starlette/security/advisories/GHSA-86qp-5c8j-p5mr

NOT_VERIFIED — Advisory presence is not a demonstrated exploit against this relay. Static inspection did not find relay use of Click.edit, AnyIO process pools, Starlette form parsing, StaticFiles or HTTPEndpoint subclasses. Patches still remove affected dependency versions. Scanner absence is not absence of vulnerabilities.

VERIFIED — OSV after updates returned zero advisory matches for 22 Python coordinates and 12 Maven coordinates listed in `osv-updated.json`. Maven covers direct app/test dependencies, libsignal Kotlin runtime graph from cached published POMs, annotations, Hamcrest and desugaring. NOT_VERIFIED — full AGP/build tool graph, native Rust/C transitive advisories and independent supply-chain audit are not included. The resolved Android graph follow-up below expands this Maven coverage.

VERIFIED — Added runtime `requirements-hashed.lock` and `requirements-test.lock` (runtime include plus test graph), SHA-256 from exact PyPI release metadata, wheels only. Runtime includes conditional Windows colorama; pydantic-core hashes cover published CPython 3.12/3.13 wheels. These hashes detect substitutions against committed values, not upstream authenticity or absence of malicious code. Existing `requirements.lock` remains an unhashed constraints file; `-c` installation alone is not integrity enforcement.

Executed (Python 3.13.12 local venv):
1. `. .venv/bin/activate; python -m pip install --dry-run --ignore-installed --report /tmp/umbra-audit-20260919/updated-deps-resolve.json -c relay/requirements.lock -r relay/requirements-test.txt` exit 0.
2. Activated venv; `python -m pip install -c relay/requirements.lock -r relay/requirements-test.txt` exit 0; `python -m pip check` exit 0.
3. `python -m pip download --require-hashes -r relay/requirements-test.lock --dest /tmp/umbra-audit-20260919/wheels313` exit 0; all 21 applicable Linux packages downloaded and hashes checked.
4. First CPython 3.12 download command omitted CLI --only-binary and pip rejected options before resolution (exit 1), even though requirement file includes that directive. Corrected command: `python -m pip download --require-hashes --only-binary=:all: -r relay/requirements-test.lock --python-version 3.12 --dest /tmp/umbra-audit-20260919/wheels312` exit 0. This is compatibility resolution/artifact verification, not execution under Python 3.12.

NOT_VERIFIED — Windows/macOS/other architectures are represented by upstream published hashes, not executed here. Backend coordinator runs product suite after these updates; root owns final full validation and container rebuild.

VERIFIED — Backend agent reported the updated dependency suite: 104 passed in 5.25s, exit 0. One upstream StarletteDeprecationWarning remains for httpx TestClient support. No tests were excluded or warnings suppressed. Proposed migration to httpx2 2.0.0 was NOT installed: OSV reports vulnerabilities there; a safe upgrade requires at least httpx2 2.12.0 and a broader transitive graph. Current httpx 0.28.1 has no OSV matches and remains supported by the pinned Starlette fallback. VERIFIED — This stabilization retains the supported fallback warning; no global warning suppression was added.


## Resolved Android runtime and instrumentation follow-up

VERIFIED — Executed both Gradle dependency reports with JDK 21.0.11, Gradle 8.13 and
`ANDROID_HOME=ANDROID_SDK_ROOT=/mnt/c/Android/sdk-linux`, each exit 0:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 .umbra-tools/gradle-8.13/bin/gradle --no-daemon -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 -p android :app:dependencies --configuration connectedDebugAndroidTestRuntimeClasspath
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 .umbra-tools/gradle-8.13/bin/gradle --no-daemon -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 -p android :app:dependencies --configuration connectedDebugRuntimeClasspath
```

VERIFIED — Extracted selected versions after Gradle `->` resolution, including BOM/module
coordinates and de-duplicating repeated trees: **19 instrumentation coordinates**, **14 app
runtime coordinates**, **28 unique coordinates**. For example, annotations resolves from
13.0 to 23.0.0, monitor from 1.7.0/1.7.1 to 1.7.2, and all kotlin-stdlib references to 2.2.20.
Sent only these public package names and versions to the [OSV batch API](https://api.osv.dev/v1/querybatch),
using its [documented query format](https://google.github.io/osv.dev/post-v1-querybatch/).
All 28 results returned without advisory matches at the recorded query time.

The exact resolved inventory, query timestamp, responses and SHA-256 hashes of the two
local Gradle reports are retained in
[2026-09-19-android-dependencies-osv.json](2026-09-19-android-dependencies-osv.json).
Local full reports: `/tmp/umbra-audit-20260919/androidtest-dependencies.log` and
`/tmp/umbra-audit-20260919/android-runtime-dependencies.log`; extraction/query command:
`python3 /tmp/umbra-audit-20260919/audit_resolved_android.py` (exit 0).

NOT_VERIFIED — This is an advisory database lookup of the resolved app/instrumentation
Java dependency graph, not a vulnerability audit or execution test. No version was changed
as a result of this follow-up. AGP/Gradle plugin build dependencies, libsignal native Rust/C
transitives, JNI implementation review, all offline/release configurations and complete SBOM
coverage are not established by these two reports. The instrumentation graph includes
`androidx.annotation:annotation:1.7.0-beta01` selected by AndroidX Test; this pre-release
transitive is documented, not silently upgraded. Local package hashing is not an independent
upstream signature or provenance verification.
