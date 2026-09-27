# Private device admission — 2026-09-27

## Estado de este recibo

PARTIAL, bajo validación. Base real consultada: PR #11 OPEN/DRAFT,
`codex/personal-vault-password`, HEAD `0de2b102d2d09b1eeed0e4fe233d3b27976e1036`.
Trabajo aislado en `codex/private-device-admission`; sin modificar la base,
MainActivity ni archivos UI de Claude. La CI de la base es histórica.

## Arquitectura implementada

Ver ADR-private-device-admission y ADMISSION.md para campos exactos, límites,
amenazas y APIs. Ed25519 dedicado mediante BC 1.86 ya fijado en Android y
PyNaCl 1.6.2/libsodium en relay. Autoridad privada solamente en Vault del admin;
relay recibe objetos públicos firmados. Credential por dispositivo más proof
único ligado a HTTP exacto/capacidad, o al transcript autenticado RFCOMM/Signal.
Revocación persistente cancela outbox conocido, sin borrar historia ni ratchets.
Migración relay v4→v5 transaccional; ausencia/futuro/corrupción falla cerrada.

## Evidencia local inicial (árbol de trabajo, no CI del HEAD final)

Python 3.13.12, OpenJDK 21.0.11, Gradle 8.13, AGP 8.13.2, SDK36,
build-tools35.0.0. `.venv/bin/activate` antes de las pruebas Python.

| Comando/capa | Resultado ejecutado |
| --- | --- |
| `bash scripts/codex_setup.sh` | exit0 |
| `bash scripts/test_local.sh` con JDK21 | exit0; 201 backend, core y guardas de fuente |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | exit0; 153 herramientas |
| `python scripts/build_android.py --check-only` | exit0 |
| `python scripts/build_android.py` | exit0 en árbol intermedio; debug, lint, permisos, Signal/JNI |
| Gradle `testConnectedDebugUnitTest testOfflineDebugUnitTest` tras cambios de revocación | exit0; 185 connected + 145 offline, sin fallos/omitidos |
| Gradle `assembleConnectedRelease assembleOfflineRelease assembleConnectedDebugAndroidTest assembleOfflineDebugAndroidTest` | exit0, R8 compilado; NO ejecutado en Android |
| Gradle con `-PumbraVaultLab=true`, ambos APK y androidTest VaultLab | exit0 en árbol intermedio; no equivale a ejecutar R8 |
| `python scripts/test_relay_integration.py` | exit0 en árbol intermedio: Engine/libsignal real, relay HTTPS/SQLite, identidades, dispositivos, ubicación, señalización y reinicio |
| AVD local | BLOQUEADO: usuario sin acceso a `/dev/kvm`, no se alteran permisos |
| AVD/Keystore/RFCOMM/admission CI del nuevo código | NO EJECUTADOS aún |
| Multimedia y contraseña sobre el nuevo HEAD | NO EJECUTADOS aún |

Logs completos locales preservados en `.run/admission/` (no se publican volcados).
No se atribuye durabilidad Android a MemoryRecords ni reapertura SQLite a una
muerte durante commit. Las pruebas de red JVM no son Bluetooth físico.

## Fallos conservados y correcciones comprobables

- Primer baseline usó javac antiguo por orden de PATH/venv: corregido el entorno,
  sin alterar requisitos ni pruebas.
- Gate nuevo rechazó fixtures históricos sin admisión: ahora cada fixture positivo
  obtiene una credencial explícita firmada por administrador sintético; no bypass.
- Nueva prueba de fallo durante renovación esperaba OperationalError; SQLite
  `RAISE(ABORT)` genera IntegrityError. Corregida expectativa exacta y mantenidas
  aserciones de rollback, consumo y revocación. Backend final local pasó.
- Un desafío HTTP por operación agotaba rate limit existente: pools de ocho nonces
  únicos, sin aumentar límite. Reinicio invalidaba el pool: un único reintento con
  desafío nuevo, lease original y ciphertext inmutable. Integración HTTPS pasó.
- Se rechazó una integración de verificación Ed25519 que aceptaba claves públicas
  degeneradas: validación completa de punto en BC/libsodium y regresiones en ambos
  lenguajes. No se atribuye un CVE no confirmado a otra biblioteca.
- Android no dispone de Files.readString/writeString en el nivel usado: helper de
  pruebas utiliza readAllBytes/write. Compilación androidTest posterior pasó.
- Invocar variantes producción y VaultLab juntas activó conflicto de tareas R8;
  se ejecutan builds separados con su propiedad correspondiente, sin desactivar R8.
- Guard rechazó local.properties creado por esta ejecución: movido a `.run/`,
  SDK suministrado por ANDROID_HOME; 153 herramientas posteriores pasaron.

## Pendientes explícitos

CI propia del commit publicado y hashes de artefactos finales; instrumentación
actual con SQLite/AndroidKeyStore, RFCOMM real del stack emulado y matrices media.
Extender aceptación negativa de admisión en RFCOMM y recorrido completo A1/A2/B1
online. No existe emisor TURN productivo en la base: se protege el Engine y las
rutas privadas por defecto; el proveedor sintético del laboratorio no se declara
servicio de producción. Revocación offline solo se aplica cuando se recibe o
expira la credencial. Rotación de autoridad y recuperación fuera de alcance.

No garantía frente a OS comprometido, anonimato ni borrado remoto. Sin prueba
física de radio/Keystore TEE/StrongBox nueva. UI pendiente de conectar las APIs;
no se conserva un acceso legacy sin admisión para mantener pantallas funcionando.

## Primera CI publicada: d63b73b, no aceptación final

PR #13 abierta en borrador hacia #11. HEAD `d63b73b940807e0cfa117ff3a771daa640ef9bfc`,
checkout de integración `11c5a0ecccc5f37d169a4bef997de16d81fc1d34`.
Admission laboratory **36309626249 SUCCESS**, debug/R8 × connected/offline:
los tres casos se ejecutaron en cada variante. Android reportó KeyInfo nivel0
(software) en la prueba aislada; no hardware-backed. Artefactos 10929170524 y
10928536039 descargados y conservados. Password laboratory **36309626214 SUCCESS**.

Verify **36309626184 FAILURE**: prueba de rechazo de dispositivo físico cargaba
PyNaCl antes de comprobar que era emulador. Corregido el orden de inicialización;
153 herramientas pasan también en entorno sin dependencias del relay. No se
instalan dependencias en la guarda para ocultar esa regresión.
Voz R8 **36309626231 FAILURE** y modulación **36309626187 FAILURE**: validación
de challenge de admisión durante bombeo de controles; stack debug localiza
AdmissionChallenge.validate. Causa temporal exacta aún no demostrada, diagnóstico
con categorías fijas añadido; no se amplían TTL ni se acepta un desafío inválido.
Video/focused consultados aún en ejecución al escribir esta entrada; revisar
estado terminado antes de informar resultado final.

La nueva aceptación JVM comprueba A2 vinculado sin admisión, aprobación posterior
independiente y entrega explícita de la credencial a A1. Se añade caso RFCOMM
negativo real al arnés, pendiente de la siguiente CI. Se rechaza reinicializar
un realm cuyo pin falta pero conserva registros, con regresiones Java/SQLite.

Benchmark local sintético (`python scripts/benchmark_admission.py`, exit0,
100 muestras): p95 verificación credential 0,147 ms, proof 0,100 ms, autorización
SQLite 4,168 ms. No medición Android ni prueba de carga o capacidad productiva.

## Diagnóstico temporal y extensión de aceptación

HEAD `6c3914f093beb7e46702cdbc26a2c384b782df95`, checkout
`bbd08fa380e5ba540b1d40710ffc52ba53a6250e`: modulación **36310120793 FAILURE**
en ambas matrices con `Admission challenge not yet valid`. Esto demuestra que
el reloj local todavía precedía el issuedAt del relay; no era autenticación
TURN ni un permiso de captura. Se añade espera breve acotada documentada en el
protocolo, manteniendo la comprobación estricta posterior, y regresiones de
reloj detenido, diferencia excesiva y lease invalidado durante la espera.
La siguiente CI debe probar la corrección; todavía no se declara resuelto.

Sobre 6c3914f, admisión **36310120795 SUCCESS** y contraseña **36310120749 SUCCESS**;
Verify aprobó guard, backend/core y contenedor, Android aún pendiente al consultar.
Voz R8 **36310120774 FAILURE**, no aceptación final.

Aceptación local ampliada, `python scripts/test_relay_integration.py` exit0:
A2 vinculado pero NOT_ADMITTED; denegación por API; aprobación propia;
A1 revocado primero en el relay, HTTP403 antes de sincronizar el cliente;
A2 y B1 mantienen admisión y entregan mensaje Signal real por HTTPS. Estado
revocado persiste al recrear Engine (no se equipara a proceso Android muerto).

Se añade force-stop de proceso Android después de commit de revocación, pendiente
de ejecutar en CI. El arnés media comprueba además HTTP403 pre-admission desde
Android mediante solicitudes directas, antes de instalar credencial; la ruta
TURN sigue siendo una comprobación default-deny, no un issuer productivo nuevo.

Verify 36310120712 terminó FAILURE: Android ejecutó **39 casos con OK**, pero el
runner exigía el total histórico36. El artefacto descargado muestra los tres
DeviceAdmissionTest adicionales, sin skip/fallo. Se actualiza el total exigido
a39 connected /37 offline y se añade regresión que rechaza el total antiguo;
la siguiente CI debe ejecutar también offline. No se reduce cobertura ni umbral.

Pruebas locales posteriores: 203 backend exit0 (incluye revocación mientras un
handler HTTP espera antes de escribir), suites JVM de ambas variantes y builds
androidTest exit0. Laboratorio R8 con listener de reinicio compilado, aún no
se cuenta ejecutado. Los recibos de CI anteriores permanecen históricos.

## 2a15f27 — recibo intermedio

HEAD `2a15f2757dac95ef3925eebe970c64b2c25d1db0`, checkout de Actions
`014c176c14a443568d73a7f531d5198060cce1fc`. Admission laboratory **36310722519
SUCCESS**: ambas variantes debug/R8, incluyendo force-stop real después de
commit y rechazo de admisión revocada en el nuevo proceso. Password laboratory
**36310722472 SUCCESS**. Otros workflows se consultarán al terminar.

Modulación debug 36310722537 falló al **instalar** la credencial, antes de media:
AdmissionCredential.validate/notBefore. No fue la comprobación del desafío.
El arnés recibía la aprobación cuando el segundo del reloj del host aún estaba
por delante del AVD. Se conserva el rechazo productivo y se espera notBefore
solo en el provisioning sintético, con límite2,5s, diferencia máxima2s y lease
de solicitud original. Nuevo test demuestra que instalar antes de notBefore
no almacena credential ni permite autenticación, y sí funciona al llegar el plazo.

`build_android.py` sobre 2a15f27 exit0: 189/149 JVM, lint, JNI, políticas APK.
SHA256 debug connected `058d5041c024e195bcc32651cbb365d21aabb7ff0b8e77b96fb224dc74a4830b`;
offline `ee09c0c017a4d6a4394bbaa80e1250b8aff0ecc0b8e970d901ee24fda786575e`.
`test_relay_integration.py` sobre ese HEAD exit0, incluyendo A1/A2/B1 y
revocación independiente. Son artefactos locales de ese SHA, no del siguiente.

Focused regressions 36310722521: los seis casos nearby fallaron antes de RFCOMM
por `ModuleNotFoundError: nacl`: ese job separado no instalaba las dependencias
del administrador sintético. Se añade instalación del lock con hashes; no se
atribuye el fallo al stack Bluetooth. Las seis repeticiones se conservan y se
agregan dos rechazos de dialer no admitido, uno por flavor. 155 herramientas
locales pasan, incluida la composición exacta de la matriz.

Modulación 36310722537 terminó: **R8 SUCCESS**, debug FAILURE por notBefore ya
descrito; no atribuir ese verde parcial a otro HEAD. La revisión añade también
cierre explícito de scopes WebSocket, sin protocolo de posesión definido en v1,
para impedir que una ruta futura eluda el gate HTTP. La nueva regresión real
TestClient confirma code1008 antes de ejecutar el handler; 17 pruebas HTTP pasan.

## Aislamiento entre repeticiones del laboratorio

En 36310722521, los logs debug muestran AdmissionCredential.verify línea27
(realm/issuer mismatch), antes de media, además de los fallos notBefore.
El arnés limpiaba synthetic-voice-* pero no los cuatro archivos de intercambio
synthetic-admission-*. Una instalación fallida dejaba una aprobación del realm
anterior que el siguiente escenario podía leer. Se añade force-stop previo y
limpieza explícita de esos cuatro archivos públicos antes de iniciar cada fixture
voz/video/modulación y RFCOMM, sin borrar la bóveda ni aceptar otra autoridad.
La regresión reproduce archivos residuales, comprueba orden stop→cleanup,
preservación del vault y rechazo si stop falla. 157 tests de herramientas exit0.
La repetición real en AVD queda a cargo de la CI del siguiente commit.

678786d: Admission 36311575649 y Password 36311575634 SUCCESS.
Checkout Admission `f344ac00063714c89be2a1c8800782ccfe741fe3`.
Artefactos Admission: 10928797756 (R8), digest SHA256
`e5d6dbd533fd8e6ee2fd2175e3286627e68999a17491f1dd514b8b82c71cca28`;
10928608210 (debug), digest SHA256
`04daa1b4c7b218b953e1c11d7ce97e5268d50a01f1c45e5104f62aece19f8087`.
Son recibos de ese SHA, no validación automática de la corrección posterior.
Build local sobre 678786d exit0: JVM, lint, JNI y APK policy ambas variantes;
hashes APK coinciden con los ya registrados de 2a15f27 (sin cambio productivo
Android entre ellos). Backend local 204 tests exit0.
