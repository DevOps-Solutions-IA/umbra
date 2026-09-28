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
