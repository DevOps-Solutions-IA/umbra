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
