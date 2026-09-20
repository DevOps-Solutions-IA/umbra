# Núcleo de identidad — bloque inicial, 2026-09-20 UTC

## Base y alcance real

VERIFIED: GitHub PR #3 OPEN, HEAD `757027020a4a2da51e3f69196f1514eccf2c10c9`,
CI `35532763825` con repository-guard, relay-and-core, relay-container y android
SUCCESS. Repositorio PUBLIC. Rama nueva `codex/secure-identity-media-core`, PR
[#4](https://github.com/DevOps-Solutions-IA/umbra/pull/4), dependiente hacia
`codex/repository-audit-fixes`. No main, force-push, merge, cambios de visibilidad,
secretos reales ni servicios nuevos. El fallo histórico del AVD no reapareció.

Este informe cubre bóveda e identidad/emparejamiento. NO declara completada la
misión de dispositivos, ubicación, señalización ni multimedia. La UI definitiva
queda fuera del alcance; se conectaron únicamente importación/exportación y
revocación a los controles existentes.

## Implementación y amenazas

- Se conserva la única IdentityKeyPair libsignal, su id SHA-256 y dispositivo 1.
  Alias no autentica; buzón/capacidades de encaminamiento son independientes.
- `PairingService`: invitación compacta firmada (<1024 caracteres) representable
  como QR/URI/archivo; nonce/capacidades aleatorias de 256 bits. Formato ASCII
  canónico y dominios fijos, versiones/tamaños/TTL/cuotas estrictos.
- Request y ack firmados atan ambas claves y la transcripción exacta. Tarjetas
  libsignal reales, incluidos prekeys Kyber, quedan fuera del QR compacto.
- Contacto, consumo y ack comparten transacción de Records; retry idéntico devuelve
  el mismo ack persistido; otro participante pierde. Revalidación de caducidad
  tras adquirir transacción. Revocación no equivale a eliminar un contacto existente.
- Relay SQLite esquema 3: tres endpoints, consumo atómico, revocación, hashes,
  cuotas 32/buzón y 4096/global, máximo 24h y migración transaccional 2→3.
  No se envían tarjetas ni nuevas claves privadas al relay.
- Engine mantiene VERIFIED_ONLY para texto y archivos, también por API directa.
  Estados UNVERIFIED/VERIFIED/IDENTITY_CHANGED/BLOCKED. Cambio explícito cancela
  sesiones/outbox antiguos, preserva historia y exige la nueva huella completa.
  No hay herencia de confianza por alias ni reenrutado del ciphertext antiguo.
- Comparación hex, decimal completo de 78 dígitos y URI QR que ata ambos ids.
  No hay lector de cámara ni representación en palabras implementados.
- UI mínima permite intercambio de tres archivos y revocación local de invitaciones
  pendientes; el relay auxiliar está integrado y probado en el cliente HTTPS real.
  El flujo UI por archivos no registra automáticamente invitaciones en el relay.

Una invitación robada permite competir por su consumo; la verificación humana
sigue siendo indispensable. Las transcripciones firmadas son transferibles y
revelan metadatos a los participantes. El relay conoce capacidades en las peticiones
HTTPS aunque persista hashes. No se promete anonimato ni deniability de invitaciones.
La API histórica de tarjetas/Bluetooth permanece explícita y requiere verificación;
no se reclasifica retrospectivamente como invitación de un uso.

## Fallos reproducidos y correcciones

| Hallazgo | Impacto / estado |
|---|---|
| Vault.onConfigure execSQL con PRAGMA que devuelve filas | Alto: impide abrir la bóveda en API35. Seis regresiones Android fallaron por variante; rawQuery y comprobación de valor 1 corrigen apertura preservando secure_delete. |
| Vault.prepareKey/destroyKey sin exclusión entre Activities | Alto: preparación/eliminación de alias concurrentes. Dos regresiones Android fallan antes, pasan después de serialización. No demuestra ataque sobre hardware físico. |
| Caducidad de invitación comprobada antes de esperar transacción | Medio: ventana de aceptación tras expirar. Se revalida dentro; dos pruebas con firma real y espera atraviesan el límite. |
| Primer rerun local sin exportar JDK21 | Entorno: JavaSyntaxCheck rechazó release21; repetido con JDK21, sin cambiar código ni desactivar control. |
| Suite añadida durante build intermedio | Verificador correcto: build compiló pero check_android_tests rechazó nueva suite aún ausente. Se repitió conjunto completo con fuentes estables, sin excluirla. |

Detalle y hashes de la reproducción Vault en
[2026-09-20-vault-lifecycle.md](2026-09-20-vault-lifecycle.md), commit
`b1a865cb07c964ea37766b83e3baefe179745d85`, CI `35535038549`: cuatro jobs SUCCESS, comprobados después de finalizar.
No extender resultados de ese commit al posterior bloque de emparejamiento.

## Versiones y comandos

Python 3.13.12 local (CI 3.12), JDK 21.0.11, Gradle 8.13, AGP 8.13.2,
compile/target SDK36, build-tools35.0.0, libsignal0.102.3; AOSP API35 x86_64,
emulator37.1.11 con KVM. No actualización indiscriminada de dependencias.

```sh
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
python scripts/build_android.py --release
python scripts/test_relay_integration.py --classpath-file android/app/build/integration/classpath.txt
```

Ejecuciones verificadas exit0: setup; backend130 (22 nuevos); core105; tooling100;
preflight; build debug+release/R8 y lint ambas variantes; JVM71 por variante
(16 nuevos); manifiestos/APK/JNI/DEX; integración HTTPS28 comprobaciones.
El preflight se registra separado del build real. Logs locales en
`/tmp/umbra-core-20260920/`; no incluyen identidades de personas reales.

Contenedor: `docker build --pull -t umbra-relay:identity-ci relay`, luego misma
configuración aislada que verify.yml (`--network none --read-only`, tmpfs,
cap-drop ALL, no-new-privileges, memoria256m/pids128); exit0. Salud HTTP interna
200 y uid10001. Primer sondeo antes de escuchar devuelve connection refused;
el siguiente pasa dentro del límite. No despliegue ni prueba de TLS productivo.

Advertencias conservadas: deprecación Starlette TestClient/httpx; Android atributo
API33 con min31; versiones más recientes de desugar/test runner/junit/ZXing/JSON;
MainActivity usa API de ciclo de vida obsoleta; reporte Gradle incubating. No nuevas
supresiones ni actualización masiva. Las advertencias no acreditan vulnerabilidad
ni se contabilizan como pruebas.

## Cobertura y límites

PASS local: dos identidades sintéticas → invitación → claimHTTPS → request/ack →
UNVERIFIED → rechazos directos de texto/archivo → comparación → verificación →
libsignal real → HTTPS relay → recepción/persistencia/ACK/reinicio/reordenamiento/
duplicados/revocación. El almacenamiento del harness JVM es MemoryRecords,
claramente separado de la bóveda Android; no prueba hardware Keystore.

PASS de regresión local: firmas alteradas, versión/canonicalización, peer incorrecto,
TTL, revocación, reintento, rollback exacto de contacto/prekeys/consumo, 8 solicitantes
concurrentes (un ganador), parser malformado acotado, identidad perdida y cambio de
clave sin heredar confianza. No se presenta el muestreo de parser como fuzzing exhaustivo.

BLOCKED por hardware: bóveda cifrada de producción/Keystore hardware y Bluetooth
físico. PARTIAL: migración completa y ventana de commit SQLiteOpenHelper, UI con
bóveda hardware, reinicio de proceso durante toda la ceremonia. NOT EXECUTED:
dispositivos múltiples/revocación, ubicación, signaling, voz/video, TURN, palabras de
verificación. RECOVERABLE BLOCKED según ADR; MAXIMUM no tiene recuperación central.

CI y artefactos del commit final se registrarán en la PR con headSHA y SHA del
checkout de Actions, que puede ser el merge sintético. No declarar cuatro jobs
SUCCESS hasta consultar la ejecución correspondiente. Los informes históricos
no se sobrescriben y no validan commits nuevos.

## Android local sobre el bloque de identidad

VERIFIED: `assembleConnectedDebugAndroidTest assembleOfflineDebugAndroidTest` exit0;
`run_android_instrumentation.py --serial emulator-5580 --flavor connected` y offline
exit0, 16 pruebas cada una (3.924s y 4.176s), logs `final-*-device.log`. Estas pruebas
cubren el conjunto Android existente y correcciones Vault, no una ceremonia UI
completa con hardware. `smoke_release_launch.py --serial emulator-5580` exit0:
ambas variantes R8 instaladas y reanudadas en pantalla bloqueada mediante firma
sintética efímera; copias/claves temporales y apps de smoke eliminadas al terminar.

APKs locales inspeccionados (no equivalen a los binarios que produzca Actions):

| APK | SHA-256 |
|---|---|
| connected debug | `988910429b46de0d2ff98052c4cbd517e4b2922b25b2beb1456eedd202ae08ce` |
| offline debug | `24ed60427623512155e51ec3aee8607945c53114a78b17df259ffc9a9f041625` |
| connected release unsigned | `ccef6c8760d671f2d027c593f3adc0a8d552e423fc789a8c30c7c18373855026` |
| offline release unsigned | `2047672e5f75e6690cf3e39e68235c828b388e77f8642323230aab77a9e269ed` |


Revisión adicional del bloque: Bluetooth consulta `Engine.authorizeTransport` al
desencolar cada escritura, no sólo al cifrar. La UI cierra el enlace y cancela relay
al bloquear un contacto. Se prueban rechazos directos antes de verificar y después
de identityChanged. No se promete retirar bytes que ya salieron del transporte.
Los hashes de la tabla anterior corresponden a la ejecución local previa a este
ajuste; los hashes finales del bloque se registran abajo/PR con el commit exacto.


## Estado local final antes del commit de emparejamiento

VERIFIED: tras integrar la comprobación al desencolar, repetidos build debug/release,
lint, APK/JNI/DEX, 71 JVM por variante, 130 backend, core105, tooling100,
HTTPS28, instrumentación16 por variante y smokeR8: todos exit0.
Logs `transport-final-*` conservan este conjunto separado del intermedio.

| APK final local | SHA-256 |
|---|---|
| connected/debug | `2e641e6886fd6740bfbe8e6cacf75eb4cc0c62c90af6b6ea869c2bd1c783390a` |
| connected/release | `d9bb1ef4aaf9a45a41c8ac7bc1471783f5782bf622344f4d4e4ea9fbdcc2d800` |
| offline/debug | `3a7c89d5ed6e9ee0a5addf78ce1bc48ca9aaf81dd0e07dd63fdeadcb145eceb1` |
| offline/release | `01023a84dc383ed5a07f9cbf623369bb02549e8f37e2b77754a9fe97846504bc` |
