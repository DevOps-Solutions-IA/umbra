# ADR: dispositivos independientes y autoridad local

Fecha: 2026-09-20. Estado: decisión para la segunda entrega; la evidencia distingue
implementación, pruebas y partes pendientes.

## Autoridad y amenazas

La identidad lógica inicial es el hash de la clave pública Signal existente de A1.
A1 es el único administrador. Cada dispositivo conserva su propia IdentityKeyPair,
registration, prekeys, sesiones y buzón. Device ID es el hash de su clave pública;
la dirección Signal sigue siendo `(Device ID, 1)`, preservando las sesiones v1.
No se copia material privado ni historial a A2. Alias y capacidades de buzón no
son identidades. El relay no decide pertenencia ni verificación humana.

A1 firma una lista completa versionada, con claves públicas y estados ACTIVE o
REVOKED. Los revocados permanecen como tombstones. Cambiar clave requiere un
Device ID nuevo y ceremonia nueva. Máximo ocho dispositivos activos y 32 entradas
históricas por identidad; alcanzar el límite exige una decisión posterior, no
eliminar tombstones. Caducidad de lista: siete días; renovación solo por A1.

Perder A1 impide autorizar, revocar o renovar: no hay recuperación central ni
transferencia automática de autoridad. Comprometer A1 compromete la delegación.
Revocar A1 significa retirar terminalmente toda la identidad; no se elige otro
administrador a partir de información del relay. Los participantes que conocen
el retiro no aceptan versiones posteriores. Un dispositivo aislado no conoce
instantáneamente una revocación; retención de listas puede negar servicio o
mantener una vista antigua hasta caducidad. No hay transparencia global ni
protección contra rollback privilegiado de toda la bóveda.

## Ceremonia y consentimiento

A1 desbloqueado emite desafío temporal firmado para la clave pública concreta de
A2. A2 responde con firma propia, desafío completo y tarjeta Signal firmada. A1
revisa la transcripción completa y aprueba explícitamente con una autorización
ligada a la época de desbloqueo; bloquear/reabrir invalida esa autorización.
La confirmación firmada vincula respuesta y lista. Se persisten consumo y nueva
lista en la misma transacción; un reintento idéntico devuelve la misma respuesta.
Invitaciones de contactos y dispositivos tienen dominios incompatibles.

La migración añade registros versionados a la bóveda existente sin tocar claves,
ratchets o mensajes. A2 solo puede adoptar otra identidad lógica si no tiene
contactos ni historial y no está ya afiliado. La pérdida parcial de registros de
dispositivos debe bloquearse, nunca convertirse en instalación nueva.

## Confianza y entrega

Una lista firmada es auténtica, no verificada humanamente. El contacto debe
comparar y aprobar la huella completa del conjunto; una nueva clave no hereda
confianza por alias o por anuncio del relay. El Engine aplica la pertenencia a
sus APIs de texto, archivos, recepción y autorización de transporte, incluidas
rutas antiguas. Revocar reduce permisos inmediatamente al aplicar la lista:
se eliminan sesiones y pendientes del revocado, se conserva historial. Bytes ya
emitidos y copias recibidas no se pueden retirar.

La entrega multidispositivo utiliza un identificador lógico cifrado y sobres
independientes por Device ID. Cifrado, estado Signal y outbox comparten transacción.
Cada entrega tiene ACK, deduplicación y reintento inmutable propios. Cambios de
lista y creación de entregas se serializan en Records. No se retransfiere historial.
La propagación offline usa el transporte cercano existente o importación explícita
de la transcripción firmada; hasta recibirla se aplica la última lista vigente.

## Relación con Sesame

Se estudió [Sesame, revisión 2](https://signal.org/docs/specifications/sesame/).
Su modelo admite claves distintas por dispositivo y sesiones por destino; no
proporciona esta ceremonia ni una autoridad autenticada de listas. UMBRA conserva
su política más estricta de verificación y ciphertext inmutable, sin adoptar
actualizaciones de claves dictadas por el servidor. libsignal 0.102.3 proporciona
SessionBuilder, SessionCipher y almacenamiento de sesiones; la orquestación y
las transacciones siguen siendo responsabilidad de UMBRA. Esto no declara una
implementación completa ni interoperabilidad con Sesame/Signal Messenger.

## Fuera de esta decisión

La revocación de capacidades del relay requiere un canal separado de delegación
sin conceder lectura del buzón; no se presume implementada por revocar una lista.
Recuperación exportable, sincronización de historial, ubicación y llamadas siguen
fuera de esta entrega. Hardware Keystore, radio física y auditoría independiente
no se sustituyen por pruebas JVM o adaptadores SQLite de laboratorio.

## Delegación de buzón aplicada en esta entrega

A2 instala un secreto independiente de solo borrado con su capacidad de lectura y
lo entrega a A1 mediante mensaje Signal autenticado, con prueba de firma ligada a
root/dispositivo/buzón. Nunca concede lectura ni firma de identidad al relay. La
revocación local y el pendiente de borrado comparten transacción; la respuesta HTTP
confirma aplicación remota. Antes de recibir esa delegación la revocación remota
está pendiente, no garantizada. Tombstones de buzones son permanentes y acotados;
se rechaza capacidad nueva al alcanzar el límite. Ver el protocolo para códigos,
migración y datos visibles. La sección anterior anticipaba este requisito; esta
sección registra la decisión concreta, no una conclusión de pruebas.
