# Revisión del relay e integración HTTPS — 2026-09-19 UTC

VERIFIED — Evidencia local del árbol de trabajo de `codex/repository-audit-fixes`,
basado en `47b2616` (PR #2 incorporada conservando autoría). No representa una
CI del commit final; consultar el informe integral para su SHA y validación final.

## Inventario revisado

Leídos `app.py`, `guard.py`, `admin.py`, `maintenance.py`, todas las pruebas relay,
Dockerfile, Compose, Caddyfile y documentación de operación. Inspeccionadas las
seis rutas (salud, registro, envío, consulta, ACK, eliminación del buzón), los
comandos administrativos invite/purge, autorización separada read/write,
transacciones, aislamiento, deduplicación, cursores, cuotas, TTL, parsers,
framing, límites temporales y de concurrencia. No hay API administrativa que
proporcione descifrado.

## Hallazgos y correcciones

- **Media, corregida — esquema persistente dañado aceptado.** La validación de
  PR #2 rechazaba versiones desconocidas y tablas ausentes, pero aceptaba pérdida
  de restricciones. `schema.py` verifica orden/tipos/nullability, claves primarias,
  unicidad, referencias/cascadas y AUTOINCREMENT. Sin esos invariantes pueden
  fallar operaciones o incumplirse deduplicación/cursores. Se preservan migración
  legacy e idempotencia de reapertura. Seis regresiones fallaron antes por ausencia
  del rechazo esperado y pasan después. El primer borrador del test usaba
  `iterdump`, que también fallaba con una FK dañada; se corrigió el verificador
  para comparar sqlite_master y se reprodujeron de nuevo los seis fallos reales.
- **Media, corregida — errores de almacenamiento sin respuesta operativa.**
  SQLITE_FULL y aborto de ACK antes producían 500 sin degradar salud. Ahora
  responden 503 opaco con Retry-After, salud degradada y diagnóstico limitado al
  código numérico SQLite, sin traceback/SQL/tokens. Las dos regresiones fallaron
  antes (500 en lugar de 503) y pasan después. La transacción conserva el mensaje,
  revierte el tombstone parcial y no consume una invitación tras fallo del alta.
- **Correcciones previas preservadas de PR #2:** rechazo de versiones y tablas
  incompatibles, recuperación periódica tras error SQLite y salud del mantenimiento.

## Pruebas ejecutadas

Entorno: Python 3.13.12, SQLite 3.46.1, OpenJDK 21.0.11, Gradle 8.13,
FastAPI 0.133.0, Starlette 1.3.1, Uvicorn 0.48.0, Pydantic 2.13.4,
pytest 9.0.3, httpx 0.28.1; libsignal JVM/JNI 0.102.3.

```bash
. .venv/bin/activate
PYTHONPATH=relay python -m pytest relay/tests -q
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/mnt/c/Android/sdk-linux
export ANDROID_SDK_ROOT="$ANDROID_HOME"
.umbra-tools/gradle-8.13/bin/gradle -p android --no-daemon :app:writeRelayIntegrationClasspath
python scripts/test_relay_integration.py --classpath-file android/app/build/integration/classpath.txt
```

VERIFIED — Backend: 104 pruebas aprobadas, cero fallidas/omitidas, exit 0, 5.25 s
tras la actualización de dependencias. Starlette advierte de la deprecación de
httpx en TestClient; se conserva visible, no se filtra. Gradle classpath: éxito,
exit 0, 23 s. Integración HTTPS final: 20 comprobaciones aprobadas, exit 0.
`git diff --check` y compilación sintáctica del runner: exit 0 (controles estáticos,
no pruebas de comportamiento).

La integración compila y usa Engine, SignalStore, Wire y RelayClient de producción
con libsignal real contra Uvicorn real y SQLite temporal. TLS valida un certificado
SAN localhost efímero con un truststore exclusivo del subproceso JVM, sin modificar
la confianza ni la política Android. Un socket local reservado mantiene el mismo
puerto durante el reinicio del relay y se reutiliza la misma base.

Se ejercitan invitación de un uso, aislamiento de capacidades, preservación de
ciphertext al reconstruir Engine, reinicio del servidor, siete sobres reordenados,
paginación 5+2, duplicados, manipulación, destinatario incorrecto, ciphertext
truncado/sobredimensionado, TTL excesivo, persistencia antes de ACK, recibos cifrados,
reintento después de ACK y revocación. Los rechazos HTTP comprueban códigos exactos
403/401/422/400. Un segundo servidor TLS local sintético retiene un cuerpo para
verificar cancelación de una operación pendiente en menos de dos segundos y que
esta no se anuncie como exitosa. Se verifica también la política bloqueada previa.

El primer ensayo detectó un **error del runner**, no del producto: Uvicorn reenvía
SIGTERM tras cerrar normalmente y devuelve -15. Se comprobó su implementación
instalada (`Server.capture_signals`) y se aceptan únicamente 0 o -SIGTERM para
ese cierre solicitado; SIGKILL u otros errores siguen fallando.

## Límites y pendientes

NOT_VERIFIED — MemoryRecords es un adaptador de pruebas en memoria; reconstruir
Engine no prueba persistencia Android ni muerte del proceso Android. SQLite y el
reinicio del proceso del relay sí son reales. SQLITE_FULL se indujo con el límite
de páginas de SQLite, no llenando un disco físico. Los abortos de transacción se
inyectaron mediante triggers SQLite sintéticos. No se ejercieron Bluetooth,
Keystore, UI, carga sostenida, corte eléctrico, backups ni TLS público del proxy.

El límite por peer detrás de Caddy continúa agregado; la confianza indiscriminada
en X-Forwarded-For permanece deshabilitada. El contenedor fija un worker, pero no
se afirma alta disponibilidad ni operación segura de una configuración modificada
por el operador. La ejecución del contenedor y las demás verificaciones finales
se documentan por separado. No se utilizaron credenciales, mensajes ni servicios
reales, ni se desplegó producción.

## Contenedor aislado reejecutado

VERIFIED — `docker build --pull -t umbra-relay:repository-audit-20260919 relay`
terminó con exit 0 e instaló el lock con hashes. Imagen local resultante:
`sha256:19dd6b538f68b491ac88a7a287340083124657099b8cf695f724350ee0a9c4f1`.
El build resolvió `python:3.12-slim` a
`sha256:2f17fc044b579bab302c2e8054d3a686e2cb9a83de48e70534b94cd8ebbe06a9`.
En ese primer ensayo el tag todavía era flotante; el cierre posterior descrito
abajo lo fijó en el Dockerfile.
Docker cliente y daemon: `28.5.2+dfsg4`.

Se ejecutó el smoke del workflow con nombre aleatorio exclusivo, `--network none`,
`--read-only`, tmpfs `/tmp` y `/data`, `--cap-drop ALL`,
`--security-opt no-new-privileges`, 128 procesos y 256 MB de memoria. Se comprobó
HTTP `/healthz` 200, UID/GID 10001, base con modo 0600 y configuración efectiva
readonly/networknone/capDropALL. Python del contenedor: 3.12.14; SQLite 3.46.1;
FastAPI 0.133.0, Starlette 1.3.1, Uvicorn 0.48.0, Pydantic 2.13.4.
El smoke final y la eliminación del contenedor propio terminaron con exit 0.
No fue despliegue, prueba TLS del contenedor ni ensayo de carga.

El primer smoke comprobó correctamente la salud y el aislamiento, pero su paso
auxiliar de inventario intentó inspeccionar un tag local `python:3.12-slim` que
BuildKit no había creado y terminó con exit 1. Se corrigió ese supuesto del
verificador, conservando el digest de la salida real del build, y se repitió el
smoke completo con éxito. Ambos contenedores temporales fueron eliminados en
`finally`. Se conservaron visibles los avisos de pip sobre instalación como root
en la **capa de construcción** y disponibilidad de una actualización de pip;
el proceso final se ejecutó como UID 10001, sin actualizar pip indiscriminadamente.

VERIFIED — Cierre posterior: `FROM` quedó fijado al digest observado
`python:3.12-slim@sha256:2f17fc044b579bab302c2e8054d3a686e2cb9a83de48e70534b94cd8ebbe06a9`.
Se repitieron `docker build --pull` y el smoke completo con la configuración
anterior, ambos exit 0; las capas fueron reutilizadas y la imagen resultante
conservó `sha256:19dd6b538f68b491ac88a7a287340083124657099b8cf695f724350ee0a9c4f1`.
El contenedor de esta repetición también se eliminó en `finally`.

## Revisión independiente de verificadores

VERIFIED — Leídos build_android.py, bootstrap_gradle.py, check_android_tests.py,
check_debug_apks.py, check_merged_permissions.py y check_apk_policy.py. Se corrigió
el conteo duplicado de suites XML, el éxito de APK sin DEX y la detección de
código test/lab en R8. La revisión halló que comprobar únicamente declaraciones
de clases del mapping no detectaba métodos lab incorporados por inlining dentro
de clases de producción; se reprodujo ese falso negativo y ahora se analiza el
mapping completo. También se detectan clases internas de los adaptadores de
pruebas en DEX secundarios. Estas comprobaciones identifican los namespaces y
adaptadores prohibidos conocidos, no sustituyen una auditoría arbitraria de DEX.

```bash
. .venv/bin/activate
python -m unittest discover -s scripts/tests -p 'test_gate_regressions.py' -v
python scripts/check_apk_policy.py --sdk /mnt/c/Android/sdk-linux --include-release
```

Cinco pruebas negativas del verificador aprobadas (exit 0), seguidas de inspección
real aprobada de los cuatro APK existentes connected/offline debug/release
(exit 0). No son cinco pruebas de comportamiento de Android ni ejecución de APK.
La compilación final y sus hashes corresponden al informe integral.

NOT_VERIFIED — El bootstrap fija y verifica el hash de las descargas nuevas de
Gradle, pero reutiliza instalaciones locales ya presentes sin volver a comprobar
su contenido. No se afirma haber autenticado una caché local arbitrariamente
modificada mediante ese control.

VERIFIED — Corrección final de contexto Docker: se comprobó que el directorio
host `umbra_relay/__pycache__` contenía bytecode CPython 3.13 generado por las
pruebas. Se añadió `relay/.dockerignore` para excluir bytecode/cachés, tests,
entornos virtuales, bases SQLite y archivos `.env` del contexto. Se reconstruyó
con `--pull` y se repitió el smoke aislado completo (exit 0), verificando además
**dentro del contenedor** que `/app/umbra_relay` no contiene `__pycache__` ni
archivos `.pyc`, y que no existe `/app/tests`. Esta comprobación no exige eliminar
el bytecode que pertenezca a dependencias de la imagen base.

La imagen **final** sustituye el ID anterior:
`sha256:32e51bb8ecd292c90f46bc86288fd0f86b1fdc536427ba506b9fd5f76d8d093f`.
Se mantuvieron base fijada, versiones, UID/GID y restricciones anteriores, y se
eliminó el contenedor propio tras el ensayo. No se publicó la imagen.

## Revisión adicional del control AUTOINCREMENT

VERIFIED — La revisión cruzada posterior detectó que el control de keyword podía
confundir un literal CHECK o el nombre entrecomillado de una restricción con
AUTOINCREMENT real. Se reprodujeron cuatro falsos positivos de arranque con
SQLite: literal de texto y nombres de restricción con comillas dobles, corchetes
y backticks. Las cuatro nuevas regresiones fallaron antes (exit 1; seis pruebas
previas de estructura pasaban). Se corrige la extracción retirando tokens quoted
y comentarios antes de buscar el keyword; no se modifica el esquema normal.
La suite backend completa posterior aprobó **108 pruebas**, exit 0, 18.24 s, con
la misma advertencia de TestClient visible y sin omisiones.

Se repitieron construcción Docker `--pull` y smoke aislado después de este cambio,
ambos exit 0, manteniendo la ausencia de bytecode del host y la eliminación del
contenedor propio. La imagen validada más reciente **sustituye los IDs anteriores**:
`sha256:8626eb7ecff3616cdca94e29da11035c0a6a0e61d94a547134f6951437614498`.
