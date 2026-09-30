# Recibo — integración final de UI de Claude (2026-09-29)

Estado: **integración de código completa y verificada localmente en las capas disponibles; CI/AVD
pendiente de publicación de la rama.** No es una aceptación física ni de producción.

## Identidad

| Campo | Valor |
|---|---|
| Rama | `claude/final-ui-integration` |
| Base técnica exacta | `e0024f091d29dc15c2d788b430c5ea11204e5060` (= `codex/security-content-completion` = `refs/pull/16/head` al crear la rama) |
| Contrato | `UI_SECURITY_CONTENT_API_V1` (`docs/UI_API_CONTRACT.md`, PR16) |
| Fuente de presentación | `ca2a706fd30c8194181588b19e72c69529f70335` (bundle `umbra-ui-security-integration-ca2a706.bundle`, SHA-256 `a8cb0eaddee9610cbc1c8ad931cec55cf718a79d84e419db825ed94c298bc9c1`, padre `538d158`, prerrequisitos `c394ea7`, `52cd1e5`) |
| Commits de UI | `2d4555e` merge con resolución manual · `0e4f474` cableado al contrato · `e604362` CI de integración UI · `ba9e2bb` documentación · `54d79e5` correcciones de revisión (ciclo de vida del visor, captura, gate de proceso) · commit de este recibo |
| HEAD verificado (antes del recibo) | `e6043620523418db03765790161eff263e823d5a`, árbol `88101f11af5e5e3af91dc562d984c3ec60f12f7e` |

No había trabajo de Claude posterior a `ca2a706` en el repositorio remoto ni en la copia local del
propietario (inspeccionado antes de integrar).

## Reconciliación

Merge explícito de `ca2a706` sobre `e0024f0` (base común `52cd1e5`), sin «ours/theirs» global:

- `docs/API_GAPS_UI_SECURITY.md`: versión técnica (G1–G7 implementados); la lista histórica de Claude queda superada.
- `scripts/run_android_instrumentation.py` y su prueba: conteo exacto = 66 técnicos + UI.
- Todos los demás archivos se fusionaron sin conflicto textual; se revisó la propiedad de cada uno.

`git diff e0024f0 HEAD -- android/app/src/main/java/app/umbra ':!…/ui'` está **vacío**: ningún archivo de
dominio, criptografía, protocolo, almacenamiento o códec cambió. Fuera de `ui/`, solo cambian:
`media/VoiceControls.java` (connected/offline: accesores de presentación y el mismo consentimiento de
video, heredado de `ca2a706`), `ui/media/NoteCapture.java` (puente de sabor nuevo; offline no captura),
`build.gradle.kts` (tres clases de prueba UI en la lista de referencias del fixture `vaultLab`), scripts
y un workflow nuevo. Los manifiestos no cambian permisos.

## Lo integrado (resumen; detalle en `docs/UI_SPEC.md`)

Vault/contraseña (sin cambios de contrato), admisión con todas las API reales, inicio privado y Nearby
explícitos, emergencia real, adaptadores de privacidad y portapapeles privado, contenido protegido
F01–F06 con visor protegido, ciclo de vida (pausa/bloqueo/emergencia/caducidad cierran y no restauran),
errores tipados sin leer mensajes.

## Pruebas

| Capa | Qué | Resultado |
|---|---|---|
| A. JVM presentación | 95 casos `Ui*Test` (incl. `UiRestrictedPresentationTest`, `UiEmergencyPresentationTest`, `UiCopyTest`, `UiErrorPresentationTest` tipado) | **PASS local** (arnés JUnit propio, 95/95) |
| B. Contrato UI↔dominio | `UiAdmissionFlowTest` (+3: expiraciones/autoridad/emitidas, cancelación, aprobación propia atómica) | Compila sin errores nuevos; **ejecución en CI** (JVM del verificador local no tiene libsignal 0.102.3) |
| C. Instrumentación | `UiContentIntegrationTest` (5), `UiSecurityFlowTest` (3), `LockedActivityTest` (3) | Compila (ambos sabores); **ejecución en CI/AVD pendiente** |
| D. Render sintético | `UiScreensRenderTest` 24 (5 nuevos: emergencia, hoja protegida, filas recibidas, visor, autoridad) | Compila; **CI/AVD pendiente**; capturas como artefacto |
| E. Ciclo de vida | Bloqueo/emergencia/caducidad de sesión en `UiContentIntegrationTest`; Activity bloqueada en `LockedActivityTest` | CI pendiente |
| F. Accesibilidad | `assertAccessible` + `assertConcise` (etiquetas, 48 dp, español) en todas las pantallas nuevas | CI pendiente |
| G/H. Debug/R8 | `ui-integration.yml` matriz `optimized: [false, true]` (vaultLab no depurable, mapeo exigido) | CI pendiente |
| I/J. Connected/Offline | Ambos sabores en cada job; offline sin INTERNET/ACCESS_NETWORK_STATE/RECORD_AUDIO/CAMERA | CI pendiente |
| Scripts | `python -m unittest discover -s scripts/tests` 232 casos; `repository_guard`; `check_source_policy` 13 | **PASS local** |
| Tipos | android-36 + stubs: 0 errores en `ui/`; completo vs base: **sin errores nuevos** (34 de deriva, idénticos en `e0024f0`) | **PASS local** |

Conteos de instrumentación completos (`verify.yml`): offline 98, connected 101 (66/69 técnicos + 32 UI).

## CI, APK, artefactos

- Push: rechazado (HTTP 403, repositorio fuera de los permisos de escritura de la sesión). **No hay
  ejecución de CI, APK, mapeo R8 ni capturas del HEAD de esta rama todavía.** Se entrega un bundle
  verificable; al publicarse la rama corren `verify.yml`, `ui-integration.yml` y los flujos técnicos.
- Hashes de APK/mapeo: se registran en los recibos que escribe `scripts/run_ui_integration.py` en CI.

## Revisión independiente

Una revisión de código separada (agente de verificación) sobre `2d4555e..ba9e2bb` encontró y se corrigió en
`54d79e5`: envío protegido desde archivo perdido tras el bloqueo del selector; «Atrás» sin cerrar el visor
(sesión, reproductor y fotograma); hoja de captura descartable con micrófono activo; notas capturadas sin
cerrar al cancelar; gate por Activity (la recreación perdía el coordinador de emergencia, ahora es de
proceso); aviso de portapapeles demasiado optimista. Confirmó correctos: ticket de emergencia, INCOMPLETE,
reconstrucción de bóveda, callbacks obsoletos, hilos, borrado de entradas y errores tipados.

## Hallazgos

`docs/CLAUDE_UI_INTEGRATION_FINDINGS.md`: F-1 CORE_BUG_CANDIDATE (documentación de `pendingRequest()`),
F-2…F-5 CONTRACT_GAP (dimensiones intrínsecas, duración de sesión en `Status`, portapapeles en hilo
principal, estado de enviados). Ninguno se resolvió con rodeos inseguros.

## Pendiente

- MANUAL_PENDING: teléfono físico (autenticación de hardware, captura real, rutas de audio, acústica).
- NEEDS_SECOND_PEER: envío y apertura entre dos teléfonos físicos.
- CI completa del HEAD publicado.

Etiqueta máxima aplicable hoy: integración de código verificada en las capas locales ejecutadas;
AVD/R8 y aceptación física pendientes.
