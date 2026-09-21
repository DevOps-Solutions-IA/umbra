# Quinta entrega — voz nativa — 2026-09-21 UTC

**PARTIAL / EN CURSO. No existe todavía aceptación de una llamada UMBRA.**
Base PR #7 OPEN comprobada: `e495793ba191cce0523db243ab7baac55389ef64`.
Rama nueva `codex/turn-voice-core`, dependiente de `codex/authenticated-call-signaling`.
No cambios en main ni ramas anteriores. La CI histórica no valida este trabajo.

## Primer bloque ejecutado

WebRTC SDK Android M150 fijado en connected, AAR/entradas/JNI con SHA-256 en
`android/webrtc-artifact.json`. Guarda de APK exige inventario exacto y hashes de
cuatro ABI; offline rechaza WebRTC nativo y Java. No permisos nuevos. La configuración
usa la API real RTCConfiguration RELAY, TLS seguro, credenciales locales acotadas,
rechazo permanente de downgrade/cambio/expiry. No concede autorización Engine.
`prepareMedia` sigue fallando cerrado mientras falta el adaptador integrado.

Comandos ejecutados con JDK21.0.11, Python3.13.12, Gradle8.13, AGP8.13.2, compile/target36,
min31, SDK build-tools35.0.0, Signal0.102.3 y WebRTC150.7871.01:

| Comando | Resultado | Salida |
|---|---|---|
| `bash scripts/codex_setup.sh` | venv preparado | 0 |
| `. .venv/bin/activate; bash scripts/test_local.sh` | 144 backend, 105 escenarios Java y 12 controles estáticos separados | 0 |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | baseline104, luego108 con guardas JNI; controladores de laboratorio112 | 0 |
| `python scripts/build_android.py --check-only` | preflight, no prueba de build | 0 |
| `python scripts/build_android.py --release` | primer intento falló por import Kotlin de MessageDigest; corregido | 1 |
| mismo build tras corrección | 138 JVM connected /117 offline; debug/release/lint y políticas APK | 0 |
| `python scripts/check_voice_artifact.py /tmp/umbra-voice-research/webrtc.aar` | AAR, manifest, classes y cuatro JNI coinciden | 0 |
| `python scripts/repository_guard.py --git-history` | higiene de fuente/historia; no auditoría | 0 |

Los APK release compilados no prueban una llamada ofuscada. TurnConfiguration no
está conectado al runtime: R8 puede eliminar código todavía no utilizado.
Instrumentación existente/HTTPS/RFCOMM y CI final requieren ejecución sobre el
árbol final. No reutilizar sus resultados anteriores como validación de esta rama.

## Laboratorio en investigación

Coturn4.18.0 imagen base fijada por digest en el helper en desarrollo. Se reprodujo
fallo de exec con `cap-drop ALL` por atributo de capacidad del binario upstream.
Una copia del ejecutable en imagen derivada omite ese atributo y permite mantener
no-root, cap-drop ALL, no-new-privileges y filesystem read-only. Arranque/cleanup
reales comprobados, sin puertos publicados y con destinos relay restringidos.
Credenciales TURN REST temporales emitidas; ningún secreto maestro llega al APK.

Prueba auxiliar `turnutils_uclient`: primer intento falló486 por perfil de asignación;
con dos clientes RTCP-mux y sin reserva EVEN-PORT, 20 mensajes recibidos de20 enviados,
0 perdidos (el generador también informó2 send dropped). Es TURN UDP real, NO audio,
Signal, HTTPS ni aceptación Android. No ocultar esos descartes del generador.

El probe nativo Android es separado de la aceptación: dos PeerConnections sintéticos
en un AVD. Primer intento falló por configuración incompatible de logger; corregido.
Siguientes intentos fallaron sin candidato TURN. No se presentó como audio aprobado.
Logs privados de trabajo `/tmp/umbra-voice-*`; no subir SDP, credenciales o PCAP crudo.

## Pendientes de la misión

NOT EXECUTED: recorrido Engine/Signal/relayHTTPS a media; dos AVD con audio
bidireccional; mute/revocación/force-stop; certificado sustituido; privacidad de red
sin fallback; IPv6; micrófono productivo/permisos/rutas; llamada release/R8.
BLOCKED por hardware ausente: prueba acústica de teléfonos y headset Bluetooth,
Keystore hardware. El resto está en desarrollo, no se clasifica como bloqueo físico.
Sin voz/video terminados, sin nueva recuperación, publicación o despliegue productivo.

## Probe nativo adicional ejecutado

Después de corregir el perfil coturn y la espera de candidatos (COMPLETE puede
preceder la primera notificación de red Android),
`python scripts/run_voice_probe.py --serial emulator-5554 --log /tmp/umbra-voice-device/native-probe-6.log`
terminó con salida0. Exige marcador `nativeVoice=PASS` y3 tests Signal del runner.
Los logs anteriores conservan sus fallos; ninguno se reclasifica como aprobado.

Dos PeerConnections/factories/ADM independientes en UN AVD API35 x86_64/KVM
intercambiaron tonos sintéticos distintos1kHz/2kHz. Al menos100 buffers PCM
decodificados por lado superaron energía media100000 y fracción espectral0,55
al tono esperado. Entrada inyectada antes del códec, AudioRecord desactivado ANTES
del factory; salida leída tras decodificación nativa, sin micrófono del equipo.
Stats nativas: DTLS conectado, candidatos locales/remotos relay y huella del
certificado remoto coincidente con el certificado generado por el otro extremo.
NO prueba identidad UMBRA por Engine, HTTPS, dos AVD o conversación humana.

TURN UDP real: coturn4.18.0-r0 por digest
`sha256:bbefd3e1fdfdc0d58770fe01b581fd8b00d9f3a5580d00acb77cf719a6bc78e3`,
imagen derivada local no publicada (`scripts/turn-lab/Dockerfile`). Red Docker
interna sin puertos publicados, listen3478, asignaciones49160–49179, destinos
restringidos a la dirección del servidor, user-quota4/total8, 1 relay thread,
duración180s, secreto REST efímero. TURN TLS NO probado: UDP de laboratorio,
distinto de DTLS-SRTP. Cleanup de contenedor/red/archivos/credenciales y force-stop
de la app al salir. Se conservan en caché capas públicas de construcción, sin secretos.

113 pruebas de herramientas aprobadas: incluyen rechazo de marcador nativo ausente,
cleanup Docker tras error y límites del emisor de credenciales. Sus mocks no cuentan
como TURN real. CI añade el probe nativo sin sustituir suites existentes.
CI del commit final aún pendiente de verificar.
