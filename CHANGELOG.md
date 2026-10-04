# Sin publicar — convergencia de producto (rama `claude/product-convergence-premium`)

- Base `11539d4` (Pairing P0). Dominio, criptografía, protocolo, manifiestos y permisos sin cambios.
- ACCESS_READINESS_V1 integrado en MainActivity: `vault.access()` canónico, autenticación Android y
  selectores/ajustes/permisos como acciones externas, causas reales de bloqueo, «4 min máx.».
- PAIRING_PRODUCT_V1 integrado: Agregar contacto (escanear QR en connected, código, mostrar QR/código,
  archivo), progresión solo en primer plano, fallos tipados, contacto agregado sin verificar.
- Arquitectura chat-first, ajustes agrupados, sistema de tokens, cargadores y estados ocupados reales,
  sin botones muertos. Diseño: `docs/design/UMBRA_PRODUCT_DESIGN_V1.md`.
- Recibo: `docs/validation/2026-10-03-product-convergence-premium.md` (CI y prueba física pendientes).

# Sin publicar — integración final UI (rama `claude/final-ui-integration`)

- Presentación de Claude (`ca2a706`) integrada sobre la base técnica aceptada `e0024f0`
  (`UI_SECURITY_CONTENT_API_V1`); dominio, criptografía, protocolo y almacenamiento sin cambios.
- Admisión con las API reales (estado y expiraciones, autoridad, credenciales emitidas, cancelación,
  evidencia de pares, aprobación propia y renovación atómicas). Errores tipados sin leer mensajes.
- Bloqueo de emergencia real, adaptadores de privacidad y portapapeles privado.
- Contenido protegido F01–F06 (foto, nota, video, PDF; Una vez / Solo en UMBRA; caducidad).
- Pruebas: JVM de presentación, integración UI+dominio en instrumentación, flujo `ui-integration.yml`
  (debug y R8, ambos sabores en AVD). Recibo: `docs/validation/2026-09-29-claude-final-ui-integration.md`.

# Sin publicar — integración UI + seguridad (rama `claude/ui-security-integration`)

- Merge de `claude/android-ui-foundation` (c394ea7) sobre `codex/private-startup-no-network` (52cd1e5).
- Contraseña personal de la bóveda: creación, inscripción de bóveda anterior, desbloqueo, cambio,
  bloqueo y autobloqueo por sesión, con las API reales de `Vault`.
- Admisión privada por dispositivo: realm, solicitud, credencial, rechazo, revocación, renovación y
  herramientas de autoridad sobre `AdmissionService`; provisioning por archivos, sin red previa.
- Inicio privado: conexión y Nearby solo por acción explícita; sin reconexión automática.
- Interfaz limpia solo en español: botones y títulos cortos, explicaciones en hojas de ayuda ⓘ,
  mensajes en inglés del motor/transporte nunca se muestran tal cual. Sin cambios de lógica ni
  seguridad. Pruebas: `UiCopyTest` y `assertConcise` en `UiScreensRenderTest`.
- Huecos de API documentados en `docs/API_GAPS_UI_SECURITY.md`. Validación:
  `docs/validation/2026-09-27-ui-security-integration.md` (CI pendiente de publicación de la rama).

# Sin publicar — interfaz Android (rama `claude/android-ui-foundation`)

- Sistema de diseño propio (tokens semánticos, tipografía en sp, iconos, componentes) y
  navegación inferior Chats/Llamadas/Cerca/Ajustes; la edición offline no muestra llamadas.
- Pantallas de bloqueo, onboarding, chats 1:1, grupos (preparado, sin motor), contacto,
  verificación, dispositivos, ubicación, llamada, modulador, video, ajustes y errores.
- Estados de seguridad tomados del motor; funciones sin motor marcadas «UI preparada · Backend
  pendiente». Pruebas JVM de presentación e instrumentadas de renderizado con datos sintéticos.
- Validación: ver `docs/validation/2026-09-26-android-ui-foundation.md` (CI pendiente).

# Cambios — 0.2.0-dev

Fecha de la entrega: 18 de septiembre de 2026. Base: código UMBRA 0.1.0-dev recuperado del ZIP anterior.

## Código

- Bóveda v2: índices HMAC, contexto GCM autenticado, control de autorización por operación/commit, migración y cuotas; claves con respaldo de hardware requerido.
- Bloqueo de tareas por generación, comprobación de autorización durante lectura de archivos, autenticación de regreso de selectores y prevención de desbloqueo en segundo plano.
- Bluetooth v2 con vinculación expresa y prueba firmada de posesión de identidad; límites de conexiones, colas, plazos y tamaño antes de reservar memoria.
- Validación JSON/UTF-8/Base64 estricta y saneamiento de nombres de archivos sin romper pares Unicode.
- Orden persistente de la cola, reintentos con backoff y confirmación cifrada; falta de espacio no se trata como mensaje inválido a eliminar del relay.
- Relay con parser restrictivo, límites ASGI, cuotas, plazos, respuestas no cacheables y migración a secuencias monotónicas.
- Dos variantes Android y comprobaciones separadas de política fuente y manifiesto combinado.

## Validación

80 pruebas relay y 105 escenarios JVM aprobados; 12 comprobaciones de política fuente aprobadas. 30 métodos de integración libsignal escritos, no ejecutados. No hubo compilación Android, prueba de radio Bluetooth ni auditoría independiente.

## Compatibilidad

Ambos extremos Bluetooth necesitan 0.2. El sobre cifrado mantiene v1. Se añade migración Android de bóveda 1→2 pendiente de validación física y migración SQLite del relay cubierta por pruebas. La variante offline tiene datos e identidad independientes. No se añadieron grupos, llamadas, malla ni recepción en segundo plano.
