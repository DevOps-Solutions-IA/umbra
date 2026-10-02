# PAIRING_PRODUCT_V1 — candidato, 2026-10-02

Contrato aditivo sobre `c117051ac5353c84ef840d9b5f1eafa81f86ddb4`, rama
`codex/pairing-p0-hardening`. Describe las APIs del árbol de trabajo revisado;
no acredita un APK físico, integración de Activity, deployment ni revisión
criptográfica independiente. No sustituye ACCESS_READINESS_V1 ni
UI_SECURITY_CONTENT_API_V1. [Evidencia y matriz](../validation/2026-10-02-pairing-p0-hardening.md).

## Entrada y autorización

Construir `new PairingProduct(records)` con el mismo Records autorizado de
Engine/Vault. Todas las operaciones son de worker; no ejecutar I/O ni consultas
SQLite en el hilo de presentación. El coordinador valida el lease antes y después
de cada transacción y alrededor de I/O online. Un snapshot describe estado y no
autoriza operaciones. Tras bloqueo/cancelación no reutilizar callbacks anteriores.

Las tres vías convergen en el protocolo firmado existente
`PairingService.createInvitation → request → accept → complete`. El invitador
crea contacto en `accept`; el receptor lo crea en `complete`. Ambos contactos
nuevos quedan `UNVERIFIED`. La verificación humana y la admisión privada siguen
siendo comprobaciones independientes necesarias para mensajería/transportes.
Una reserva o un ACK almacenado por el servidor no concede confianza.

## API pública implementada

| Método | Resultado y límite |
|---|---|
| `createPairing()` | `Step` con invitación firmada local; TTL de producto 600 s |
| `resumeFile(String id)` | Recupera el mismo payload pendiente tras autorización nueva; no restaura el lease del selector anterior |
| `importFile(String)` | Despacha únicamente invite/request/ack v1 completos; devuelve `Step`; máximo 24000 caracteres |
| `pairingStatus(String id)` | `PairingSnapshot` observado del estado local |
| `pendingPairings()` | Lista inmutable de snapshots de los registros issued/pending existentes; puede incluir terminales aún conservados |
| `cancelPairing(String id)` | Cancelación local persistente; revoca issued o cancela pending; no implica revocación remota |
| `createQrInvite(RelayClient)` | Crea invitación local y publica rendezvous; devuelve `Step` |
| `acceptQr(String, RelayClient)` | Valida invitación QR, crea request firmado y entrega ciphertext al courier |
| `createHumanCode(RelayClient)` | Devuelve `HumanCode`, publica invitación cifrada asociada al código |
| `acceptHumanCode(char[], RelayClient)` | Reclama el código, autentica/descifra invitación, crea y entrega request |
| `humanCode(String id)` | Recupera explícitamente HumanCode persistido en Vault cifrado; exige estado/lease vigente, nunca snapshot |
| `retryPairing(String id, RelayClient)` | Reintenta publicación o submit original con nueva autorización actual y ciphertext conservado |
| `advancePairing(String id, RelayClient)` | Un paso de intercambio acotado; no inicia polling en background |
| `revokeOnline(String id, RelayClient)` | Cancela primero localmente y después intenta revocar courier; un fallo remoto no deshace la cancelación local |

`Step.snapshot()` se refresca después de publicar/enviar correctamente;
`pairingStatus` permite observar cambios posteriores. `Step.delivery()` es `null`
después de enviar request online o completar un ACK. Si no es
null, `Delivery.payload()` es material exportable sensible. Su `toString()` solo
devuelve una etiqueta redactada; no registrar ni persistir payload en UI/logs.

`HumanCode.id()` identifica la operación. `display()` devuelve una copia `char[]`;
el consumidor debe limpiarla en finally. `close()` limpia el array interno. No
prometer borrado forense de Java/RAM. `acceptHumanCode` no toma ownership del
array del caller: corresponde al caller limpiarlo después de usarlo.

## Snapshot y errores

Campos exactos de `PairingSnapshot`:

| Campo | Significado |
|---|---|
| `id` | Identificador de operación, no capability; evitar telemetría correlacionable |
| `role` | `INVITER` o `JOINER` |
| `phase` | `INVITE_CREATED`, `REQUEST_CREATED`, `ACK_CREATED`, `COMPLETE`, `EXPIRED`, `REVOKED`, `CANCELLED` |
| `nextAction` | `SHARE_INVITATION`, `DELIVER_REQUEST`, `WAITING_FOR_PEER`, `DELIVER_ACK`, `VERIFY_IDENTITY`, `NONE` |
| `expiresInSeconds` | Restante de reloj de pared, nunca negativo; no es lease de autorización |
| `failure` | Código cerrado o null |
| `peerId` | Disponible en ACK_CREATED y COMPLETE; puede ser null para registros heredados del invitador |
| `verificationRequired` | Es falso cuando el peer conocido ya es VERIFIED; nunca concede confianza ni sustituye DevicePolicy/admisión |

No contiene código humano, invitación, request, ack, clave ni capability.
`ACK_CREATED` no prueba que el otro extremo completó. `COMPLETE` es finalización
local del protocolo y no verificación humana ni confirmación de mensaje recibido.

`PairingException.code()` ofrece `SELF_PAIRING`, `INVALID_FORMAT`,
`INVALID_SIGNATURE`, `EXPIRED`, `REVOKED`, `WRONG_INVITATION`, `WRONG_ROLE`,
`STATE_MISMATCH`, `ALREADY_CONSUMED`, `AUTHORITY_MISMATCH`, `ADMISSION_INVALID`,
`VAULT_LOCKED`, `CAPACITY_REACHED`, `CANCELLED`, `REPLAY`, `PAYLOAD_TOO_LARGE`,
`UNAVAILABLE`. No parsear texto de excepción. La existencia de un enum no
significa que cada fallo de una API heredada tenga una clasificación granular:
algunos fallos de almacenamiento/transporte se normalizan a UNAVAILABLE. Ningún
rechazo autoriza borrar estado, cambiar realm ni regenerar identidad.

## Realm preservado, sin descarte silencioso

Se mantiene el rechazo de `Engine.importCard` ante evidencia de membresía no
vacía que sea inválida o de otra autoridad. No se descarta esa evidencia para
“hacer funcionar” pairing. No hay admisión por instalar APK, escanear QR, conocer
un código, vincular Bluetooth o verificar un contacto.

| Configuración | Comportamiento observado en JVM |
|---|---|
| Ambos admitidos en el mismo realm | Intercambio completo; nuevos contactos UNVERIFIED |
| Ambos sin realm/admisión | Tarjetas de evidencia vacía permiten contacto; verificar no permite conversar sin admisión |
| Realms distintos | Rechazo antes de consumir/contactar en accept; se conserva pin original |
| Invitador admitido y joiner sin realm | Invitador puede crear contacto UNVERIFIED; joiner rechaza complete, queda PENDING |
| Invitador sin realm y joiner admitido | Rechazo en accept; sin contactos |
| Uno admitido y otro no admitido con el mismo realm fijado | Pairing puede completarse; el segundo continúa NOT_ADMITTED |

No presentar los casos mixtos como éxito bilateral. Una política futura de
contactos entre realms sería otra decisión de compatibilidad y revisión humana.

## QR y código humano

`PairingQrCodec.decode/encode` valida solo invitaciones firmadas; el safety QR de
verificación es otro protocolo y se rechaza. `render` produce BitMatrix y
`decodeLuminance(byte[], width, height)` procesa un plano de luminancia acotado:
1024 caracteres de payload y 4194304 píxeles como máximo, tamaño exacto del array.
No hay scanner de cámara ni permiso nuevo por estas APIs. Un roundtrip de píxeles
sintéticos no acredita enfoque, iluminación, lectura desde pantalla o dos móviles.

El código de producto se persiste en el Records cifrado para recuperación explícita
tras fallo de publicación mediante `humanCode(id)`; nunca se envía en claro al relay
ni se incluye en snapshot. Su representación String transitoria no permite prometer
borrado total de RAM.

El código lleva 16 símbolos de un alfabeto de 32 caracteres, mostrados en cuatro
grupos: 80 bits generados por SecureRandom. Solo se normalizan mayúsculas ASCII,
espacios y guiones. Los dominios HKDF-SHA256 separan locator, capability e
invitación; AES-256-GCM protege blobs con nonce fresco de 96 bits y tag de 128.
Request y ack usan dominios separados derivados del nonce independiente de la
invitación firmada, sin extraer claves del ratchet Signal. AAD liga versión,
dirección, id, digest de invitación y expiración. Ver
[ADR](../adr/ADR-pairing-product.md). Esto es una nueva composición de courier
pendiente de revisión; no reemplaza libsignal ni demuestra deniability.

## Relay, persistencia y límites

Online requiere Connected, RelayClient configurado con HTTPS, admisión vigente y
consentimiento explícito existente. El payload escaneado no elige endpoints.
Offline conserva parsing/render/file sin añadir INTERNET ni canal de laboratorio.
[Endpoints, schema y cuotas exactos](../../relay/PAIRING_RENDEZVOUS.md).

Límite de metadata preexistente: AdmissionCredential contiene la clave pública
Signal y se presenta al relay para autenticación. El courier cifra las tarjetas,
prekeys y transcripts; no oculta información pública ya revelada por admisión.
El servidor tampoco recibe las claves privadas. Interpretar la fase11 como
ocultación adicional de esa clave pública requiere una decisión de contrato de
admisión; no está implementada ni implícitamente aprobada en esta entrega.

El cliente persiste ciphertext de retry; el courier exige bytes inmutables. El
invitador valida firmas localmente antes de seleccionar candidato. Código robado
puede reservar el claim o saturar candidatos: es un límite de disponibilidad, no
identidad acreditada. Los snapshots no sustituyen la comparación fuera de banda.

La persistencia depende del Records utilizado: MemoryRecords no demuestra
SQLite, cierre de proceso, Keystore ni autenticación física. El arnés Android
`DevicePairingPersistenceTest` usa Vault/SQLite cifrado y fixture Keystore aislado;
su ejecución debe tener recibo propio. Reabrir Vault no equivale a process death.

La UI debe ofrecer acciones explícitas, mantener secretos fuera de logs/clipboard
implícito y presentar errores tipados, estado local y verificación pendiente.
La implementación de pantalla, cámara, flujo Nearby y recepción humana sigue
pendiente; este contrato no declara integración de Claude.
