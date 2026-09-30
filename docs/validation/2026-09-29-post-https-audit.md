# Post-acceptance HTTPS/backend audit — 2026-09-29

VERIFIED source base: `e0024f091d29dc15c2d788b430c5ea11204e5060`, isolated
`/tmp/umbra-post-acceptance-audit`. This review changes only focused tests and this
report. No product transport, timeout, TLS, replay, admission or backend behavior
was changed. No Android, ADB, phone, UI, commit, push or deployment was performed.

## Historical outcome: HISTORICAL_UNCONFIRMED

VERIFIED by reopening the retained archive:
`/tmp/umbra-security-content-completion/.run/security-content/7425385-ci/focused-debug-failed.zip`,
SHA-256 `c9e6234d7b20a60621b17405f82d83a655ae35be352029470b6d1dd2d23c5604`.
The `video-stop-race-1` endpoint-A stack fails reading HTTP response headers in
Android `Http1xStream.readResponse`, through `RelayClient.requestRawChecked` /
`admittedRequest` / `sendAuthorized` / `VoiceEngineFixtureListener.pump`.
The archive contains no server lifecycle receipt. The previous report associates
it with run36525583313/job109268229430 and source7425385; no fresh CI query was
made during this local subreview.

INFERRED hypotheses remain server/peer closure, connection-pool idle race,
transport interruption or cancellation. The response-header stack alone does not
identify which peer closed, whether the request committed, whether a keepalive
expired, or whether the connection was reused. No historical product cause was
demonstrated and no fix is claimed. A new Python TLS probe is not the Android
HTTP stack or a reproduction of that historical failure.

## Reviewed invariants

VERIFIED by source inspection: `RelayClient` retains one TLS socket-factory identity
per client; delegates certificate and hostname verification; bounds request/body
size; refuses redirects; uses fixed-length request streaming; closes tracked
sockets on cancellation; propagates transport IOExceptions and calls
`network.failed()`. The fresh-challenge renewal path preserves a frozen body and
original authorization, retires the previous challenge before attempting delivery,
and is bounded to one renewal. It is not a generic IOException replay path.

VERIFIED locally installed upstream sources: uvicorn0.48.0/h11 0.16.0. The observed
laboratory protocol delegates callbacks unchanged. Uvicorn schedules its existing
five-second idle keepalive after response completion and closes the transport
on idle expiration. Neither timeout nor exception handling was changed here.

VERIFIED source/backend tests: message PUT persists ciphertext and its digest in a
transaction; duplicate IDs with unchanged digest are idempotent, changed payloads
conflict; ACK inserts the retained digest and removes the pending message in one
transaction. Existing storage-failure tests exercise rollback of both ACK changes.
This is not recipient plaintext access or permission to ACK after local failure.
The existing client path calls receive before ACK and distinguishes vault failure.
No UI code was changed or executed.

## New bounded causal probes and results

`relay/tests/test_post_https_framing.py` uses the real isolated relay and verified
TLS against the exact ephemeral synthetic certificate. It introduces no production
endpoint, trust override, retry, timeout extension or background workload.

- VERIFIED: six health responses reuse the identical TLS socket, each has exactly
  its declared Content-Length and valid JSON; a seventh explicit-close response
  completes and closes. The lifecycle receipt records seven complete responses
  without idle expiration or incomplete response.
- VERIFIED: conflicting Content-Length headers receive400 and connection closure;
  a separate subsequent health request succeeds. The server remains alive.
  A malformed request before an ASGI cycle can have zero completed application
  responses and `incompleteResponse=false`; this flag is not a complete parser
  error classifier.
- VERIFIED: the pre-existing real-TLS idle test observes the unchanged five-second
  keepalive closure, without an incomplete response.

First focused run:1 failed/2 passed. The new explicit-close test incorrectly
required `transportError=false`. All seven responses had already completed and
parsed successfully; the server still recorded a transport error during Python client closure.
INFERRED explanation: `http.client` closes the socket without an explicit TLS
`unwrap()` shutdown; no error-string capture was added to prove that explanation. That assertion was
replaced with validation of its boolean schema; complete frames, socket reuse,
closure and incomplete-response checks remain required. This explicitly preserves
the observation that a transport-error flag alone does not demonstrate application
failure. No product change was made to make the test pass.

First full-suite command lacked `PYTHONPATH=relay` and stopped during collection
with8 import errors; no behavioral result is attributed to that invocation.
Correct invocation:

```sh
PYTHONPATH=relay /tmp/umbra-private-startup/.venv/bin/python -m pytest relay/tests -q
/tmp/umbra-private-startup/.venv/bin/python -m unittest discover -s scripts/tests -p test_voice_http_diagnostics.py -v
```

VERIFIED:207 backend tests passed in19.40s, including both new TLS framing tests
and existing idle lifecycle test. Existing Starlette/httpx deprecation warning
remains unsuppressed.2 diagnostic unit tests passed separately; these are synthetic
counter/schema checks, not2 extra transport acceptance cases.

Required future causal evidence is a failure-time client/server correlation on the
same connection and request stage, with cancellation/deadline and response/close
ordering preserved without headers, tokens, message bodies or private identifiers.
Later successful scenarios cannot retroactively establish why the old EOF occurred.

## Production JVM/HTTPS regression after restored build

VERIFIED: after the coordinating agent reported its restored Android build complete,
the following command completed exit0 on the current checkout. The script compiled
production Java sources afresh with JDK21; the previous worktree supplied dependency
JAR paths only, not previously compiled product classes. RelayClient, integration
script and backend were compared with e0024f0 with no differences.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
/tmp/umbra-private-startup/.venv/bin/python scripts/test_relay_integration.py \
  --classpath-file /tmp/umbra-security-content-completion/android/app/build/integration/classpath.txt
```

Dependency classpath-file SHA-256:
`f443d6b4238881a378728b5e211007b99ede91c8343b3db417bbfa9ebaddb983`.
The script reported50 real HTTPS integration checks passed, plus its named device
and admission subscenarios. These include real Signal JNI, synthetic SQLite,
verified TLS, wrong-hostname rejection, response bounds, persistence/ACK/deduplication,
immutable ciphertext after restart, eight canceled-read samples, emergency closure,
restricted-content delivery and independent per-device admission/revocation.
The isolated relay remained healthy. This is JVM/loopback TLS evidence, not Android
HTTP pooling, Android process death, Keystore hardware, Bluetooth or the historical
EOF's causal resolution.
