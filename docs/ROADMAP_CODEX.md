# Backlog de finalización verificable

Actualización integral 2026-09-19 UTC: [nuevo informe](validation/2026-09-19-integral-stabilization.md)
con evidencia local ampliada P0-01/P0-02, instrumentación limitada, Bluetooth emulado,
cliente real/relay, contenedor y R8. Esto no cierra en bloque P0-03–P0-12: quedan
matrices sin ejecutar, hardware y revisión/CI remota pendientes. No hay auto-merge.

Actualización histórica 2026-09-19 UTC: P0-01 verificado localmente en la rama
`codex/android-build-validation`; [evidencia](validation/2026-09-19-p0-01.md).
PR y CI deben revisarse antes de fusionar. Se verificaron permisos y JNI de los APK
debug, pero P0-02 completo (incluido release) y los demás paquetes siguen abiertos.

Todos los paquetes siguientes están ABIERTOS al transferir el proyecto. Son tareas propuestas
con criterios de aceptación, no funcionalidades ya entregadas. Terminar una fase no autoriza
marcar las posteriores como terminadas. Mantener tickets/PRs separados y enlazar evidencia.

| ID | Trabajo | Criterio de aceptación |
|---|---|---|
| P0-01 | Build real Android | Dependencias resueltas, API/Java/Gradle compatibles, 30 métodos JUnit/libsignal ejecutados sin mocks sustitutivos, APKs debug y lint de ambas variantes; prueba de que JNI sigue empaquetado correctamente. |
| P0-02 | Invariantes de APK | Inspección con herramientas Android de permisos, exported components, backups y configuración TLS; offline sin permisos de red; ninguna instrumentación/laboratorio en release. |
| P0-03 | Almacenamiento y bloqueo | Instrumentación de SQLite/Keystore, migración 1→2, claves perdidas, rollback, corrupción, transacciones y bloqueo durante cada operación; no pérdida o reinicialización silenciosa. |
| P0-04 | Laboratorio aislado | Dobles inyectables solo en tests/variante lab no distribuible, identidades diferentes, UID/applicationId/data separados; build release con rechazo verificable a configuración lab. |
| P0-05 | Transporte adversarial | Mensajes truncados/repetidos/reordenados, desconexiones, backpressure, límites de tiempo y tamaño, contacto incorrecto, replay de desafíos y cambios de identidad; libsignal real por encima del transporte de prueba. |
| P0-06 | Bluetooth emulado | Dos AVD compatibles con netsim: descubrir/emparejar, conexión RFCOMM real del stack emulado, retos, chat bidireccional, adjuntos, reconexión; captura de metadatos de prueba y clasificación como emulación. |
| P0-07 | Backend integrado | Cliente real contra relay de prueba: invitaciones de un uso, capacidades, revocación, deduplicación, cursor, TTL, errores de disco y reinicio; contratos/documentación API y pruebas de rechazo. |
| P0-08 | Operación del relay | TLS real en staging, política explícita de proxies confiables, rate limit por capacidad/borde, cuotas, recuperación y retención, monitoreo sin contenido ni identificadores innecesarios; sin escalar SQLite a múltiples workers por accidente. |
| P0-09 | Cadena de suministro | Revisar CVEs/licencias, fijar transitivas y hashes, Gradle verification metadata/wrapper verificado, SBOM, imágenes por digest y política de actualización. No declarar auditoría criptográfica a partir de un escáner. |
| P0-10 | UX completa del alcance base | Identidad, invitación, bloqueo, contactos/QR y verificación, chats, adjuntos, expiración, errores, conexión cercana y ajustes; accesibilidad, permiso denegado, rotación y proceso destruido; nada de mensajes de ejemplo disfrazados de datos reales. |
| P0-11 | Hardware y revisión independiente | Keystore y ciclo de vida en modelos compatibles; dos radios cercanas para las afirmaciones físicas Bluetooth; revisión independiente de protocolo de aplicación y hallazgos corregidos. Si falta recurso, mantener bloqueo explícito. |
| P0-12 | Release y operación | Firma fuera de Git/agentes, distribución y actualización verificables, reproducibilidad acordada, pruebas de release/R8/JNI, canal de vulnerabilidades, respuesta/rollback y criterios RELEASE_CHECKLIST cerrados con evidencia. |

## Recursos
Empezar con los recursos locales y CI del repositorio. No contratar, elevar presupuestos ni
activar nubes/device farms de pago por esta instrucción general. Antes de seleccionar un proveedor,
verificar disponibilidad, límites, confidencialidad y apoyo del caso Bluetooth/Keystore exacto.
No es necesario integrar SDKs de analítica en UMBRA para usar un laboratorio de pruebas.

## Funciones ampliadas, no implementar mediante atajos
Grupos, llamadas, video, iPhone, escritorio, múltiples dispositivos, recepción bloqueada,
Wi-Fi Direct, malla y recuperación de identidad no existen en la base. Crear ADR y pruebas
específicas antes de añadirlas; requieren decisiones de protocolo y alcance. No incluirlos como
terminados por tener pantallas, ni reducir silenciosamente la seguridad para igualar WhatsApp.

## Evidencia mínima por tarea
Commit probado, fecha, sistema/runtimes, herramientas/imágenes exactas, comandos y códigos de
salida, contadores reales, artefactos sin secretos, resultado esperado/observado, fallos y
limitaciones. Separar resultados unitarios, integración criptográfica, instrumentación,
emulación, hardware, carga, revisión estática y auditoría humana.
