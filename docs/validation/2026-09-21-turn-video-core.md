# Sexta entrega — video — trabajo en curso (2026-09-21 UTC)

**PARTIAL. Video todavía NO IMPLEMENTADO.** Este informe registra el primer bloque
de regresiones nativas y laboratorio R8; no acredita cámara, frames remotos, TLS,
IPv6 o hardware. Se preserva íntegra la evidencia anterior.

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
