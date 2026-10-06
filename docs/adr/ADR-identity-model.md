# ADR: identidad local, dispositivo y encaminamiento

Fecha: 2026-09-20. Estado: decisión aplicada en el código del bloque de identidad;
la lectura de implementación no sustituye pruebas ni CI del commit final.

## Decisión

UMBRA conserva la `IdentityKeyPair` de libsignal almacenada en `meta/identity`.
`identityId` es el SHA-256 hexadecimal minúsculo de su clave pública serializada por
libsignal. Las firmas de vinculación utilizan esa misma clave, con transcripciones
acotadas y separadas por dominio. No se añade una segunda raíz de identidad ni se
reimplementan PQXDH o Double Ratchet.

El dispositivo actual usa el identificador libsignal `1`. Ese valor es un número
local dentro del modelo de sesiones, no una identidad humana ni una afirmación de
compatibilidad multidispositivo. `registration` pertenece al contrato libsignal;
no es el identificador público de cuenta ni un dispositivo adicional.

El UUID del buzón y sus capacidades de lectura/escritura son encaminamiento y
acceso al relay. No sustituyen la identidad criptográfica. El alias es presentación:
puede repetirse y cambiar; nunca autoriza a heredar contactos o verificaciones.
El código comparado fuera de banda vincula las dos identidades públicas locales.

## Cambio de clave

Una clave pública distinta produce una identidad distinta. La coincidencia de
alias no es una prueba de continuidad. Una operación de sustitución debe nombrar
explícitamente el contacto anterior y el nuevo, mostrar ambas huellas y requerir
aprobación local. El nuevo contacto permanece sin verificar hasta repetir la
comparación fuera de banda. No se retargetean sobres pendientes al nuevo destinatario,
ni se reutilizan sesiones, claves fijadas o la marca de verificación anterior.

Si falta la identidad de una bóveda existente, se bloquea el acceso. No se interpreta
como instalación nueva. La reinstalación que genera otra identidad no recupera la
anterior; ver [ADR de recuperación](ADR-recovery.md).

## Consecuencias y límites

La identidad pseudónima estable facilita continuidad, pero también permite
correlación entre invitaciones y conversaciones que la revelen. Separar buzón e
identidad no elimina esa vinculación en los sobres actuales. Un handshake firmado
prueba posesión de una clave, no que el interlocutor sea la persona esperada.

La composición de vinculación es protocolo de aplicación y requiere revisión
independiente. La especificación [PQXDH de Signal](https://signal.org/docs/specifications/pqxdh/)
distingue autenticación de claves y verificación externa; la implementación base es
[IdentityKeyPair de libsignal 0.102.3](https://github.com/signalapp/libsignal/blob/v0.102.3/java/shared/java/org/signal/libsignal/protocol/IdentityKeyPair.java).
