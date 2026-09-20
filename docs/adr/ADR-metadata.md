# ADR: metadatos de identidad y vinculación

Fecha: 2026-09-20. Estado: límites explícitos del diseño.

## Decisión

Las invitaciones y pruebas de posesión se tratan como datos privados de contacto.
La firma autentica campos y contexto; no cifra el documento. Quien reciba una
invitación compacta puede ver identidad pública, dispositivo, nonces, tiempos y
capacidad de vinculación. Request/ack también revelan sus tarjetas firmadas con alias,
preclaves y encaminamiento; no se debe confundir el QR compacto con una tarjeta completa. Una capacidad de 256 bits impide
adivinarla de forma práctica bajo el supuesto de aleatoriedad segura; no impide su
copia, captura de pantalla, reenvío o robo desde un equipo comprometido.

El relay solo debe conservar el digest opaco necesario para registrar/reclamar una
capacidad de vinculación, su vencimiento y estado, además de los metadatos de
buzón/autorización necesarios. El digest es un identificador correlacionable, no una
identidad humana. Registrar o reclamar en el relay no verifica personas ni sustituye
las firmas y el consumo transaccional local. La API de registro recibe tokens por HTTPS y los reduce a hashes para persistencia;
no equivale a que el proceso servidor nunca pueda observarlos. Las firmas públicas
de vinculación son transferibles: no se extiende la denegabilidad del intercambio
libsignal a esas transcripciones. No se registran capacidades, tarjetas,
claves, contenido de mensajes ni transcripciones completas en diagnósticos.

## Exposición que permanece

El relay y su infraestructura pueden observar IP, tiempos, tamaños, buzones y estados
de operación. Los sobres de mensajes actuales incluyen identidades pseudónimas de
remitente y destinatario. Un administrador con acceso a la base del servidor puede
observar esa información aunque no pueda descifrar mensajes. El sistema Android y los
proveedores externos de archivos pueden observar datos que se les entregan.

SQLite local revela categorías, cantidades y tamaños aunque sus valores se protejan.
El borrado lógico y el vencimiento no garantizan eliminación forense. Los historiales
de deduplicación y los estados consumidos deben conservarse durante su ventana de
validez para evitar reuso, con límites de recursos y limpieza explícitos.

## Criterio de comunicación

No se promete anonimato, «cero metadatos» ni secreto absoluto. Una prueba JVM, una
transcripción simulada o un claim HTTP no demuestran Bluetooth físico, protección
hardware, confidencialidad del sistema anfitrión ni revisión criptográfica independiente.
