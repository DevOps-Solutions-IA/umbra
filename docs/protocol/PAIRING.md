# Vinculación autenticada y capacidades de un uso

Fecha: 2026-09-20. Estado: contrato contrastado por lectura con `PairingService` y `RelayClient` del
bloque en desarrollo. La CI del commit final sigue pendiente. No es una especificación
auditada de un protocolo criptográfico nuevo.

## Capas de confianza

La vinculación utiliza firmas reales de la identidad libsignal existente. No cifra
mensajes ni reemplaza el establecimiento de sesión de libsignal. Una invitación
compacta firmada enlaza identidad, dispositivo, capacidad, nonce, creación y vencimiento.
No incluye alias, buzón ni tarjeta/preclaves: el material Kyber no cabe en el QR compacto.
Las tarjetas firmadas se intercambian en request/ack y se atan a esas mismas identidades. La autenticación mutua prueba posesión; la comparación fuera de banda
sigue siendo necesaria para habilitar conversaciones con contactos nuevos.

La capacidad es aleatoria de 256 bits, privada y de un uso lógico. La expiración y
revocación se verifican localmente. Un registro o claim del relay es control adicional
de consumo, nunca autoridad para fijar claves, cambiar una identidad o marcarla
verificada. La variante offline conserva esa validación local sin adquirir permisos
de red.

## Codificación implementada

La transcripción es ASCII con campos en el orden fijo indicado, separados por LF
(`0x0a`) y con LF final. No se admiten CRLF, campos adicionales, líneas vacías,
relleno, variantes numéricas ni normalización silenciosa. Los tiempos son segundos
Unix decimales canónicos. La clave pública es la serialización libsignal en base64
estándar canónico. Cada token contiene 32 bytes aleatorios en base64url canónico sin
padding. El body completo y la firma se codifican en base64url canónico sin padding.

```text
umbra:invite:1:<base64url(body)>.<base64url(signature)>

UMBRA-PAIR-INVITE-1
<id token>
<inviter public key, base64>
<consume token>
<inviter nonce token>
<created>
<expires>
1
```

El último `1` del body es el identificador de dispositivo. No se crea otra clave de
identidad. El token de revocación independiente se guarda exclusivamente en el
estado privado del invitador. El QR codifica solo esta URI compacta; el parser limita
la URI a 1024 caracteres ASCII; no acepta una tarjeta Kyber como invitación compacta.

```text
umbra:request:1:<base64url(body)>.<base64url(signature)>

UMBRA-PAIR-REQUEST-1
<full invitation URI>
<joiner public key, base64>
<joiner nonce token>
<joiner signed card JSON, base64>
```

El solicitante firma el body con su identidad libsignal. Deben coincidir su clave
pública, el identificador calculado y la identidad de su tarjeta firmada. La URI de
invitación se conserva exactamente; no se reconstruye desde campos normalizados.

```text
umbra:ack:1:<base64url(body)>.<base64url(signature)>

UMBRA-PAIR-ACK-1
<invitation id token>
<SHA-256 of full request URI, lowercase hexadecimal>
<recipient identity id, lowercase hexadecimal>
<inviter public key, base64>
<inviter signed card JSON, base64>
```

El invitador firma este body. El digest se calcula sobre la URI request completa,
incluida su firma, en ASCII; enlaza ambos nonces y la invitación. La tarjeta del
invitador debe coincidir con su clave pública y con la identidad de la invitación.
El destinatario debe coincidir con el solicitante local. JSON de tarjeta se verifica
con el contrato existente de Engine, sin un segundo parser permisivo.

Límites implementados: creación de invitación entre 60 y 86400 segundos; hasta
32 registros emitidos y, por separado, 32 solicitudes pendientes/completadas, contando
consumidos hasta su expiración. Request/ack admiten como máximo 24000 caracteres ASCII
cada uno; el body decodificado tiene límite de 18000 bytes. El parser de invitaciones
exige expiración posterior a creación, duración máxima de 86400 segundos, validez
actual y creación como máximo 300 segundos en el futuro. El mínimo de 60 segundos
se aplica al emitir; recibir no exige 60 segundos de vida restante.

## Requisitos del intercambio local

1. El invitador persiste la invitación emitida antes de compartirla. Las firmas cubren
   una representación canónica versionada con un dominio fijo y acotado; se rechazan
   claves duplicadas, campos extra, coerciones, codificaciones ambiguas y versiones no
   admitidas. No se firma arbitrariamente contenido proporcionado por otra aplicación.
2. El solicitante valida la firma y el material de la invitación antes de crear una
   solicitud. La solicitud firmada enlaza la invitación exacta, ambos extremos, el rol
   y el material del solicitante; un desafío aleatorio impide trasladarla a otro
   intercambio. Debe persistirse para que el reintento no cambie su transcripción.
3. El invitador valida solicitud, firma, capacidad, vencimiento y estado local. Contacto
   pendiente, consumo y respuesta firmada se confirman en la misma transacción. La
   respuesta enlaza la solicitud exacta y las identidades. No puede confirmarse consumo
   si falla la persistencia de contacto o respuesta.
4. El solicitante valida la respuesta contra su solicitud guardada y la identidad de
   la invitación antes de confirmar el contacto. No acepta un ack aislado, cruzado o de
   otra identidad.
5. Un reintento idéntico puede devolver la respuesta ya guardada, sin crear otro contacto
   ni producir ciphertext nuevo. Un segundo solicitante o una transcripción distinta
   tras consumo se rechaza. Revocar o expirar impide usos nuevos; la política de reintento
   debe permanecer acotada por la validez original.

La lectura de código verifica esa correspondencia de campos, no ejecución ni aprobación
de seguridad. Ninguna firma correcta permite omitir validación de tarjeta, consumo local
o verificación humana. Los resultados ejecutados deben consultarse en evidencia del
commit final, separando JVM, HTTPS aislado y pruebas Android.

## Contrato auxiliar del relay

La implementación `relay/umbra_relay/pairing.py` separa tres valores independientes de
32 bytes, en base64url canónico sin padding: `id`, `consume_token`, `revoke_token`.
La credencial de revocación no debe formar parte de la invitación que recibe el tercero.

| Operación | Autorización y cuerpo | Resultado |
|---|---|---|
| `POST /v1/boxes/{box}/pairing-invites` | Bearer de lectura del buzón; `id`, `consume_token`, `revoke_token`, `expires` entero | 201 al registrar; 200 para registro idéntico vigente |
| `POST /v1/pairing-invites/{id}/claim` | Bearer `consume_token`; `request_hash` SHA-256 hexadecimal minúsculo de 64 caracteres | Claim idéntico idempotente; 409 para otro digest tras consumo |
| `DELETE /v1/pairing-invites/{id}` | Bearer `revoke_token` | Revocación lógica; 204 |

El servidor persiste hashes de identificador y capacidades, buzón propietario,
vencimiento, digest del claim y marca de revocación. El registro recibe los tokens
antes de transformarlos; HTTPS y ausencia de logging de credenciales siguen siendo
necesarios. No recibe tarjetas, claves de identidad ni contenido de mensajes mediante
estos endpoints. El `request_hash` enviado ya es un digest y el servidor vuelve a
hashearlo al guardarlo; no conoce la transcripción completa.

Límites del registro: 60 segundos a 24 horas restantes, 32 registros por buzón y 4096
globales. La actualización de claim se serializa en transacción SQLite: dos digests
diferentes no pueden ganar el mismo registro. Credencial inválida, vencida o revocada
rechaza con 403; estado incompatible con 409; cuota agotada con 429; esquema/tipos
inválidos se rechazan. Los tombstones no se eliminan al consumir: se conservan hasta
expiración para impedir recreación/reuso.

Estos endpoints no transportan por sí mismos request/ack del cliente. Su existencia
no demuestra un flujo extremo a extremo instalado ni sincronización automática de
revocación. Si falla el relay, no debe presentarse su claim como confirmado ni relajarse
la verificación local.

## Amenazas y controles a demostrar

La revisión debe cubrir robo/reenvío del bearer, replays, intercambio de identidades,
reflexión entre roles, alteración de tarjeta/tiempos, transcripciones no canónicas,
consumo simultáneo, disco lleno, rollback, bloqueo durante el commit, reinicio entre
commit y entrega del ack, revocación y limpieza. Las cuotas reducen consumo de recursos;
no prometen disponibilidad ante un adversario autorizado.

Una invitación copiada revela sus campos y puede ser reclamada antes que el destinatario
esperado. Una firma no impide ese robo ni identifica personas. Las transcripciones
firmadas son pruebas transferibles y requieren revisión independiente. No se debe
llamar Bluetooth físico a estas pruebas cuando solo ejecutan JVM o HTTP aislado.


## Persistencia y compatibilidad del cliente

`pairing-issued` conserva URI, capacidad privada de revocación, expiración y estado
`ISSUED`, `CONSUMED` o `REVOKED`. El consumo almacena digest request y ack inmutable.
Revocar borra el ack local e impide devolverlo por reintento. `pairing-pending` conserva
URI, request inmutable, identidad peer, expiración y estado `PENDING` o `COMPLETE`.
Completar otra vez antes de expiración vuelve a validar e importar la misma tarjeta;
no se afirma que sea una confirmación de transporte para el invitador.

El nuevo flujo llama `Engine.importCard`: una identidad ya conocida conserva sus
flags de verificación, bloqueo y cambio de identidad, mientras una identidad nueva
entra sin verificar. No se modifican mensajes existentes, sesiones ni ciphertext de
salida al repetir vinculación con la misma identidad. La importación heredada de
tarjetas sigue disponible: la capacidad de un uso protege este flujo de invitaciones,
no constituye por sí sola una prohibición global de importar tarjetas antiguas.

`Engine.identityChanged` suspende el contacto, elimina su outbox y sus sesiones,
conservando el historial de mensajes. `confirmIdentityChange` requiere ese estado,
una tarjeta de identidad distinta y el código de seguridad nuevo correcto; importa y
verifica la nueva identidad y bloquea la anterior en una transacción. Una excepción
revierte la sustitución. No reescribe el historial antiguo ni lo reasigna al nuevo peer.
El código nuevo es el acto explícito de verificación; no hereda confianza por alias.

La integración relay está expuesta mediante métodos de `RelayClient`. El servicio local
no realiza llamadas HTTP ni convierte automáticamente ISSUED/PENDING/COMPLETE en estado
remoto. Hay que coordinar transporte y presentación sin anunciar una revocación remota
si la petición falló. El cliente valida respuestas `registered`/`claimed` como booleano
verdadero y rechaza campos adicionales. Un claim puede negar servicio si un poseedor del
bearer lo consume con un digest ajeno; no permite suplantar firmas de identidad.

## Autoridad y compatibilidad con dispositivos

El emisor concreto de `pairing-issued` es la autoridad de consumo offline. La
respuesta debe volver a ese emisor; dos copias desconectadas de su bóveda no ofrecen
consumo único global. La reclamación del relay es rendezvous opcional; sus estados
no se confunden con el commit local ni con la recepción del ACK en el solicitante.
A1 CONSUMED prueba aceptación local, no que el solicitante haya completado. Reintento
idéntico conserva ACK hasta vencer; revocación local no debe anunciarse aplicada al
relay hasta recibir su respuesta. Las tarjetas permanentes `umbra-contact-v1` y
el enrolamiento Bluetooth anterior no son invitaciones de un uso. Ninguna de esas
rutas verifica humanamente por posesión; Engine mantiene VERIFIED_ONLY. La ceremonia
de [dispositivos](DEVICE_LINKING.md) usa prefijo/propósito distintos e incompatibles.
