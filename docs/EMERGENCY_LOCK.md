# Bloqueo de emergencia local — contrato v1

Implementación en validación; no es una declaración de aceptación ni de producción.

`Engine.emergencyLock()` solicita el cierre mediante el coordinador del AccessGate
de la bóveda. No exige contraseña. Antes de crear un Engine, el propietario del
AccessGate puede invocar `gate.emergency().request()` sobre esa misma instancia.
La UI debe conservar el coordinador propietario de la sesión durante el cierre;
crear otro AccessGate no es una forma de controlar los recursos anteriores. `Engine.emergency().status()` devuelve una
instantánea inmutable de progreso. La solicitud es idempotente para esa generación.
No hay endpoint, receiver exportado ni mensaje remoto que active este API.

## Contrato para Claude/UI

- Invocar `engine.emergencyLock()` en la acción explícita del botón. Su retorno
  incluye `requestedNanos` e `invalidatedNanos`. Ocultar inmediatamente contenido
  sensible; esto no significa que hayan cerrado cámara, sockets o proveedores.
- Mostrar `INVALIDATED`/`CLOSING` como cierre en curso. Consultar `status()` sin
  acceder a registros cifrados. `results()` informa por participante y subsistema:
  CLOSING, CLOSED, FAILED o TIMED_OUT. Puede haber varios participantes de un tipo.
- Mostrar cierre confirmado **solo** en `CLOSED`. `INCOMPLETE` mantiene denegadas
  operaciones y nueva autenticación. No mostrar excepciones del proveedor ni
  contenido, credenciales o claves. No reintentar automáticamente ni desbloquear.
- Tras CLOSED, obtener `prepareAuthentication()` **antes de iniciar una nueva
  autenticación local Android**. Solo su callback válido puede llamar
  `AccessGate.unlock(ticket)`. A continuación se necesita `Vault.unlock(password)`;
  el ticket no sustituye autenticación Android, contraseña ni Keystore.
- Reconstruir los servicios si se cerraron y llamar `connectivity.vaultUnlocked()`
  después de abrir realmente la bóveda. Permanecer UNLOCKED_OFFLINE. Connect y
  Nearby requieren acciones separadas; no restaurar llamadas, captura o colas.
- Un propietario nuevo de la misma ruta no puede solaparse con otro abierto o con
  cierre incompleto. Tras CLOSED requiere autenticación posterior a la confirmación.

El botón de Claude, sus estados visuales y la aplicación combinada **siguen
pendientes de integración y pruebas**. No se han cambiado diseño, navegación ni
componentes de sus ramas. La superficie multimedia heredada únicamente notifica
su liberación y limpia la última imagen al recibir cierre.

## Orden, datos y errores

Primero se deniega el gate con una barrera atómica, sin esperar transacciones ni
servidores. Después cada participante solicita su cierre local en un trabajador
independiente. Vault espera la transacción SQLite antes de cerrar. Las operaciones
que cruzaron el commit pueden permanecer confirmadas; otras hacen rollback. No
se borran archivos, identidad, ratchets, mensajes ni credenciales.

El coordinador observa hasta cinco segundos; agotarlos produce TIMED_OUT e
INCOMPLETE, nunca éxito. Los observadores de ejecutores de red/media tienen un
presupuesto de cuatro segundos dentro de ese límite. Un proveedor que no termina
puede dejar el cierre incompleto: no se habilita una sesión superpuesta.

DocumentIO cierra flujos y espera que termine la operación; un `open()` del proveedor
no cooperativo permanece pendiente. HTTP espera que salgan las solicitudes activas.
RFCOMM cierra sockets y espera trabajadores. Media espera liberación nativa y ambos
executors; ubicación solicita removeUpdates. Ninguno espera ACK, END o TURN para
invalidar autorización. No se vacía la cola hacia la red antes de bloquear.

Los paquetes ya entregados al sistema, frames en tránsito y exportaciones ya
escritas no se pueden retirar. No se promete borrado forense de memoria ni control
del sistema operativo. La emergencia no revoca admisión administrativa.

## Evidencia y límites

Las pruebas de dominio no equivalen a sensores, radio física o hardware Keystore.
El laboratorio nuevo ejecuta SQLite/Keystore Android aislado y media sintética
mediante Engine, Signal, HTTPS, WebRTC y TURN existentes, en debug/R8. Solo su
resultado real sobre el commit correspondiente podrá confirmar aceptación.

No se amplían TTL 60/180 segundos, autolock ni segundo plano. Offline conserva sus
permisos y carece de componentes WebRTC. No se añade criptografía ni dependencia.

El código no inicia escaneo BLE, publicidad BLE ni `startDiscovery`. La pantalla
heredada puede pedir al sistema visibilidad Bluetooth durante 120 segundos mediante
`ACTION_REQUEST_DISCOVERABLE`. Esa visibilidad temporal del adaptador pertenece a
Android, no a un socket UMBRA: cerrar RFCOMM no demuestra que terminó antes su plazo.
No se apaga el Bluetooth global ni se emplean APIs privilegiadas para ocultarlo.
La [documentación Android](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices)
distingue descubrimiento, visibilidad y conexión; el cierre aquí confirma recursos
de UMBRA, no invisibilidad del dispositivo ante otros equipos.
