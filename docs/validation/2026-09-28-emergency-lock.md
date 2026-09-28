# Emergency lock — 2026-09-28 UTC

Estado: PARTIAL, implementación y validación en curso. No producción.

## Base y preservación

Worktree independiente `/tmp/umbra-emergency-lock`, rama `codex/emergency-lock`.
Base exacta `52cd1e531b9de41ecd398ba53309b630ce1074f5`, PR #14 abierta en borrador;
`gh pr view 14 --json headRefOid,state,isDraft` y `git ls-remote` confirmaron que
no avanzó. Main, ramas anteriores y carpetas de Claude permanecen sin modificar.
No hay commits anteriores de emergencia ni parche que aplicar nuevamente.

## Cambios y revisión

- Barrera inmediata en AccessGate, resultados por recurso y límite de 5 segundos.
- Coordinación independiente de Vault, Connectivity, calls/location, HTTP,
  RFCOMM, documentos, audio/video y superficie remota heredada.
- Registro por ruta impide evadir un cierre incompleto creando otra Activity/gate.
- Nueva autenticación con ticket de generación solo tras CLOSED; contraseña y
  Keystore siguen siendo necesarios. No auto-connect ni auto-Nearby.
- Media confirma terminación de ejecutores, no la bandera `disposed` al empezar.
  Un InterruptedException de cámara no se convierte en cierre confirmado.
- Errores de un cierre no impiden intentar los otros. Sin END/ACK obligatorio,
  borrado de datos, renovación de lease ni fallback del modulador.

## Comandos ejecutados hasta este recibo

Python 3.13.12, JDK 21, Gradle 8.13; AGP 8.13.2, SDK 36 definidos por el proyecto.
Activación `. .venv/bin/activate` antes de Python. Dependencias existentes.

- `bash scripts/test_local.sh`: primera iteración PASS; aún no validación final.
- `python -m unittest discover -s scripts/tests -p 'test_*.py'`: 168 PASS antes de
  añadir la nueva prueba del recibo de emergencia. No atribuir ese total al HEAD final.
- Gradle `:app:testConnectedDebugUnitTest --tests app.umbra.EmergencyLockTest`:
  seis casos PASS. Incluye proveedor/exportación activo y timeout real de 5s.
- Compilación de ambos APK de instrumentación y suites JVM: PASS en iteración
  inicial; no ejecución de esos APK en Android.
- Iteración posterior con lint: FAILED, dos SuspiciousIndentation en el adaptador
  de ubicación. Se añadieron llaves explícitas; nueva ejecución pendiente al
  redactar este recibo. No se suprimió el detector.

Los logs completos de iteraciones están en `.run/emergency/`, sin datos reales.
Segunda ejecución local, código revisado:
- Gradle assembleConnectedDebugAndroidTest / assembleOfflineDebugAndroidTest,
  testConnectedDebugUnitTest / testOfflineDebugUnitTest y lintConnectedDebug /
  lintOfflineDebug: exit 0, BUILD SUCCESSFUL. JVM 215 connected / 158 offline,
  cero fallos, errores u omitidas. Incluye ocho nuevas regresiones de dominio.
- Herramientas: 169 casos, exit 0. repository_guard: 389 archivos, exit 0.
- `git diff --check`: exit 0. Ninguna ruta MainActivity/Claude UI modificada.

Todavía no existe HEAD nuevo publicado ni CI propia aprobada para esta entrega.

## Aceptación pendiente

Laboratorio añadido: seis casos Android (Vault/SQLite/AndroidKeyStore, aprobación
administrativa en espera y proveedor AOSP sintético) en ambos
flavors, debug/R8; media entre dos AVD con actividad positiva previa de voz/video,
coordinador de emergencia, medición monotónica y callbacks posteriores. Hasta
comprobar sus recibos siguen **NO EJECUTADOS**, no aprobados por existir código.

También se añadieron RFCOMM activo de ambos flavors y force-stop tras emergencia
con observación UID/DNS. Siguen pendientes de AVD/CI, no aprobados por compilación.
Las suites existentes continúan y no sustituyen estos nuevos escenarios.
Muerte exacta durante commit y apertura de cámara/micrófono simultánea al bloqueo
no se declaran cubiertas por reapertura SQLite ni por cerrar media ya activa.

KVM local existe, pero `test -r /dev/kvm` y `test -w /dev/kvm` devuelven 1 para
este usuario. No se cambiaron permisos ni se eludió la restricción. AVD requiere
CI autorizada. Esto no demuestra una limitación de KVM en GitHub Actions.

Hardware físico, TEE/StrongBox, radio, micrófono/cámara físicos y UI combinada:
NO EJECUTADOS. No hay hashes de artefactos finales hasta generar/verificar el HEAD.

## Regresiones adicionales reproducidas antes de publicar

1. Carrera de cierre del proveedor: la escritura terminaba mientras el cierre de
   cancelación seguía bloqueado y luego fallaba. `provider-close-before.log`:
   exit 1, CLOSED incorrecto frente a INCOMPLETE esperado. Serializar los cierres
   y conservar el fallo corrige la prueba (`provider-close-after.log`, exit 0).
2. HTTPS retenido: `disconnect()` esperaba el lock de `MeteredStream.close`
   mientras otro hilo leía TLS. Diagnóstico `https-emergency-thread-dump.txt`.
   Cierre de sockets propiedad del cliente antes de disconnect, sin cambiar TLS.
   `real-https-emergency-fixed.log`: exit 0. Después se añadió rechazo por nombre
   de certificado incorrecto: `real-https-with-certificate-rejection.log`, exit 0,
   36 comprobaciones principales más escenarios de dispositivos y admisión.
3. Errores del arnés conservados: variable Java duplicada (corregida); nuevo
   cliente sin nuevo consentimiento tras el caso previo (corregido mediante
   disconnect/connect explícitos). No justifican alterar la política productiva.

Las variantes debug/release y sus políticas APK pasaron antes del último ajuste
HTTPS. R8 VaultLab ambos flavors y MediaLab también compilaron y la inspección R8
pasó; se están repitiendo sobre el candidato actualizado. Offline inspeccionado sin
INTERNET/ACCESS_NETWORK_STATE, micrófono, cámara ni WebRTC; el recibo final aún
requiere nuevas hashes y CI. No se introdujo dependencia ni binario nativo nuevo.

## Candidato local antes de publicación

La repetición completa terminó con exit 0: `python scripts/build_android.py
--release` (debug/release, lint, JVM y políticas APK), APK de instrumentación de
ambos flavors, VaultLab R8 ambos flavors y MediaLab R8 connected.
`check_optimized_media.py` confirma minificación/ofuscación y paquete aislado;
no ejecuta media. Herramientas: 169 tests PASS; repository_guard: 389 archivos,
exit 0. No se atribuye instrumentación Android a estas compilaciones.

SHA-256 de los cuatro APK locales de este candidato:

| APK | SHA-256 |
|---|---|
| connected debug | a0bcdabd5fe2e04c0209c4616008c7a9f77b4b37992bbe405b6fd65528f08b5c |
| connected release unsigned | 9260796b92a65e5553ad406eb2c291399480162253dec345fecf57e96ddb1a32 |
| offline debug | c024187bb3b76f80cfc25f1df9110e5af7a0bfd2925b0b65fac26dae11286bd3 |
| offline release unsigned | 8b596a2288b3de41c1a708d096b2960315e6ec87b9f6e5069cb9ede7b172a8e2 |

Estos hashes identifican builds locales, no artefactos de una CI todavía pendiente.

## Primera CI del código publicado

HEAD `9dbccddbc07302df3914cb10886b9fd34260403e`, checkout de integración
`44932d8095657a7dacc4c1a112effd914df4ce62`. PR #15 en borrador contra #14.
JVM final local: 217 connected / 160 offline; test_local con JDK 21 exit 0.
Se conserva una invocación errónea con JDK predeterminado anterior a 21 que falló
al compilar JavaSyntaxCheck, sin atribuirla a un defecto Android.

Emergency laboratory `36368708133`: los seis casos Android connected pasaron
en debug y R8, incluido fallo intencional de cierre que exige INCOMPLETE. El paso
siguiente de inicio privado falló por sensorOrScanAccess, después de ejecutar
el caso positivo de ubicación en ese mismo paquete. El recibo muestra cero
paquetes UID IPv4/IPv6 y cero consultas DNS en esa ventana. La guarda AppOps
examina también accesos históricos, por lo que la nueva orquestación ejecuta
inicio privado antes de cualquier adquisición intencional; no borra ni ignora
la comprobación. Se añade el resultado AppOps por etapa a los artefactos.
La explicación histórica se debe contrastar con esos nuevos registros; no se
atribuye el fallo automáticamente a una captura que siguiera activa.

RFCOMM de emergencia de ambos flavors: job `108760237310` SUCCESS. Media R8:
job `108760237268` FAILED antes de audio, en probe UDP: listener exit 1,
`nc: connect: Network is unreachable`, cero bytes. No se corrigió ni omitió
la aserción: se añaden comandos, códigos, stderr acotado y topología antes/después
a los recibos para investigar una nueva reproducción. No se modifica TURN.

Suites existentes de este HEAD: admisión `36368708029`, contraseña
`36368708017` e inicio privado `36368708001` SUCCESS. Otras aún no finalizadas
al escribir esta entrada. Estos resultados no validan el commit siguiente.

Media debug de la misma ejecución (`108760237344`) alcanzó cierre y produjo
`failedClosed=true`, 1000 ms hasta observación, 500 ms observados, cero callbacks
tardíos. Falló el verificador host: `valid_stop` exigía exactamente las cinco
claves anteriores y rechazaba los campos adicionales de emergencia. Reproducido
localmente en `emergency-media-receipt-before.log` (exit 1). Se añade un esquema
estricto separado de emergencia, sin relajar ventanas ni aceptar campos desconocidos;
171 pruebas de herramientas PASS (`emergency-media-receipt-after.log`). Los recibos
completos se conservan antes de evaluar para no perder diagnóstico ante fallo.
El cierre de proceso que aparece después del fallo host pertenece a su cleanup,
no prueba por sí solo un crash previo del motor. La aceptación multimedia completa
sigue pendiente de una ejecución que termine todos los controles.

Verify `36368708031`: 48 casos connected ejecutados, uno falló en el callback
coarse de LocationAndroidTest. El orden documentado muestra que la nueva prueba
de emergencia había concedido FINE runtime previamente; el caso coarse denegaba
Fine por AppOps, pero `fine=0` seguía indicando permiso runtime concedido. Antes
de esta PR ese caso se ejecutaba antes de cualquier concesión Fine. Se corrige
la contaminación de la fixture de emergencia: no concede Fine y usa el proveedor
compatible con el permiso disponible (GPS si ya concedido; AOSP network si coarse).
Afirma que no modificó el permiso Fine. No se cambia política productiva, timeout
ni la prueba coarse existente; falta comprobar el nuevo orden en CI.

En f9e25a6, inicio privado R8 falló después de la pérdida de red por
`No default network available`, antes de la acción que debía conectar y bloquear.
El arnés habilitaba Wi-Fi y datos y esperaba tiempo fijo sin comprobar la red
default. Ahora restaura solo Wi-Fi, observa `getActiveNetwork()` hasta 15 segundos
dentro de la barrera host existente de 45 segundos, y comprueba que el Engine
permanece desconectado durante esa espera. No reintenta connect ni modifica el
comportamiento productivo; el fallo si no aparece red se conserva explícito.

Focalizadas de 9dbccdd: los tres credential-expiry debug fallaron antes de acabar
reactivación; video activo tardaba 45–47 s frente a 24–31 s en la base verde
36361843278. Revisión encontró un wrapper TLS nuevo por petición y cierre
incondicional de sockets normales, que impiden reutilizar conexiones Android.
Se conserva una fábrica TLS por RelayClient y propiedad acotada de sockets hasta
cancelación, manteniendo disconnect normal y cierre forzado en timeout/emergencia.
Integración HTTPS/JNI local repetida: 36 comprobaciones principales PASS, incluidos
lectura bloqueada, emergencia y nombre TLS incorrecto. Los TTL no se aumentan.
La mejora temporal y aceptación credential-expiry aún deben verificarse en AVD.

Preflight UDP R8 de f9e25a6 volvió a fallar, ahora con topología conservada:
`wlan0` tenía 10.0.2.16, pero faltaban las rutas IPv4 de su tabla 1016 mientras
las reglas enviaban el tráfico a esa tabla (el otro AVD sí tenía ruta). Una
dirección DHCP no acreditaba preparación del encaminamiento Android. Se amplía
la condición de preparación existente de 20 s para exigir `ip -4 route get`
por wlan0 con origen esperado, sin generar tráfico, alterar rutas ni aumentar
el plazo. Se conserva después el intercambio UDP real y sus rechazos. Tres
regresiones host prueban dirección sin ruta, llegada de ruta y fallo persistente;
174 herramientas PASS. Inicio privado usa la misma observación de preparación
antes de abrir la aplicación. La causa de este fallo está demostrada por el
recibo; no se generaliza a todos los fallos UDP históricos sin igual evidencia.

Emergency media debug de f9e25a6, job 108764001966: SUCCESS, voz y video sintéticos
activos antes de emergencyLock, cierre y ventana posterior. RFCOMM y dominio
debug también SUCCESS. Media R8 quedó bloqueada en el preflight anterior, y
dominio R8 tuvo un fallo de conexión inicial al relay; no son aprobados.

42eace1: la preparación inicial de rutas pasó, pero restaurar solamente Wi-Fi
después de deshabilitar ambas redes dejó `getActiveNetwork()==null` durante la
espera acotada. Los jobs de emergencia debug/R8 fallaron explícitamente en esa
precondición, no se dieron por correctos. Se revierte exclusivamente esa restricción
de orquestación y se restauran ambas redes originales, conservando la comprobación
de disponibilidad real antes de una nueva acción connect y las observaciones
de cero tráfico de UMBRA. No se añade reconexión automática al producto.

42eace1 media R8: voz PASS; video falló con AccessGate.LockedException en
VoiceEngineFixtureListener:520 al leer CallService.session después de solicitar
emergencyLock. El motor aún estaba ACTIVE durante el cierre asíncrono y el arnés
continuaba su auditoría SDP. La bóveda rechazó correctamente la lectura. Se
corrige el arnés: tras invalidación no bombea ni consulta datos sensibles, observa
cierre/progreso, acepta DISCONNECTING solamente durante emergencia con autorización
de red denegada, y sigue exigiendo CLOSED y cero callbacks al finalizar. No se
ignora LockedException ni se desbloquea para terminar el test.

43a96d1 inicio privado: connected debug/R8 PASS, incluidas recuperación y
force-stop. El fallo siguiente era la precondición de red aplicada por error
al flavor offline: después de los cambios de red anteriores no había dirección
wlan0. Se limita esa preparación exclusivamente al recorrido HTTPS connected;
offline debe funcionar sin una ruta de Internet y conserva todas sus pruebas
UID/DNS/sensores, rechazo de connect y reinicio. No se omite ninguna suite ni
se modifica el preflight UDP de los laboratorios multimedia connected.
