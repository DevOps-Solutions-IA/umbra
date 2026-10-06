# Quinta entrega — voz nativa — 2026-09-21 UTC

**Núcleo de voz comprobado en laboratorio Android debug. Matriz completa PARTIAL:** hardware, IPv6, TURN/TLS y recorrido de voz R8 no ejecutados. Los bloques siguientes conservan la historia; el recibo actual está al final.
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


## Reconstrucción nativa: intentos y límites (en curso)

La preparación local de fuente `73cb8180f7258ee292878d6edd05177f41883962`
y depot_tools `ca054941f756b50e1a3d83727270d879bec1f331` no produjo un AAR.
Una descarga HTTPS acotada a 180 s terminó con código 28 tras 5.53 MB de
69.64 MB; se detuvieron los procesos propios de preparación y se trasladó la
compilación a runners estándar aislados. No se contrataron recursos ni se usó RBE.

- Actions `35574931688`: cuatro ABI FAILED antes de compilar por argumentos GN
  mal pasados a `build_aar.py`. También se corrigió LASTCHANGE: el fork no conserva
  Change-Id en ese commit; ahora registra el SHA real mediante opciones soportadas.
- Actions `35575488377`: cuatro ABI FAILED al arrancar Siso (`-j2` no admitido).
  GN sí generó 7.603 objetivos. La ayuda de la versión instalada indica
  `-local_jobs=2`, que se utiliza en la siguiente ejecución.
- Actions `35575890767`, fuente de construcción UMBRA `92d7a10962f87165e56cb43c6cae962458ec7742`:
  EN CURSO al registrar esta sección. No atribuirle todavía binarios ni garantías.

La receta y el parche están en `native/webrtc/`. Rechaza todos los 300 antes de
modificar el destino, sin cambiar DTLS ni cifrado. Sus pruebas C++ de rechazo aún
no se han ejecutado: generar el parche no equivale a validarlo. Los artefactos de
compilación no actualizan automáticamente el pin Android ni habilitan producción.

Las ejecuciones normales `35574248065`, `35574855102`, `35574934765`,
`35575492326` y `35575894499` fueron sustituidas por commits posteriores y la
concurrencia existente. No se cuentan como CI final. `35576050479` corresponde a
`ba5a813cecbd6833bee3a3c430e4703841baf078` y está EN CURSO al registrar.

Repetición local de `test_local.sh`: el primer intento usó el JDK predeterminado
incorrecto; backend (144) y utilidades (105) pasaron, pero el verificador de sintaxis
rechazó release 21. Se repitió con JAVA_HOME/PATH de JDK21 y terminó en 0, incluidos
los 12 controles de política estática (no sumados como pruebas de comportamiento).
Después del ajuste de cierre/watchdog y hash por streaming: 121 pruebas de herramientas
pasaron y `:app:testConnectedDebugUnitTest` terminó en 0. No son audio físico ni CI final.


## Caducidad TURN y corrección de preparación de red

Ejecutados localmente con dos AVD, Engine/HTTPS/libsignal y la distribución Maven
original (no atribuir al AAR nativo nuevo):
- `expired-auth`: salida 0. Usuario/HMAC TURN correctamente formado pero caducado;
  metadato local deliberadamente inconsistente solo en el fixture negativo para
  comprobar el rechazo del servidor. Cero captura/decodificación, tráfico TURN observado.
- `allocation-expiry`: salida 0. Dos asignaciones observadas en el namespace privado
  del servidor antes de matar los procesos y todavía dos tras reabrir SQLite. Con
  máximo de asignación de 20 s, desaparecieron tras esperar otros 12,314 s. No se
  deduce borrado instantáneo por vencimiento de credencial; son mecanismos distintos.

CI `35577087307` falló al exigir ping entre AVD, después de compilación, JVM/HTTPS,
instrumentación, reinicio de ubicación, smoke release y probe nativo. Ambos AVD
arrancaron en emulator37.1.11 y compartían netsim; no es el fallo histórico del AVD.
La preparación se cambia a desafío UDP aleatorio recibido en un socket cuya escucha
se comprueba; ICMP no se toma como sustituto de alcance UDP. El motivo específico
de la diferencia ICMP del host CI no se ha demostrado. La nueva preparación exige
entrega real y no omite el control si falla.

Sondas directas antes/después fuera de la ventana de observación de media. La sonda
negativa con firewall debe encontrar receptor válido y no recibir el desafío; un
receptor fallido produce error, nunca prueba de bloqueo. Repeticiones `direct-blocked`
y `turn-loss` con esta preparación: salida 0 ambas. No se excluyen paquetes multimedia
del verificador. Herramientas: 123 pruebas, salida 0, incluidos rechazos del verificador.


## Artefacto nativo corregido y validación local (2026-09-21)

Actions de construcción `35577083313`, commit de receta
`eb58cf72815a96da7f215be79bbf05d920cc72bd`: las cuatro ABI SUCCESS.
Fuente `73cb8180f7258ee292878d6edd05177f41883962`, depot_tools
`ca054941f756b50e1a3d83727270d879bec1f331`; parche compuesto SHA-256
`947a426dafef2ba7590a3712abf342157b3a2582fd0049bbdbdc5a9a6d5058c4`.
Los logs del compilador registran ejecución local, remote/cache/cache-write=0.
El intento `35575890767` falló por LinkedHashSet sin parámetros genéricos;
se corrigió la fuente, conservando warnings-as-errors. Las pruebas C++ del parche
NO EJECUTADAS; sí se ejecutó la regresión de red sobre el binario Android resultante.

`python native/webrtc/package.py /tmp/umbra-native-artifacts-35577083313 /tmp/umbra-native-repeat.aar`
combina cuatro ABI con Java/manifest idénticos, licencias de componentes y
normalización ZIP. No se afirma todavía reproducibilidad del compilador completo
por repetir solamente el empaquetado. Procedencia y hashes por entrada se conservan
en `android/webrtc-artifact.json`. AAR final de 23.728.073 bytes:
`bbc5675f91b31f901e1a482b00991a36ac2b3d912d2782b80e1cc1b756b1c413`.
La guarda permite exclusivamente esa ruta/tamaño/hash; su límite general permanece.

Con ese AAR, dos AVD Android35/emulator37.1.11/KVM y coturn aislado:
- `python scripts/run_voice_integration.py --a emulator-5554 --b emulator-5556 --scenario unauthorized-redirect --reports /tmp/umbra-voice-integrated-device/patched-redirect`: salida 0.
  9 paquetes TURN al destino autorizado; 0 al alternativo no autorizado, 0 UDP/STUN
  no autorizado. Cero audio. El segundo extremo no recibió descripción: su intento
  de media figura NO EJECUTADO, no como prueba positiva de red. Antes del parche se
  observaron 8 paquetes al puerto alternativo: la evidencia histórica se conserva.
- Mismo comando con `--scenario audio` y reportes `patched-audio`: salida 0;
  dos Engine/SQLite/libsignal/HTTPS, Opus bidireccional, mute/unmute, auditoría SDP,
  estadísticas relay/relay y vinculación del certificado nativo.
- `python scripts/run_voice_probe.py --serial emulator-5554 --log /tmp/umbra-voice-patched-certificate.log`: salida 0, pipeline PCM/Opus y rechazo de certificado
  incompatible con cero audio en el caso negativo, más tres pruebas JNI.

Después se habilitó la entrada productiva exclusivamente para el hash revisado,
con regresión de artefacto desconocido y lease revocado. Esto no sustituye permisos,
consentimiento, selección de dispositivo, autorización Engine ni comprobaciones DTLS.

`. .venv/bin/activate; JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, PATH de JDK21,
ANDROID_HOME/ANDROID_SDK_ROOT=/mnt/c/Android/sdk-linux:
`python scripts/build_android.py --release`: salida 0, 144 JVM connected y 117
JVM offline sin errores/omisiones, debug/release, lint, R8, políticas APK/DEX/JNI.
Python3.13.12, JDK21.0.11, Gradle8.13, AGP8.13.2, compile/target36, min31,
build-tools35.0.0, libsignal0.102.3. El compilador hermético de WebRTC se fija
por DEPS/CIPD; no se confunde con el JDK21 usado por Gradle.

SHA-256 APK locales de esta compilación (release sin firma productiva):
- connected debug: `c2f28521f5bdb1d4cb539d57e1d856e4f4c1f6fee7b9f159530be475ded1dde3`
- connected release: `874dd0e0168f42aa65441c09114032c456ca92d06eee703783604c297c80ebea`
- offline debug: `e572f38b78e7da653c147cb7fc30a28f75e2c5f489789705bdedbf80d3e43df9`
- offline release: `f688a41d94a534dfd139b86b387dc85d95a3a5a695c590eb21b850d3997c9738`
Offline carece de red, micrófono, WebRTC JNI/DEX/assets. Sin permisos de cámara.

CI `35578892651` sobre HEAD `d1a192f59723425a5f120b88831f9634192540fc`:
cuatro trabajos SUCCESS. Corresponde todavía al artefacto Maven y entrada cerrada;
NO valida la nueva sustitución nativa. La CI del nuevo commit se registrará aparte.
Persisten NO EJECUTADOS: audio acústico/hardware, headset físico, IPv6, TURN/TLS,
llamada con código ofuscado R8, muerte durante commit SQLite y auditoría independiente.
No hay reconexión automática: fallo de ICE exige nueva sesión y consentimiento.

Repetición del empaquetado y `cmp`: salida 0, AAR idéntico byte a byte.
`python -m unittest discover -s scripts/tests -p 'test_*.py' -v`: 125 pruebas, salida 0; incluye licencia alterada y assets WebRTC prohibidos en offline.


## CI del núcleo corregido y cierre de evidencia — 2026-09-21

PR #8 OPEN/DRAFT hacia `codex/authenticated-call-signaling`; base #7 permanece abierta.
HEAD de código **c325788f2ecbf97f5ea0b2c0da7b47d3d60e669a**.
Actions **35580903584**: repository-guard SUCCESS, relay-and-core SUCCESS,
relay-container SUCCESS, android SUCCESS. Checkout de integración realmente probado:
**b9d5256b47ffeb3ce4c6aa9bbc8342d4f611085c**; no se confunde con el HEAD de la PR.
[Resultado y artefactos](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/35580903584).

Se conserva el recibo sanitizado, con 15 resultados, hashes APK y metadatos de
artefactos, en [2026-09-21-turn-voice-ci-receipt.json](2026-09-21-turn-voice-ci-receipt.json).
No contiene PCM, SDP, credenciales, direcciones de endpoints ni capturas crudas.

Controles y comandos de `.github/workflows/verify.yml`, todos con salida 0:
- repository_guard --git-history y 125 pruebas de herramientas.
- test_local.sh: 144 backend, 105 escenarios Java; 12 guardas estáticas separadas.
- testConnectedDebugUnitTest / testOfflineDebugUnitTest: 144 /117, sin fallos ni skips.
- assemble/lint connected/offline debug/release, guardas manifest/APK/JNI/DEX,
  cliente JVM contra relay HTTPS aislado y contenedor no root.
- run_android_instrumentation.py: 25 pruebas connected y 25 offline.
- run_location_restart.py en ambas variantes; smoke_release_launch.py (solo arranque).
- run_voice_probe.py: dos PeerConnections nativos, PCM/Opus y certificado incompatible
  rechazado; no se presenta este probe como el escenario integrado de dos AVD.
- run_voice_integration.py con audio, direct-blocked, expired-auth, allocation-expiry,
  invalid-auth, unreachable, turn-loss, trust-loss, lock, credential-expiry,
  device-revoked, storage-failure, force-stop, permission-revoked y unauthorized-redirect:
  15 escenarios PASS con Engine/SQLite/libsignal/HTTPS y dos AVD reales.
- run_bluetooth_emulation.py mediante ci_bluetooth.sh: RFCOMM del stack emulado en connected
  y offline, incluidas ubicación cifrada, texto, adjuntos, duplicados y recibos.

Audio normal: 113/111 buffers decodificados; pares nativos relay/relay, certificado
ligado a la señalización y codec Opus. Captura IPv4 UDP: 183/200 paquetes TURN y cero
STUN/no-sistema UDP fuera del destino autorizado en esa ventana. Con ruta directa
bloqueada y con TURN caído se mantuvo el contrato; no inferir cobertura IPv6/TCP.
Redirección: 9 peticiones al TURN autorizado, 0 al destino alternativo, 0 audio.
Casos rechazados sin descripción en el receptor indican media NOT_EXECUTED para ese
extremo: sus ceros no se cuentan como prueba positiva de transporte.
Caducidad: dos asignaciones antes y después de recuperar procesos, cero tras esperar
11,595 s adicionales con vida máxima de 20 s. No equivale a revocación instantánea
por fecha del usuario TURN. Force-stop prueba muerte entre operaciones y reapertura;
la ejecución JUnit interrumpida no se cuenta como aprobada ni como muerte durante commit.

Hashes CI (no reutilizar los debug locales, cuya firma efímera difiere):
- connected debug: `9c02ff1255aea3787e4dfab4fcd5af74de5aed1202ff60bdc238adbec18e6431`
- offline debug: `9952f74d0f7dd81276d1f3ab65a2f5492c85c62541004a37396d2d44122323a1`
- connected release sin firma: `874dd0e0168f42aa65441c09114032c456ca92d06eee703783604c297c80ebea`
- offline release sin firma: `f688a41d94a534dfd139b86b387dc85d95a3a5a695c590eb21b850d3997c9738`
Artefacto debug ID10631520171, ZIP SHA `9f25e1bcf5da1cf8149f3a56a4129a90cc28a026e3b0a7a211dc301df5f6ba5d`;
informes ID10630594607, ZIP SHA `b38b411a4ea0c56b78c327711d04e8dbe70cd3d531b699fb7bf0816c1c497e6d`.
Retención Actions: 7 días. Los release se compilaron/inspeccionaron y se registraron
sus hashes; el workflow publica como artefactos de CI solamente los APK debug y
los informes. No se publicaron releases ni se usó firma de producción.

Advertencias visibles: Starlette depreca su adaptador httpx de TestClient en favor de
httpx2; las pruebas actuales pasan con el lock existente. La migración requiere
revalidar la suite/backend y no se hace indiscriminadamente dentro de la dependencia
nativa de voz. Pip avisa de instalación como root al construir la imagen; el runtime
es no root y su smoke se ejecutó. No se añadieron supresiones ni continue-on-error.

La guarda local posterior al commit revisó 255 archivos y 468 blobs históricos,
salida 0. Se cerraron ambos AVD propios, se eliminaron sus discos sintéticos y los
pcap privados; contenedores, redes, secretos y asignaciones efímeros se limpiaron.
Los informes sanitizados y APK locales se mantienen para revisión.

NO EJECUTADOS/BLOQUEADOS: micrófono/altavoz acústico y headset físicos, Keystore de
hardware real, IPv6, TURN/TLS, llamada con código R8 ofuscado, muerte SQLite durante
commit y pruebas C++ upstream. Las cuatro ABI se construyeron; runtime de voz
comprobado en x86_64 AVD. Auditoría independiente pendiente. No hay video ni llamada
en segundo plano, ni reconexión automática: una pérdida ICE exige consentimiento y
sesión nuevos. La política no promete anonimato frente al operador TURN/ISP/relay.

Este recibo fija el commit de código ejecutado. El commit posterior que incorpora
el recibo solo cambia documentación y se somete nuevamente a los cuatro jobs; su
HEAD/checkout final se publica en la PR y en commit.txt de sus artefactos, sin atribuir
anticipadamente a ese commit los resultados de esta ejecución.


Reconstrucción independiente `35580899208` sobre c325788: cuatro ABI SUCCESS.
Los cuatro `libjingle_peerconnection_so.so` descargados coinciden exactamente con
los hashes fijados a partir de `35577083313`; recibo JSON actualizado. Esto comprueba
reproducción del JNI con esa receta/toolchain, no con cualquier compilador, ni igualdad
de timestamps de los ZIP crudos. El AAR normalizado local también se reprodujo.
Tras añadir el recibo: guarda de 256 archivos y 125 pruebas de herramientas, salida 0.


## Regresión final de cierre del pipeline nativo

La revisión del fixture encontró una limitación de cobertura: el marcador
failedClosed acreditaba estado/autorización, pero no medía el cese del callback ADM.
No se reprodujo un fallo productivo. Se reforzó el fixture sin cambiar producción:
después de observar estado terminal se espera nominalmente 1.000 ms y se observa
nominalmente durante 500 ms, midiendo ambos intervalos con reloj monotónico. Se exige
inicio de observación entre 1.000 y 2.000 ms y duración entre 500 y 1.500 ms; los
márgenes acotan jitter del runner y un retraso superior falla. Se exigen cero callbacks
de captura sintética durante esa ventana. El controlador
rechaza el marcador antiguo o una ventana vacía, más larga o con callbacks tardíos.
No es una medición de micrófono físico ni del tiempo desde una revocación remota aún
no recibida; es cierre del pipeline nativo local durante una ventana acotada.

Sobre 365607f + esta regresión: assembleConnectedDebugAndroidTest, salida 0;
126 pruebas de herramientas, salida 0. Dos AVD nuevos y el AAR fijado:
`python scripts/run_voice_integration.py --a emulator-5554 --b emulator-5556 --scenario lock --reports /tmp/umbra-voice-integrated-device/closure-timed-lock`
y el mismo comando con `--scenario storage-failure --reports /tmp/umbra-voice-integrated-device/closure-timed-storage`: salida 0 ambos, cero callbacks
tardíos en ambos extremos, PCM/Opus, Signal/HTTPS y mute/unmute previos. Los reportes
locales se guardaron en /tmp/umbra-voice-integrated-device/closure-timed-{lock,storage};
los contadores sanitizados están en el recibo JSON. La CI vuelve a ejecutar estos
controles también para TURN caído, pérdida de confianza, credencial vencida y dispositivo
revocado. No se cuenta como ejecutada esa repetición hasta consultar el resultado del
commit que incorpora la regresión.


Resultado de la repetición documental previa: CI35583363861, cuatro SUCCESS sobre
HEAD365607f7cf6916c9c30ce288501075d23f68349f; commit.txt confirma checkout
f1a393612a76c182d1fd2436ef6f7802c9a2b849. Esa ejecución precede la nueva comprobación
ADM, por lo que no se usa para darla por aprobada. La repetición local con intervalos
monotónicos midió inicio a 1.000 ms y ventanas de 500/500 ms (lock), 501/500 ms
(error de almacenamiento), cero callbacks en los cuatro extremos observados.


## Fallo de CI35585672689: frontera temporal del verificador HTTPS

HEAD7f085571b0c41e6519f03c2daa6e09b16a0e5b71: repository-guard, relay-and-core y
relay-container SUCCESS; Android FAILED en `test_relay_integration.py`, antes de
instalar/arrancar AVD. Por tanto la nueva regresión ADM NO se ejecutó en ese intento;
no existe emulator.log de ese intento que pueda demostrar su resultado.
Error visible: `AssertionError: relay rejects excessive TTL` en
RelayIntegrationTest.java:99. El helper antiguo omitía el HTTP recibido.

El fixture construía expires=Bytes.now()+604801, apenas un segundo fuera del máximo.
El log sitúa el control previo a 09:57:43.994 UTC y el error a 09:57:44.010 UTC.
Reproducción determinista sobre FastAPI/SQLite con reloj controlado: el mismo mensaje
modificado recibe 400 en t y 409 en t+1, cuando su expiración queda dentro del máximo
y alcanza la comprobación de colisión con el ID ya almacenado. Esto demuestra la
carrera del verificador y es compatible con el límite de segundo observado en CI;
el HTTP concreto de aquel intento no quedó registrado y no se inventa.

Corrección: el rechazo de integración HTTPS utiliza MAX_TTL+3600, inequívocamente
inválido durante el timeout de la petición. MAX_TTL productivo permanece 604800.
Se añaden pruebas backend con reloj controlado para expires=now, now+1,
now+MAX_TTL y now+MAX_TTL+1, más la transición 400→409 sin alterar el mensaje almacenado.
Así se conserva explícitamente la frontera exacta, no se aumenta el TTL ni se excluye
el caso. El helper informa esperado/recibido como códigos HTTP, sin cuerpos o secretos.

Resultados después de corregir:
- `. .venv/bin/activate; PYTHONPATH=relay python -m pytest relay/tests/test_relay.py -q`:
  38 pruebas, salida 0. Un intento anterior sin PYTHONPATH terminó en 2 durante la
  colección (umbra_relay no importable); no se contó como prueba ejecutada.
- `bash scripts/test_local.sh`, venv y JDK21: salida 0; 149 backend, 105 escenarios
  Java y 12 controles estáticos separados.
- `python scripts/test_relay_integration.py`, venv, JDK21 y SDK configurado: salida 0;
  28 controles principales HTTPS, más escenario multidispositivo, con Engine,
  libsignal JNI, relay aislado SQLite y verificación TLS real.

La siguiente CI debe validar el commit que integra esta corrección y la regresión
ADM; ninguna ejecución verde anterior se atribuye al código nuevo.
