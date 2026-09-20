# Segunda entrega: dispositivos — 2026-09-20 UTC

Estado: núcleo validado en las capas ejecutadas; cobertura de hardware y operación
completa PARTIAL. La sección final distingue el run aprobado y la CI propia del
último commit de evidencia/fixtures. No es aprobación de producción ni auditoría
independiente. Solo datos sintéticos.

## Base preservada

PR #4 consultada OPEN, HEAD `5193b73e3a7e6176d48c4a3531ea07532aa5802d`.
CI histórica `35536317146`, cuatro jobs SUCCESS, comprobada por SHA. Rama nueva
`codex/device-linking-core`, dependiente de `codex/secure-identity-media-core`.
No se ha modificado main ni las ramas anteriores. El fallo histórico AVD no se
reabrió; se conserva su reparación y su evidencia.

## Alcance implementado para validación

- DeviceService/DeviceRoster/DevicePolicy: autoridad A1, claves independientes,
  desafío específico, consentimiento ligado a desbloqueo, prueba de posesión,
  consumo transaccional, lista firmada/versionada, aprobación completa, revocación.
- Engine: política también en APIs antiguas/Bluetooth, fanout libsignal por destino,
  UUID lógico dentro del ciphertext, entrega/ACK/reintento independiente, revocación
  de outbox/sesiones sin borrar historial ajeno, controles internos cifrados.
- Backend: delegación opaca solo de borrado, esquema 3→4, tombstones permanentes,
  rechazo de capacidades revocadas y recreación del mismo UUID. Sin claves privadas,
  nombres humanos o directorio de identidades.
- SQLite de laboratorio únicamente en androidTest, UID debug `.dev`, distinto de release, en un directorio sintético exclusivo, datos sintéticos.
  No es Vault cifrado, Keystore hardware ni una variante productiva con fallback.
- Corrección de Vault: un fallo anidado capturado por el llamador no debe devolver
  éxito después de que SQLite revierta toda la transacción; se conserva la regresión
  existente y se exige ahora rechazo explícito.

## Reproducción y comandos

Activada `.venv/bin/activate` antes de las pruebas Python. Baseline local ejecutado:
`bash scripts/codex_setup.sh`, `bash scripts/test_local.sh`, unittest de scripts,
repository_guard y build_android --check-only; todos código 0. Baseline: 130 pytest,
105 escenarios Java, 12 controles estáticos separados, 100 tests de herramientas.

Las pruebas nuevas incluyen errores de almacenamiento, pérdida parcial, expiración
esperando transacción, consentimiento anterior al bloqueo, participantes/card
incorrectos, parser no canónico, activación repetida, listas antiguas/tombstones,
concurrencia de alta y revocación/envío, rollback del fanout y prueba de delegación.
Los resultados finales se añaden una vez ejecutados, sin heredar contadores de #4.

Fallos iniciales observados y corregidos: parser Base64 lanzaba IllegalArgumentException
en lugar del rechazo de protocolo esperado; la aserción HTTPS contaba también la
entrega saliente al contar mensajes entrantes de A2. Ninguno se ocultó excluyendo tests.

## Cobertura y límites que permanecen

A1/A2/B1 se ejercita con libsignal JNI real y relay HTTPS aislado. Sus Stores JVM
son memoria sintética: recrear Engine no demuestra durabilidad Android. Los tests
Android añadidos usan SQLite real separado para reapertura, ratchets y tombstones.
El backend prueba reinicio de la aplicación sobre el archivo SQLite conservado.

La ceremonia se usa desde APIs/harnesses; no se ha añadido interfaz definitiva ni
flujo visual de administración. R8 smoke prueba arranque bloqueado/JNI existente;
no equivale a ejecutar la ceremonia desde la interfaz release. No hay copia
automática de historial ni espejo de mensajes salientes entre dispositivos propios.

La revocación de relay exige delegación previamente instalada y recibida; antes de
eso es PENDIENTE, aunque la revocación local ya bloquee nuevas entregas. Listas
retenidas, clientes desconectados y bytes emitidos tienen los límites del ADR.
A1 único administrador implica pérdida de administración si se pierde; compromiso
de A1 compromete delegación. Reincorporación exige nueva clave y ceremonia.

BLOCKED: hardware Keystore, bóveda cifrada/migración exitosa en hardware y Bluetooth
físico. PENDING: auditoría humana de protocolo/autoridad y recuperación exportable.
Ubicación, voz, vídeo y TURN no se han implementado. No se promete anonimato ni
seguridad absoluta. La advertencia Starlette/httpx de la base y APIs Android
obsoletas existentes requieren seguimiento; no se suprimen globalmente.

## Resultados de corrección y cobertura local

Código de runtime: `e50bfa5ade2bcd982f5d5a6faea081eb147b0583`.
Suites ampliadas: `ef1106f8d7322a6fb4cc9c543aa93829e0927cec`; la entrega final
incluye además duplicados de todos los tipos no-ACK en RFCOMM. Los cambios posteriores
de fixture/documentación no alteran los APK de aplicación generados localmente.

| Hallazgo | Severidad / clase | Evidencia y estado |
|---|---|---|
| Fallo anidado Vault podía devolver éxito exterior después del rollback | Media, API de persistencia; Vault.java:193 | Corregido en `04f3064`; regresión SQLite Android exige excepción exterior y conserva filas anteriores. El comportamiento anterior está en la prueba histórica, no se cuenta como reproducción nueva de hardware. |
| Suspender la identidad de A1 no suspendía sendText directo a A2 | Alta, autorización; DevicePolicy.java:32 | Nueva regresión falló realmente antes del arreglo: 1 test/1 fallo. Corregido en `e50bfa5`; se comprueba rechazo por API y transporte. |
| Escritura autorizada podía adquirir una sesión nueva tras lock/unlock al usar courier directamente | Endurecimiento de ciclo de vida; Engine.java:209 | Lease de Records capturado al iniciar entrega y revalidado antes de escribir; prueba de lease antiguo y contacto bloqueado. Main conserva además su generación/cancelación. |
| Directorio `.test/cache` no accesible desde UID objetivo de instrumentación | Error del fixture | Dos fallos locales y en CI inicial. Corregido usando directorio sintético exclusivo dentro de `.dev`, separado de release; ningún cambio de Keystore. |
| Fixture A1 no había aprobado membresía de B1 antes de recibir contenido v2 | Error del fixture | Un fallo local y en segunda CI. El rechazo del Engine era correcto: la prueba ahora exige rechazo, aprobación explícita y reintento del mismo ciphertext, sin relajar el contrato. |
| Android Settings dejó de responder antes de emparejar AVD locales reutilizados | Limitación transitoria del entorno | Timeout y cleanup efectivos. Repetición con directorios AVD nuevos pasó; no se aumentaron límites ni desactivó KVM. La causa interna del ANR no está determinada. |

Una iteración local de endurecimiento rechazó también el bootstrap legítimo (11
fallos JVM); se corrigió antes del commit de corrección. No se excluyó ninguna
prueba ni se utilizó un baseline para ocultarlo.

### Herramientas verificadas

Python local 3.13.12 en `.venv`; CI configura Python 3.12. JDK local
21.0.11+10-1-Debian; Gradle 8.13; Android Gradle Plugin 8.13.2;
compile/target SDK 36; build-tools 35.0.0; libsignal Android/client 0.102.3.
Emulator local 37.1.11.0 build 15917651; imagen Android 35 default x86_64 revisión 2;
adb 36.0.0-13206524; Linux WSL2 6.18.33.2, KVM obligatorio. Docker 28.5.2.
No se actualizaron indiscriminadamente dependencias.

| Comando / control local | Código y resultado |
|---|---|
| `bash scripts/codex_setup.sh` | 0, entorno Python preparado |
| `. .venv/bin/activate; bash scripts/test_local.sh` | 0: 136 pytest, 105 escenarios Java; 12 controles estáticos aparte |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | 0: 100 tests de herramientas |
| `python scripts/repository_guard.py --git-history` | 0: fuente e historial revisados; no es auditoría de secretos exhaustiva |
| `python scripts/build_android.py --check-only` | 0; solo preflight |
| `python scripts/build_android.py --release` | 0: debug/release connected/offline, lint, manifiestos, APK/DEX/JNI |
| `gradle :app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest` + `check_android_tests.py` | 0: 92 JVM por variante, sin failures/errors/skips |
| `gradle :app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest` | 0: APKs de instrumentación reales |
| `python scripts/test_relay_integration.py` | 0: 28 comprobaciones previas y escenario A1/A2/B1 HTTPS/JNI añadido |
| `run_android_instrumentation.py` connected y offline | 0: 18 pruebas ejecutadas por variante |
| `smoke_release_launch.py` | 0: ambas variantes instaladas con firma efímera, UI bloqueada; copias/clave temporal eliminadas |
| `ci_emulator.sh` + `ci_bluetooth.sh`, AVD nuevos | 0: dos extremos por variante, RFCOMM emulado, firmas/listas, texto/adjunto y ACK; no radio física |
| `docker build --pull` + `docker run --network none --read-only ...` + health/UID | 0: UID 10001, salud HTTP interna, contenedor aislado eliminado |

La prueba Bluetooth ampliada conserva el duplicado de texto existente: se duplican
lista, texto y adjunto, y el verificador exige el marcador de lista autenticada.
Las suites JVM no son instrumentación; las comprobaciones de fuente no suman pruebas
de comportamiento. A1/A2/B1 en instrumentación son tres Engines/archivos SQLite
independientes dentro de un AVD, no tres teléfonos ni tres aplicaciones Android.
La reapertura real de SQLite no se presenta como corte de energía ni force-stop
inyectado en mitad del commit: esa matriz sigue NO EJECUTADA.

### Artefactos locales de aplicación

APKs debug de laboratorio, sin publicación de release ni firma productiva:

| Artefacto bajo `android/app/build/outputs/apk/` | SHA-256 |
|---|---|
| connected/debug/app-connected-debug.apk | `e80f12f574b08c5d8f32ffc27a5d236df064d785a7536541a3541eebd382e819` |
| offline/debug/app-offline-debug.apk | `fb3274a623d7ddf5b3e19c2a029e2247b6f335a38318b07ba26fed15d24c0ed7` |
| connected/release/app-connected-release-unsigned.apk | `ebbe4cd9151da03c813a89a4605927cdd296f9dc8cccd2a07baae5417443dc02` |
| offline/release/app-offline-release-unsigned.apk | `59a0d858ab18d7b388cbd9cc93f501c8f7a7929984319fe2a82d8ce00c1aa62f` |

Offline final inspeccionado sin INTERNET ni ACCESS_NETWORK_STATE. Todas las ABI
Signal esperadas están presentes; JNI de testing/escritorio y clases lab/test están
excluidas de los APK de aplicación. Las firmas debug de runners pueden cambiar el
hash debug; se registran por separado los artefactos CI.

## Historial de CI de esta PR, sin ocultar fallos

| Run | HEAD | repository-guard / relay-and-core / relay-container / android |
|---|---|---|
| `35543327059` | `e64e652ab7b86f8d3676e1fda55c0d840411e8d7` | SUCCESS / SUCCESS / SUCCESS / FAILURE (2 fixtures SQLite) |
| `35543752545` | `e50bfa5ade2bcd982f5d5a6faea081eb147b0583` | SUCCESS / SUCCESS / SUCCESS / FAILURE (membresía B1 ausente en fixture) |

Se descargaron ambos reports: las causas coinciden con la reproducción local.
Checkout de CI inicial: `ecc5ecc215d9df74162d89910033cf2fa4e3eb9b`; segundo:
`ef3850f73c039ea549ba4837f03d1255b5ace635`. Son commits de merge de Actions, no
el HEAD de la rama. Reports de fallo: artefactos `10615349176` y `10616160276`.
El fallo fue de instrumentación después de arrancar Android; **no** reapareció el
problema histórico de AVD inexistente. Los logs de emulator/instrumentación se
conservan en los artefactos de esas ejecuciones; no se copian datos sintéticos de
identidad ni logs completos al repositorio.

### CI aprobada de implementación y comparación de artefactos

[Run 35544189738](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/35544189738):
**repository-guard SUCCESS, relay-and-core SUCCESS, relay-container SUCCESS,
android SUCCESS**. HEAD `ef1106f8d7322a6fb4cc9c543aa93829e0927cec`; checkout real
`949f93a1fe9e46215afc7a339a8b079ddf349a41`. Se descargaron los reports y APK debug,
se verificaron sus hashes y se leyeron los resultados: 18 instrumentadas por variante,
R8 bloqueado, listas firmadas por RFCOMM en ambos extremos/variantes. El código final
de fixtures añade además duplicación de los tres tipos no-ACK, ya repetida localmente
con éxito; su nueva CI y HEAD se registran en la descripción de la
[PR #5](https://github.com/DevOps-Solutions-IA/umbra/pull/5). No atribuir este run anterior
a ese commit posterior de evidencia/fixtures sin comprobar su ejecución propia.

| Artefacto CI de `35544189738` | Identificador / SHA-256 |
|---|---|
| ZIP debug, descargado y comprobado | `10616515852` / `05546de4100e24ac482cf9bad8dd599b5f88ee9a96f4944adc3869855bfb109e` |
| ZIP reports, digest publicado por Actions | `10616810033` / `8fd304300ae5ae4732bf3a8b5a170f0a051c5a29f0f5cfc3842c7f48a386f9f4` |
| connected debug, recalculado al leer ZIP | `328fb363b5c12213e50d1a6b1f672e68e9d80ae7aa9bd4968ff5401900f25689` |
| offline debug, recalculado al leer ZIP | `5c099571df00432b708a3c8d8bfa8d43579de8634195d5def936f5c8c8b60753` |
| connected release unsigned, report CI | `ebbe4cd9151da03c813a89a4605927cdd296f9dc8cccd2a07baae5417443dc02` |
| offline release unsigned, report CI | `59a0d858ab18d7b388cbd9cc93f501c8f7a7929984319fe2a82d8ce00c1aa62f` |

Los hashes unsigned release coinciden con los locales. Eso no demuestra todas las
condiciones de reproducibilidad posibles ni sustituye una firma/distribución segura.
Los APK unsigned release se compilaron e inspeccionaron; el workflow solo publica
artefactos debug de prueba y reports, no una release del repositorio.

## Estado de cierre de cobertura

APROBADO: suites ejecutadas descritas, ceremonia A1/A2/B1 por API con libsignal,
relay HTTPS aislado, persistencia SQLite de laboratorio, ambas variantes, R8/APK y
RFCOMM emulado. FALLIDO HISTÓRICO: dos CI y las iteraciones locales documentadas;
no se presentan como éxitos. OMITIDOS en suites ejecutadas: ninguno.

BLOCKED / NO EJECUTADO: hardware Keystore, bóveda cifrada positiva sobre ese hardware,
Bluetooth físico, corte de proceso/energía durante commit, tres aplicaciones/AVD
simultáneos para la ceremonia y auditoría independiente. La UI de administración
no está hecha: se entregan APIs y harnesses ejecutados, no un producto terminado.
La retirada del administrador es terminal en Engine; el borrado remoto cubre buzones
secundarios con delegación recibida. El buzón propio de A1 conserva el flujo anterior
de baja por su capacidad de lectura; no se promete borrado remoto automático de un
administrador perdido o comprometido.

Se preservó evidencia histórica. PR #5 permanece dependiente de #4, abierta y sin
auto-merge. No se han fusionado PRs, hecho force-push, cambiado main, publicado releases,
contratado servicios ni utilizado secretos reales. Los emuladores/contenedores locales
creados para esta validación terminaron y se limpiaron; `adb devices` quedó vacío.
