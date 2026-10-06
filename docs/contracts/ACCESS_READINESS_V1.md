# ACCESS_READINESS_V1

Contrato aditivo de dominio, basado en `1a0b040ff0fc3cd723bc519c279818616155b0b7`.
No sustituye UI_SECURITY_CONTENT_API_V1. No modifica presentación ni autoriza usar
un snapshot como permiso. Consumidor previsto: futura integración de Claude.

## Instancia y alcance

Obtener `AccessSession access = vault.access()`; una instancia canónica por Vault.
No crear coordinadores alternativos ni restaurarlos desde preferencias. Password y
metadata se trabajan fuera del hilo UI. `snapshot()` no adquiere el monitor de
SQLite/Vault ni espera Argon2. No realiza I/O de red, Nearby o sensores.

El contrato no cambia por sí solo MainActivity. Sus llamadas heredadas a `lock()`
no llevan causa y se observan como UNKNOWN. Claude deberá conectar los eventos
reales al adaptador no visual; no inferir BACKGROUND a partir de cualquier cierre.

## AccessSnapshot

Record inmutable con estos campos exactos:

| Campo | Significado |
|---|---|
| `phase()` | Fase de acceso actual, no resultado de una pulsación |
| `selectedAutoLockMillis()` | Política configurada, rango existente 1..240000 ms; UI actual ofrece 60000/120000/240000 |
| `effectiveRemainingMillis()` | Resto del límite vigente del gate, redondeado hacia abajo; cero no concede acceso ni sustituye la comprobación nanosegundo |
| `effectiveDeadlineMonotonicNanos()` | Deadline en el reloj monotónico de AccessGate, cero cuando está denegado |
| `absoluteGateRemainingMillis()` | Resto del techo ORIGINAL de autenticación Android, nunca renovado por password/callback |
| `epoch()` | Generación observada; no token que pueda restaurarse o usarse para fabricar permiso |
| `lockCause()` | `AccessGate.LockCause`, causa de invalidación/diagnóstico; UNKNOWN mientras no existe causa conocida |
| `operation()` | Última operación del coordinador: id local, tipo, estado, outcome; distinta del acceso actual |
| `externalAction()` | Acción externa pendiente, enum; nunca contiene URI, password ni material criptográfico |

Los tiempos pertenecen al reloj de AccessGate (por defecto `System.nanoTime`),
**NO** al origen de `SystemClock.elapsedRealtime` ni al reloj de pared. No restar
un origen del otro. La presentación puede mostrar el remaining observado; para
un nuevo valor consulta otro snapshot. No persistir deadlines/generaciones.
Un snapshot puede quedar obsoleto inmediatamente: cada operación vuelve a validar.

Fases:

- LOCKED (incluido nuevo proceso) y ANDROID_AUTH_REQUIRED.
- ANDROID_AUTHENTICATING, solo tras comenzar explícitamente esa acción externa.
- METADATA_REQUIRED: autenticación disponible pero protección aún no inspeccionada.
- PASSWORD_CREATE_REQUIRED, LEGACY_ENROLLMENT_REQUIRED, PASSWORD_REQUIRED.
- PASSWORD_CREATE_WORKING, PASSWORD_UNLOCK_WORKING, PASSWORD_CHANGE_WORKING.
- OPEN únicamente si Vault conserva clave desbloqueada y gate vigente.
- LOCKING, CORRUPT, KEY_UNAVAILABLE.
- EMERGENCY_CLOSING, EMERGENCY_CLOSED, EMERGENCY_INCOMPLETE: prevalecen sobre las
  fases ordinarias; CLOSED exige el resultado real del coordinador de emergencia.

LOCKING está reservado para trabajo de cierre observado; bloquear el gate es
síncrono y puede no generar una muestra intermedia. No exigir que cada lector vea
todas las transiciones. Un proceso nuevo no restaura una fase OPEN anterior.

Causas cerradas:
`PROCESS_RESTART`, `USER_REQUEST`, `BACKGROUND`, `AUTOLOCK`,
`ANDROID_AUTH_EXPIRED`, `EMERGENCY`, `KEY_INVALIDATED`, `VAULT_FAILURE`, `UNKNOWN`.
Un cierre de limpieza redundante conserva la primera causa; emergencia prevalece.
La key invalidada o corrupción estructural no se presentan como password erróneo.
No confundir error de autenticación de contenido con metadata estructural inválida.

## Plazos efectivos y seguridad

Autenticación Android exitosa crea un epoch nuevo, con techo de dominio de 240 s.
La configuración Keystore existente sigue exigiendo autenticación del sistema y
protección de dispositivo; no se cambia su duración de 300 s ni su política hardware.

Al abrir con password:

```
deadline efectivo = min(deadline previo del gate,
                        instante de commit del unlock + política elegida)
```

`AccessGate.restrict(lease, nanos)` solo acorta el límite actual. La policy no se
aplica antes de terminar Argon2; el tiempo gastado en Android auth/password ya
reduce el techo original. `invalidateAuthorizations()` cambia epoch pero no
renueva ningún plazo. Cambiar policy requiere Vault cerrado y no extiende un
acceso que esté abierto.

Con gate completo, 60/120/240 s permiten acceso hasta deadline−1ns; exactamente en
el límite las comprobaciones deniegan. Si se consumieron 90 s de autenticación,
elegir 240 s deja como máximo 150 s. No se usa wall clock para autorizar.

El límite seleccionado ahora se comprueba en AccessGate en cada acceso, además
del timer Vault que provoca limpieza al vencer. Un timer retrasado no amplía
permisos. El tick heredado de MainActivity sigue comprobando su techo de 240 s:
no se eliminó ni se cambió en esta ejecución. La observabilidad nueva está
centralizada; las guardas previas no se relajan.

`background()` invalida inmediatamente. `foreground()` solo observa. Ni volver,
ni un picker, ni un permiso, ni un callback viejo, ni process recreation reabren
la bóveda. Una nueva autenticación válida crea otro epoch; el anterior no revive.
No interpretar “4 min” como cuatro minutos en background ni como timeout de
inactividad que se renueva al tocar controles.

## Operaciones de password

Métodos síncronos de worker:

- `createPassword(byte[])`
- `unlock(byte[])`
- `changePassword(byte[], byte[])`
- `refresh()` — inspección de metadata pública/key presence tras autenticación;
  puede lanzar un error genérico, conservando el estado CORRUPT/KEY_UNAVAILABLE.

Los métodos de password devuelven `AccessSnapshot.OperationResult` y toman
ownership de arrays: los limpian en finally, también si BUSY/rechazo. El caller
no debe reutilizarlos. No se garantiza borrado forense en Java/Keystore.

`Operation`: NONE, CREATE_PASSWORD, UNLOCK, CHANGE_PASSWORD, EXTERNAL.
`OperationState`: IDLE, WORKING, SUCCESS, FAILED, REQUIRES_USER_ACTION, CANCELLED,
EXPIRED, UNAVAILABLE. No se usa este pequeño vocabulario para reemplazar los
estados propios de llamadas, contenido o admisión.

| Resultado | Significado |
|---|---|
| SUCCESS / COMPLETED_LOCKED | Create/change confirmó persistencia y terminó bloqueado; no es OPEN |
| SUCCESS / OPENED | Unlock confirmó clave/lease; snapshot debe revalidarse antes de presentar contenido |
| FAILED / GENERIC_FAILURE | Password incorrecta y tag/ciphertext/salt/nonce adulterados permanecen indistinguibles |
| FAILED / CORRUPT | Formato público/schema inválido; no se repara ni borra |
| FAILED / KEY_UNAVAILABLE | Alias ausente o clave permanentemente invalidada; no se regenera |
| CANCELLED o EXPIRED / STALE | Operación vieja perdió su epoch; su resultado no abre una sesión posterior |
| UNAVAILABLE / BUSY | Otra operación aceptada sigue ejecutándose; pulsar no equivale a aceptación |
| FAILED / COMMITTED_CLEANUP_FAILED | El cambio SQLite YA fue confirmado y el cierre reportó fallo. No asumir rollback ni repetir automáticamente |
| AUTHENTICATION_REQUIRED | Se requiere autenticación nueva; no reusar el grant anterior |
| REQUIRES_USER_ACTION / EXTERNAL_ACTION_REQUIRED | Contexto externo creado; aún requiere la acción real de Android/usuario |
| CANCELLED / EXTERNAL_CANCELLED | El usuario canceló la acción externa; no se reanuda trabajo sensible |

La operación y el estado actual se mantienen separados: SUCCESS histórico nunca
concede OPEN después de background/emergencia. El resultado de una operación vieja
no sustituye WORKING de otra. No hay setters de snapshot que autoricen operaciones.
Las APIs heredadas Vault continúan existiendo; sobrecargas con lease esperado
permiten rechazar trabajo encolado antes de entrar a otra sesión. `lockWithCause`
se llama distinto de `lock` para preservar compatibilidad de method references Java.

Las APIs del coordinador no transportan excepciones criptográficas detalladas,
password, claves, salt, nonce o contenido. Outcome GENERIC_FAILURE no identifica
cuál factor falló. Solo un rechazo tipado de clave de dispositivo se conserva
internamente desde la envoltura; los tags siguen siendo fallo genérico.

## Acciones externas y lifecycle

`beginExternal(ExternalAction)` produce un `ExternalRequest` opaco, no autorización:
DOCUMENT_PICKER, ANDROID_SETTINGS, PERMISSION_PROMPT o ANDROID_AUTHENTICATION.
Invalida acceso antes de salir. No lanza Activities ni diálogos.

`externalReturned(request, cancelled)` consume una vez el contexto. Picker/settings/
permiso regresan REQUIRES_USER_ACTION/AUTHENTICATION_REQUIRED o CANCELLED. Puede
conservarse el ID opaco a través de background, jamás el lease ni bytes sensibles.
Una nueva operación sustituye ese contexto; retorno tardío entonces es STALE.
Un cierre explícito cancela contextos. Process death no los restaura.

`androidAuthenticationSucceeded(request)` solo se invoca desde un callback exitoso
real de BiometricPrompt/device credential. Comprueba ownership/epoch/vigencia,
consume el ticket y usa el AccessGate existente; **no abre** el Vault con password,
no prueba hardware por sí mismo y no sustituye la autenticación AndroidKeyStore.
Ante emergencia solo acepta una autenticación preparada después de CLOSED.
Un background real cancela ese ticket; la futura integración debe diferenciar
la notificación de prompt en curso de abandonar la aplicación. No inventar un
success si Android no lo entregó ni usar este método desde onResume genérico.

`lock(LockCause)` y `background()` son callbacks no visuales. `foreground()` solo
retorna un snapshot. El harness prueba su conexión a lifecycle en una Activity
exclusivamente de laboratorio; **MainActivity productiva no fue modificada** y
Claude todavía debe integrar y probar ese consumer.

## Qué puede consumir Claude y qué no inferir

Puede leer todos los campos del record, separar WORKING/resultados/acción humana,
mostrar el plazo efectivo y usar causas conocidas. Debe conservar el ID de una
operación únicamente para correlacionar su respuesta y pedir estado actual.
No debe inferir autorización a partir de phase/id/remaining: el dominio la valida.

No debe inferir: SUCCESS=create abierto, Android auth=password validada,
foreground=reanudar, picker concedido=lease vigente, deadline=wall clock,
CONNECTED implícito, admisión/VERIFIED nuevos, hardware autenticado por un AVD,
ni CLOSED por haber ocultado el contenido. No diseñamos pantalla, navegación,
copy, paleta, iconos o jerarquía visual en este contrato.

## Validación y compatibilidad

Las regresiones JVM usan clocks controlados sin simular Signal. Android usa Vault,
SQLite, Argon2 y AndroidKeyStore con claves de laboratorio existentes, aisladas;
no cambia prepareKey productivo. El harness `accessLab` solo se incluye opt-in en
debug/vaultLab; el APK guard prohíbe sus clases en APK ordinario. El lab R8 mantiene
optimización y nombres ofuscados y verifica el coordinator en mapping.

Resultados concretos y limitaciones: ver el addendum de Execution02 en la auditoría
y el recibo de validación de esta rama. La evidencia emulada no sustituye una
validación de autenticación hardware ni del producto gráfico combinado.

Una cancelación observada invalida acceso antes de esperar Argon2/SQLite. Mientras
ese worker aún termina, otro intento puede devolver BUSY aunque el snapshot ya
muestre CANCELLED/EXPIRED. Esto impide solapar trabajo sensible; no es una nueva
autorización ni una razón para restaurar el epoch antiguo.
