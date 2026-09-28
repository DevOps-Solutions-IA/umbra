# ADR — bloqueo de emergencia coordinado (v1)

Estado: implementación en curso, no aceptación. Base exacta PR #14
52cd1e531b9de41ecd398ba53309b630ce1074f5, remota coincidente el 2026-09-28 UTC.

## Auditoría y decisión

AccessGate controla épocas y el punto de commit SQLite. Vault protege DEK y
password, invalida leases y no borra datos al bloquear. ConnectivityService
cancela generaciones online/Nearby y recursos registrados. CallService invalida
MediaLease; NativeVoiceSession solicita cierre nativo asíncrono, pero su bandera
`disposed` indica inicio, no fin. LocationService cancela grants; el proveedor
Android necesita removeUpdates. DocumentIO verifica leases entre fragmentos,
pero una apertura/lectura de proveedor puede permanecer pendiente. BluetoothLink
cierra sockets/executors y RelayClient desconecta HTTP; actualmente varios errores
de cierre se descartan. No hay escaneo/publicidad propio automático.

Se añade coordinación al AccessGate existente, no otra identidad/autorización.
Solicitud local sin contraseña: barrera atómica en el gate antes de esperar
SQLite, invalida cualquier lease y prohíbe unlock heredado. Notificación visual
inmediata solo significa contenido ocultable/autorización denegada, no cierre.
Trabajadores independientes solicitan cada cierre; uno bloqueado no impide los
otros. Recursos confirman el fin real de sus callbacks/operaciones, no únicamente
haber recibido cancelación. Vault espera transacciones antes de cerrar SQLite;
ningún cierre multimedia se espera manteniendo un lock SQLite.

## Orden y contratos

1. Solicitud y barrera de autorización (monotónico), antes de locks lentos.
2. Invalidación de épocas/calls/location/connectivity y solicitud de cierres.
3. Cierres independientes de HTTP, Nearby, media, proveedor y documentos.
4. Bóveda: descartar material y cerrar SQLite tras rollback/commit ya iniciado.
5. CLOSED únicamente con todas las confirmaciones. Error/timeout: INCOMPLETE,
   nuevas operaciones denegadas; no recuperación ni reconexión automática.

El commit que ya cruzó AccessGate.commit puede terminar; no se promete deshacerlo.
Los demás deben fallar o hacer rollback. No se borra outbox/identidad/ratchets.
No se obtiene red para enviar END/STOP ni se esperan ACK. Bytes ya entregados al
SO/proveedor o al destinatario no pueden retirarse.

Presupuesto nuevo de observación del coordinador: 5 segundos por cierre,
concurrencia acotada y estados persistentes en memoria. No sustituye límites
multimedia previos (invalidación/cierre de captura) ni permite declarar éxito
por timeout. Cierre incompleto conserva bloqueo hasta reinicio seguro; no se
solapan sesiones. Pruebas recogerán tiempos reales y ventanas positivas.

Tras cierre confirmado se exige iniciar explícitamente una nueva autenticación,
con ticket ligado a esta generación; el viejo callback unlock no puede liberarla.
Después se mantiene la contraseña/Keystore existentes y UNLOCKED_OFFLINE.
Nearby y online requieren nuevamente sus consentimientos. No reanudación de media.

La UI de Claude no se modifica. El botón y la aplicación combinada quedan
pendientes de conectar/probar con el contrato documentado en EMERGENCY_LOCK.md.
Límites: best-effort de memoria; no borrado forense, revocación administrativa,
ocultación OS ni protección ante OS comprometido. Sin afirmaciones físicas/TEE.

Una segunda Activity no puede evadir el cierre creando otro AccessGate: Vault
mantiene propiedad por ruta privada en el proceso. Rechaza un propietario distinto
mientras la instancia previa está abierta o su emergencia queda incompleta. Tras
CLOSED, un propietario nuevo necesita autenticación posterior a la confirmación.
No se persiste esta autorización; un proceso nuevo inicia bloqueado. El registro
no contiene contraseñas ni claves y no sustituye el wrapping Keystore/Argon2id.

## Cancelación HTTPS reproducida

La integración JVM con TLS real y cuerpo retenido reprodujo una espera en
`HttpURLConnection.disconnect -> MeteredStream.close`: la lectura mantenía el
lock interno. El cierre previo directo podía ganar la carrera antes del read;
no probaba interrupción una vez bloqueado. Se conserva el volcado de hilos y el
resultado rojo, sin contenidos ni credenciales.

RelayClient registra sockets mediante una SSLSocketFactory delegada: mantiene
la fábrica TLS de la conexión, ciphers y verificador de nombre existentes.
Cierra primero el socket subyacente y después la conexión HTTP; no espera body,
ACK ni close_notify remoto. La API estándar [Socket.close](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html#close())
permite terminar I/O bloqueado. Se prueba también nombre de certificado incorrecto;
no se cambia confianza, no se añade CA productiva ni se habilita HTTP.

DNS/conexiones de plataforma aún no entregadas a la fábrica y proveedores no
cooperativos pueden quedar pendientes. Se registra INCOMPLETE, no una afirmación
de desaparición instantánea de todos los paquetes del SO. El plazo no se amplía.
