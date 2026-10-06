# S1_PROVISIONING_V1 — configuración pública sin conexión automática

Esta API es aditiva. No modifica UI_SECURITY_CONTENT_API_V1, RealmConfig v1,
credenciales, pruebas de posesión, Signal, permisos offline ni políticas TURN.

## Consumo desde presentación

`engine.admission().provisioning()` devuelve el mismo servicio asociado a la
bóveda y a la admisión de ese Engine. No crea otro controlador de conectividad.

- `review(exactHttpsOrigin, publicRealmConfig)` devuelve `Review` después de
  validar formato y compatibilidad con la configuración almacenada.
- `Review.exactOrigin()` y `Review.realm()` permiten comprobar el destino y la
  huella pública por un canal administrativo autenticado fuera del relay.
  Estas lecturas exigen el lease que creó la revisión.
- `install(review, sourceAuthenticatedConfirmed)` exige esa confirmación
  explícita y el mismo servicio/lease. Persiste el realm y el origen en una
  transacción. `false`, revisión ajena o sesión antigua se rechazan.
- `status()` devuelve `Status(configured, admissionState, registered,
  exactOrigin, realm)`. No devuelve tokens, invitaciones ni claves privadas.

Parsear un realm o pulsar confirmar no es autenticación criptográfica de su
fuente. El consumidor debe obtener la tupla de una fuente verificada, mostrar
los datos exactos para su comprobación y no enviar `true` automáticamente.
No existe un nuevo formato firmado de provisioning en esta entrega.

## Semántica y límites

Aprovisionar no concede admisión, registro, consentimiento de red, Nearby ni
verificación de contactos. No abre sockets, consulta DNS, desbloquea la bóveda
o comprueba salud. Conserva la configuración y credenciales existentes en un
reintento exacto; una sustitución del origen o realm se rechaza. No normaliza
silenciosamente espacios, slash, case o puerto porque el origen se vincula a
las pruebas HTTP. Una sustitución administrativa futura requiere otra decisión.

La identidad debe estar inicializada y ser consistente antes de revisar. La
persistencia hereda el cifrado y las transacciones del Vault; MemoryRecords o
SQLite sintético no son prueba de cifrado. La revisión se invalida al bloquear,
expirar o cambiar la generación. Process death exige autenticación nueva.

La instalación exige observar que online/Nearby no estén activos, antes y al
final de su transacción. No es un mutex global de conectividad: una acción
concurrente explícita de conexión conserva sus propios requisitos y lease.
El servicio no cambia bindings existentes ni concede permisos de conectividad.
`configured` no significa `ADMITTED`, `registered` ni servidor accesible.

## Errores para Claude

`ProvisioningException.code()` distingue INVALID_CONFIGURATION,
SOURCE_CONFIRMATION_REQUIRED, REVIEW_UNAVAILABLE, BINDING_MISMATCH y
CONNECTIVITY_ACTIVE. Las excepciones de acceso/almacenamiento existentes
se conservan; no transformarlas en una configuración vacía o repararla.
Los errores y `toString()` no imprimen la configuración ni secretos.

## Integración pendiente

Claude debe conectar la API al flujo de importación administrativa controlada
para sustituir el formulario técnico. El dispositivo nuevo sigue generando su
solicitud de admisión individual y recibiendo la aprobación offline del admin.
La invitación de registro del buzón es separada y secreta; este contrato no la
incluye en el realm público ni implementa un issuer automático. Registrar exige
el consentimiento explícito de red y el gate de admisión existentes.

No confundir esta preparación con un producto ya conectado a una VPS. DNS,
HTTPS, disponibilidad, costos y aceptación física se verifican después de la
autorización específica y de la integración de presentación.
