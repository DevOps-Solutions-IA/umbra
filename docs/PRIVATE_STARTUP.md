# Inicio privado estricto (v1)

Estado de entrega: implementación bajo validación. El recibo de
[validación](validation/2026-09-27-private-startup.md) distingue ejecución de diseño.

`PRIVATE_STARTUP_STRICT` es la única política. Un controlador recién creado comienza
`LOCKED_PRIVATE`; no restaura permisos de conectividad desde SQLite o preferencias.
El desbloqueo criptográfico de la bóveda permite notificar `vaultUnlocked()` y
produce `UNLOCKED_OFFLINE`. Esta llamada no abre sockets, no resuelve nombres, no
obtiene credenciales TURN y no inicia sensores ni Bluetooth.

## Contrato de dominio

`Engine.connectivity()` expone:

- `getConnectivityState()`, `getPolicy()`, `canConnect()`.
- `connect(origin, confirmed)`: autorización explícita, bóveda actual, admisión
  vigente y origen HTTPS válido. Offline rechaza esta transición.
- `disconnect()`: invalida la generación, cierra trabajo registrado y cancela
  llamadas locales, conservando la bóveda abierta.
- `isNetworkSessionAllowed()`, `networkLease(origin)`: permisos acotados al origen
  y generación; los callbacks antiguos no adquieren una sesión nueva.
- `startNearby(confirmed)`, `stopNearby()`, `isNearbySessionAllowed()`: consentimiento
  separado para RFCOMM, con las pruebas Signal/admisión existentes intactas.
- `cleanupFailed()`: fallo de cancelación que impide conceder nuevas sesiones.

Transiciones: LOCKED_PRIVATE → UNLOCKED_OFFLINE → CONNECTING → CONNECTED.
Al desconectar: DISCONNECTING → UNLOCKED_OFFLINE. Fallo de transporte/membresía:
OFFLINE_ERROR, que exige otra acción explícita. Bloquear termina en LOCKED_PRIVATE.
No se permite desbloquear durante una cancelación anterior todavía en curso.
CONNECTED indica consentimiento local para I/O a demanda, no disponibilidad del
servidor, conexión ICE ni una llamada activa. Connect no precarga TURN.

La integración Android utiliza `AndroidConnectivity.connect(context, service,
origin, confirmed)`. Registra observación de la red predeterminada después del
consentimiento; pérdida, bloqueo o sustitución de esa red invalida su generación.
No llama `requestNetwork`, no reconecta al recibir `onAvailable` y desregistra el
callback al desconectar. La API pura permite pruebas JVM e integración HTTPS;
los errores de transporte también cierran esa autorización.

## Límites y composición

Todos los métodos de RelayClient, incluidos los de datos públicos de admisión,
exigen el gate antes de abrir una conexión. En strict v1, un dispositivo todavía
no admitido importa su configuración/solicitud/credencial por provisioning offline.
La admisión por sí sola no conecta, y vincular un dispositivo no concede sesión.
Las llamadas exigen conexión también en CallService y sus leases de captura.
No cambia RELAY_ONLY, certificados, Signal ni las reglas de verificación humana.

La Activity heredada dejó de interpretar `profile.online` como autorización. Su
control de conexión llama el dominio explícitamente. Esta es la integración mínima
necesaria para cerrar la ruta automática de `refresh` → `tick` → relay. No se usa
ni modifica `claude/android-ui-foundation`; la conexión de esa UI sigue pendiente.
Una futura UI debe solicitar contraseña y conexión como decisiones independientes.

Ubicación, micrófono y cámara conservan sus consentimientos propios. No se crean
capturadores al construir el gate. La cola local de mensajes puede prepararse sin
red; transmitirla requiere una sesión online o Nearby actual. Una concesión online
no inicia escucha, descubrimiento o emparejamiento Bluetooth. Acciones realizadas
por el usuario en los ajustes de Android son responsabilidad del sistema, no un
servicio de escaneo iniciado automáticamente por UMBRA.

El reinicio del proceso pierde todas las concesiones. No hay servicios de boot,
alarms/jobs, WorkManager, push, analítica ni comprobaciones de actualización que
reanuden la conexión. Las futuras funciones de red deben pasar por este gate.
Sin conexión no hay mensajes ni llamadas entrantes instantáneas.

Disconnect no retira bytes previamente escritos, buffers del SO o paquetes en
tránsito. La revocación offline depende de recibir evidencia firmada; no es
instantánea universal. Esta política no oculta tráfico del sistema operativo,
no controla otras apps y no resiste un OS comprometido. No promete anonimato.

## Validación

JVM: generaciones, separación de consentimientos, locks concurrentes, callbacks
antiguos, limpieza y fallos. Instrumentación: Vault/Keystore aislado y SQLite real.
El laboratorio dedicado observa contadores IPv4/IPv6 del UID y un DNS trampa
controlado, con un control positivo conectado. El DNS de NetworkStack se atribuye
por el nombre sintético; no se confunde su UID con el de UMBRA. Prueba HTTPS/Signal,
ACK, pérdida/restauración de red y force-stop. Los adaptadores sintéticos están
solo en APK de test; no habilitan Keystore software de producción.

El perfil R8 de laboratorio mantiene optimización/ofuscación con firma sintética;
no es el APK exacto de producción. Contadores IPv6 sin tráfico no prueban una ruta
IPv6 positiva. Los resultados ejecutados y los pendientes se registran en el recibo,
no se deducen de la presencia del código o de CI de entregas anteriores.

Las comprobaciones de cancelación por fragmento usan la época de conexión y
bóveda; no repiten firmas criptográficas por cada lectura de socket. La admisión
completa se verifica en los límites de cada operación y al aceptar la respuesta.
Esto no es una caché de autorización: revocaciones conocidas invalidan la época
y las operaciones nuevas vuelven a validar membresía y caducidad.
