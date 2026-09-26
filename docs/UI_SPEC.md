# Interfaz UMBRA — fundamentos (2026-09-26)

> Estado: código escrito y verificado por tipos/JVM; build Gradle, lint, instrumentación y
> capturas en emulador pendientes de la CI de la rama `claude/android-ui-foundation`.
> Evidencia: `docs/validation/2026-09-26-android-ui-foundation.md`.

## Principios

- La interfaz muestra estados reales del motor; nunca infiere verificación, modulación, cámara
  o llamada desde preferencias visuales. `FeatureAvailability` decide qué se presenta como
  funcional; lo demás dice «UI preparada · Backend pendiente» o no aparece (edición offline).
- Ningún estado se comunica solo por color: tono + icono + texto (y `stateDescription`).
- Sin jerga criptográfica en pantallas normales; «Detalles técnicos» es opcional.

## Sistema de diseño (`ui/design`)

| Token | Valor | Uso |
|---|---|---|
| backgroundPrimary | `#0B0C10` | fondo antracita |
| backgroundSecondary | `#111319` | barras, bloques de código |
| surface / surfaceElevated | `#171A21` / `#1F232C` | tarjetas, hojas |
| accentPrimary | `#9A8CFF` violeta | acciones principales, voz modulada |
| accentSecondary | `#5AB0FF` azul | grupos, modo offline/Bluetooth |
| textPrimary / textSecondary | `#F3F4F7` / `#AAB1BF` | texto |
| verified (success) | `#5FD39B` | + escudo con check |
| warning (no verificado / identidad cambió) | `#F4B860` | + escudo / triángulo |
| blocked (danger) | `#FF7B7B` | + icono de bloqueo |

Todos los pares texto/superficie superan 4.5:1 (probado en `UiDesignTokensTest`). Tipografía
en sp: Display 32, Title 22, Heading 17, Body 16, Caption 13, SecurityLabel 12 (mayúsculas),
Monospace 16 para códigos. Componentes: botones (principal, secundario, destructivo, texto),
controles de llamada, tarjetas, banners, chips, badges, avatares (círculo persona / cuadrado
redondeado grupo), indicador de confianza, estados vacío/error/carga, campo de contraseña,
control segmentado, filas con interruptor, barra superior, navegación inferior, burbujas y hojas
inferiores seguras (FLAG_SECURE).

## Navegación

Inicio con navegación inferior: **Chats** (filtro Todos/Personas/Grupos), **Llamadas** (solo
connected), **Cerca** (Bluetooth) y **Ajustes** (secciones). Desde un chat: contacto →
verificación; adjuntar archivo / foto (desactivada) / ubicación; llamada y videollamada.
Bloquear vacía la pila de navegación.

## Pantallas

Bloqueo, onboarding de 3 pasos, lista de chats, chat 1:1, chat grupal (vista previa),
nuevo mensaje, nuevo grupo, contacto, verificación (código, QR, comparación), dispositivos,
hoja de ubicación, llamada de voz, panel VOZ (modulador), videollamada con consentimiento por
dirección, llamada entrante, ajustes (Perfil, Privacidad, Seguridad, Dispositivos,
Notificaciones, Red, Almacenamiento, Acerca de) y estados de error.

---

## Histórico: especificación 0.2 (reemplazada)

> **Actualización 0.2:** se conserva esta especificación visual de base. El flujo actual de Cerca separa «Vincular un nuevo contacto» de reconectar contactos verificados; Ajustes distingue Connected/Offline. Toda salida al selector de documentos bloquea la app y exige autenticación de regreso. La UI no se ha ejecutado ni revisado en teléfonos. El código de `MainActivity.java` y el README actual prevalecen ante diferencias con el diseño inicial.

### Interfaz implementada en código (0.2)

UMBRA utiliza Views nativas de Android, no una maqueta web. Se programó una identidad oscura con fondo azul grisáceo, acento menta, contraste claro, icono propio en forma de U y textos de estado explícitos. No se generaron capturas ni se comprobó visualmente la app en un emulador o dispositivo.

| Pantalla | Contenido y acciones |
|---|---|
| Bloqueo | Marca, autenticación de Android y acceso a ajustes de seguridad si falta bloqueo del dispositivo |
| Alta | Alias, generación de identidad local e información sobre la pérdida de acceso sin recuperación |
| Chats | Búsqueda por contacto, lista, estado del transporte y acceso a conversación |
| Conversación | Burbujas entrantes/salientes, marcas de entrega, caja de texto, adjuntos y acceso al contacto |
| Cerca | Modo solo Bluetooth, escucha, selección de equipo emparejado, ajustes del sistema y desconexión |
| Identidad | Identificador y exportación/importación de tarjeta de contacto |
| Verificación | Código completo, QR del mismo código y campo de verificación; no incluye escáner de cámara |
| Ajustes | Servidor HTTPS e invitación, registro, sincronización, retención, eliminación de buzón y controles locales |
| Contacto | Estado verificado/bloqueado, verificación, bloqueo y limpieza de conversación |

La caja de texto evita un envio inadvertido cuando el contacto no está verificado mediante validación en el motor, no solo deshabilitando un botón. La confirmación de transporte se diferencia de entrega al destinatario. No se muestran publicidad, directorio público, número de teléfono, estado en línea, doble marca de lectura ni botones de funciones inexistentes.

## Verificación visual pendiente

Comprobar tamaños de pantalla, texto ampliado, contraste medido, TalkBack, foco, teclado, orientación, edge-to-edge/insets con target SDK 36, selección y exportación de archivos, navegación hacia atrás, autenticación y mensajes de error. La existencia del código de interfaz no demuestra que no haya solapamientos ni fallos de uso. No añadir capturas de una simulación y presentarlas como ejecución de Android.
