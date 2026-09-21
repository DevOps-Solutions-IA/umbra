# Quinta entrega — voz nativa — 2026-09-21 UTC

**PARTIAL / EN CURSO. Audio sintético integrado ejecutado; aceptación completa pendiente.**
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

## Segundo bloque local: Engine y dos AVD (en curso)

Se conservan los resultados anteriores como históricos. La rama publicada al empezar
este bloque es `edbe1cf25f9e1c644803ff60bf44696f65e3bf3f`; estas ejecuciones corresponden
a cambios de trabajo posteriores, NO a aquel commit ni a un checkout final de CI.

CI **35566926573** de edbe1cf: repository-guard, relay-and-core, relay-container SUCCESS;
Android FAILED en RFCOMM offline. El probe nativo de voz anterior sí pasó dentro del
job, pero NO convierte el job Android en verde. Checkout de integración observado en
el nombre del artefacto: `374b8e355cddd51a83c50b0be70c75ab02fe7ad0`.

Se reprodujo localmente el cierre anticipado del harness RFCOMM. La barrera de drenaje
mostró además recibos regenerados con el mismo ID después de duplicados: el conjunto
histórico `sent` los omitía aunque estuvieran nuevamente en outbox. Corrección solo
del harness: reenvío de recibos pendientes y barrera de ambos extremos antes de cerrar,
sin excluir controles ni sustituir Bluetooth por TCP. Ambas variantes volvieron a pasar
con RFCOMM emulado, ubicación cifrada, roster, texto, adjunto, duplicados y recibos.

Aceptación positiva ejecutada entre dos AVD independientes (API35 x86_64/KVM):
Engine, identidades independientes verificadas, SQLite de laboratorio, libsignal,
relay HTTPS aislado, INVITE/ACCEPT/SELECT, SDP real, coturn y DTLS-SRTP/Opus. Cada extremo
analiza el tono diferente del otro después de decodificar. En la repetición con barrera
de activación/cancelación: A recibió 104 buffers reconocidos/50 paquetes audio; B 104/51.
Ambos reportaron `audio/opus`, certificado DTLS ligado a Signal y par relay/relay.
No son micrófonos, altavoces físicos ni Keystore hardware. No es evidencia R8 de voz.

Reproducciones intermedias conservadas localmente: la variante inicial del generador
PCM corría sin reloj; se añadió pacing de frames. Crear transceiver en ambos extremos
producía dos secciones de audio; ahora el receptor usa el transceiver remoto único.
La revisión de cancelación añadió barrera local contra unmute/activación tardíos. Cleanup
intenta liberar todos los recursos y emitir END fuera del hilo UI; no espera al relay.

| Ejecución del segundo bloque | Resultado real |
|---|---|
| `python scripts/build_android.py` (tras corregir variable Java de diálogo duplicada) | 0; 142 JVM connected/117 offline, debug/lint/APK |
| `python scripts/build_android.py --release` | 0; debug/release/R8/lint y políticas APK/JNI/DEX; no ejecución de voz ofuscada |
| `gradle ... :app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest` | 0; APKs instrumentados |
| `python scripts/run_voice_integration.py --a emulator-5554 --b emulator-5556 --reports .../activation-gate` | 0; aceptación positiva descrita arriba |
| `python scripts/run_bluetooth_emulation.py ... --flavor connected` y `offline` (repetición después de corregir recibos) | 0 ambos; tres pruebas JNI posteriores en cada extremo |
| `. .venv/bin/activate; bash scripts/test_local.sh` | 0; backend/core/controles existentes conservados |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | 0; 118 pruebas de herramientas; no equivalen a media |

Primer intento de observación de red: **FAILED del verificador**, no aprobado. Emulator
37.1.11 rechaza rutas absolutas para `network capture start`, aun devolviendo adb código
0 (`KO`). Se adapta a nombre efímero en AVD/console_out, se comprueba respuesta/archivo
y se elimina el pcap después del resumen. Mute/unmute alcanzó el final del fixture,
pero la ejecución completa falló en ese verificador. La repetición queda pendiente hasta
registrar su resultado. No publicar pcaps, SDP, credenciales temporales ni claves.

Estado todavía PARTIAL: escenarios TURN adversariales, observación de red final, APIs
Android físicas, revocación/force-stop durante media, IPv6, recorrido release y CI final
requieren evidencia propia. La ruta productiva de micrófono y controles está implementada;
no declarar probada una conversación humana. No hay video ni TURN productivo.

La siguiente captura de consola también se rechazó: cero TURN/STUN/UDP observados
mientras el audio sí se decodificaba. Era el canal Ethernet antiguo, no Wi-Fi netsim.
La solución usa la captura por radio de netsim bajo ANDROID_TMP privado. Se comprobó
además la sintaxis instalada: `-netsim-args --pcap` (argumentos separados); la forma con
`=` produjo un error explícito de arranque y fue corregida. No se aumenta el timeout ni
se omite la guarda. Las siguientes ejecuciones deben incluir evidencia positiva de
tráfico observado y no solo contadores negativos vacíos.

## Observación ejecutada: audio, mute y rechazo de TURN

Repetición `wifi-network`: **PASS** con captura netsim Wi-Fi real. Ruta IPv4 directa
comprobada por ping en ambos sentidos. Se observaron 172/177 paquetes hacia TURN,
13/18 STUN hacia TURN, cero STUN fuera de TURN y cero otro UDP fuera del tráfico de
sistema definido. Ambos SDP nativos pasan revisión de candidatos relay/direcciones
relacionadas, ambos certificados se contrastan con Signal y ambos extremos decodifican
Opus (107/102 buffers reconocidos; 51 paquetes audio cada uno). Mute produjo ausencia
del tono remoto tras drenar jitter; unmute volvió a producir >=50 buffers reconocidos.

Rechazos reales ejecutados, salida 0:
- `--scenario invalid-auth`: contraseña temporal deliberadamente inválida; iniciador
  realizó 10 peticiones TURN/STUN y no capturó ni decodificó audio; no fallback UDP.
- `--scenario unreachable`: puerto de TURN sin servicio; 16 peticiones del iniciador,
  sin captura/decodificación y sin fallback UDP.
- `--scenario turn-loss`: tras audio/mute/unmute reales, se detuvo el contenedor TURN.
  Se volvió a comprobar la ruta directa disponible. Ambos adaptadores fallaron cerrado;
  566/543 paquetes TURN observados durante la ventana, cero STUN/UDP directo.

En los dos primeros rechazos, el receptor nunca recibió SDP porque el iniciador no
obtuvo candidato relay. Su intento de transporte figura **NOT_EXECUTED**; sus ceros
no se cuentan como prueba de transporte. Sí se comprobó que no activó captura. La guarda
sigue exigiendo tráfico observado en el iniciador y en ambos extremos del caso positivo.

Otros fallos de harness reproducidos y corregidos: archivos de coordinación leídos
antes de finalizar `cat` (ahora temporal+rename), y polling sostenido que provocaba
HTTP429 (ahora una consulta por segundo y extremo, cuota backend intacta). La preparación
comprueba Wi-Fi/ruta de los AVD con límites internos; no se cambian tiempos globales CI.
Se mantiene pantalla activa únicamente en los AVD sintéticos de prueba; producción no
recibe renovación del consentimiento, autolock ni servicio de fondo artificial.

La nueva compilación debug/release/R8/lint pasó con **143 JVM connected /117 offline**;
la suite de herramientas suma **119** casos. Los nuevos escenarios de ciclo de vida
incluidos en el harness todavía requieren sus resultados específicos. No se confunde
terminar compilación con voz ejecutada desde release.


## Ciclo de vida y rechazo DTLS ejecutados localmente

Ocho escenarios adicionales terminaron con salida 0: `direct-blocked`, `trust-loss`,
`lock`, `credential-expiry`, `device-revoked`, `storage-failure`, `force-stop` y
`permission-revoked`. Cada uno repite Engine/libsignal/HTTPS/Opus entre dos AVD y
observación Wi-Fi IPv4 UDP. El caso directo bloqueado comprueba primero la ruta,
instala y limpia exclusivamente reglas de los AVD de laboratorio. La revocación de
dispositivo retira al administrador local; no sustituye una prueba de propagación
entre tres dispositivos. Error de escritura/reapertura SQLite no es muerte durante commit.
Force-stop y revocación Android comprueban muerte del PID y reapertura del estado
terminal sin reanudación multimedia; no prueban micrófono físico.

Prueba nativa DTLS adicional: salida 0, `nativeCertificateRejection=PASS`, certificado
efectivo incompatible con SDP provoca fallo y cero tonos decodificados. Es subsistema
nativo, separado del recorrido completo Engine. Los fixtures alterados son solo tests.
Un intento anterior no llegó a ejecutarla: instalación APK falló por espacio del AVD.
Se recrearon los dispositivos efímeros y se repitió; ahora el instalador reutiliza un
APK solo si su SHA-256 instalado coincide. No omite ninguna instrumentación.
Suite de herramientas posterior: **121 pruebas, salida 0**.

## Bloqueo de autorización de destinos TURN (2026-09-21)

**BLOCKED para voz productiva.** La distribución fijada sigue `300 ALTERNATE-SERVER`
hacia un destino que no figura en la configuración local. `IceTransportsType.RELAY`
no impide esa redirección. Se reprodujo con coturn aislado y captura de los AVD:
ocho paquetes hacia el puerto alternativo no autorizado, sin audio. La entrada pública
`NativeVoiceSession.open` y el control de voz rechazan antes de inicializar WebRTC o
solicitar micrófono. El laboratorio mantiene una entrada interna con AudioRecord
desactivado para investigar y verificar; no existe un interruptor de éxito productivo.

Se prepara una compilación de la revisión fijada que rechace redirecciones antes de
cualquier I/O. Hasta construirla y repetir la prueba real, el parche no acredita una
corrección. No filtrar después de recibir candidatos: el contacto ya habría ocurrido.
Las pruebas positivas de audio anteriores no demuestran esta garantía pendiente.

Validación posterior al cierre productivo: `python scripts/build_android.py --release`
salida 0, 144 JVM connected /117 offline, debug/release, lint, R8, JNI y políticas
APK. `python scripts/repository_guard.py --git-history`: salida 0, 246 archivos
actuales y 406 blobs históricos. Un intento con opción inexistente `--history`
terminó en 2 antes de escanear; se corrigió el comando, no el verificador.
