# Private startup — 2026-09-27

Estado inicial del recibo: PARTIAL; no aceptación de producción.
Base verificada: PR #13 OPEN/DRAFT, a78a3ffd6962d15acf38efc070ca8f88385fc1fa.
Worktree independiente, rama codex/private-startup-no-network. No se modifican
main, admisión ni UI de Claude. Cambios anteriores y sus recibos se conservan.

## Ejecuciones locales durante implementación

- Python 3.13.12, JDK 21.0.11, Gradle 8.13, AGP 8.13.2, SDK 36/build-tools 35.0.0.
- `bash scripts/codex_setup.sh`: 0; `. .venv/bin/activate` antes de Python.
- `bash scripts/test_local.sh`: 0.
- `python scripts/build_android.py --check-only`: 0; no equivale a compilación.
- `python -m unittest discover -s scripts/tests -p 'test_*.py'`: 159, 0 fallos,
  antes de añadir cuatro casos de evidencia startup.
- `python scripts/test_relay_integration.py`: 0; relay HTTPS/SQLite y libsignal
  reales, incluido multidispositivo, ubicación, señalización y revocación.
- JVM intermedia: 200 connected / 150 offline, sin fallos/errors/skips; los tres
  casos de carrera posteriores pasaron en su ejecución enfocada (11 gate tests).
- `python scripts/build_android.py`: 0 sobre un árbol intermedio: dos APK debug,
  JNI Signal y políticas APK aprobadas. Offline sin permisos de red/audio/cámara.
  Estos binarios no validan los cambios añadidos después ni un futuro HEAD.
- Compilación de instrumentación de ambos flavors aprobada antes del nuevo
  listener. El primer intento de compilar ese listener detectó JSONArray.isEmpty
  no disponible en Android; corregido a length()==0, nueva ejecución pendiente.
- Primer test del parser de contadores falló al consumir un salto de línea;
  corregido. Los cuatro tests de evidencia startup pasan, sin rebajar controles.

## Causas y correcciones

Persistir profile.online permitía que refresh/tick reactivara relay tras desbloquear.
Las rutas públicas de RelayClient no exigían consentimiento central. Bluetooth
conservaba autenticación pero carecía de un lease de Nearby separado. Se introduce
el gate previo al I/O, integrado con admisión, Vault, Signal call consent y transportes.
No se altera el cifrado ni la autoridad de admisión.

Regresiones específicas prueban que un lease viejo no bloquea la sesión nueva,
que un lock mientras espera admisión no publica CONNECTED, y que una cancelación
en curso impide desbloquear una nueva generación antes de terminar callbacks.

## Pendientes de ejecución

- Laboratorio nuevo Android debug/R8, contadores por UID, DNS trampa con control
  positivo, pérdida de red, sensores/scan, force-stop y ambas variantes.
- CI propia del HEAD final: Verify, vault, admission, private-startup, voz R8,
  video, modulación y focused/RFCOMM.
- Hashes de los artefactos finales y checkout de integración.
- Hardware físico/TEE/StrongBox/radio/sensores: no probado. KVM local no utilizable
  por el usuario de esta sesión; no implica ausencia de KVM en GitHub Actions.

Ningún resultado histórico de PR #13 se atribuye a estos cambios. Los laboratorios
son sintéticos y el perfil R8 instrumentable no es el APK exacto de producción.

## Primera publicación: controles locales actualizados

- `gradle ... compileConnectedDebugAndroidTestJavaWithJavac
  compileOfflineDebugAndroidTestJavaWithJavac lintConnectedDebug lintOfflineDebug
  testConnectedDebugUnitTest testOfflineDebugUnitTest`: 0; 203 connected y 150
  offline, sin fallos/errors/skips. La instrumentación está compilada, no ejecutada.
- Herramientas: 163 tests, código 0 (incluye cuatro nuevas regresiones del recibo).
- `repository_guard.py`: 0, 376 archivos en la comprobación local; no auditoría.
- No nueva dependencia ni cambio de permisos. Laboratorio nuevo preparado para
  debug/R8 con UID IPv4/IPv6, DNS NXDOMAIN loopback con control positivo, HTTPS/Signal
  y force-stop. Su primera ejecución CI permanece pendiente.

## Primer HEAD publicado / primer rojo conservado

HEAD `5163497ac7fdf2591d1ee2753d57a1c4dade3aee`, árbol
`4969197321dbb2a2e3bf69ca9847ff00e7488176`; PR #14 draft contra admisión.
La compilación local `python scripts/build_android.py --release` terminó con 0:
203 JVM connected / 150 offline; políticas debug/release y Signal JNI aprobadas.
SHA-256 locales de ese commit:

- connected debug: `9af8dc6db507ce403fb536ea8714b0480852077763c407055bf309e5355febb4`
- connected release sin firma: `5ded6e1de530223fcc07844bda2a16b7a2f362a5ba66f76c15f0e4ecdfcb1636`
- offline debug: `a927ca6c568fff751906f9896809138f3df7c22339a929487bd744257f2ad46a`
- offline release sin firma: `709e1046d67ea1ee53a065fee26a6428ce13503eac4798d02a94d858fd7f0f07`

Nueva integración HTTPS sobre ese commit: 0. No instrumentación local (KVM).

Actions **36353247177** falló en el laboratorio nuevo:

- Debug, job **108715839299**: tres pruebas de dominio aprobadas; UID con cero
  paquetes IPv4/IPv6 en Activity fría, Engine frío y después del desbloqueo,
  ventanas de ~5 s; sin acceso a sensores/scan observado. Tras connect hubo
  23 paquetes IPv4 / 19659 bytes y el fixture alcanzó el intercambio HTTPS/Signal.
  El control positivo DNS dio cero: **no se acepta ausencia de DNS** cuando falla
  ese control. Configuración equivocada del arnés: `-dns-server` recibe la IP
  del servidor del host, no el alias del host visto desde Android. Se corrige
  `10.0.2.2` a `127.0.0.1`, manteniendo el servidor ligado exclusivamente a loopback.
  Documentación y ayuda del emulator verificadas; requiere repetición real.
- R8, job **108715839501**: tres fallos `NoSuchMethodError` (`Engine.connectivity`
  y `setOnline`). Las nuevas clases de test no estaban incluidas en TraceReferences;
  R8 eliminó/integró entradas que el APK de test externo llamaba. Se añaden solo
  PrivateStartupTest, PrivateStartupFixtureListener y su AdmissionLab al trazado
  del perfil de laboratorio. Se mantiene optimización y ofuscación; no keep global.

Checkout de Actions de este intento: `aa1f62a61af7b07c3b0f76fe51d5fdd76fc405d4`.
Los artefactos del intento rojo se conservan en Actions; no se reinterpretan como
aceptación. Se refuerza además cleanup para intentar todas las acciones y reportar
los fallos juntos aunque falle ADB; el diagnóstico DNS sintético se conserva.

Verify **36353247132** también quedó rojo: el runner exigía el total antiguo de
39 casos connected. El artefacto confirma **OK (42 tests)**, sin fallos, tras añadir
los tres PrivateStartupTest. Se actualizan los totales exigidos a 42 connected /
40 offline (39/37 anteriores + tres), sin reducir ni omitir pruebas. Offline y
RFCOMM de ese job no llegaron a ejecutarse después del rechazo del verificador.
Los tres jobs no Android de Verify pasaron. Los laboratorios password y admission
completaron ambas matrices en este primer HEAD; no validan el siguiente commit.

La reconstrucción local corregida del perfil vaultLab R8 de ambos flavors y sus
APK de instrumentación terminó con 0. Las reglas generadas contienen específicamente
`Engine.connectivity()` y `setOnline(boolean)` manteniendo allowoptimization y
allowobfuscation. La ejecución del binario corregido en AVD sigue pendiente.

## Modulación: contrato de cierre del arnés

Run **36353247133**, debug y R8, rojo en `lock`. La evidencia previa de voz y
modulación remota sí se produjo; `closure-observation` registra 1000 ms hasta la
ventana, 500 ms observados y cero callbacks tardíos. El fallo posterior está en
`VoiceEngineFixtureListener:486`, `voice.close(); pump(relay,engine)`: después de
lock/unlock, el fixture intenta hacer fetch con el RelayClient de la generación
anterior. El nuevo gate rechaza correctamente esa operación. No se habilita una
reconexión para conservar el comportamiento anterior.

Corrección del fixture: solo en el escenario cuyo lock local realmente se aplicó,
se exige LOCKED_PRIVATE, media terminal, transmisión denegada y rechazo explícito
de `relay.poll` con el cliente viejo. Las demás salidas siguen ejecutando su pump
normal y no toleran errores inesperados. El host exige un marcador adicional de
esta aserción para aceptar `lock`. Se preservan todos los criterios de audio,
modulación, ventanas de captura y cancelación. Nueve pruebas del verificador pasan;
el perfil mediaLab R8 corregido compila. Reejecución real todavía pendiente.

Validación intermedia Android: run 36353999933, HEAD
`46d6fbc8103acc73088392dd64574a4a1a211901`, checkout
`962da28827a7e1c924376c84204743ef5ff346b3`, completó ambas matrices
(debug/R8), connected y offline. Los cuatro recibos descargados indican PASS.
Las ventanas de observación de aproximadamente 5 segundos registraron cero
paquetes del UID, cero consultas al hostname controlado y ningún acceso AppOps
a sensores/scan antes de conectar, después de desconectar y tras force-stop.
El control positivo connected obtuvo dos consultas DNS en ambas matrices y
tráfico HTTPS/Signal (26/27 paquetes respectivamente). Esto valida este SHA,
no sustituye la ejecución final de la corrección del fixture de media.

## Iteración de coordinación mute

HEAD `405a77e1975a77f547a127d4575a24a7489f0665`, árbol
`57fce5032ca4243945b9d44080d91bc1783983bc`: startup 36354688668,
password 36354688670 y admission 36354688661 terminaron SUCCESS.
Startup ejecutó checkout `f6b6d005321d491d93eb2a802a53b09fff3a93b2`,
mismo árbol; cuatro recibos PASS, control positivo de dos consultas DNS
por connected, cero tráfico de UID en las ventanas privadas.
Local: build_android.py --release exit 0 (204 JVM connected/150 offline),
163 herramientas exit 0; test_local.sh primero falló con JDK heredado
incompatible (release 21), y pasó al repetir con JAVA_HOME JDK21 explícito.

Modulación debug 36354688653 falló en video antes de modular: `Decoded
peer tone continued while both endpoints muted` (artefacto 10943472599).
Verify previo 36353999930 había fallado con la misma aserción en
allocation-expiry. Además se conserva el overrun de ventana de modulación
de 36353999915; todavía no tiene causa demostrada.

La inspección encontró una carrera verificable del arnés: la observación
remota se medía desde el mute LOCAL, sin confirmación del mute del otro
AVD. Una demora de coordinación podía contabilizar voz legítimamente aún
no silenciada por el interlocutor. Se añadió una barrera: ambos extremos
confirman retorno del mute nativo antes de abrir observaciones. Se preservan
2 s de drenaje, al menos 1.2 s de observación y máximo tres tonos; se rechazan
ventanas vacías o superiores a 2.5 s. Los recibos conservan tiempos monotónicos
locales, sin comparar relojes entre AVD. Regresiones del coordinador prueban
confirmación tardía, ausente y ventana vacía. Esto corrige la carrera del
arnés; no atribuye todavía todos los fallos históricos a esa causa ni prueba
por sí solo el mute multimedia. Requiere nueva ejecución AVD/debug/R8.

La matriz R8 de modulación 36354688653 sí completó SUCCESS en `405a77e`,
incluido el nuevo rechazo del relay después de lock/unlock; el workflow
permanece FAILURE por su matriz debug. La corrección de barrera compila
con `-PumbraMediaLab=true` (exit 0, R8 real) y 165 pruebas de herramientas
pasan. La primera invocación Gradle sin esa propiedad falló porque el
build de laboratorio está deliberadamente deshabilitado por defecto.

En `0b33802`, modulación debug 36355391928 pasó el mute inicial pero falló
más adelante: `Remote processing mismatch step=1, expected=quiet,
natural=0, modified=21, loud=22`. El ciclo de cambios del modulador también
carecía de confirmación del emisor antes de iniciar la ventana del receptor.
Se extendió la barrera a cada paso, con índice validado y tiempos locales
registrados; se rechazan confirmaciones de otro paso. La acción se aplica
una sola vez. No se cambian los límites de settle/observación ni el máximo
de tres buffers inesperados. Todos los archivos nuevos de coordinación se
eliminan al inicio y al final del escenario para evitar evidencia residual.
La regresión del coordinador prueba una confirmación tardía y rechazo de
índice anterior. El efecto nativo sigue requiriendo evidencia remota real;
esta prueba de herramientas no se contabiliza como audio ejecutado.

`2609d35` startup/password/admission terminaron SUCCESS. Modulación debug
36355889735 reprodujo `Processing transition observation began too late`:
la observación se temporizaba en el bucle que también ejecuta HTTPS síncrono.
Se separó la medición del coordinador: `DecodedAudioWindow` (solo androidTest)
recibe contadores y reloj monotónico del callback de audio ya decodificado.
No retiene PCM. El controlador lee un resultado inmutable; una lectura tardía
no cambia el intervalo observado. Si los callbacks mismos llegan tarde,
la prueba sigue fallando con los límites originales (settle 1200–2500 ms,
observación 2000–3500 ms). Ausencia de callbacks nunca produce éxito.
Se utiliza también para mute con sus ventanas positivas. Una regresión Java
real reproduce lectura tardía del coordinador, callback tardío, contadores
inválidos y ventana incompleta. No modifica DSP, Opus ni código productivo.

Focused histórico 36354688712/nearby falló antes de RFCOMM: UiAutomator
informó `null root node` al obtener la pantalla del segundo AVD. No existe
resultado de handshake en ese intento; no se atribuye al protocolo.

Esta iteración pasó 167 pruebas de herramientas. El primer build R8 rechazó
la clase nueva ausente del source jar de TraceReferences. Se añadió únicamente
`DecodedAudioWindow*.class` a ese conjunto de fixtures y el build R8 posterior
pasó (21 s, exit 0), conservando minificación y optimización. La aceptación
AVD de esta medición todavía requiere la ejecución del commit publicado.

## Coste de autorización del gate

`854cfaf` Focused R8 y Nearby pasaron, pero Focused debug agotó plazos de
negociación en varias repeticiones. La inspección identificó trabajo nuevo
redundante: cada comprobación de fragmento de RelayClient ejecutaba de nuevo
`requireAdmission()` (lectura, binding y firma), además de la autorización
completa ya existente en la operación. CallService repetía también esa
validación antes de `Engine.authorizeTransportSelf()`, que la realiza de nuevo.
La regresión mide seis lecturas de registros por comprobación completa.

Se separan `Lease.checkEpoch()` (bóveda, generación y cancelación; cero lecturas
en 100 comprobaciones) y `Lease.check()` (validación completa). Relay conserva
validación completa antes de crear URLConnection y antes de aceptar respuestas,
incluido HTTP 204; conserva además sus pruebas de admisión/posesión existentes
antes de escribir. Cada fragmento sigue comprobando cancelación y generación.
CallService conserva la validación completa de Engine y usa el gate de época
sin duplicarla. Revocación conocida/lock desconectan e invalidan la época.
No se cachean credenciales ni se amplía su TTL.

Regresiones: rechazo antes de DNS/socket si membresía es ilegible; la época
invalidada sigue rechazada; revisión de invitación que espera almacenamiento
no puede tomar prestada una conexión nueva. Los plazos multimedia y del arnés
no se amplían. La correlación con los retrasos debug requiere repetición AVD;
los errores históricos no se reclasifican como aprobados.

La repetición acotada de modulación debug sobre `854cfaf` (run 36356592527,
intento 2) también falló, esta vez durante video después de completar voz.
No se considera resuelta por reintentar. Se conserva antes de la corrección
de coste. La implementación corregida pasó 207 JVM connected/150 offline,
167 herramientas, build debug/release/lint/JNI/APK policies y los 28 controles
de integración HTTPS real (todos exit 0). La nueva carrera de invitación
aprobó el rechazo de reconexión mientras espera una transacción.

## Validación de 512c02e y presupuesto del laboratorio

HEAD `512c02e7738bfa7725314ba5386892b774912b61`, checkout de Actions
`ed3d285820abd528b0904e24f7821e82c319f6de`, mismo árbol
`387903e8a8d387c30cbc1adfc208f3fa6b4734e6` (API git/commits verificada).
Verify 36358139220 SUCCESS: 42/40 instrumentadas connected/offline,
RFCOMM emulado positivo y rechazo de no admitido, APK/JNI/lint y backend.
Password 36358139176, admission 36358139183, startup 36358139191,
voz R8 36358139185 y modulación 36358139186: SUCCESS.
Focused 36358139180: R8/Nearby SUCCESS en intento 1; debug falló antes de
media con toybox nc `Network is unreachable` en el preflight UDP. Un único
reintento acotado aprobó sus nueve casos. No se demostró la causa de esa
intermitencia ni se modificó el preflight; ambos resultados se conservan.

Video 36358139192: R8 aprobó 32 casos. Debug completó 30 PASS y un FAIL,
y Actions lo canceló a los 45 minutos durante IPv6/TLS (NO EJECUTADO completo).
El FAIL fue degraded-network: ambos extremos llegaron ACTIVE, decodificaron
patrones remotos, apagaron cámara y conservaron audio; agotaron el presupuesto
compartido de 70 s en stage=4, reactivación, todavía con native=ACTIVE/failure=none.
La primera negociación/observación consumió ~19 s (1911/1948 callbacks de audio)
bajo netem 128 kbit, 80 ms y 2% pérdida; la parada observada consumió 4.2–5 s.
No se trata de cámara capturando tras apagar ni de fallo UDP en este caso.

Se conserva el presupuesto de voz de 70 s y se asignan 25 s para cada una de
las dos negociaciones adicionales de video: presupuesto total de arnés 120 s,
host 140 s con margen de coordinación. Modulación permanece 140/160 s.
No se cambia el TTL productivo de invitación de 60 s ni sesión de 180 s, ni
los límites de cancelación, silencio, frames o patrones. Se registra ahora
el tiempo monotónico transcurrido en cada recibo de video. La aceptación
remota de esta corrección está pendiente de la nueva CI; no es éxito por diseño.

La matriz de video se divide en dos particiones de 16 casos por configuración,
con artefactos distintos y el mismo timeout global de 45 minutos. Una regresión
comprueba cobertura exacta, sin duplicados/omisiones y rechazo de particiones
vacías o inválidas. Todos los errores siguen propagándose. No se elimina ningún
escenario. Los artefactos históricos están preservados fuera del repositorio en
`/home/wundah/umbra-evidence/private-startup-512c02e/` y en las ejecuciones citadas.

Repetición local de test_local.sh sobre 512c02e: exit 0, 204 backend, 20 utility,
85 JVM adicionales. Advertencia Starlette/httpx conservada, sin supresión.

Validación local de la partición/presupuesto: 168 pruebas de herramientas,
repository_guard (378 archivos), build debug/release/lint/APK y compilación
instrumentada debug y R8: exit 0. Un comando inicial intentó combinar la tarea
debugAndroidTest con -PumbraMediaLab=true y falló porque ese perfil selecciona
otro testBuildType. Se ejecutaron correctamente en invocaciones separadas;
no se modificó la configuración para ocultar el error de invocación.
