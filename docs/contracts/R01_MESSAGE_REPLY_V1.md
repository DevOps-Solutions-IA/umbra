# R01 Message Reply v1 — fundamento aislado, contrato propuesto

Fecha: 2026-10-06. Base de código inspeccionada:
`4fdd338f3fff4c6864ea820ca85cba6cf284b8da`.
Rama: `chatgpt/r01-message-reply`. Estado: **PARALLEL_DEVELOPMENT_UNMERGED**.

Este documento separa lo implementado de las obligaciones de integración. No
habilita `Feature.MESSAGE_REPLY`, no publica un protocolo compatible y no cierra R01.

## 1. Base existente comprobada

`Engine.sendText()` usa `send()`/`encrypt()`: libsignal, padding, outbox e historial
se coordinan dentro de la transacción de `Records`. `Engine.receive()` descifra,
valida `Wire.content()` y persiste recepción/ACK/deduplicación de forma atómica.
`Wire.content()` requiere campos exactos: un `reply` adicional en un texto antiguo,
o un `kind` desconocido, es rechazado. No se debe asumir compatibilidad por JSON.

El historial saliente se indexa por UUID; el entrante por `peer:UUID`. Por eso una
referencia no se define solo por UUID ni por alias. El fanout v2 además incorpora
`logicalId`, `logicalFrom` y `logicalTo`, y no es el mismo caso que un mensaje v1
entre dos dispositivos. Los controles de confianza/roster son propiedad del dominio.

## 2. Unidad R01-A implementada

`app.umbra.messaging.reply.ReplyReference` es un valor inmutable con:

```json
{"v":1,"id":"UUID-canónico","from":"identidad-del-autor","to":"identidad-destinataria"}
```

El ejemplo describe nombres de campos, no es una referencia válida para enviar.
El parser exige UUID canónico e identidades de 64 dígitos hexadecimales minúsculos,
participantes distintos, versión entera exacta y ningún campo adicional. La entrada
raw usa el parser `Wire.parse` existente con límite de 512 bytes, sin coerción de tipos
ni claves JSON duplicadas. `fromJson()` solo sirve para un objeto anidado que ya haya
pasado ese parser estricto. No reemplaza el límite del payload exterior.

La referencia contiene únicamente el identificador y los dos extremos: ningún texto,
miniatura, nombre de archivo, coordenada, binario, hash de contenido o copia del original.
`toString()` es redactado; la serialización wire es una operación explícita, no un log.
Es **metadato, no autorización, ni prueba independiente de autoría del original**.

`ReplyTargetPolicy` verifica metadatos de un original ya obtenido bajo autorización:
- Conversación exacta y dirección original, incluso si se repite un UUID en otro sentido.
- `peer` y `outgoing` persistidos coherentes; sin coerción de booleanos/fechas.
- Solo originales ordinarios de tipo `text`, `file` o futura `reply`.
- Sin controles `receipt`, `device-*`, `location`, `call` ni contenido `restricted`.
- Selección nueva: original presente y vivo; un original ausente/caducado se rechaza.
- Resolución visual posterior: ausente/caducado produce `UNAVAILABLE`, no una cita inventada.
- Original presente pero de otra referencia/contexto: rechazo, nunca búsqueda en otro chat.
- Sin escritura, red, descifrado de contenido protegido, ampliación del TTL ni lectura del texto.

Por ahora el policy rechaza mensajes v2/fanout. Debe definirse explícitamente su binding
lógico al integrar con Y02; no se permite convertirlos silenciosamente a v1.

## 3. Integración pendiente: no implementada por R01-A

1. API de envío/recepción real y contrato de compatibilidad del peer. No añadir campos
   opcionales a `kind=text` a espaldas del validador viejo. Un tipo nuevo como `reply`
   necesita validación/negociación explícita antes de habilitarlo; sigue siendo propuesta.
2. Referencia dentro del payload cifrado/autenticado por libsignal; nada nuevo en claro
   en el sobre de relay/Bluetooth. Ratchet, mensaje, referencia y outbox atómicos.
3. Revalidar sesión, confianza, admisión, peer, roster y original dentro de la transacción
   de envío. Un snapshot/ReplyReference no sustituye `authorization()` ni consentimiento.
4. UI de seleccionar/responder/cancelar/enviar y borrador ligado a conversación y epoch.
   Sin copiar citas a clipboard, Bundle, notificación, log ni almacenamiento fuera de Vault.
5. Preview obtenido de nuevo desde almacenamiento local vigente bajo autorización. Si el
   original ya no está, mostrar que no está disponible; no cargarlo del servidor ni resucitarlo.
   Un remitente puede alegar una referencia inexistente: no presentar su alegación como cita verificada.
6. Recepción fuera de orden, deduplicación, reintentos de ciphertext inmutable, expiración,
   eliminación, crash/rollback y cambios de identidad/dispositivo, probados con libsignal real.
7. Compatibilidad negativa con parser anterior, JVM de ambas variantes, builds/lint/R8,
   pruebas Android únicamente físicas con los APK exactos autorizados y gates de integración.

La referencia no amplía la vida del original. El texto nuevo de una respuesta tendrá
su propio TTL según el contrato aprobado; ningún preview automático debe conservar
contenido caducado. Esto no impide que un participante copie manualmente texto que vio.

## 4. Coordinación y límites

Codex conserva los amarillos y PR26. En R01-A no se modifican `Engine`, `Wire`,
`Records`, `Vault`, `MainActivity`, `ChatScreens`, `FeatureAvailability`, permisos,
workflows ni dependencias. La función permanece pendiente y no tiene entrada visible.

Pruebas del helper son de host/JVM, no de Android, radio ni cifrado end-to-end nuevo.
La integración a main requiere revisar la base amarilla actualizada, acordar los hunks
compartidos, revalidar el SHA combinado y conservar los gates. Ningún resultado de R01-A
se debe atribuir al HEAD de PR26 ni utilizar para cerrar GATE-00.
