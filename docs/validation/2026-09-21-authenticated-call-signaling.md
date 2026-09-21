# Cuarta entrega: señalización autenticada — 2026-09-21 UTC

Estado: señalización implementada y validada localmente con los límites siguientes.
El recibo final de la PR registra el HEAD, checkout y CI; no se atribuye aquí CI
histórica al código nuevo.
No es audio/video ejecutado. Solo identidades, coordenadas y controles sintéticos.

## Base y alcance

PR #6 OPEN comprobada al iniciar y durante el trabajo, HEAD
`78af23b3a87e1f1d56ee5340e648be4d7e6c2b17`. Su CI histórica no valida esta entrega.
Rama `codex/authenticated-call-signaling`, PR dependiente hacia
`codex/encrypted-location-core`. No cambios en main ni ramas anteriores.

[ADR](../adr/ADR-call-signaling.md), [contrato v1](../protocol/CALL_SIGNALING.md).
Engine cifra controles por destinatario con libsignal real; selección atómica por
el dispositivo iniciador, consentimiento de sesión, plazos 60/180 s, sobres ≤30 s,
terminales persistentes y rechazo de nuevas membresías, replay y revocación.
SQLite relay no cambia: sigue transportando envelopes opacos. Migración local
aditiva `calls`; pérdida parcial de identidad no habilita reinicialización.

## Política TURN: separación de afirmaciones

- Política implementada: únicamente RELAY_ONLY, DIRECT_ALLOWED inactivo y rechazado;
  configuración local inmutable, errores terminales, sin fallback automático.
- Contrato preparado: API Android upstream `RTCConfiguration.iceTransportsType =
  IceTransportsType.RELAY`, verificada en fuente oficial; TLS seguro y TURN locales.
  No hay un AAR/JNI WebRTC seleccionado/instalado en esta PR. No se afirma que esta
  asignación ya se ejecute en un motor nativo.
- Transporte multimedia real: NOT EXECUTED. No TURN desplegado ni sockets de media,
  micrófono/cámara o captura. `prepareMedia` falla cerrado incluso con configuración.
- Pendiente siguiente entrega: parser SDP/ICE del adaptador mantenido, salida sin IP
  directas/related addresses, par seleccionado/stats, captura de tráfico que descarte
  P2P, fallo TURN y reconexión/restart, IPv4/IPv6, audio real y certificado DTLS.

Los blobs SDP/ICE sintéticos prueban ligadura/estado/cifrado; NO prueban validez de
una negociación WebRTC ni privacidad de direcciones de un adaptador inexistente.
TURN no descifra DTLS-SRTP pero su operador ve IP/tiempos/volumen. No anonimato frente
al operador, ISP o servicio de señalización. No exportación de claves Signal.

## Comandos y entorno

Python 3.13.12, JDK 21.0.11, Gradle 8.13, AGP 8.13.2, SDK compile/target36,
min31, build-tools35.0.0 y libsignal0.102.3 conservados.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/mnt/c/Android/sdk-linux
bash scripts/codex_setup.sh
. .venv/bin/activate
bash scripts/test_local.sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/build_android.py --check-only
python scripts/build_android.py --release
.umbra-tools/gradle-8.13/bin/gradle -p android --no-daemon \
  :app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest
python scripts/test_relay_integration.py
python scripts/repository_guard.py --git-history
```

AVD local: API35 default x86_64, KVM obligatorio; mismos helpers ci_emulator.sh,
run_android_instrumentation.py, run_location_restart.py, smoke_release_launch.py y
ci_bluetooth.sh. Directorio efímero, cleanup por trap, usuario local/grupo kvm.
No runner público instalado en el equipo personal. CI conserva cuatro trabajos.

## Fallos reproducidos y revisión

1. Primer build de tests falló por namespace incorrecto de BuildConfig y JSONArray
   sin método isEmpty en la dependencia actual. Corregidos los tests; no exclusiones.
2. Reinicio JVM clasificó como EXPIRED una sesión al comparar el monotónico de otra
   instancia. Regresión falló (128 tests, uno fallido); se exige misma instancia
   antes de interpretar su plazo. Reinicio invalida la autorización y produce FAILED.
3. Revisión: Permit de cifrado debe comprobar cancelación inmediata además del lease;
   se añadió revalidación del estado vivo antes de cifrar controles no terminales.
4. Se amplió el verificador de suites a fuentes `testConnected` sin omitir ninguna
   suite común: connected prueba protocolo; offline prueba rechazo, no simula llamadas.
5. `force-stop` connected exige el marcador de llamada además del de ubicación y
   una suite no vacía; la regresión de herramientas rechaza la falta de ese marcador.

## Cobertura y límites

A: Engines/libsignal y relay HTTPS reales, selección y controles de negociación/END.
B: APIs/ingress no falsificables, confianza y autorización en Engine/transporte.
C: dos ACCEPT simultáneos en JVM, un ganador; recorrido multidispositivo HTTPS.
D: cambios de identidad, membresía y revocación, lease de escritura invalidado.
E: CANCEL antes de INVITE, ACCEPT después de terminal, duplicados/collisions y TTL.
F: llamadas cruzadas terminan BUSY sin dos sesiones utilizables.
G: rollback de estado/outbox/ratchet, reapertura SQLite; force-stop real separado.
H: descripción/digest/huella/generación/rol incorrectos; no parser multimedia casero.
I: downgrade remoto/directo rechazado, configuración y errores TURN sin fallback.
J: señalización no crea captura ni amplía ubicación; lock invalida ambas funciones.

UI mínima para invitar, consentir y rechazar/terminar; sin rediseño ni sonido,
notificación/servicio de segundo plano. Requiere los contactos/conjuntos aprobados
existentes. Bloqueo al pausar y autolock se mantienen. No prueba positiva de bóveda
hardware: SQLite sintético está solo en androidTest y ausente de release.

BLOCKED: hardware Keystore y radios/GPS físicos. NOT EXECUTED: muerte durante commit,
WebRTC/TURN/audio/video reales, matriz completa Android y auditoría independiente.
Force-stop entre operaciones no acredita muerte durante una transacción.
Las advertencias heredadas lint/API y Starlette/httpx no se suprimen globalmente.


## Resultados ejecutados de esta entrega

| Capa | Resultado local | Código |
|---|---|---|
| Setup y preflight | Python/SDK/JDK disponibles; no sustituyen build | 0 |
| Backend | 144 pruebas, incluida prohibición de metadatos de llamada en claro | 0 |
| Java puro | 105 escenarios; 12 controles de fuente separados | 0 |
| Herramientas | 104 pruebas, incluye marcador obligatorio de force-stop de llamada | 0 |
| JVM Signal | 134 connected, 117 offline; sin fallos/errores/skips | 0 |
| Build | connected/offline debug/release; lint, manifest/APK/DEX/JNI | 0 |
| HTTPS real | selección de único receptor, negociación cifrada y END; regresiones de identidad/dispositivos/ubicación | 0 |
| SQLite/Android | 25 instrumentadas por variante; rollback de selección y reapertura | 0 |
| Force-stop | proceso realmente terminado, connected no restaura llamada; ubicación ambas variantes | 0 |
| Release/R8 | instalación con firma efímera, arranque bloqueado, cleanup | 0 |
| Bluetooth | RFCOMM de dos AVD, ambas variantes; chat/adjuntos/ubicación, no llamadas Bluetooth | 0 |
| Contenedor | build y health no root, network none, read-only, capacidades retiradas | 0 |

El primer sondeo de health encontró ConnectionRefused durante arranque; el reintento
acotado pasó. No se ignoró un fallo final del contenedor.

Runtime de aceptación Android local: `52530d5`; el endurecimiento adicional de forma
INVITE/SELECT/END está en `c77be89`, pruebas en `866781c`. Se repitieron build completo,
JVM y HTTPS sobre `866781c`; la CI de la PR ejecuta TODAS las capas sobre el checkout
final. No se presenta la instrumentación anterior al último endurecimiento como
prueba de ese árbol final. La documentación posterior no modifica runtime.

El recibo de PR contiene hashes de los APK CI descargados/comprobados, IDs de
artefactos y comparación del árbol HEAD con el checkout de integración. Los APK
release se inspeccionan sin firma productiva; no se publican releases. Los informes
históricos y fallos de desarrollo se preservan; ninguna suite se excluyó.

## Hashes locales de `866781c`

```text
SHA256 6a702ac5526b55e5fce32ed67b2e00bf5be4ef6d2ef909b13b82ed7c3e06411d  android/app/build/outputs/apk/connected/debug/app-connected-debug.apk
SHA256 f954e2fadbe269c7dcf94eca0a9bbc4aeb4df0abd3f69f042729d54fe1f6ae37  android/app/build/outputs/apk/connected/release/app-connected-release-unsigned.apk
SHA256 db123c78499f0061e4cbae0dcf007a2e2ba01ddb46553177ff4222ae2ede18d4  android/app/build/outputs/apk/offline/debug/app-offline-debug.apk
SHA256 ab93924da464e23738370d2b392d57014062917ca58dec9c047e87321afd6aaa  android/app/build/outputs/apk/offline/release/app-offline-release-unsigned.apk
```

Los hashes debug de CI pueden diferir por su firmante efímero; se registran aparte.
