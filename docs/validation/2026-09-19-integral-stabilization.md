# Estabilización integral — 2026-09-19 UTC

VERIFIED — Código final validado: `8fdea14efb3c8a78a0faf66d01f9d254a8dc8a20`,
rama `codex/repository-audit-fixes`. Los commits posteriores de evidencia/documentación
no modifican los binarios ni el código probado. Este informe distingue revisión,
pruebas ejecutadas, limitaciones y controles estáticos; no certifica seguridad total.

## Base y conservación del trabajo

VERIFIED — Repositorio privado `DevOps-Solutions-IA/umbra`. Se consultaron la PR #1,
sus comentarios/revisiones y checks. Continúa OPEN, sin fusionar, HEAD
`0f4d566880454939dda130167c42de3ac8b4fe77`, base main
`3716f09e415c69f59102e74cffa6a1bbb154dec6`. Se creó la rama desde ese HEAD.
Se encontró además la PR #2 abierta, HEAD `47b26169717fcf57cfd9e01d79e10b68562ded56`,
base `codex/android-build-validation`; se revisó e incorporó mediante fast-forward,
conservando su commit y autoría. Se revisó el proyecto resultante completo.
No se modificaron main, las otras ramas, visibilidad, CODEOWNERS ni protecciones.
La nueva PR debe apuntar a la rama de #1 y explicar que incluye #2; ninguna se fusiona.

VERIFIED — Se leyeron AGENTS, PROMPT_CODEX, handoff, roadmap, testing sin teléfonos,
security, release checklist y workflow. No se encontraron instrucciones Android
adicionales. No estaban configuradas variables AGENT_MEMORY_* en este entorno.

## Inventario y límites de revisión

| Módulo | Revisado / ejecutado | Límite |
|---|---|---|
| UI/MainActivity y documentos | Navegación, lock, worker, callbacks, export/import; regresiones de streams y ciclo de vida bloqueado en Android | Sin flujo completo desbloqueado, muerte de proceso/selector ni matriz completa de permisos revocados |
| Vault/Records/AccessGate | Lectura completa, transacciones, índices, claves y migración; codec/gate JVM; política Keystore Android real | El AVD informa claves software: producción las rechaza; no se ejecutó una bóveda con fallback |
| Engine/SignalStore/Wire/core | Revisión completa; libsignal JNI JVM y Android; fallos de persistencia, identidad, límites y rechazo | MemoryRecords no prueba durabilidad SQLite Android |
| BluetoothLink/Framing | Revisión; RFCOMM real entre dos stacks Android emulados, ambas variantes | No radio física, alcance/interferencias ni matriz completa de reconexión/cortes/backpressure |
| RelayClient/integración | Cliente productivo JVM contra Uvicorn HTTPS/SQLite aislados; TLS verificado, reinicio, ACK, rechazo y cancelación | No integración de la UI desbloqueada/Keystore con el relay |
| Relay API/DB/admin/guard/maintenance | Todas las rutas, modelos, SQL, CLI y pruebas; rechazo de capacidades, cuotas, TTL, dedup, migración, disco lleno y rollback | No carga sostenida, corte eléctrico, TLS público ni despliegue/proxy real |
| Contenedor/operación | Docker/Compose/Caddy revisados; imagen construida y smoke aislado, no root | Un worker fijado; overrides del operador y alta disponibilidad no certificados |
| Build/scripts/CI/dependencias | Compilación limpia, lint, cuatro APK, R8, gates, publicador, workflow/actionlint y avisos actuales OSV | Sin auditoría independiente; grafo AGP/native Rust y SBOM/licencias completos pendientes |

Detalles: [Android/lifecycle](2026-09-19-android-lifecycle-review.md),
[Android/emulación](2026-09-19-android-device-emulation.md),
[relay/integración](2026-09-19-relay-backend-review.md),
[dependencias](2026-09-19-dependencies-review.md).

## Hallazgos y correcciones

| Severidad | Archivo/línea final | Impacto y estado |
|---|---|---|
| Alta | `android/.../crypto/Engine.java:30` | Identidad ausente/corrupta con registros remanentes podía interpretarse como instalación nueva. Corregido: validar libsignal/perfil/registro y rechazar pérdida parcial, sin reinicialización. Cinco regresiones fallaron antes; suite nueva de ocho pasa, incluyendo rollback del ratchet/recibos. |
| Alta | `android/.../ui/MainActivity.java:664`, `core/DocumentIO.java` | Una exportación iniciada en una sesión podía liberar plaintext después de lock/unlock durante apertura del proveedor. Corregido con lease original y escritura acotada. Harness anterior liberó 16384 bytes; ocho regresiones pasan. No se revocan bytes ya entregados a un proveedor. |
| Media | `MainActivity.java:95` | Destrucción no cerraba SQLite helper. Se encola cierre tras el trabajo en curso y se usa Application context. Recreación bloqueada probada; un proveedor no cooperativo aún puede retrasar el cierre. |
| Baja | `MainActivity.java:393,585` | Lectura de EditText desde worker; ahora se captura en UI antes de encolar. Revisión y compilación, sin test UI desbloqueada específico. |
| Media | `DocumentIO.java:read` | Lectura sin comprobar antes el lease y proveedor sin progreso podía consumir trabajo indefinido. Rechazo y limpieza de buffers probados. |
| Media | `relay/umbra_relay/schema.py:21,54` | Esquemas sin restricciones podían adoptarse; además CHECK/nombres quoted simulaban AUTOINCREMENT. Diez regresiones fallaron antes, pasan tras validar metadatos y keyword fuera de comentarios/literales. |
| Media | `relay/umbra_relay/app.py:345` | SQLITE_FULL/ACK abortado devolvían 500 sin salud degradada. Ahora 503 opaco/Retry-After, sin SQL/credenciales; rollback conservado. Dos regresiones de respuesta fallaron antes y pasan después; cuatro pruebas nuevas cubren recuperación/reinicio. |
| Media, dependencia | Locks Python | Se retiraron versiones con advisories actuales, usando mínimos corregidos y FastAPI compatible; hashes/wheels y base Docker por digest. No se demostró explotación de esos advisories en UMBRA. |
| Baja, verificador | `scripts/build_android.py:14` | PATH JDK21 podía ocultar JAVA_HOME distinto que Gradle usaría. Repro con respuestas sintéticas: antes preflight 0, ahora rechazo. Gradle recibe el JDK validado explícitamente. |
| Baja, verificador | `check_android_tests.py`, `check_apk_policy.py` | Gates nuevos rechazan suites vacías/omitidas/duplicadas, DEX ausente, código test en multidex o inlining R8. Regresiones sintéticas de verificadores, separadas de pruebas Android. |
| Baja, operación | `scripts/publish_github.py:18` | Propietario antiguo y rechazo de organización autorizada. Actualizado a organización actual con consultas GET/membresía/permisos exactos y tests; no se ejecutó una nueva publicación ni se alteraron permisos. |
| Baja, build | `relay/.dockerignore` | Bytecode/cache del host podía entrar al contexto de imagen. Excluido y ausencia comprobada dentro de la imagen final. |

Se preservaron las correcciones de #2: rangos/tipos/canonicalización de tarjetas,
retención de prekeys, mantenimiento/health SQLite y allowlist de permisos/ELF.
No se eliminaron pruebas ni se relajaron cifrado, identidad, Keystore o permisos offline.

## Entorno y comandos ejecutados

VERIFIED — Python local **3.13.12**, JDK **21.0.11+10-1-Debian**, Gradle **8.13**,
AGP **8.13.2**, compile/target SDK **36** (plataforma revisión 2), Build Tools **35.0.0**,
minSdk **31**, libsignal **0.102.3**. Emulator **37.1.11**, imagen
`system-images;android-35;default;x86_64` revisión **2**, KVM/SwiftShader bajo WSL2.
El acceso KVM se realizó con grupo transitorio mediante sudo, sin cambiar grupos/permisos
persistentes. Dos AVD nuevos, independientes, sin PIN ni datos reales.

Docker cliente/daemon **28.5.2+dfsg4**; Python del contenedor **3.12.14**.
SQLite **3.46.1**; FastAPI **0.133.0**, Starlette **1.3.1**, AnyIO **4.14.2**,
Click **8.3.3**, pytest **9.0.3**, Uvicorn **0.48.0**, Pydantic **2.13.4**.
El setup instaló locks con hashes; `pip check` exit 0. No actualización indiscriminada.

Comandos desde la raíz (logs locales en `/tmp/umbra-audit-20260919`):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/mnt/c/Android/sdk-linux
export ANDROID_SDK_ROOT="$ANDROID_HOME"
bash scripts/codex_setup.sh
. .venv/bin/activate
bash scripts/test_local.sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/repository_guard.py --git-history
python scripts/build_android.py --check-only
.umbra-tools/gradle-8.13/bin/gradle --no-daemon -p android clean
python scripts/build_android.py --release
.umbra-tools/gradle-8.13/bin/gradle --no-daemon -p android :app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest
python scripts/test_relay_integration.py
python scripts/run_android_instrumentation.py --serial emulator-5580 --flavor connected --log /tmp/umbra-audit-20260919/final-device-connected.log
python scripts/run_android_instrumentation.py --serial emulator-5580 --flavor offline --log /tmp/umbra-audit-20260919/final-device-offline.log
python scripts/run_bluetooth_emulation.py --serial-a emulator-5580 --serial-b emulator-5582 --address-a BB:BB:BB:00:00:01 --address-b BB:BB:BB:00:00:02 --flavor connected --log-dir /tmp/umbra-audit-20260919/final-bt-connected
python scripts/run_bluetooth_emulation.py --serial-a emulator-5580 --serial-b emulator-5582 --address-a BB:BB:BB:00:00:01 --address-b BB:BB:BB:00:00:02 --flavor offline --log-dir /tmp/umbra-audit-20260919/final-bt-offline
python scripts/smoke_release_launch.py --serial emulator-5582
docker build --pull -t umbra-relay:repository-audit-20260919 relay
/tmp/umbra-audit-20260919/actionlint/actionlint
```

Todos esos comandos finales terminaron con **exit 0**. El baseline también ejecutó
`python scripts/build_android.py` sin --release; su salida contenía tareas UP-TO-DATE,
por lo que no se cuenta como nueva ejecución de JUnit. La validación final hizo clean:
**211 tareas, 203 ejecutadas, 8 up-to-date**; ambos test tasks se ejecutaron realmente.
El ensamblado posterior de test APK ejecutó 54 de 98 tareas, reutilizando las productivas.
El smoke Docker ejecutó los comandos aislados del workflow con nombre exclusivo,
readonly, network none, tmpfs, cap-drop ALL, no-new-privileges, 128 procesos/256 MB.
UID/GID 10001, DB 0600, health 200 y ausencia de bytecode del host comprobados; contenedor eliminado.

## Resultados finales separados

| Clase | Resultado real |
|---|---|
| Backend | **108 passed**, cero fallos/omisiones; una advertencia httpx visible |
| Core Java | **105 escenarios** (20 + 85), aprobados; no son Android ni libsignal |
| JVM Android | **55 métodos por variante**, 110 ejecuciones, cero fallos/errores/skips: 47 Signal/Engine + 8 DocumentIO por variante |
| Herramientas | **83 unittest**, cero fallos/omisiones; incluye mocks de respuestas de herramientas, no sustitución del producto/cripto |
| Integración HTTPS | **20 comprobaciones**, cliente real + libsignal JNI, relay/SQLite reales, almacenamiento cliente sintético |
| Instrumentación Android | **8 por variante**, 16 ejecuciones en AVD: JNI, ciclo de vida bloqueado, rechazo Keystore software/sin PIN |
| Bluetooth emulado | **Ambas variantes, ambos extremos PASS**: RFCOMM, retos, códigos comparados por host, texto/adjunto, duplicados, recibos. Se repiten además 3 tests JNI por extremo; no se presentan como casos nuevos |
| Debug/release | Cuatro APK compilados e inspeccionados; dos R8 instalados/arrancados hasta UI bloqueada mediante copias con firma sintética temporal |
| Lint | Cuatro variantes: **0 errores, 6 advertencias cada una** |
| Controles estáticos | 12 políticas fuente; 29 archivos Java parseados; repositorio/historia; actionlint **1.7.12**; manifiestos/APK/ELF/R8. No se suman a pruebas de comportamiento |

VERIFIED — Keystore del AVD informó nivel **0 (software)** y la política productiva
lo rechazó. No se cambió `requireHardware`. Los adaptadores están en src/test o
src/androidTest, fuera del APK productivo; no se introdujo variante lab publicable.

VERIFIED — R8: el runner genera una clave efímera exclusiva, firma **copias**, rechaza
instalaciones release previas, instala, comprueba Activity resumed y «Bóveda bloqueada»,
desinstala y elimina clave/copias. Los originales siguen unsigned. Esto no prueba
cifrado ofuscado, desbloqueo o persistencia release. No se usaron claves de producción.

## Artefactos y trazabilidad

[Inventario exacto: seis APK, tamaños y SHA-256](2026-09-19-integral-artifacts.json).
Los cuatro productivos finales están en `android/app/build/outputs/apk/` (ignorados
por Git); los otros dos son APK de instrumentación. No se publicaron releases.

| Artefacto | SHA-256 |
|---|---|
| connected debug | `8cd1a977b9e41d3cc9c724c18ffc061daf5c3e87c07c187c37e92abafdbb08d8` |
| offline debug | `5da848c3f6c0ec87e25e8bc10a33a12dd8d304360e70417954776f6fd4c80151` |
| connected release unsigned | `e6c370622bf8a84b72eb254f1c6aa5c1215b866025ef647a05d7432ad3b79a7b` |
| offline release unsigned | `8747c9511841d7ecf5bca45a3709b57982ea32589051e8af24ad21bf59853351` |
| imagen Docker final local | `8626eb7ecff3616cdca94e29da11035c0a6a0e61d94a547134f6951437614498` |

Offline **debug y release** carecen de INTERNET y ACCESS_NETWORK_STATE. Se verificaron
componentes exportados, backup, confianza TLS solo sistema, cleartext deshabilitado,
flag debug correcto, cuatro ABI ELF JNI y exclusión de JNI testing/escritorio.
Se analizaron DEX/mapping R8 para adaptadores/lab conocidos; no sustituye una auditoría
completa del bytecode. Reproducibilidad operativa comprobada; no se afirma igualdad
bit a bit entre máquinas, especialmente por firmas debug locales.

## Fallos iniciales, advertencias y trabajo abierto

VERIFIED — Conservados logs iniciales y finales locales. Baseline: 80 backend,
105 core, 46 herramientas; no se reciclan como resultados nuevos. Reproducciones
fallidas del producto/verificadores se describen arriba. También hubo errores de
los nuevos harness: cierre Uvicorn -SIGTERM interpretado inicialmente como fallo;
inspección de tag Docker local no creado por BuildKit; `pm path` devolviendo 1 para
una app ausente; fixture Java usando readString no disponible en Android. Se corrigieron
y repitieron. Ninguno se presentó como fallo del producto ni como prueba aprobada inicial.

Advertencias conservadas sin supresión global:

- Lint `UnusedAttribute`: callback de back API 33+, ignorado por API 31–32; se conserva
  el fallback nativo de compatibilidad. Javac también avisa de APIs antiguas de Activity.
- Cinco avisos de versiones más nuevas: desugar, ZXing, JSON y las dos dependencias
  AndroidX Test. Se mantienen versiones fijadas/resueltas y ejecutadas en esta revisión,
  sin advisories OSV retornados en el alcance consultado, evitando mezclar actualización
  general de APIs con estabilización. Esto no promete ausencia de CVEs. AndroidX Test
  arrastra annotation 1.7.0-beta01; revisión documentada, no entra a producción.
- TestClient/httpx: fallback aún soportado pero deprecado por Starlette. Las primeras
  versiones de httpx2 examinadas tenían advisories; una migración mayor de la pila de
  pruebas queda pendiente. La advertencia aparece en todos los resultados.
- `android-actions/setup-android` v3 sigue usando Node 20 upstream; las otras acciones
  se migraron a versiones fijadas con Node 24 verificadas en sus action.yml. No se fuerza
  runtime no soportado para ocultar el aviso.
- Docker emite avisos de pip como root durante build y actualización disponible; runtime
  es UID 10001. No se actualizó pip indiscriminadamente ni se suprimieron sus avisos.

BLOQUEADO — Bluetooth físico y protección TEE/StrongBox reales: no hay dos teléfonos
ni hardware compatible disponible. Bóveda productiva desbloqueada en el AVD también
bloqueada por la política de hardware que se conserva.

NO EJECUTADO — Migración SQLite Android/invalidación real de claves, disco lleno
Android, lock durante cada operación, muerte de proceso/continuación de selector,
matriz exhaustiva UI/permisos, reconexión/cortes/backpressure Bluetooth, carga sostenida,
TLS público, auditoría independiente, SBOM/licencias/grafo AGP/native completo. No se
cierran P0-03 a P0-12 en bloque. Proveedor externo no cooperativo puede retrasar el worker;
la ventana gate/commit de SQLiteOpenHelper.onUpgrade necesita una regresión específica.

VERIFIED — Se consultó también la ejecución solicitada `35407207521`, SHA main
`3716f09e415c69f59102e74cffa6a1bbb154dec6`: repository-guard, relay-and-core y
relay-container success; Android failure antes de compilar por paquete SDK `tools`
inexistente. La selección explícita `platform-tools` de #1 se conserva.

CI REMOTA BLOQUEADA — Se consultó la ejecución de #2 `35415101796` y su anotación:
GitHub no inició el job por pagos fallidos o límite de gasto. No es un test fallido
ni un resultado verde. La ejecución verde histórica de #1 `35409062951` no valida
este código. El workflow nuevo propaga errores, comprueba conteos, incluye integración,
instrumentación y smoke R8; mantiene contents:read, checkout sin credenciales persistentes
y artefactos ligados al SHA. Su validación remota final se registra por separado tras
abrir la PR. No se cambió facturación, permisos ni infraestructura para sortear el bloqueo.
