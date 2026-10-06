# Recibo — Product convergence premium (Execution 04), 2026-10-03/04

Estado: **convergencia de producto completada en presentación y verificada en las capas locales
disponibles. CI de GitHub, APK y aceptación física: NO ejecutados** (el entorno de Claude no tiene
Gradle, SDK completo, emulador, permiso de escritura al repositorio ni API de GitHub). No reemplaza
recibos previos.

## Identidad y recuperación

| Campo | Valor |
|---|---|
| Base exacta | `11539d4af1762aee11e77a8119b7fb9532439343` (árbol `51fa5e7523cd0a7c644cd53298e8d3485ec7100d`, PR #22) |
| Rama | `claude/product-convergence-premium` (Draft apilado previsto contra `codex/pairing-p0-hardening`) |
| Espacio de trabajo | worktree aislado de la sesión de Claude (`/home/claude/pcp`), remoto `DevOps-Solutions-IA/umbra` |
| Commits | `f96993e` → `d568d6c` → `2b967e1` → `9bcfd1f` → `ba28ae7` → commit de cierre de este recibo |
| Preservación previa | bundle `--all` (sesión) y bundle de rama `e0024f0..ba28ae7` copiado al clon canónico del propietario, donde `ba28ae7` ya existe como rama local (checkout del propietario sin cambios) |
| Historia | commits normales; sin rebase, squash ni force-push; PR #22 intacto |

## Equipo

Líder (integración, contratos, MainActivity, presentación, pruebas, documentación) y subagentes:
inventarios (APIs de dominio; rutas/botones/ciclo de vida; CI y auditorías), implementación aislada del
escáner camera2 (revisado e integrado por el líder) y dos revisiones independientes (seguridad de
acceso/vinculación; regresiones de toda la rama). Ningún subagente cambió contratos ni dominio.

## Alcance

`git diff 11539d4 HEAD -- android/app/src/main/java/app/umbra ':!…/ui'` **vacío**. Manifiestos y
`permission_policy.py` sin cambios. Nuevos archivos de producción solo en capa UI (incluido
`ui/media/QrScanner` connected + stub offline) y `res/values/dimens.xml`, `integers.xml`.

## Resultado por área

| Área | Resultado |
|---|---|
| ACCESS_READINESS_V1 | `vault.access()` canónico; autenticación Android como acción externa; contraseña vía `AccessSession`; `background()`/`lock(causa)`; selectores/ajustes/permisos como acciones externas; causa de bloqueo; «4 min máx.»; «Bloqueo en m:ss» observado al renderizar (sin consultas periódicas) |
| PAIRING_PRODUCT_V1 | Solo `PairingProduct`; QR (PairingQrCodec), código de un uso (char[] borrado), escáner camera2 connected, progresión en primer plano con retroceso, cancelar/revocar, fallos tipados, archivo, «Contacto agregado · Verificación pendiente» |
| Primer uso | Protegido → Identidad → Listo (Configurar conexión / Agregar contacto / Ir a chats) |
| Acceso privado | Estados en lenguaje normal; detalles técnicos ocultos; Administración en avanzado |
| Llamadas | Estados reales existentes; una revisión por toque; falta de lista de dispositivos explicada |
| Ubicación | Permisos tras confirmación; «Midiendo…» hasta medición real |
| Visor protegido | Fase real con spinner; cierres existentes preservados |
| Cerca | Fases reales de `BluetoothLink.Stage`; «Conectado» solo con AUTHENTICATED |
| Arquitectura/diseño | Chats primero; tokens; cargadores; progreso por fases; sin botones muertos visibles |

## Auditoría de botones (segunda pasada)

Ver `docs/design/UMBRA_PRODUCT_DESIGN_V1.md` §13. Retirados de recorridos visibles: grupos, agregar/
revocar dispositivo, eliminar contacto, chips «Próximamente» de QR, interruptores de privacidad falsos
(ahora estados), sección Notificaciones, «Abrir» inalcanzables. Restan pantallas de grupo preparadas pero
inalcanzables (HIDDEN_PRODUCT_GAP), cubiertas por su prueba de render existente.

## Auditoría funcional

| Función | Visible | Ejecutable | Backend | Dominio | Físico | UI |
|---|---|---|---|---|---|---|
| Bóveda/contraseña/autobloqueo | Sí | Sí | n/a | Sí | NO EJECUTADO | Integrada (AccessSession) |
| QR / código de vinculación | connected | Sí | Relay requerido | Sí | NO EJECUTADO | Integrada; exige relay+acceso privado |
| Archivo de vinculación | Ambas | Sí | n/a | Sí | NO EJECUTADO | Integrada |
| Verificación | Sí | Sí | n/a | Sí | NO EJECUTADO | Existente |
| Texto / adjuntos / protegido | Sí | Sí | Relay o Cerca | Sí | NO EJECUTADO | Existente + guardas |
| Grupos | No | No | No | Sin E2E | — | HIDDEN_PRODUCT_GAP |
| Cerca | Sí | Sí | n/a | Sí | NO EJECUTADO | Fases reales |
| Dispositivos | Lectura | Parcial | — | Aprobación no conectada | — | PRODUCT_GAP |
| Ubicación | Sí | Sí | Relay | Sí | NO EJECUTADO | Estado honesto |
| Llamada / video / modulación | connected | Sí | TURN manual | Sí | NO EJECUTADO | Existente |
| Emergencia | Sí | Sí | n/a | Sí | NO EJECUTADO | Existente |

## Pruebas locales

PASS_EXECUTED (en este entorno):
- Tipos de UI (android-36 + stubs), connected y offline: 0 errores.
- Tipos de androidTest UI (`UiScreensRenderTest`, `LockedActivityTest`, `UiContentIntegrationTest`,
  `UiSecurityFlowTest`), ambos sabores: 0 errores.
- Tipos completos frente a la base: sin errores nuevos (solo deriva del verificador local).
- JVM de presentación `Ui*Test` con arnés JUnit local: 116/116.
- Scripts `unittest` relevantes (`test_ui_integration`, `test_android_execution`): OK.
- `repository_guard`, `check_source_policy` (13), `git diff --check`: PASS.

BLOCKED_ENVIRONMENT:
- 9 pruebas de scripts que requieren `tcpdump` y `nacl` (ausentes aquí; preexistente, no se tocaron).
- Gradle, lint, R8, APK, guardas de APK/JNI, instrumentación y AVD: sin Gradle/SDK completo/emulador.
- JVM de dominio con libsignal (`UiAdmissionFlowTest` y suites técnicas): sin libsignal 0.102.3 local.

NOT_EXECUTED: CI de GitHub (push bloqueado), pruebas físicas.

## Conteos y presupuesto

- Suite UI: **39 debug / 36 R8** (`UiScreensRenderTest` 28).
- Inventario completo: **124 offline / 127 connected**.
- Timeout de suite 330 → 345 s: **PROVISIONAL**. Primera medición real (CI del PR #23 sobre `7eeec43`):
  connected **318.188584192 s**, offline **307.961290426 s** con `SUITE_TIMEOUT_SECONDS = 345`. Sin cambio
  del timeout; la decisión 330 vs 345 se tomará con mediciones adicionales.

## Cierre de CI (PR #23)

Único fallo en `7eeec43`: Verify UMBRA / android, en `scripts/smoke_release_launch.py`, que aún exigía el
copy histórico «Bóveda bloqueada»; la pantalla de bloqueo actual (y `LockedActivityTest`) muestra «UMBRA
bloqueado». En ese run la instrumentación completa pasó (127 connected / 124 offline), JVM 470 connected /
409 offline y la política de APK debug/release de ambos sabores. Corrección solo de tooling: el gate usa
`LOCKED_STATUS = 'UMBRA bloqueado'`, y `scripts/tests/test_release_smoke_contract.py` lo ata a
`EntryScreens.lock`, `AccessPresentation` (LOCKED) y `LockedActivityTest`. UI, dominio y timeout sin cambios.

## Pendiente (no completado por inferencia)

- Push normal y PR Draft (403 en el entorno de Claude) — el propietario publica desde su entorno.
- CI completa del HEAD; APK connected/offline exactos (ruta, paquete, versión, SHA-256) del run verde.
- Aceptación física Xiaomi ↔ Motorola (QR, código, archivo, verificación, mensajes con ACK, reinicio,
  contraseña, cámara/permisos, segundo plano, bloqueo, Cerca). Sin autorización todavía para instalar.
- Relay de producción no configurado aquí: la UI termina en «Conexión privada no disponible».

CONTRACT_CHANGE_REQUIRED: **NO**. SECURITY_INVARIANTS_PRESERVED: **SÍ**. PHYSICAL_EXECUTED: **NO**.
PHYSICAL_READY: **NO** hasta CI verde.
