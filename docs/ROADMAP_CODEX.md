# Backlog de finalización verificable

## Accesos restaurados — estabilización 2026-09-26

GitHub y descargas disponibles tras autorización del propietario; los bloqueos
de publicación anteriores son históricos. KVM local sin permisos efectivos;
usar CI. Video falla antes de revocar CAMERA, durante stopVideo. Causas de esa
transición terminal y RFCOMM todavía bajo investigación. Ver
[continuación](validation/2026-09-26-modulator-stabilization.md).

## Continuación 2026-09-26 — CI incompleta

HEAD `2d9ac6e`: modulación/voz R8/video R8 aprobados; pendientes de diagnóstico
los fallos de RFCOMM connected en Verify y camera-permission-revoked en video
debug. Wrong-fingerprint ya pasa. Mantener abiertos estos bloqueos y los de
hardware; ver [recibo](validation/2026-09-26-modulator-diagnostics.md).

## Modulación local de voz — en curso, 2026-09-25

PR #10 borrador dependiente de #9, rama `codex/local-voice-modulator`.
Base #9 verificada: `26d7326a8b2a43ee9533284d20896727ad8bb3ce`; no se modifica
su rama. DSP por llamada después de AEC/NS/ganancia, antes de Opus; modos natural
y timbre modulado a 100 Hz, error silenciado sin fallback. No anonimización
biométrica. Build nativo 36187887900: cuatro ABI, 83 TURN + 12 política multimedia;
AAR `.5` corrige preparación de formato antes del primer audio admitido.
Ocho recorridos reales `.5` debug/R8 aprobados en CI 36202456119 para
HEAD `383fc37`, checkout `7839081`, árbol común `064b1089`. También se ejecutaron
localmente los cuatro casos por configuración: voz inicial, video, lock y
revocación. Un fallo local sin audio no se reprodujo; causa no confirmada,
diagnóstico de laboratorio añadido sin reducir umbrales. Verify/voz/video
completos y CI del siguiente commit siguen pendientes en este recibo.
Ver evidencia 2026-09-25-local-voice-modulator.md para comandos y hashes.
Hardware, inteligibilidad física y medición cuantitativa de sincronía A/V no
ejecutados; no confundir mediaLab R8 con el APK productivo exacto.

CI posterior `63862cc`: Verify/voz/modulación SUCCESS; video R8 falló únicamente
wrong-fingerprint (debug31/31, R830/31). Se conserva el fallo 36204852534.
Corrección implementada: tick cancelado no sobrescribe el diagnóstico terminal;
observación estable de 350 ms y tres repeticiones R8 enfocadas aprobadas. No se
amplían motivos aceptados. Nueva CI completa requerida para el commit corrector.


## Aceptación de laboratorio de la sexta entrega — 2026-09-22

PR #9 sigue OPEN/DRAFT hacia `codex/turn-voice-core`. Código `171324bf` probado
en checkout `dca789944`: Verify 35661260991 (cuatro SUCCESS), voz R8 35661261035
y video 35661261036 SUCCESS. Video: 31 casos debug y 31 R8; 15 escenarios de voz
por variante. [Recibo y límites](validation/2026-09-22-turn-video-acceptance.md).
Implementación integrada comprobada con medios sintéticos; no hardware físico ni
APK productivo exacto. Pendientes: timestamp de último datagrama video, asignación
relay IPv6, hardware y auditoría. No reabrir fallos históricos como pendientes sin
una regresión nueva. Las secciones siguientes conservan la evolución anterior.

## Sexta entrega — video — EN CURSO, 2026-09-21

PR #9 / `codex/turn-video-core`, dependiente de #8. Primero regresiones C++ y voz R8.
Voz sintética optimizada comprobada localmente; corregida eliminación de JNI Zero
que abortaba inicialización nativa. [Evidencia](validation/2026-09-21-turn-video-core.md).
Video PARTIAL: consentimiento direccional y renegociación implementados; patrones
sintéticos remotos + audio comprobados en dos AVD debug/R8 sobre un árbol intermedio.
Pin `.3`, C++ seleccionado, Camera2 AVD, TURN/TLS e IPv6 hacia TURN cuentan con
evidencia parcial nueva. Pendientes: aceptación final, negativas completas,
CI del nuevo árbol y revisión de límites de cancelación. El consentimiento
unidireccional nativo y las nuevas regresiones del cierre de superficies ya tienen
ejecución local. No cerrar por CI anterior.
UI definitiva, hardware y auditoría continúan fuera.

## Quinta entrega — voz — en curso, 2026-09-21

PARTIAL — rama `codex/turn-voice-core`, base #7 abierta `e495793`.
[Estado y comandos ejecutados](validation/2026-09-21-turn-voice-core.md).
Distribución WebRTC/guardas/configuración RELAY verificadas. Dos AVD han ejecutado
Engine/SQLite/libsignal/HTTPS y Opus sintético bidireccional por coturn real. Adaptador
connected y controles mínimos integrados; no conversación humana ni hardware validado.
Corregida la redirección TURN nativa mediante fuente fijada: cuatro ABI compiladas
(run35577083313), cero paquetes al destino alternativo en dos AVD x86_64; Opus y rechazo
DTLS repetidos con ese AAR. La capacidad productiva se liga al hash revisado y conserva
la autorización Engine. Código c325788: CI35580903584, cuatro SUCCESS; checkout
b9d5256b47ffeb3ce4c6aa9bbc8342d4f611085c. Recibo JSON/Markdown en docs/validation.
La entrega documental posterior debe comprobar su propia CI y conservar esta distinción.
Caducidad de credenciales/asignaciones y rutas UDP reales ejecutadas en laboratorio.
Hardware, IPv6, TURN TLS y recorrido de voz R8 siguen pendientes.
No se modifica señalización anterior ni se reducen Keystore/permisos offline.


## Cuarta entrega — señalización — 2026-09-21 UTC

Protocolo de invitación/aceptación/selección/negociación/fin integrado con Engine,
Signal y relay; [evidencia](validation/2026-09-21-authenticated-call-signaling.md).
No cerrar WebRTC/audio/video por intercambiar controles. TURN obligatorio y sin
fallback: pendiente adaptar motor nativo, probar red/estadísticas y audio real.
Interfaz definitiva posterior. Hardware y auditoría siguen abiertos.

## Tercera entrega — ubicación — 2026-09-21 UTC

Ubicación manual/proveedor, reducción local, sesiones temporales con consentimiento
por conjunto destinatario y cancelación al bloquear: implementación y pruebas en el
[informe nuevo](validation/2026-09-21-encrypted-location-core.md). No cerrar hardware,
Vault positivo ni auditoría por resultados de laboratorio. El flujo conserva primer
plano y bloqueo automático; no ubicación background. Siguientes bloques mantienen
señalización, audio/video y TURN, pendientes de implementación propia.


## Segunda entrega — dispositivos

`codex/device-linking-core` parte del HEAD abierto de #4. Añade autoridad/listas,
ceremonia, sesiones por dispositivo, fanout y revocación; ver [informe nuevo](validation/2026-09-20-device-linking-core.md)
para resultados sobre el commit probado: `ef1106f`, CI `35544189738` con cuatro
SUCCESS. El HEAD de evidencia/fixtures posterior requiere su CI propia en PR #5.
No declarar cerrado P0-03 por SQLite de
laboratorio ni P0-11 por Bluetooth emulado. UI de administración completa e historial
sincronizado siguen pendientes; ubicación, voz, vídeo y TURN no implementados.


## Bloques del núcleo — 2026-09-20

PR #4 depende de #3, cuya CI 35532763825 pasó cuatro jobs. Esta nueva rama
no modifica estabilización ni main. Consultar el informe de identidad para la
validación del nuevo commit; una CI anterior no lo valida.

- Bóveda: corregidos apertura SQLite y carrera de alias, con reproducción Android.
  P0-03 sigue parcial: hardware/migración completa y commit del framework pendientes.
- Identidad/emparejamiento: implementación de invitación firmada compacta, request/ack,
  consumo atómico, revocación, límites, confianza y cambio explícito; validación del código `d1598e2` en CI `35535786431`: cuatro jobs SUCCESS. Exportación/importación mínima en la interfaz existente.
- Dispositivos/recuperación: siguiente bloque; RECOVERABLE bloqueado según ADR.
- Ubicación cifrada, señalización, voz y vídeo WebRTC: NOT EXECUTED en este bloque.
  No confundir los contratos propuestos en la misión con funcionalidad terminada.


Actualización integral 2026-09-19 UTC: [nuevo informe](validation/2026-09-19-integral-stabilization.md)
con evidencia local ampliada P0-01/P0-02, instrumentación limitada, Bluetooth emulado,
cliente real/relay, contenedor y R8. Esto no cierra en bloque P0-03–P0-12: quedan
matrices sin ejecutar, hardware y revisión/CI remota pendientes. No hay auto-merge.

Actualización histórica 2026-09-19 UTC: P0-01 verificado localmente en la rama
`codex/android-build-validation`; [evidencia](validation/2026-09-19-p0-01.md).
PR y CI deben revisarse antes de fusionar. Se verificaron permisos y JNI de los APK
debug, pero P0-02 completo (incluido release) y los demás paquetes siguen abiertos.

Todos los paquetes siguientes están ABIERTOS al transferir el proyecto. Son tareas propuestas
con criterios de aceptación, no funcionalidades ya entregadas. Terminar una fase no autoriza
marcar las posteriores como terminadas. Mantener tickets/PRs separados y enlazar evidencia.

| ID | Trabajo | Criterio de aceptación |
|---|---|---|
| P0-01 | Build real Android | Dependencias resueltas, API/Java/Gradle compatibles, 30 métodos JUnit/libsignal ejecutados sin mocks sustitutivos, APKs debug y lint de ambas variantes; prueba de que JNI sigue empaquetado correctamente. |
| P0-02 | Invariantes de APK | Inspección con herramientas Android de permisos, exported components, backups y configuración TLS; offline sin permisos de red; ninguna instrumentación/laboratorio en release. |
| P0-03 | Almacenamiento y bloqueo | Instrumentación de SQLite/Keystore, migración 1→2, claves perdidas, rollback, corrupción, transacciones y bloqueo durante cada operación; no pérdida o reinicialización silenciosa. |
| P0-04 | Laboratorio aislado | Dobles inyectables solo en tests/variante lab no distribuible, identidades diferentes, UID/applicationId/data separados; build release con rechazo verificable a configuración lab. |
| P0-05 | Transporte adversarial | Mensajes truncados/repetidos/reordenados, desconexiones, backpressure, límites de tiempo y tamaño, contacto incorrecto, replay de desafíos y cambios de identidad; libsignal real por encima del transporte de prueba. |
| P0-06 | Bluetooth emulado | Dos AVD compatibles con netsim: descubrir/emparejar, conexión RFCOMM real del stack emulado, retos, chat bidireccional, adjuntos, reconexión; captura de metadatos de prueba y clasificación como emulación. |
| P0-07 | Backend integrado | Cliente real contra relay de prueba: invitaciones de un uso, capacidades, revocación, deduplicación, cursor, TTL, errores de disco y reinicio; contratos/documentación API y pruebas de rechazo. |
| P0-08 | Operación del relay | TLS real en staging, política explícita de proxies confiables, rate limit por capacidad/borde, cuotas, recuperación y retención, monitoreo sin contenido ni identificadores innecesarios; sin escalar SQLite a múltiples workers por accidente. |
| P0-09 | Cadena de suministro | Revisar CVEs/licencias, fijar transitivas y hashes, Gradle verification metadata/wrapper verificado, SBOM, imágenes por digest y política de actualización. No declarar auditoría criptográfica a partir de un escáner. |
| P0-10 | UX completa del alcance base | Identidad, invitación, bloqueo, contactos/QR y verificación, chats, adjuntos, expiración, errores, conexión cercana y ajustes; accesibilidad, permiso denegado, rotación y proceso destruido; nada de mensajes de ejemplo disfrazados de datos reales. |
| P0-11 | Hardware y revisión independiente | Keystore y ciclo de vida en modelos compatibles; dos radios cercanas para las afirmaciones físicas Bluetooth; revisión independiente de protocolo de aplicación y hallazgos corregidos. Si falta recurso, mantener bloqueo explícito. |
| P0-12 | Release y operación | Firma fuera de Git/agentes, distribución y actualización verificables, reproducibilidad acordada, pruebas de release/R8/JNI, canal de vulnerabilidades, respuesta/rollback y criterios RELEASE_CHECKLIST cerrados con evidencia. |

## Recursos
Empezar con los recursos locales y CI del repositorio. No contratar, elevar presupuestos ni
activar nubes/device farms de pago por esta instrucción general. Antes de seleccionar un proveedor,
verificar disponibilidad, límites, confidencialidad y apoyo del caso Bluetooth/Keystore exacto.
No es necesario integrar SDKs de analítica en UMBRA para usar un laboratorio de pruebas.

## Funciones ampliadas, no implementar mediante atajos
Grupos, llamadas, video, iPhone, escritorio, múltiples dispositivos, recepción bloqueada,
Wi-Fi Direct, malla y recuperación de identidad no existen en la base. Crear ADR y pruebas
específicas antes de añadirlas; requieren decisiones de protocolo y alcance. No incluirlos como
terminados por tener pantallas, ni reducir silenciosamente la seguridad para igualar WhatsApp.

## Evidencia mínima por tarea
Commit probado, fecha, sistema/runtimes, herramientas/imágenes exactas, comandos y códigos de
salida, contadores reales, artefactos sin secretos, resultado esperado/observado, fallos y
limitaciones. Separar resultados unitarios, integración criptográfica, instrumentación,
emulación, hardware, carga, revisión estática y auditoría humana.
