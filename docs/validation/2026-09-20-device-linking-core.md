# Segunda entrega: dispositivos — 2026-09-20 UTC

Estado de trabajo: PARTIAL hasta registrar la CI del commit final. No es aprobación
de producción ni auditoría independiente. Solo datos sintéticos.

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
- SQLite de laboratorio únicamente en androidTest, UID de test, datos sintéticos.
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
