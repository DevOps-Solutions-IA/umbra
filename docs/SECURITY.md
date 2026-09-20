# Seguridad y límites de UMBRA 0.2

**No utilizar para secretos reales hasta cerrar las pruebas y la auditoría pendientes.** Esta entrega no está certificada, no es invulnerable y no tiene comparación de superioridad frente a otros mensajeros.

## Activos y adversarios considerados

Se pretende proteger contenido, claves de protocolo y almacenamiento local frente a observación de red, alteración de mensajes, suplantación de claves/contactos no verificados, consultas a archivos privados sin su clave de protección y fallos accidentales de ciclo de vida. Se añaden límites frente a entradas malformadas y agotamiento de recursos. Revisar las medidas concretas en `HARDENING_0_2.md`.

No se asegura protección de plaintext frente a un sistema operativo, teclado, servicio de accesibilidad autorizado, destinatario o dispositivo comprometido; extracción de memoria en una sesión abierta; una cámara externa; rollback privilegiado del almacenamiento; análisis de tráfico; interferencia Bluetooth; vulnerabilidades desconocidas de Android, JNI, bibliotecas o este código. Una clave protegida en hardware no impide por sí sola el abuso del proceso autorizado para utilizarla.

## Política de fallo

Fallo de autenticación, firma, contexto, esquema, claves, almacenamiento o autorización no debe producir texto sin verificar ni reinicializar automáticamente la identidad. Las claves perdidas no se reemplazan para intentar leer una bóveda existente. Los errores locales de espacio o de acceso no se confunden con autorización para borrar un mensaje remoto. No hay claves maestras, acceso administrativo a plaintext ni recuperación implementada.

## Exposición que persiste

SQLite local muestra categorías y tamaños aunque los valores y nombres lógicos se protejan. El relay observa sobres, identificadores de encaminamiento y tiempos. Su proxy y alojamiento pueden observar IP y metadatos. La vinculación Bluetooth comparte una tarjeta privada con alias y datos de contacto; verificar humanamente el código antes de conversar. Los exportes están fuera de la bóveda. El borrado lógico, `secure_delete` y limpiar arrays no garantizan borrado físico o forense.

## Cadena de suministro y pruebas pendientes

Las dependencias conservan versiones explícitas, pero el grafo transitorio, hashes de artefactos, CVEs, SBOM, tags de imágenes/acciones, shrinker/JNI y firma de publicación requieren cierre. No basta que libsignal tenga documentación pública para considerar segura su integración. No inventar criptografía para evitar resolver esas dependencias.

`test_local.sh` ejecuta únicamente pruebas verificables en este entorno. `build_android.py` no tiene un camino que simule éxito si falta SDK. `check_merged_permissions.py` falla sin salidas reales. `RELEASE_CHECKLIST.md` define los criterios restantes, todos pendientes salvo la evidencia local expresamente registrada.

## Reporte y operación

No existe todavía un canal de vulnerabilidades de producción configurado. El propietario deberá establecer uno, con responsables de respuesta y actualizaciones. No enviar secretos, tokens de buzón ni claves privadas en reportes. Conservar un caso de reproducción mínimo con identidades sintéticas y comunicar el hash de la revisión probada.


## Identidad y emparejamiento v1 — bloque de 2026-09-20

La nueva invitación es un secreto bearer firmado, no un directorio ni una prueba
humana. El QR compacto contiene clave pública, capacidad y tiempos; quien obtenga
el archivo puede intentar consumirlo primero. Request/ack revelan tarjetas a los
participantes y generan transcripciones firmadas transferibles. No extender a
esas transcripciones las propiedades de deniability de mensajes libsignal.

El relay persiste hashes y un digest de solicitud, nunca tarjetas ni nuevas claves
privadas. Ve capacidades durante las solicitudes HTTPS y puede correlacionar
creación/consumo/IP/tiempos. Un relay hostil puede negar servicio; no puede marcar
contactos verificados ni hacer que se acepte una firma inválida. El consumo local
con contacto y confirmación comparte transacción; reintentar el mismo transcript
es idempotente, otro solicitante pierde. Revocar después de vincular no deshace
el contacto: bloquearlo es una operación distinta.

VERIFIED_ONLY se aplica a texto y archivos en Engine, incluidas llamadas directas.
Una sustitución requiere suspender la identidad anterior y verificar explícitamente
la nueva; el alias no transfiere confianza. Invitaciones de tarjetas históricas y
vinculación Bluetooth siguen disponibles por compatibilidad: no se reclasifican
como invitaciones de un uso. Los dos extremos necesitan verificación humana.

Ver [identidad](adr/ADR-identity-model.md), [metadatos](adr/ADR-metadata.md),
[recuperación](adr/ADR-recovery.md) y [protocolo](protocol/PAIRING.md).
Dispositivos, ubicación y llamadas aún no forman parte del bloque implementado.


## Dispositivos v1 — segunda entrega

[ADR de autoridad](adr/ADR-device-model.md) y [protocolo](protocol/DEVICE_LINKING.md).
La lista firmada no equivale a verificación humana: agregar una clave exige aprobar
el conjunto completo desde una raíz ya verificada. Engine rechaza claves revocadas
incluso por APIs de tarjetas/Bluetooth antiguas. A1 administra y cada dispositivo
tiene claves/ratchets propios; el relay no concede pertenencia. Retirar A1 es terminal.

La delegación de borrado de buzón se transmite cifrada y no concede lectura. Antes
de recibirla no se promete revocación remota. Un cambio local no retira bytes ya
emitidos ni copias recibidas. Clientes desconectados aplican cambios al aprenderlos;
listas caducadas requieren renovación/importación. No hay detección global de
forks/retención ni defensa contra rollback privilegiado de toda la bóveda.

Los harnesses usan identidades sintéticas. SQLite de androidTest se ejecuta en UID
de test y está ausente de release; no rebaja Keystore. Véase el [informe](validation/2026-09-20-device-linking-core.md)
para distinguir integración ejecutada de hardware y auditoría pendientes.
