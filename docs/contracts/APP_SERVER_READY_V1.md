# APP_SERVER_READY_V1 — cliente preparado antes del servidor

## Objetivo

La edición `connected` de UMBRA no expone al usuario configuración de servidor, IP, puerto, relay ni TURN.
El origen privado controlado por el producto queda fijado en build como `https://relay.egoumbra.sbs`. La edición
`offline` mantiene el origen vacío y no obtiene INTERNET por esta decisión.

## Aprovisionamiento

El archivo público de realm continúa siendo el wire `umbra:realm:1:...` existente. No se introduce un nuevo
formato firmado. En la edición connected, la presentación entrega el wire a `ProvisioningService.review()` junto
con el origen exacto fijado por build, muestra la identidad pública para comparación por el canal administrativo
acordado y exige una confirmación explícita antes de `install()`. Instalar no admite el dispositivo, no registra
el buzón y no abre red. En la edición offline se conserva la importación de realm existente sin origen de red.

## Registro y red

El formulario normal de Red no contiene una dirección editable ni muestra el host configurado. El registro obtiene
el origen exclusivamente de `ProvisioningService.status()` y rechaza cualquier valor que no coincida exactamente
con `BuildConfig.RELAY_ORIGIN`. Todos los accesos HTTPS y el gate de conexión rechazan también un relay persistido
que no coincida exactamente con ese origen; una instalación antigua no puede conservar silenciosamente un destino
arbitrario. El botón `Conectar y registrar` constituye consentimiento explícito de red para esa operación;
desbloquear la bóveda nunca conecta automáticamente.

Los estados de servicio se presentan como `Servicio privado` / `Conexión privada no disponible`. Pairing, Admission
y VERIFIED siguen siendo estados independientes.

## Estado de infraestructura

Este contrato prepara el cliente; no afirma que DNS, TLS, relay, TURN ni la VPS estén desplegados. Si la
infraestructura no responde, UMBRA debe conservar los datos en cola y mostrar indisponibilidad sin inventar éxito.
