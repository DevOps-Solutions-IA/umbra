# Rendezvous de invitaciones de un uso (v1)

El relay arbitra una capacidad de consumo; no valida una identidad humana, una
firma libsignal ni una tarjeta de contacto. El cliente debe verificar firma,
contexto y vigencia antes de solicitar consumo, conservar el mismo request_hash
para reintentos y confirmar localmente la vinculación. Una respuesta HTTP no
reemplaza esa verificación. Esta API no proporciona directorio ni contenido.

- `POST /v1/boxes/{box}/pairing-invites`: Bearer de lectura del buzón propietario.
  JSON estricto `{id, consume_token, revoke_token, expires}`. Los tres tokens son
  valores independientes canónicos base64url de 32 bytes; expires es Unix UTC en
  segundos, entre ahora+60 y ahora+86400. Devuelve 201 `{registered:true}`;
  reintento idéntico devuelve 200, sin reactivar una invitación revocada. Diferente
  contenido o propietario con el mismo id: 409. TTL inválido: 400.
- `POST /v1/pairing-invites/{id}/claim`: Bearer consume_token y JSON estricto
  `{request_hash}` (SHA-256 de la solicitud canónica, 64 caracteres hex minúsculos).
  La primera solicitud gana mediante transacción SQLite inmediata. Devuelve
  200 `{claimed:true}` también en reintentos del mismo hash; otro hash recibe 409.
  El relay no recibe la solicitud cuyo hash se registra.
- `DELETE /v1/pairing-invites/{id}`: Bearer revoke_token; devuelve 204 y es
  idempotente hasta el vencimiento. Funciona después de claim. No revierte una
  vinculación que el cliente ya haya confirmado; impide consumos/reintentos
  posteriores. Consumir una invitación revocada, vencida, inexistente o con una
  capacidad incorrecta devuelve 403 sin detalles. El token de consumo no revoca.

Se conservan únicamente hashes SHA-256 del id y capacidades, un hash adicional
del request_hash, buzón propietario, vencimiento y estado de revocación. No se
almacenan claves de firma, tarjetas, alias ni plaintext. La vinculación al buzón
es un metadato visible para el operador. Los tokens de consumo/revocación van en
Authorization; el id viaja en la ruta y sigue sujeto a las políticas de logs del
proxy. El servidor existente desactiva access logs y no imprime estas entradas.

Cuotas: 32 entradas por buzón y 4096 globales, incluyendo consumidas/revocadas
hasta su vencimiento; exceso devuelve 429. La limpieza TTL elimina entradas
vencidas y el borrado del buzón aplica cascada. No hay GET/listado. Schema SQLite
3 añade la tabla/indexes en una migración transaccional desde 2, conservando las
restricciones previas, colas y buzones. El código anterior a schema3 debe rechazar
la base nueva, nunca degradarla. Errores SQLite mantienen rollback y el handler
opaco 503; no implican consumo confirmado.

Esta API no cambia capacidades ni semántica de la cola existente. El reuso de un
id después de su TTL no se impide eternamente: el cliente rechaza tarjetas
firmadas vencidas y genera un id aleatorio nuevo para cada invitación. Los
clientes deben reservar margen respecto al mínimo TTL por la latencia de red.
