> **Actualización 0.2:** se conserva esta especificación visual de base. El flujo actual de Cerca separa «Vincular un nuevo contacto» de reconectar contactos verificados; Ajustes distingue Connected/Offline. Toda salida al selector de documentos bloquea la app y exige autenticación de regreso. La UI no se ha ejecutado ni revisado en teléfonos. El código de `MainActivity.java` y el README actual prevalecen ante diferencias con el diseño inicial.

# Interfaz implementada en código

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
