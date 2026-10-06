# UMBRA Device Linking v1

Estado: implementación de la segunda entrega; resultados y límites en
[validación](../validation/2026-09-20-device-linking-core.md).
[Autoridad y amenazas](../adr/ADR-device-model.md).

## Identificadores y migración

Identidad lógica = SHA-256 de la clave pública Signal de A1, administrador único.
Device ID = SHA-256 de la clave pública Signal de cada dispositivo. Son 64 dígitos
hexadecimales minúsculos; A1 conserva exactamente su ID anterior. Dirección Signal:
`(Device ID, 1)`. `registration` no cambia de significado. Buzón UUID y capacidades
256 bits son encaminamiento, no alias ni identidad humana.

`DeviceService.migrate()` es una migración explícita, transaccional y aditiva sobre
Records: crea `meta/device-affiliation` v1, `device-roster` y `device-index`. No
modifica identidad, prekeys, ratchets, contactos, mensajes o esquema cifrado Vault
v2. Un conjunto parcialmente perdido bloquea operaciones; no se reinicializa.
Los clientes antiguos continúan con tarjetas y contenido v1, pero no interpretan
la pertenencia nueva. No se considera su participación validación multidispositivo.

## Codificación y firmas

Transcripción ASCII, LF después de cada campo incluido el último, sin CR ni espacios
adicionales. URI/cadena/archivo/QR transportan los mismos bytes:

`umbra:device:<kind>:1:<base64url(body)>.<base64url(signature)>`

Base64url sin padding y con recodificación idéntica; firma Signal de 64 bytes.
Primer renglón `UMBRA-DEVICE-<kind>-1`; primer campo siempre clave del firmante
Signal serializada de 33 bytes, base64url canónica de 44 caracteres. Máximo 32.000
caracteres de URI y 23.000 bytes decodificados; número y orden exactos de campos.
No hay JSON con campos duplicados en esta capa. JSON de tarjetas se valida mediante
Wire/StrictJson y firmas existentes. Decimales positivos sin ceros iniciales,
máximo 12 dígitos; segundos UTC. Creación admite desfase futuro máximo 300 s.

| kind | Campos, después del encabezado |
|---|---|
| challenge | clave A1; clave A2; nonce 256 bits base64url; created; expires |
| response | clave A2; challenge completo; tarjeta A2 JSON UTF-8 base64url |
| approval | clave A1; nonce; SHA-256(response completo); roster completo; tarjeta A1 JSON UTF-8 base64url |
| roster | clave A1; versión monotónica; created; expires; miembros |

Desafío: 60–600 s; máximo 16 registros no expirados por emisor/receptor. A1 es la
única autoridad de consumo y conserva la respuesta exacta. Copiar una bóveda
administradora desconectada no proporciona consumo único global. Los prefijos de
PairingService para contactos nunca son válidos aquí.

Miembros: `clave:A` o `clave:R`, separados por coma, ordenados estrictamente por
Device ID; sin duplicados. A=ACTIVE, R=REVOKED. Máximo ocho activos y 32 entradas;
retención permanente de tombstones. A1 debe figurar; si A1 está revocado, todos
están revocados y la identidad es terminal. Lista válida siete días como máximo.
Una lista nueva no elimina miembros ni reactiva tombstones. Misma versión exige
exactamente la misma transcripción. El administrador puede renovar una lista
expirada; los demás no pueden usarla mientras tanto.

## Ceremonia y estados

1. A2 genera sus propias claves mediante Engine.initialize; muestra clave pública.
2. A1 migrado/desbloqueado emite desafío específico para esa clave.
3. A2 revisa `reviewChallenge`, confirma propietario y produce `respond`. Se exige
   una identidad fresca, sin contactos, sesiones, historial o afiliación previa.
4. A1 revisa `reviewResponse` y confirma `approve`. Consent es un objeto opaco ligado
   al servicio, propósito, transcripción y época de desbloqueo. No sirve después de
   bloquear/reabrir. Firma, participante, caducidad y estado se revalidan dentro de
   la transacción, también tras esperar el lock SQLite.
5. A1 guarda nueva lista y aprobación junto con consumo. Reintento idéntico devuelve
   la aprobación almacenada mientras siga vigente y el dispositivo esté activo.
6. A2 `complete` comprueba solicitud pendiente, root, clave y firma, importa la
   tarjeta y persiste afiliación/lista sin copiar ningún secreto de A1.

A1 sabe AUTHORIZED cuando guarda aprobación; no afirma que A2 la recibió. A2 sabe
COMPLETE al guardar la aprobación. Pérdida del archivo/ACK se resuelve repitiendo la
misma solicitud/aprobación antes de vencer. Después, hace falta ceremonia nueva si
no llegó a completarse; no se borra la autorización ya concedida silenciosamente.
La delegación cifrada recibida de A2 confirma que A2 completó el enlace connected;
no se confunde con la verificación humana de un contacto.

## Verificación y APIs

`apply` verifica firma, versión, retención y autoridad conocida; aplica reducciones
inmediatamente. Una adición queda sin aprobar. `reviewRoster`/`approveRoster`
requieren comparar **los 64 hex completos de SHA-256 de la transcripción**. Cambiar
la lista mientras se revisa o cambiar el interlocutor invalida el consentimiento.
A1 debe estar previamente verificado. Aprobar el conjunto autoriza las claves
incluidas conforme a esta política; el alias no interviene. Ninguna búsqueda pública.

Engine exige la política en sendText/sendFile, fanout, receive, verify,
proveNearby/verifyNearby y authorizeTransport. Las tarjetas antiguas no pueden
reverificar una clave revocada ni aprobar una adición pendiente. Un contacto
bloqueado o IDENTITY_CHANGED sigue bloqueado; aprobar la lista no lo desbloquea.

## Mensajería y propagación

`sendIdentityText/File` crea un UUID lógico y una entrega por dispositivo activo y
aprobado del destinatario. Si falta una tarjeta/sesión o falla almacenamiento,
se revierte todo el fanout local. Cada sobre v1 mantiene su UUID propio, ciphertext,
ACK, deduplicación, TTL y backoff. El contenido cifrado v2 añade `logicalId`,
`logicalFrom`, `logicalTo`; no aparecen en el relay. Cada receptor contrasta esas
identidades con su lista local. No se comparte ciphertext entre dispositivos.

Desconectar A2 no impide ACK de A1; el pendiente de A2 conserva sus bytes. Se mantiene
TTL 60 s–7 días, texto 16.000 bytes y adjuntos 256 KiB. No hay sincronización automática
del historial ni copia automática de mensajes salientes al resto de dispositivos
propios; el fanout es al conjunto destinatario indicado explícitamente.

Contenido interno v1 `device-roster` contiene `roster` (máximo 32.000 caracteres).
`sendDeviceRoster` permite propagar la lista propia mediante Engine/libsignal y el
transporte existente, incluido RFCOMM offline. Recepción aplica la lista dentro de
la transacción de descifrado; el mensaje no aparece como chat. Importación firmada
por archivo sigue disponible como API. Tras revocar el propio receptor o emisor no
se crea ACK cifrado que contradiga esa revocación. El flujo A1/A2/B1 HTTPS prueba la
propagación a B1; la nueva ceremonia completa por radios físicos queda pendiente.

## Relay: delegación de revocación sin lectura

A2 genera una capacidad de revocación independiente, la instala mediante
`PUT /v1/boxes/{box}/revocation` con bearer de lectura, body `{token}`. 201 nueva,
200 reintento exacto, 409 sustitución, 401 sin autorización, 422 formato inválido,
503 capacidad global agotada o fallo SQLite. Máximo 4096 delegaciones/tombstones.
No permite lectura, escritura ni creación de dispositivos. A2 debe completar este
paso y entregar la delegación cifrada a A1 para que A1 pueda revocar ese buzón.

`sendDeviceDelegation` manda contenido Signal v1 interno `device-grant` con `box`,
`token`, `proof`. Proof es firma Signal/base64 estándar de 64 bytes sobre:
`UMBRA-DEVICE-RELAY-GRANT-1\n<device>\n<root>\n<box>\n<token>\n`.
El Engine receptor exige dispositivo activo, root administrador, buzón coincidente
con tarjeta y prueba firmada. Incluso llamar directamente al método de recepción
sin prueba válida se rechaza. Se conserva solo dentro de Records cifrados; no en
QR, invitación, alias, log o contenido visible de chat.

A1 revoca localmente y encola el borrado del relay en la misma transacción.
`DELETE /v1/boxes/{box}/revocation`, bearer separado, borra buzón/colas y deja
tombstone permanente. 204 también en reintentos, 401 capacidad incorrecta. Ni la
capacidad read anterior ni un nuevo convite administrativo recrean ese UUID.
`relayRevoked` confirma la respuesta; un fallo deja pendiente y la sincronización
connected reintenta. El esquema SQLite migra 3→4 conservando mensajes/buzones.

**Límite explícito:** hasta recibir la delegación, A1 no puede invalidar remotamente
ese buzón. La revocación local de claves sí bloquea nuevas entregas conocidas, pero
no debe anunciarse revocación del relay confirmada. Un dispositivo hostil puede
crear otros buzones si consigue otra invitación administrativa; eso no autoriza
su clave revocada en ningún Engine actualizado. El relay ve metadatos de tráfico,
capacidades HTTPS y buzones; no las listas, tokens delegados dentro de Signal,
texto ni claves privadas. Suplantar listas requiere la clave administradora.

## Revocación, límites y fallos

Aplicar una revocación elimina sesiones y outbox del dispositivo; conserva historial
local de los demás. Revalidación inmediata antes de escritura HTTPS/RFCOMM. Datos ya
emitidos o recibidos no se retiran. Un dispositivo desconectado mantiene la vista
anterior hasta recibir actualización/caducidad; no hay consenso global instantáneo.
Borrar o corromper metadatos localmente falla cerrado; rollback privilegiado de toda
la bóveda no está resuelto. Pérdida/compromiso de A1 exige nueva identidad y nueva
verificación humana; RECOVERABLE permanece bloqueado.

Errores de formato/firma/contexto/versión/expiración/autorización son rechazos sin
mutación; errores de almacenamiento revierten transacción y no producen ACK.
Los adaptadores de tests no son almacenamiento de producción ni fallback Keystore.

Una aprobación perdida que ya venció no autoriza borrar registros: A1 revoca esa
alta incompleta y A2 necesita material nuevo para una ceremonia distinta. Una lista
ya expirada bloquea el transporte de aplicación; su renovación requiere importar
explícitamente una lista firmada vigente antes de continuar, sin saltar verificación.
