# UMBRA Location v1

Estado: tercera entrega. [Decisión y amenazas](../adr/ADR-location.md).
No cambia el sobre relay v1. Contenido Signal v2 `kind=location`, con los campos
comunes de Wire, `logicalId`, `logicalFrom`, `logicalTo` y objeto `location`.
El tipo y las coordenadas están dentro del ciphertext. Las versiones anteriores
rechazan este kind; no lo interpretan como texto ni lo convierten a otro control.

## Objeto location

Campos exactos, sin duplicados ni coerción JSON; máximo 4096 caracteres:

| Campo | Contrato |
|---|---|
| v | entero 1 |
| type | LOCATION_POINT, LOCATION_LIVE_START, LOCATION_LIVE_UPDATE, LOCATION_LIVE_STOP |
| session | UUID canónico aleatorio, nunca reutilizado |
| owner / device / recipient | 64 hex minúsculos; identidad lógica emisor, dispositivo emisor, identidad lógica receptora |
| targets | 1–8 Device IDs, ordenados estrictamente, sin duplicados; conjunto fijado por consentimiento |
| seq | 0–1921; START/POINT=0; UPDATE/STOP>0 |
| started / ends | segundos UTC enteros; duración 60–28800 segundos; POINT máximo 120 |
| mode | MANUAL, PRECISE, APPROXIMATE, ZONE |
| point | solo POINT/UPDATE; objeto descrito abajo |

`owner/device/recipient` deben coincidir con el contexto autenticado de Engine;
`targets` debe incluir el dispositivo receptor. La lista no autoriza una clave:
DevicePolicy, roster aprobado, contacto VERIFIED y lease local siguen siendo
obligatorios. Una clave añadida después no amplía targets, incluso si se aprueba.
Una revocación reduce las entregas, sin reescribir el conjunto del transcript.
No se replican automáticamente coordenadas a dispositivos propios.

`point` contiene exactamente:
- `latE7`, `lonE7`: enteros en ±900000000 y ±1800000000.
- `sensorAccuracyMm`: incertidumbre declarada por proveedor, 0–100000000 mm;
  -1 únicamente para MANUAL, que no tiene medición de sensor.
- `measured`: segundos UTC de medición/declaración, antigüedad máxima 120 s,
  futuro máximo 30 s, no posterior a ends ni anterior a started−120.
- `source`: MANUAL, ANDROID_FINE o ANDROID_COARSE. Coarse nunca permite PRECISE.
- `cellE7`: 0, 100000 (APPROXIMATE) o 1000000 (ZONE), separado del sensor.

No se aceptan floats, cadenas numéricas, NaN, infinito, campos extra o tipos
remotos desconocidos. El adaptador valida además edad monotónica del Location,
`hasAccuracy`, permiso actual y proveedor habilitado. No infiere veracidad física.
No se envían altitud, velocidad, rumbo, identificadores del proveedor o mapas.

## Reducción local

Tras validar rangos finitos, convertir a E7 mediante redondeo. Para celda g:
`floor((latE7 + 900000000)/g)*g - 900000000 + g/2`, limitando el polo norte
al centro de la última celda. Longitud usa origen −180°; +180° normaliza a −180°.
Se publica únicamente el centro y g. El receptor verifica que sea centro de celda.
Las distancias físicas varían con latitud; no es un radio de exactitud ni anonimato.
La posición original no se persiste antes de reducirla. Actualizaciones sucesivas
pueden revelar una trayectoria pese a usar celdas.

## Consentimiento, captura y reloj

`review` captura destinatario, conjunto aprobado, modo, duración y lease. Confirmar
requiere acción explícita local, mismo servicio/época, menos de 60 segundos desde
review y revalidación dentro de la transacción. Confirmación consumible una vez.
Duraciones UI: 15 minutos, 1 hora, 8 horas; API admite 60–28800 s. El límite
real también incluye bloqueo automático y salida de primer plano. No hay FGS ni
ACCESS_BACKGROUND_LOCATION, ni recepción que active GPS. MANUAL nunca pide permiso.

El plazo emisor usa elapsedRealtime Android y ends UTC: vence al alcanzar cualquiera.
Retroceso antes de started−30 s interrumpe. UPDATE no renueva ends. Leases solo en
memoria; muerte del proceso, force-stop, reinicio o nuevo Engine no restauran permiso.
La pantalla debe volver a obtener consentimiento. Android solo aproximado selecciona
NETWORK_PROVIDER si existe, sin fallback GPS. Falta de proveedor no fabrica posición.

## Estado persistido y reordenamiento

Emisor: PENDING (punto), ACTIVE (temporal), SENT (punto en cola), STOPPED, EXPIRED,
INTERRUPTED. Los terminales no reactivan el identificador. Bloqueo cancela primero
los grants/callbacks sin depender del disco; reapertura registra interrupción y
elimina backlog. Fallo guardando STOP conserva la cancelación en memoria; un proceso
nuevo tampoco recupera grants. Signal no retrocede al descartar entregas.

Receptor: POINT, ACTIVE, STOPPED, EXPIRED, INTERRUPTED. UPDATE lleva contexto completo
y puede llegar antes de START; START tardío no sustituye una secuencia mayor. STOP
puede llegar primero y crea tombstone. Mismo session con contexto distinto se rechaza.
Secuencias menores/repetidas se consumen sin sustituir la posición, con deduplicación
Signal/Engine y ACK transaccional. Un terminal no acepta una reactivación. Una nueva
instancia receptora no presenta una sesión anterior como activa automáticamente.

Vista ACTIVE solo indica RECENT si la última medición tiene ≤30 s por reloj de pared
y por la edad al recibir más el tiempo monotónico transcurrido; si no, LAST_KNOWN.
STOPPED/EXPIRED/INTERRUPTED nunca se presentan como en vivo. La duración receptora
se acota también por plazo monotónico en ese proceso. STOP no entregado no extiende
ends. El contenido de la última posición se elimina tras ends+120 s; el tombstone
sin coordenadas persiste hasta ends+7 días+300 s. Esto no elimina copias externas.

## Entregas, persistencia y límites

Cada evento tiene UUID lógico y ciphertext independiente por destino. Caducidad
por entrega `min(ends, ahora+120 s)`; no se sube una entrega vencida. START puede
permanecer junto a la última UPDATE: máximo dos pendientes por sesión/destino.
STOP cancela pendientes de esa sesión y genera un control nuevo, con lease válido.
Nunca se cambia un ciphertext existente ni se rebobina el ratchet al coalescer.
Si bloquear impide cifrar STOP, basta detener localmente; receptor depende de expiry.

Máximo cuatro grants, 256 registros emisor y 256 receptor; intervalo mínimo 15 s.
Cada sesión de ocho horas genera ≤1920 updates, dentro del límite de saltos por
sesión Signal. Los límites globales de outbox, cuotas, seen y ACK siguen vigentes.
No hay historial de puntos intermedios; se persiste solo el último punto recibido.

Revalidación antes de capturar, callback, cifrar, encolar y escribir HTTPS/RFCOMM.
El adaptador liga permiso/AppOps, primer plano y proveedor al grant; también se
comprueban al escribir una entrega pendiente. Una denegación invalida ese grant.
Se exige la entrega concreta aún autorizada: una copia retirada de outbox no obtiene
una autorización nueva. Bytes que ya salieron pueden llegar; no se promete retirada.

Migración aditiva: buckets `location-out`/`location-in`, sin cambiar claves, ratchets,
metadatos de identidad o SQLite del relay. Vault cifra los registros con su política
hardware intacta. Los adaptadores SQLite/JVM de pruebas contienen solo datos sintéticos.
Almacenamiento fallido revierte ratchet, estado receptor y ACK; no confirma antes de
persistir. Reapertura SQLite, muerte entre operaciones y muerte durante commit son
controles diferentes, con resultados separados en el informe.

Vaciar conversación elimina coordenadas visibles y detiene las sesiones salientes
que incluían ese dispositivo. Conserva tombstones ocultos terminales para que replay
no restaure posiciones borradas. Un STOP confirmado libera el grant en memoria sin
borrar el tombstone persistente. No se eliminan copias de otros dispositivos.
