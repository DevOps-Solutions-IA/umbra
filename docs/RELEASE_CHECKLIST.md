# Criterios de aceptación para una publicación

**Ningún punto pendiente se presume aprobado. No utilizar secretos reales para estas pruebas.**

## Compilación y procedencia

- [ ] Resolver todos los artefactos en una instalación limpia con JDK 21/SDK 36; corregir errores reales sin sustituir libsignal por mocks.
- [ ] Ejecutar los 30 métodos de prueba de integración con libsignal real y revisar cobertura, fallos y excepciones nativas.
- [ ] Ejecutar lint Android; compilar debug y release con R8; validar que JNI y callbacks sobreviven al shrinker.
- [ ] Confirmar disponibilidad y compatibilidad de cada versión; revisar vulnerabilidades actuales y licencias transitivas.
- [ ] Fijar verificaciones de dependencias y digests/commits de imágenes/acciones; generar SBOM e inventario de licencias completo.
- [ ] Configurar firma release con una clave bajo control del propietario, guardada fuera del código y con recuperación administrativa documentada.

- [ ] Verificar los manifiestos y APK de Connected/Offline; Offline no debe declarar INTERNET ni ACCESS_NETWORK_STATE.
- [ ] Validar migración de bóveda 1→2 con claves Keystore reales, fallo a mitad, disco lleno y bloqueo durante la migración.
- [ ] Revisar el saludo Bluetooth v2: firmas, roles, vinculación explícita, claves no verificadas, replay y análisis de privacidad.

## Funcionalidad entre terminales

- [ ] Dos Android de fabricantes distintos y versiones compatibles; permisos concedidos/denegados/revocados, Bluetooth apagado, emparejamiento cancelado.
- [ ] Alta offline; tarjetas independientes por destinatario; importación expirada o alterada; identidad nueva con alias antiguo.
- [ ] Confirmar que NO se permite conversar sin comparar y verificar el código en los dos extremos.
- [ ] Bluetooth sin Wi-Fi/datos: envío simultáneo, 1.000 mensajes de prueba, adjuntos al límite, separación, reconexión y enlace equivocado.
- [ ] Probar cambio Bluetooth/internet y duplicado por ambos transportes: una sola visualización, un ciphertext por envío, estado de entrega correcto.
- [ ] Probar mensajes fuera de orden, pérdida del primer mensaje de sesión, cierre entre cifrar y persistir y retraso de confirmaciones.
- [ ] Probar red hostil: interrupciones, respuesta muy lenta, TLS inválido, redirección, respuestas sobredimensionadas, servidor malicioso.
- [ ] Bloqueo a cuatro minutos, pantalla apagada, cambio de app, rotación, selector de archivo, ajustes del sistema y vuelta a la app.
- [ ] Bóveda con poco almacenamiento, datos corruptos, biometría cambiada, credencial cambiada, clave invalidada y reinstalación.
- [ ] Adjuntos vacíos, nombres extraños, archivos corruptos y exportación; confirmar advertencias y ausencia de apertura automática.
- [ ] Caducidad con app cerrada y reapertura; diferencias de reloj; ausencia de reaparecimiento por reintentos después de limpiar una conversación.
- [ ] Pruebas visuales y de accesibilidad: teclado abierto, tamaño de letra grande, TalkBack, pantallas estrechas y navegación del sistema.

## Seguridad y operación

- [ ] Auditoría independiente de integración criptográfica y almacenamiento; no basta con que libsignal sea un proyecto conocido.
- [ ] Evaluación móvil con OWASP MASVS/MASTG, fuzzing de entradas y pruebas de memoria/logs/backups/transferencia.
- [ ] Modelo de amenazas aprobado por el propietario: reconocer explícitamente límites de terminales, metadatos y destinatarios.
- [ ] Despliegue real TLS/Docker, revisión de privilegios, carga, cuotas, expiración, registros y respuesta a incidentes.
- [ ] Corregir el límite global detrás de Caddy antes de un despliegue de muchos usuarios; probar límites por credencial/borde.
- [ ] Canal de reporte privado, responsables de mantenimiento y plan de actualizaciones autenticadas.
- [ ] Revisar marca, política de privacidad, avisos/licencias, jurisdicción de operación y requisitos de distribución sin asumir un país.

## Alcance comercial

- [ ] Definir si la primera publicación de producto acepta exclusivamente chat individual y app en primer plano. Si exige llamadas, grupos, iOS, malla o recepción permanente, diseñarlos, implementarlos y auditarlos: **no están en esta entrega**.
- [ ] Emitir un informe de aceptación con evidencia de cada prueba. Publicar únicamente la versión exacta auditada, con hash y firma verificables.

Estado de 0.2.0-dev a 18 de septiembre de 2026: **lista pendiente; no existe aprobación para producción**.
