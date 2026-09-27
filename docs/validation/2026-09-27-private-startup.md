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
