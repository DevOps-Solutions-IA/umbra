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
