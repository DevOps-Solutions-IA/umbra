# Sexta entrega — video — trabajo en curso (2026-09-21 UTC)

**PARTIAL — aceptación final pendiente.** Hay implementación y ejecuciones de
video/audio sintéticos nativos en dos AVD, incluido un arnés R8 aislado. Los apartados
siguientes conservan los resultados y fallos de cada árbol intermedio. La CI del
último HEAD publicado no está verde; no se declara terminada la entrega ni probado
hardware físico. Los resultados posteriores no validan retroactivamente otro binario.

## Base y aislamiento

PR #8 consultada OPEN/DRAFT en `a11b5797ec9a8c81890ef9ea62efb3d22eaf13ed`.
Árbol limpio antes de crear `codex/turn-video-core`; no existía rama local/remota.
Nueva [PR #9](https://github.com/DevOps-Solutions-IA/umbra/pull/9), borrador hacia
`codex/turn-voice-core`. No cambios en main o ramas anteriores, ni auto-merge.

## Ejecuciones nuevas de la base

Python3.13.12, JDK21.0.11, Gradle8.13, AGP8.13.2, compile/target36,
build-tools35.0.0, Signal0.102.3; AAR WebRTC revisado sin sustituir:
`bbc5675f91b31f901e1a482b00991a36ac2b3d912d2782b80e1cc1b756b1c413`.

Con venv activado y JAVA_HOME/PATH/ANDROID_HOME configurados:

| Comando | Resultado nuevo |
|---|---|
| `bash scripts/codex_setup.sh` | 0 |
| `bash scripts/test_local.sh` | 0;149 backend,105 escenarios Java;12 guardas estáticas separadas |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | 0;126 pruebas de base |
| `python scripts/repository_guard.py --git-history` | 0;256 archivos,483 blobs |
| `python scripts/build_android.py --check-only` | 0;preflight, no compilación |
| `python scripts/build_android.py --release` | 0;144/117 JVM, debug/release/lint y políticas APK |

Baseline nuevo en Actions **35625489078** sobre a11b579, inicialmente EN CURSO.
Los 15 escenarios de voz se ejecutan allí; no atribuir resultados antes de consultar
su conclusión. No es la CI de los cambios nuevos.

## Pruebas C++ añadidas

Commit `e67762f`: ejecutor `native/webrtc/test-turn.sh` sobre la fuente/depot/patch
fijados. Construye `rtc_p2p_unittests` para Linux x64 y ejecuta las suites
`TurnPortTest.*` y `TurnPortWithMockDnsResolverTest.*`, incluso el test deshabilitado
upstream. No se declara toda la suite WebRTC ejecutada. Verificador exige resultados
completados y las 18 regresiones de redirección; rechaza vacío, repetidos, error y skip.
Tres pruebas de ese verificador pasaron; no equivalen a pruebas C++ o red.

Actions **35625739825**, fuente e67762f: reconstrucción cuatro ABI y ejecución C++
EN CURSO al iniciar este recibo. El AAR de la aplicación no se reemplaza por estos
artefactos automáticamente. C++ usa sockets/tiempo virtuales upstream, distintos de
la observación de tráfico de dos AVD existente.

## Laboratorio R8 — intentos iniciales (resultado posterior al final)

`-PumbraMediaLab=true` crea un target separado `app.umbra.privatechat.medialab`,
firma sintética debug, `debuggable=false`, minificación/optimización/shrinking de
release. AndroidTest permanece en APK separado; no hay SQLite sintético o CA de
laboratorio en el target ni en release. Keystore productivo permanece sin cambios.
El host usa `su` al UID exacto del paquete sintético en AOSP AVD comprobado; no hace
debuggable el target ni utiliza un dispositivo físico. No es el APK productivo exacto.

Guardas verifican identidad, flags, ausencia de clases de laboratorio en DEX/mapping,
clases Engine/CallService/NativeVoiceSession ofuscadas presentes en DEX y ausencia
de `dontoptimize/dontobfuscate/dontshrink`. Registran hash APK/configuración/mapping;
su salida declara explícitamente que no ejecutó media.

Fallos conservados, no resultados verdes:
- Primera compilación: R8 del APK de tests no encontraba dos anotaciones ErrorProne
  de AndroidX. Dependencia de anotaciones fijada 2.28.0 en androidTestCompileOnly.
  Usarla como implementation produjo una referencia JDK ausente; no se suprimió.
- Primera ejecución: runner cayó por `kotlin.jvm.internal.Lambda` ausente.
- Segunda ejecución: runner cayó por `kotlin.LazyKt` ausente. Reglas limitadas a
  Lambda, fachadas Lazy y su interfaz, solo en mediaLab, permiten optimización.
  Ninguna clase UMBRA se conserva mediante una regla general.

La compilación posterior y su ejecución requieren resultados propios; un mapa R8
no acredita audio. Se añadieron guardas negativas de evidencia; 130 pruebas de
herramientas pasaron en el árbol de trabajo. No inflar ese número como pruebas
multimedia. Logs de trabajo bajo `/tmp/umbra-video-*`; no publicar PCM, credenciales
TURN, SDP o capturas de red sin sanitizar.

## Continuación pendiente

Cerrar aceptación R8 y resultados C++; diseñar/implementar consentimiento y generaciones
de video en el mismo Engine/PeerConnection; pipeline de frames bidireccionales y cámara
AVD; TLS/IPv6 por combinación ejecutable; medidas monotónicas desde solicitud de cierre;
regresiones y CI final. No ampliar plazos ni permisos offline. No cámara/micrófono físicos,
hardware Keystore, auditoría independiente o muerte durante commit acreditados.

## Recibo de baseline terminado

Actions **35625489078** terminó con repository-guard, relay-and-core,
relay-container y android SUCCESS. Al ser workflow_dispatch, HEAD y commit.txt
son ambos **a11b5797ec9a8c81890ef9ea62efb3d22eaf13ed**; no es un merge sintético.
Se descargaron y comprobaron 15 `voice-evidence.json`, uno por escenario vigente,
en `/tmp/umbra-video-baseline-ci-35625489078`. Estos resultados son referencia
de base y NO validan el arnés R8 nuevo o video.

Hashes de los APK de esa ejecución nueva:
- connected debug `c8031f5e0f9d1dda37bd3c29b342be9071170fb5a566e920b712b149ae109023`
- offline debug `3492a1681665a8d8847780d52015b8b6555204f1ee07b19e50c11abf7411259f`
- connected release `874dd0e0168f42aa65441c09114032c456ca92d06eee703783604c297c80ebea`
- offline release `f688a41d94a534dfd139b86b387dc85d95a3a5a695c590eb21b850d3997c9738`

## Referencias del arnés separado y R8

Tras resolver el runner, se reprodujeron `NoSuchMethodError` para AccessGate.unlock
y RelayClient(String): los tests externos llamaban entradas eliminadas/inlined.
La solución en curso usa TraceReferences del mismo R8/AGP, con solo cinco familias
de clases de fixture como origen, para conservar sus firmas exactas y permitir
optimización/ofuscación de cuerpos y nombres. No conservar `app.umbra.**` entero.
El archivo generado es entrada de R8/lint con dependencias de tareas explícitas.
Dos intentos fallaron por dependencias implícitas de lint y otro en la guarda al
conservarse el nombre de NativeVoiceSession por su enum; ninguno cuenta como audio.

## Fallo JNI real reproducido y primera voz R8 comprobada

El intento `r8-audio-6` llegó a Engine/Signal/HTTPS y abortó al cargar WebRTC:
`JNI DETECTED ERROR: java_class == null`, con excepción pendiente
`ClassNotFoundException: org.jni_zero.JniZero`. La regla anterior conservaba
org.webrtc pero omitía JNI Zero del M150 fijado. La inspección del APK productivo
release anterior también confirmó ausencia de esa entrada en DEX. Impacto:
la inicialización de voz optimizada no podía completarse. No se atribuye este
crash a Keystore ni se cambia su política.

Corrección productiva: regla de miembros anotados `CalledByNative`, acotada a
org.jni_zero y basada en `third_party/jni_zero/proguard.flags` de la fuente fijada.
No conservar toda la app ni desactivar optimización. Guarda APK exige JniZero y
CommonApis en connected y rechaza JNI Zero también en offline. Regresión negativa
ejecutada contra el APK release anterior; seis pruebas del verificador pasan.

Después, `run_voice_integration.py --a emulator-5554 --b emulator-5556 --optimized
--scenario audio --reports /tmp/umbra-video-integrated-device/r8-audio-7`: **salida0**.
Dos AVD independientes con SQLite sintético, Engine/libsignal/HTTPS, coturn y
DTLS-SRTP/Opus: 100/112 buffers remotos reconocidos, 52/53 paquetes de audio,
mute/unmute y tres pruebas JNI por extremo. Auditoría SDP y certificados ligada
al mismo protocolo UMBRA. Ventana IPv4 UDP 10,529 s: 177/195 paquetes hacia TURN,
14/27 STUN TURN, cero STUN/UDP no-sistema fuera de TURN. IPv6 NO EJECUTADO.

Target no-debuggable `app.umbra.privatechat.medialab`, APK SHA-256
`c2e397006fd956ecebf3f29ae6b0ab5b3a47b21ef0cc8e438245be8e3e9503f5`.
R8: NativeVoiceSession→app.umbra.media.k, CallService→a.k, Engine→c.l.
Mapping SHA `086cd6779ce5d18044b5eea02d6ad8b1ad4a8854eedb7ce8776951305c1756f8`.
No es el APK productivo exacto: identidad/firma sintéticas, APIs del fixture
retenidas mediante TraceReferences (cuerpos/nombres optimizables), entradas Kotlin
del runner y almacenamiento/PCM en APK de instrumentación separado. No micrófono,
Keystore hardware o video probados. Los resultados pertenecen al árbol de trabajo
posterior a e67762f; el commit publicado y CI final se deben registrar aparte.

## Native failure and corrective rebuild (in progress)

Run `35625739825` compiled all four Android ABI targets and the Linux x64
`rtc_p2p_unittests` target (2303 build steps). The actual TURN suite aborted in
`TurnPortTest.DISABLED_TestTurnCustomizerAddAttribute`: `StunMessage::AddAttribute`
rejected modification after signing. This is an upstream-disabled test with an
explicit integrity TODO at the pinned source revision. The suite is **FAILED**,
not passed; its XML was not completed. Logs are retained in the native test
artifact. No complete upstream test suite has been executed.

The follow-up recipe adds `customize-before-integrity.patch`: authenticated TURN
requests invoke the customizer after adding credentials but before calculating
MESSAGE-INTEGRITY; unauthenticated requests still invoke it once. Allocation,
refresh, permission and channel binding share this rule. Integrity validation and
the original disabled regression assertions are unchanged. The full TURN test
selection still includes disabled tests. Rebuild/test results and adoption into
the Android pin remain pending; the application still uses the previous AAR.

## Prerequisite results checked after the corrective native build

- `35630454176`, builder `02d1ebf939bf6455571b9df35a0627711b31a4d6`:
  four source ABI jobs SUCCESS; 83 TURN C++ tests actually executed and passed,
  including all 18 redirect regressions and the previously aborting customizer.
  Receipt: `2026-09-21-video-native-turn-receipt.json`. Not the entire upstream suite.
- `35630460378`: optimized media laboratory SUCCESS; downloaded and inspected all
  15 voice scenario receipts. Published HEAD `02d1ebf…`; actual integration checkout
  `854bff10b00d0ccf3d469e4a1ab72993da4c055c`. This code precedes local video changes.
- `35630460376`: first attempt FAILED in debug voice with sanitized stage
  `native-binding`; second attempt on the SAME code completed all four jobs SUCCESS.
  The first failure is retained, not replaced by the retry. Its precise cause is
  unresolved; local adapter diagnostics now distinguish fixed stages without SDP,
  addresses or credentials. This is an outstanding reliability finding, not proof
  that the initial failure was harmless.
- The initial native-fix publish command also attempted unsupported
  `repository_guard.py --staged` (exit 2) and reported patch-context whitespace.
  Those are command/format errors, not successful guards. The supported
  `python scripts/repository_guard.py` subsequently passed (268 source files);
  blank patch context formatting was corrected without changing patch semantics.

An additional native SDP section constraint and focused C++ tests are being added
before completing video acceptance. It uses the upstream parsed model to reject
DataChannel/unsupported sections and excess tracks, rather than a text/regex parser.
It needs a new four-ABI build and its own test results before adoption.

## Local video execution, working tree (not final published validation)

The first integrated debug attempts failed: repeated relay uploads ignored
`nextRelay` and hit the unchanged quota; the fixture now follows production retry
scheduling. Subsequent attempts showed native decoded frames but only one sink
callback. In the pinned Java API, `getTransceivers()` disposes previous wrappers,
including receiver tracks/sinks. Retaining wrappers between actual SDP changes
fixed the observed loss. Neither quotas nor decoded-frame assertions were reduced.

`video-positive-5` and `video-r8-positive-1` exited 0 with two independent AVDs,
real Engine/SQLite/Signal/HTTPS, coturn and native WebRTC. Distinct changing I420
patterns entered before encoding; opposite-end decoded frames were checked after
decoding. Both sides passed voice → video → video off with continuing audio →
freshly consented video generation 3. Sanitized receipts are
`2026-09-21-video-debug-working-tree.json` and
`2026-09-21-video-r8-working-tree.json`. R8 is the isolated non-debuggable mediaLab
variant, not the exact production APK. Physical cameras/audio are NOT EXECUTED.

These executions used candidate AAR `.2` (SHA-256
`26cdb1dc1e711f80081520c2a0f00684a41142898e8e421ee7ced2de2cf01b41`)
and uncommitted video code atop published `d4cb521…`. They do not validate subsequent
TLS/camera changes or the pending native SDP constraint build.

TURN/TLS remains IN PROGRESS. Attempt 1 timed out with an IP SAN; the pinned native
name verifier uses `X509_check_host`, so the laboratory now explicitly supplies its
synthetic DNS name using IceServer.setHostname, preserving SECURE certificate mode.
Attempts 2/3 decoded bidirectional audio/video but failed packet policy: first the
checker included inbound HTTPS responses, then OS private-DNS traffic. The checker
now selects the actual AVD source address and still rejects unexpected TCP/UDP.
Disposable AVD setup disables OS connectivity probes/private DNS; APK trust and ICE
policy are unchanged. Attempt 4 failed at native relay-pair validation before audio.
This is a retained failure, not a successful TLS acceptance result.

Two owned AVDs have IPv6 routes, but an isolated Docker IPv6 bridge probe returned
100% packet loss from both (`ping6`, exit 1). The earlier `ping -6` command was invalid
on this image (exit 2). IPv6 multimedia is NOT EXECUTED; route presence or IPv4
success does not prove it. Further topology diagnosis remains pending.

Latest local Python tools run: 134 tests passed, exit 0, activated `.venv`.
Connected/offline JVM and debug/test APK build passed after the camera listener and
sanitized native candidate-type diagnostics (Gradle exit 0). These are intermediate
working-tree results, not a final CI claim.

## Native `.3` and additional intermediate execution

Build `35634570646` finished all four ABI jobs SUCCESS. Downloaded inventories,
hashes and XML were checked: 83 TURN tests and 12 parsed SDP model/policy tests
passed, including the six new media-policy cases. Combined AAR `.3` SHA-256:
`5743b0e47574a7d8bad047b00fdef8f49e56e41c944a12542282e2b91ccf9433`.
See `2026-09-21-video-native-media-receipt.json`; this is not the full upstream suite.

On `.3`, the Camera2 provider test executed permission denial, front/back synthetic
AVD capture, camera switching, source closure under 2 seconds and a positive
1-second post-close observation. This provider test is separate from Engine/codec
acceptance and does not validate a physical camera.

Two-AVD debug video/audio passed over IPv4 UDP and over an IPv6 client-to-TURN leg.
Native counters report VP8; opposite-end synthetic patterns were decoded along
with Opus audio, including off-with-audio and a newly consented reactivation.
Receipts: `2026-09-21-video-native3-ipv4-udp.json` and
`2026-09-21-video-native3-ipv6-udp.json`, with exact APK hashes. These precede the
new malicious-fingerprint fixture and final CI.

IPv6 diagnosis supersedes an inference from ICMP failure: an owned IPv6 TCP listener
was reachable from both AVDs. The first TCP probe command timed out because its
listener did not service the connection; a serviced challenge/response succeeded.
The media test then needed to include AVD site-scope temporary/stable IPv6 addresses
instead of querying only scope global. The pinned allocator requests an IPv4 relay
allocation: successful IPv6 TURN client traffic is NOT an all-IPv6 media allocation.

Intermediate `.2` TLS IPv4 eventually passed decoded video/audio and network policy
with the isolated AVD system probes disabled. Wrong name, expired leaf and unrelated
CA also rejected before capture. These do not automatically validate `.3` TLS.
The expired-certificate generator originally used unsupported `openssl x509 -days -1`;
it now uses an explicitly past CA issuance interval, tested with real OpenSSL verification.

The current `.3` negative matrix retains failures in direct-blocked, allocation-expiry
and TURN-loss cases. Invalid/expired credentials, unreachable TURN, trust loss and
lock have passed in that matrix so far. Do not declare the matrix or final CI green.
Publishing a complete SDP fixes an actual omission of later candidates, but has not
by itself resolved every native negotiation failure. Candidate diagnostics remain
sanitized and a peer-reflexive classification never authorizes media.

Local baseline command first failed with an inherited mismatched Java toolchain
(`release version 21 not supported`). Re-running with explicit JDK21 and activated
`.venv` passed `test_local.sh`; 136 Python tool tests passed, and history guard
checked 281 current source files / 541 reachable historical blobs without a supported
credential pattern. Android preflight passed; it is not a compilation result.

## Follow-up after publishing 7c996345e21815f3d215b19d5d11b1c3107cb1c9

CI `35641166463` failed Android media execution; the other three Verify jobs passed.
Optimized media `35641166388` also failed. Sanitized diagnostics reproduced an
asymmetric stall: one endpoint had published its candidate; the other stayed in
GATHERING with a usable candidate but withheld its description. Waiting for COMPLETE
was therefore not a sufficient fix. New working-tree code publishes the first native
candidate and sends later candidates through authenticated Engine ICE controls.
It does not raise the negotiation timeout or authorize peer-reflexive/direct media.

Video matrix `35641166472` optimized job failed during SDK installation with
`Error on ZipFile unknown archive`, before app compilation. Early report creation
and SDK-output capture now preserve diagnostics even for this infrastructure failure.
No SDK failure is counted as a passed multimedia test.

Intermediate local incremental-ICE execution passed direct-blocked, direct API
camera denial with continuing audio, and CAMERA revocation/process restart without
resuming media. A netem assertion initially rejected Android's equivalent `80.0ms`
format; after correcting the verifier, real 80 ms / 2% loss / 128 Kbit / 20-packet
queue execution passed decoded audio/video and required actual processed/dropped
packet counters. The wrong-fingerprint scenario remains under investigation: it
has not yet produced the required native binding rejection evidence. A timeout is
not accepted as proof of that property. These are working-tree results, not final CI.

Packaging explicitly preserves only `libjingle_peerconnection_so.so`: all four
reviewed ELF binaries are already stripped (no `.debug_*` or `.symtab`). This keeps
the packaged bytes equal to the pinned inventory instead of asking a host NDK to
strip them again; it does not disable R8 or broadly suppress native checks.

The repeated incremental-ICE matrix also passed TURN loss with a direct route still
available; allocation expiry failed its 35-second socket-release expectation after
successful media. Reviewing pinned coturn 4.18.0 found `stun_adjust_allocate_lifetime`
checks the 600-second minimum before the configured maximum. A short refresh request
can therefore obtain 600 seconds after an initial 20-second allocation. References:
[function](https://github.com/coturn/coturn/blob/4.18.0/src/client/ns_turn_msg.c),
[minimum](https://github.com/coturn/coturn/blob/4.18.0/src/client/ns_turn_msg_defs.h).
The laboratory now uses a 180-second allocation for this case, kills the clients
before the first 90-second refresh, and allows a bounded 190-second observation
of actual socket release. Its re-execution is pending; configuration alone is not
an expiry result. This does not extend the application's 180-second session limit.

## Latest local verification before the next publication

With activated `.venv`, explicit JDK21 and SDK:
- `bash scripts/test_local.sh`: exit 0, 149 backend tests, 105 core scenarios,
  12 static policy checks kept separate.
- `python -m unittest discover -s scripts/tests -p 'test_*.py' -v`: exit 0, 139 tests.
- `python scripts/build_android.py --release`: exit 0; 157 connected / 117 offline
  JVM tests, both debug/release builds, lint and APK policy. Exact APK SHA-256 values
  and scope are in `2026-09-21-video-local-build-receipt.json`.
- Direct `repository_guard.py`: exit 0, 288 current files. A history invocation
  initially used unsupported `--history` (exit 2); the correct flag is `--git-history`.
- `check_optimized_media.py`: exit 0, non-debuggable `.medialab`, minification and
  optimization retained; Engine, CallService and NativeVoiceSession are obfuscated.
  This static check alone does not execute media.

New real executions: incorrect fingerprint rejected by native binding at both ends
before capture; allocation sockets 2 → 0 after forced process death (147.469 seconds
observed after recovery, maximum configured lifetime 180 seconds); IPv4 TLS video;
IPv6 client-to-TURN TLS with IPv4 relay allocation; and synthetic bidirectional
video/audio over the isolated R8 target. Receipts are in
`2026-09-21-video-ice-tls-r8-working-tree.json` and the other dated ICE receipts.
A receive-only endpoint decoded the remote changing pattern while capturing zero
local frames, including stop/reactivation. Camera2 provider tests passed separately
in debug and R8, including a second switch racing local lease cancellation. These
are not Engine-lock tests or physical camera evidence. The real Engine lock,
revocation, force-stop and media cases remain separate suites.

The negative fingerprint gate permits the counterpart to close following the first
rejection; it still requires at least one actual native certificate-binding failure
and zero capture at both endpoints. Watchdog/negotiation timeout never qualifies.
The prior failing attempts are retained; one successful repetition alone does not
establish stability. The next CI matrix must pass without retrying failed cases away.

Warnings remain visible: Starlette's TestClient httpx deprecation already documented
in the voice delivery, and a deprecated Android API in existing location instrumentation.
No global suppression was added. The fixture dependency migration and physical
camera/headset/Keystore validation remain separate work.

Cancellation receipts measure local monotonic request, invalidation, last capture
callback, resource closure and a positive quiet window. They do NOT timestamp the
last encrypted video datagram independently of audio multiplexed on the same TURN
transport; do not describe those receipts as that unperformed wire-level measurement.
No images, PCM, raw SDP, credentials or packet captures are published.

## Follow-up to HEAD 69e9dc19305a096fa88b2162b08385414bba5e67

Published HEAD and merge checkout `7560d23fee67ac50d4af2c65ae65e7e36847b5a4`
remain distinct. Verify `35646143351`: guard/backend/container SUCCESS, Android FAIL.
Optimized voice `35646143234`: SUCCESS. Video `35646143377`: FAIL in both targets;
retain matrix failures (debug credential-expiry/TLS/TLS-loss, optimized force-stop).
Native `35646136452`: all four jobs SUCCESS, 83 TURN + 12 media model/policy tests.
All four rebuilt JNI objects match the pinned `.3` bytes; receipt in
`2026-09-21-video-native-repeat.json`.

Two orchestration races were visible in failure logs: auditing the call after its
lease had concurrently expired, and treating the deliberately injected TURN loss
as an unexpected failure before reading the host's termination command. The fixture
now consumes that command before classifying termination and still requires actual
native capture closure. A rejected authorization snapshot is tolerated only after
the adapter has left ACTIVE, not as successful media evidence.

Review also found a product resource leak: MainActivity replaces the dialog's
OnDismissListener to maintain its registry, overwriting the video renderer cleanup.
Two real Android regressions reproduced cleanup count 0 instead of 1. Cleanup now
belongs to MediaDialog.onStop, independently of replaceable listeners, and runs
once even on repeated dismiss/cancel. Both regressions then passed; the complete
connected instrumentation ran 27 tests, with offline's existing 25 unchanged.
The Camera2/R8 helper also explicitly executes these lifecycle checks.

Stopping video previously scheduled a persistent STOP and then wrote it directly,
creating redundant concurrent operations. The synchronous API now invalidates
capture and performs only its direct write; the UI asynchronous path retains its
single queued write. Native track attachment/start is serialized with camera
closure to prevent accessing a track disposed by the cancellation watchdog. No
permission, lease, fingerprint or TURN check was relaxed.

### Comprobación local de la corrección de ciclo de vida

Se conserva el fallo inicial R8 `NoSuchMethodError`: el nuevo test de diálogo no
estaba entre los orígenes de TraceReferences y su constructor había sido eliminado.
Se añadió exclusivamente `VideoSurfaceLifecycleTest*.class` a esos orígenes, con
optimización/ofuscación conservadas. Tras reconstruir, Camera2 AVD y los dos tests
de cierre pasaron; force-stop con audio/video real sintético R8 y reinicio pasó.
Hashes/evidencia: `2026-09-21-video-lifecycle-r8-working-tree.json`.
Las comprobaciones de disco quedan fuera del monitor de cierre de captura; dentro
se revalida el lease sin disco antes de usar la pista. El cierre no espera ese acceso
SQLite. El recorrido ejecutado sigue siendo un arnés separado del APK productivo.

Nueva ejecución local: `bash scripts/test_local.sh` exit 0 (149 backend,105 Java,
12 guardas estáticas), herramientas exit 0 (140 tests), guardia con `--git-history`
exit 0 (293 archivos,594 blobs). La advertencia existente Starlette/httpx continúa
visible. Compilación mediaLab/test exit 0; helper Camera2 `--optimized` exit 0;
`run_voice_integration.py --optimized --video --scenario force-stop` exit 0.
La CI final de esta corrección todavía debe ejecutarse.
