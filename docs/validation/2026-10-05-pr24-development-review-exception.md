# PR #24 — revisión técnica asistida por IA y excepción de desarrollo

Fecha de decisión: 5 de octubre de 2026, America/Bogota. Registro técnico UTC: 6 de octubre de 2026.

## Decisión del propietario y alcance

**USER_DECISION:** el propietario autoriza una excepción documentada al requisito
de revisión humana para integrar exclusivamente PR #24 como baseline de desarrollo.
Acepta expresamente que no existe revisión humana especializada. Exige revisión
técnica real asistida por IA, resolución de bloqueantes y CI completa del SHA final.

**HUMAN_SPECIALIZED_REVIEW: NOT_PERFORMED / REQUIRED_BEFORE_PRODUCTION.**
No se crea una aprobación de GitHub ni se presenta esta decisión como revisión
humana. La regla general de AGENTS.md permanece vigente para los cambios sensibles
futuros. La excepción se consume con este único merge; no habilita producción,
OCI, reemplazo de APK, cambios de protecciones ni reducción de seguridad.
Si GitHub exige una aprobación, se respeta: no se usa --admin ni se altera la regla.

## Referencia y trazabilidad

- PR: https://github.com/DevOps-Solutions-IA/umbra/pull/24
- Rama: `codex/consolidacion-main-20261005`.
- Candidato inspeccionado: `96eaf212a9760574c15b2f5570019572c74e38ac`.
- TREE inspeccionado: `68c7b985b52fcb9ec3a6c11e9bcf79272fdc2448`.
- main de referencia: `3716f09e415c69f59102e74cffa6a1bbb154dec6`.
- Consolidación acumulativa: 253 commits anteriores al nuevo cambio; 728 archivos
  en el diff contra main. No es solamente UI.
- La CI anterior de ese candidato: 11 workflows / 29 jobs SUCCESS, checkout
  `7e16e882980a7d823eeede8996a67be3b8f7814f`, TREE igual por consulta GitHub.
  Es evidencia histórica y **no valida el SHA corrector/documental nuevo**.

El recibo final en PR #24 y la carpeta local de control vincularán SHA final,
TREE, checkout de integración, resultados y artefactos. Esta documentación no
puede incluir su propio SHA sin crear una referencia circular; el recibo externo
se comprueba después del commit. No se declara merge ni CI nueva aprobados aquí.

## Revisión técnica ejecutada

Lectura directa de fuentes por el coordinador y tres revisores IA independientes
con alcance separado, inicialmente de solo lectura. No es auditoría criptográfica
formal ni revisión humana. Se contrastaron rutas sensibles con pruebas existentes
y evidencia de laboratorio; no se presume revisar exhaustivamente cada línea del
diff acumulativo ni certificar plataformas físicas no ejecutadas.

| Alcance | Archivos y controles inspeccionados |
|---|---|
| Bóveda, acceso y cierre | Vault, PasswordEnvelope, AccessGate, AccessSession, VaultSessionRegistry, EmergencyLock, ConnectivityService y AndroidConnectivity: claves perdidas, hardware obligatorio, wrapping doble, epoch, deadlines, commit/rollback, cierres confirmados y startup offline. |
| Identidad, protocolo y relay | Engine, SignalStore, Records, Wire, PairingService, PairingProduct, AdmissionService; relay admission_http/admission_store/admission_context/guard/app/rendezvous/pairing: recipient binding, ratchet/outbox atómicos, ciphertext inmutable, replay, admisión independiente de VERIFIED, cuotas y default deny. |
| Medios, dependencias y aislamiento | Gradle/manifiestos/proguard, webrtc-artifact.json, AAR e inventario interno, TurnConfiguration, NativeVoiceSession, parches native TURN/SDP, RestrictedContentService/Playback/Flow y políticas de APK/CI. SHA del AAR y hashes internos comprobados directamente; no reconstrucción nativa nueva. |
| Consumidores y exportación | MainActivity (solo lectura): startup/autenticación, background, callbacks por generación, Nearby explícito, external actions, emergencia y exportación; OrdinaryTextExport, DocumentIO y manifiesto: consentimiento ligado a lease, bounds, cierre, ausencia de visor/share/URI externo para contenido restringido. |

Los informes detallados, comandos y salidas se conservan en la carpeta persistente
`UMBRA_CONTROL/2026-10-05_INTEGRACION_PRIMERO/AI_REVIEW_EXCEPTION_G1_20261006T015917Z`,
fuera del código del producto, sin claves ni contenido de usuarios.

## Hallazgo bloqueante reproducido y corrección mínima

**G1-AI-01 — fallo de invalidación conserva el lease anterior.**
`AccessGate.invalidateAuthorizations()` ejecutaba `invalidate()` antes de avanzar
el epoch. Si un invalidator lanzaba RuntimeException, `invalidate()` intentaba los
otros callbacks y propagaba un error; la llamada nunca incrementaba el epoch y
mantenía `open=true`. La prueba aislada aceptó el lease previo después del error.
Un callback también podía usar el lease antiguo durante la propia invalidación.

Reproducción RED con fuentes reales y JUnit: VaultGateTest, 5 tests, 3 fallos
nuevos; se conserva `gate-red.log`. Un probe independiente también terminó exit 1
con OLD_LEASE_ACCEPTED. Esto demuestra una violación del contrato de autorización,
**no demuestra extracción de plaintext de una bóveda enrolada**, cuyo propio
invalidator descarta el DEK.

Corrección: incrementar epoch antes de callbacks; ante fallo de limpieza cerrar
el gate y registrar VAULT_FAILURE, propagando la excepción original. Se conservan
el intento de todos los invalidators, los plazos y el instante de autenticación.
No hay bypass, nueva API, renovación, recuperación automática ni cambio de crypto.
Tres regresiones: lease denegado durante callbacks; limpieza fallida cierra y
rechaza nuevas operaciones; recuperación no revive epoch viejo. Las cuatro suites
VaultGateTest, AccessReadinessGateTest, EmergencyLockTest y DocumentIOTest pasan
**29/29** localmente con JDK21 tras la corrección. La revisión independiente del
diff confirmó la corrección mínima y ausencia de cambios de API/deadline; la CI
final sigue siendo condición adicional para integrar.

## Límites conservados y pendientes

- La migración SQLite v1→v2 sigue bajo commit final de SQLiteOpenHelper. La última
  comprobación de gate no es atómica con ese commit del framework; está advertido
  en Vault. No se afirma rollback de un commit ya confirmado ni bypass de apertura.
- Objetos restringidos caducados se rechazan al abrir y su purga se realiza al
  listar/recibir contenido restringido. No se promete eliminación inmediata del
  registro cifrado ni borrado forense. No se observó acceso posterior a expiry.
- El EOF HTTP histórico original permanece sin causa confirmada. La reproducción
  controlada del pool TLS/keepalive es evidencia distinta, no resolución retroactiva.
- UI de Claude se inspecciona como consumidor pero no se modifica. No se valida
  con esta revisión una conversación humana, cámara/micrófono físico, dos radios
  físicas ni la semántica completa de cada fabricante.
- Procedencia fijada/CI no sustituyen revisión humana especializada, evaluación
  de dependencias actualizada, firma/distribución ni aceptación de producción.
- PR #17 no está incluido: permanece separado; no se fuerza su README histórico.

## Gate de integración

Antes del merge: revisar hallazgos y corrección; ejecutar controles locales y
las 11 suites de CI (29 jobs) del **nuevo HEAD**, sin omitidos/errores sin explicar;
comprobar checkout/TREE, artefactos y refs; conservar archivos del propietario.
Si el HEAD o main avanzan, revalidar su delta. Merge commit protegido por SHA
esperado; lectura posterior de GitHub, fetch/ascendencia y worktree limpio de main.
No auto-merge, force-push, eliminación de ramas, despliegue ni instalación.
