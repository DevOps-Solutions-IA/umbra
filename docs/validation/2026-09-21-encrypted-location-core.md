# Tercera entrega: ubicación cifrada — 2026-09-21 UTC

Estado: implementación e integración local verificadas con los límites siguientes.
La CI del HEAD publicado se registra en el recibo de la PR dependiente; este informe
no presenta una ejecución histórica como validación del commit final.
Datos exclusivamente sintéticos. No es auditoría ni aprobación de uso con secretos.

## Base y alcance

PR #5 consultada OPEN, HEAD `c538b68a803b3c7d84d4fbe3700264f4db02877e`.
CI histórica `35544833512` comprobada por API: cuatro SUCCESS sobre ese HEAD.
Rama nueva `codex/encrypted-location-core`, base `codex/device-linking-core`.
No se modifica main ni ramas previas. HEAD y checkout de Actions se registrarán
separadamente en el recibo final de la PR, con comparación de árboles.

Implementación: [ADR](../adr/ADR-location.md), [protocolo v1](../protocol/LOCATION.md).
MANUAL sin proveedor; PRECISE/APPROXIMATE/ZONE con adaptador LocationManager AOSP;
START/UPDATE/STOP, consentimiento de sesión y conjunto destinatario fijo, reducción
antes de persistir/cifrar, política central y entregas libsignal independientes.
No hay cambios de esquema/endpoints del relay; cuatro regresiones exigen rechazo
de coordenadas/tipo/mapa en claro. No se añade geocodificación ni SDK de mapas.

La migración solo añade Records location-in/location-out; conserva claves, sesiones,
bóveda y listas existentes. Reabrir no restaura grants de captura. Nuevas claves
no amplían sesiones. Revocar reduce destinatarios; suspender confianza interrumpe
persistentemente, aunque después se restaure. El botón detener cancela localmente
incluso si escribir el estado/STOP falla. La autorización de transporte comprueba
el ID/ciphertext pendiente, además del contacto y lease anteriores.

## Entorno y comandos

Python 3.13.12, `.venv` activada. JDK seleccionado explícitamente:
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` y su bin al comienzo de PATH.
Gradle 8.13, AGP 8.13.2, compile/target 36, mínimo 31, build-tools 35.0.0,
libsignal 0.102.3. SDK `/mnt/c/Android/sdk-linux`; emulador API 35 default x86_64,
KVM obligatorio, directorios AVD efímeros. No actualización indiscriminada.

Comandos de validación utilizados (resultados finales se completan abajo):

```bash
bash scripts/codex_setup.sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/mnt/c/Android/sdk-linux
. .venv/bin/activate
bash scripts/test_local.sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/repository_guard.py --git-history
python scripts/build_android.py --check-only
python scripts/build_android.py --release
.umbra-tools/gradle-8.13/bin/gradle -p android --no-daemon \
  :app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest
python scripts/test_relay_integration.py
```

Scripts de AVD: ci_emulator.sh, run_android_instrumentation.py,
run_location_restart.py, smoke_release_launch.py y ci_bluetooth.sh. Ejecutados
como el usuario del workspace con grupo kvm, sin relajar aceleración o Keystore.
Contenedor: `docker build --pull -t umbra-relay:location-validation relay`, arranque
con network none, read-only, tmpfs sintético, cap-drop ALL y no-new-privileges;
UID 10001 y health HTTP interna. Sin despliegue ni publicación release.

## Fallos iniciales y correcciones, sin sustituirlos por resultados anteriores

- Baseline: java del PATH era 25.0.3 y javac 21.0.11. Backend 136 pasó; verificador
  Java falló. Se seleccionó explícitamente JDK 21 y se repitió. No fue fallo del
  cifrado ni del AVD histórico.
- Primer build: intento de usar PermissionChecker de un paquete no público produjo
  error de compilación. Se utiliza comprobación de permiso Android y AppOps público.
- Lint detectó MissingPermission al no reconocer el helper/catch genérico. Se añadió
  manejo explícito de SecurityException al solicitar updates, sin suppress global.
- Guardas de fuente/prueba rechazaron los permisos de ubicación que antes no existían.
  Se amplió la lista únicamente con COARSE/FINE, se conserva rechazo de BACKGROUND,
  servicios de ubicación, cámara/audio y red offline. Se añade regresión explícita.
- PairingTest tuvo un fallo de rechazo en una mutación por sufijo literal `AA`.
  Esa técnica puede dejar una firma sin cambios. El fixture ahora cambia siempre un
  byte de la firma decodificada y recodifica canónicamente; conserva el rechazo.
- Primera instrumentación: 23 ejecutadas, tres fallos positivos del proveedor por
  permiso rechazado antes de captura, reproducidos una segunda vez. Evidencia:
  permisos runtime concedidos y AppOps IGNORED, por falta de Activity visible.
  El fixture ahora usa ActivityScenario visible y AppOps UID foreground; no desbloquea
  Vault ni concede ubicación background. Las repeticiones posteriores pasaron 23/23
  en ambas variantes. No se reclasifican los dos intentos fallidos como aprobados.

## Separación de evidencia y límites

- JVM: libsignal real, memoria transaccional sintética; no durabilidad Android.
- HTTPS: Engine y RelayClient reales contra relay local TLS/SQLite, identidades A1/A2/B1.
- Android: SQLite real de laboratorio dentro de UID debug y test APK; no Vault
  cifrado positivo sobre Keystore hardware. LocationManager recibe posiciones
  sintéticas de un proveedor de prueba; no GPS físico ni ubicación del equipo.
- Force-stop: control del proceso entre operaciones, con cola ya comprometida;
  no muerte en mitad de commit ni corte de energía. Su resultado debe registrarse.
- RFCOMM: stack Bluetooth de dos AVD; no TCP ni radio física. Su resultado se registra
  para ambas variantes, sin inferirlo de la suite JVM.
- Release/R8: compilación/inspección y arranque de UI bloqueada con firma efímera;
  no ceremonia de desbloqueo hardware ni distribución de producción.

Bloqueo al salir de primer plano y a los cuatro minutos se mantiene. Los máximos
15 min/1 h/8 h no conceden excepción. Sin ACCESS_BACKGROUND_LOCATION ni servicio
persistente. No recuperación de captura tras reinicio ni backlog de trayectoria.
Solo la última posición y estado necesario; tombstones acotados para replay.
El receptor no puede demostrar la ubicación física, impedir capturas/copia externa
ni recibir instantáneamente revocaciones mientras esté desconectado. Offline no
usa permisos de red, pero el proveedor del SO puede utilizar sus propios servicios.

BLOCKED: GPS físico, radios físicas, Keystore hardware y bóveda positiva en teléfonos.
NOT EXECUTED: muerte durante commit, matriz completa de dispositivos Android, ocho
horas de captura continua (además incompatible con el bloqueo actual), auditoría humana.
Señalización, llamadas, audio/video, TURN y recuperación exportable no implementados
en esta entrega; siguen en roadmap. No rediseño gráfico.


## Revisión de las nuevas rutas

| Hallazgo / riesgo | Archivo | Corrección y evidencia |
|---|---|---|
| Alta: A2 podía conservar un grant al bloquear/desbloquear a su propio administrador | location/LocationService.java, suspendIdentity | Se incluye owner además de device/recipient. Regresión falló realmente en Offline: 1 fallo en 109; después pasó, sin excluirla. |
| Alta: Detener en UI podía esperar al worker antes de invalidar escrituras | ui/MainActivity.java y LocationService.cancelCapture | Invalidación inmediata sin I/O; STOP/persistencia posterior. Regresión obtiene autorización de escritura, cancela y exige rechazo antes de ejecutar el worker. Hallazgo de revisión, no se afirma una reproducción de la carrera del sistema. |
| Alta: cambio de permiso entre captura y entrega | AndroidLocationCapture y LocationService.bindCapturePolicy | Comprobación Android asociada al grant y revalidada en escritura, con denegación terminal. Regresión de política y pruebas AppOps reales; no una afirmación sobre GPS físico. |
| Media: secuencia mayor con medición más vieja podía reemplazar la última posición | LocationService.publish/receive | Rechazo local e ignorado transaccional remoto de mediciones anteriores; prueba Signal con payload autenticado adversarial. |
| Media: STOP confirmado ocupaba capacidad hasta el plazo original | LocationService.maintain | Libera grant tras ACK/expiración de su pendiente, mantiene tombstone. Ocho ceremonias consecutivas en regresión. |
| Media: vaciar chat debía incluir la ubicación nueva | Engine.clearConversation / LocationService.clearPeer | Borra coordenadas y cancela sesiones del destinatario; mantiene tombstones ocultos que impiden replay. |

Los errores de disco se inyectan tanto al crear entrega como al persistir recepción;
se exige rollback del ratchet/estado/ACK y éxito del mismo ciphertext al recuperar
almacenamiento. SQLite Android se reabre realmente después del fallo de recepción.
Esto no se presenta como muerte durante commit. Force-stop se prueba por otro helper,
con PID vivo comprobado antes, desaparición después y reapertura posterior.

Las seis advertencias lint heredadas sobre versiones disponibles y atributo back
API 33 se mantienen visibles. Se conserva compatibilidad API 31 de AppOps mediante
unsafeCheckOpNoThrow (deprecado en 36); no se suprime globalmente la nota de compilación.
Starlette/httpx conserva su advertencia existente. No se actualizaron dependencias
por una sugerencia de versión nueva ni se eliminaron controles de aceptación.

## Resultados locales nuevos

Todas las filas siguientes terminaron con código 0; los intentos fallidos anteriores
se conservan arriba. Runtime final probado: `8712cef` (el commit posterior solo
completa documentación). Instrumentación y RFCOMM locales: `1e32c43`, antes del
ajuste final de antigüedad monotónica del receptor; la CI de la PR vuelve a ejecutar
el conjunto sobre el checkout final. No se confunden esos dos árboles.

| Control | Resultado |
|---|---|
| Setup / preflight | Python y SDK disponibles, requisitos comprobados |
| test_local.sh | 140 backend, 105 escenarios Java; 12 controles estáticos separados |
| unittest scripts/tests | 103 pruebas, sin fallos |
| Build debug/release connected/offline | 4 APK, lint y políticas de manifest/DEX/JNI aprobados |
| JVM libsignal | 113 por variante, sin fallos ni omitidas |
| HTTPS aislado | Engine/RelayClient/relay TLS SQLite reales; manual multidispositivo y START/UPDATE/STOP, ACK y revocación |
| Android instrumentado | 23 por variante, sin fallos; proveedor AOSP sintético y SQLite real de laboratorio |
| Force-stop | Ambas variantes: PID vivo, terminación real, reapertura sin captura ni backlog |
| Release/R8 smoke | Ambas variantes: arranque bloqueado con firma efímera, sin desbloqueo hardware |
| Bluetooth | Ambas variantes, dos AVD y RFCOMM real del stack emulado, ubicación cifrada y ACK |
| Contenedor | Build y health aislado no root aprobados |

La regresión final exige LAST_KNOWN después de 31 segundos monotónicos aunque el
reloj de pared no avance. Compilación completa, 113 JVM por variante y HTTPS se
repitieron después de ese cambio. No hubo exclusiones ni rebajas de políticas.

Cobertura A–H: A y D por Signal JVM + HTTPS; B por LocationManager instrumentado;
C por parsers/JVM/SQLite; E por listas y entregas Signal independientes; F por
leases, AppOps, SQLite y force-stop; G por colas acotadas, TTL y antigüedad;
H por RFCOMM emulado en connected/offline. La muerte durante transacción no se
infiere de F. No se probó GPS ni Bluetooth físico.

SHA-256 de APK locales del runtime `8712cef` (debug no publicable; releases sin firma):

| APK | SHA-256 |
|---|---|
| connected debug | `7599c65f15601f3e283072b7eb9ed6f3190d5d2db65b3d2cc2142ebf970f132b` |
| offline debug | `89afac82e6d45f12b7d72b74e7d7b18505db87fc32f6c3cda69a35de9e67d9c2` |
| connected release unsigned | `970ce38d9bc144963e087fe27d79b9123097dddc7f58451b33daa6cd5641b6c5` |
| offline release unsigned | `ee60bd938ae72eec4230d19cdb1ddce83902fef2126f81b5b77d76db391d7372` |

Imagen local del contenedor:
`sha256:fe52c19e92be3e19c33a5dc15ab2813dc1fe27d1fd0b3b96eaf26d744d92ff61`.
Los hashes CI se registran separadamente: la firma debug del runner puede diferir.
No se afirma reproducibilidad binaria entre firmantes.

La UI mínima requiere un conjunto de dispositivos previamente aprobado mediante
las APIs/harnesses existentes. No equivale a un onboarding definitivo ni demuestra
el desbloqueo de producción en un teléfono. La revisión humana de autenticación,
wire format y persistencia sigue siendo necesaria antes de fusionar.
