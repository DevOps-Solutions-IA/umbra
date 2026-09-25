# Seguridad y límites de UMBRA 0.2

**No utilizar para secretos reales hasta cerrar las pruebas y la auditoría pendientes.** Esta entrega no está certificada, no es invulnerable y no tiene comparación de superioridad frente a otros mensajeros.

## Activos y adversarios considerados

Se pretende proteger contenido, claves de protocolo y almacenamiento local frente a observación de red, alteración de mensajes, suplantación de claves/contactos no verificados, consultas a archivos privados sin su clave de protección y fallos accidentales de ciclo de vida. Se añaden límites frente a entradas malformadas y agotamiento de recursos. Revisar las medidas concretas en `HARDENING_0_2.md`.

No se asegura protección de plaintext frente a un sistema operativo, teclado, servicio de accesibilidad autorizado, destinatario o dispositivo comprometido; extracción de memoria en una sesión abierta; una cámara externa; rollback privilegiado del almacenamiento; análisis de tráfico; interferencia Bluetooth; vulnerabilidades desconocidas de Android, JNI, bibliotecas o este código. Una clave protegida en hardware no impide por sí sola el abuso del proceso autorizado para utilizarla.

## Política de fallo

Fallo de autenticación, firma, contexto, esquema, claves, almacenamiento o autorización no debe producir texto sin verificar ni reinicializar automáticamente la identidad. Las claves perdidas no se reemplazan para intentar leer una bóveda existente. Los errores locales de espacio o de acceso no se confunden con autorización para borrar un mensaje remoto. No hay claves maestras, acceso administrativo a plaintext ni recuperación implementada.

## Exposición que persiste

SQLite local muestra categorías y tamaños aunque los valores y nombres lógicos se protejan. El relay observa sobres, identificadores de encaminamiento y tiempos. Su proxy y alojamiento pueden observar IP y metadatos. La vinculación Bluetooth comparte una tarjeta privada con alias y datos de contacto; verificar humanamente el código antes de conversar. Los exportes están fuera de la bóveda. El borrado lógico, `secure_delete` y limpiar arrays no garantizan borrado físico o forense.

## Cadena de suministro y pruebas pendientes

Las dependencias conservan versiones explícitas, pero el grafo transitorio, hashes de artefactos, CVEs, SBOM, tags de imágenes/acciones, shrinker/JNI y firma de publicación requieren cierre. No basta que libsignal tenga documentación pública para considerar segura su integración. No inventar criptografía para evitar resolver esas dependencias.

`test_local.sh` ejecuta únicamente pruebas verificables en este entorno. `build_android.py` no tiene un camino que simule éxito si falta SDK. `check_merged_permissions.py` falla sin salidas reales. `RELEASE_CHECKLIST.md` define los criterios restantes, todos pendientes salvo la evidencia local expresamente registrada.

## Reporte y operación

No existe todavía un canal de vulnerabilidades de producción configurado. El propietario deberá establecer uno, con responsables de respuesta y actualizaciones. No enviar secretos, tokens de buzón ni claves privadas en reportes. Conservar un caso de reproducción mínimo con identidades sintéticas y comunicar el hash de la revisión probada.


## Identidad y emparejamiento v1 — bloque de 2026-09-20

La nueva invitación es un secreto bearer firmado, no un directorio ni una prueba
humana. El QR compacto contiene clave pública, capacidad y tiempos; quien obtenga
el archivo puede intentar consumirlo primero. Request/ack revelan tarjetas a los
participantes y generan transcripciones firmadas transferibles. No extender a
esas transcripciones las propiedades de deniability de mensajes libsignal.

El relay persiste hashes y un digest de solicitud, nunca tarjetas ni nuevas claves
privadas. Ve capacidades durante las solicitudes HTTPS y puede correlacionar
creación/consumo/IP/tiempos. Un relay hostil puede negar servicio; no puede marcar
contactos verificados ni hacer que se acepte una firma inválida. El consumo local
con contacto y confirmación comparte transacción; reintentar el mismo transcript
es idempotente, otro solicitante pierde. Revocar después de vincular no deshace
el contacto: bloquearlo es una operación distinta.

VERIFIED_ONLY se aplica a texto y archivos en Engine, incluidas llamadas directas.
Una sustitución requiere suspender la identidad anterior y verificar explícitamente
la nueva; el alias no transfiere confianza. Invitaciones de tarjetas históricas y
vinculación Bluetooth siguen disponibles por compatibilidad: no se reclasifican
como invitaciones de un uso. Los dos extremos necesitan verificación humana.

Ver [identidad](adr/ADR-identity-model.md), [metadatos](adr/ADR-metadata.md),
[recuperación](adr/ADR-recovery.md) y [protocolo](protocol/PAIRING.md).
Dispositivos, ubicación y llamadas aún no forman parte del bloque implementado.


## Dispositivos v1 — segunda entrega

[ADR de autoridad](adr/ADR-device-model.md) y [protocolo](protocol/DEVICE_LINKING.md).
La lista firmada no equivale a verificación humana: agregar una clave exige aprobar
el conjunto completo desde una raíz ya verificada. Engine rechaza claves revocadas
incluso por APIs de tarjetas/Bluetooth antiguas. A1 administra y cada dispositivo
tiene claves/ratchets propios; el relay no concede pertenencia. Retirar A1 es terminal.

La delegación de borrado de buzón se transmite cifrada y no concede lectura. Antes
de recibirla no se promete revocación remota. Un cambio local no retira bytes ya
emitidos ni copias recibidas. Clientes desconectados aplican cambios al aprenderlos;
listas caducadas requieren renovación/importación. No hay detección global de
forks/retención ni defensa contra rollback privilegiado de toda la bóveda.

Los harnesses usan identidades sintéticas. SQLite de androidTest se ejecuta en UID
debug `.dev`, distinto de release y en directorio sintético exclusivo y está ausente de release; no rebaja Keystore. Véase el [informe](validation/2026-09-20-device-linking-core.md)
para distinguir integración ejecutada de hardware y auditoría pendientes.


## Ubicación v1 — tercera entrega

[ADR](adr/ADR-location.md), [protocolo](protocol/LOCATION.md) y
[evidencia](validation/2026-09-21-encrypted-location-core.md).
Ubicación es contenido sensible Signal, con VERIFIED_ONLY, consentimiento local,
leases y dispositivos destinatarios fijos. Firma/autenticación no prueba dónde
está una persona. El receptor y un OS comprometido pueden conservar/copiar puntos.
Las celdas reducen detalle; trayectorias repetidas pueden seguir siendo identificables.
No hay mapas ni coordenadas/tipo en claro en el relay; IP, tiempos, tamaños y patrones
de conexión siguen visibles. No se promete anonimato ni borrado de copias remotas.

Captura visible solo mientras la bóveda está desbloqueada y Activity en primer plano;
pausa, expiración, permiso/proveedor perdido, revocación o confianza suspendida detienen.
Reabrir exige consentimiento nuevo. Solo COARSE/FINE añadidos, no BACKGROUND_LOCATION,
servicios de captura, micrófono o cámara. Offline sigue sin permisos de red; el SO
puede usar sus servicios de posicionamiento independientemente. Pruebas sintéticas
Android/JVM no acreditan GPS ni Keystore hardware; revisión independiente pendiente.


## Señalización 1:1 v1 — cuarta entrega

[ADR](adr/ADR-call-signaling.md), [protocolo](protocol/CALL_SIGNALING.md),
[evidencia](validation/2026-09-21-authenticated-call-signaling.md).
El iniciador selecciona un receptor del conjunto consentido mediante Signal y
persistencia transaccional. Aceptar no activa media ni eleva confianza. Versiones
de membresía, identidad y lease se revalidan; reinicio/bloqueo no reanudan llamadas.
RELAY_ONLY único modo habilitado, sin downgrade por control remoto o error TURN.
El contrato no demuestra tráfico exclusivamente TURN: adaptador/WebRTC real queda
pendiente. Sin SDP/ICE/callId en claro en relay, pero persisten metadatos de buzones,
IP, tiempos y tamaños. TURN no ofrece anonimato frente al operador. Sin secretos
TURN en APK ni claves multimedia derivadas/exportadas del ratchet. Offline rechaza
llamadas y conserva chat/ubicación/RFCOMM. No nuevos permisos micrófono/cámara/red.

## Voz nativa — quinta entrega en curso

Connected incorpora WebRTC fijado y TURN obligatorio antes de crear PeerConnection.
Consentimiento de media ligado a CallService/selección/lease; DTLS-SRTP y comparación
del certificado remoto efectivo con huella autenticada por Signal antes de habilitar
pistas. Bloquear/cancelar invalida captura; no recuperación automática ni llamadas
prolongadas en segundo plano. Fallar conectividad no activa P2P. Offline no incorpora
WebRTC, RECORD_AUDIO ni MODIFY_AUDIO_SETTINGS. No hay cámara.

La evidencia de audio es sintética en AVD con SQLite de laboratorio, no micrófonos ni
Keystore hardware. El operador TURN sigue viendo direcciones/tiempos/volumen. Pruebas
adversariales completas de red, IPv6, revocación durante audio y release necesitan sus
resultados específicos; ver [informe](validation/2026-09-21-turn-voice-core.md).

## Autorización de destinos TURN (2026-09-21)

La distribución Maven inicial seguía TURN `300 ALTERNATE-SERVER` a un destino no
autorizado (ocho paquetes reproducidos). Se sustituyó por una compilación fijada con
rechazo nativo antes de modificar el destino. Las cuatro ABI compilaron en Actions
35577083313. En dos AVD x86_64: nueve peticiones al TURN autorizado, cero al destino
alternativo; el mismo AAR conservó Opus bidireccional, mute y rechazo DTLS adulterado.
No extrapolar esta observación IPv4/UDP a otras familias o hardware.

El SHA del AAR revisado es `bbc5675f91b31f901e1a482b00991a36ac2b3d912d2782b80e1cc1b756b1c413`.
Gradle, la guarda de repositorio y `NativeDistributionPolicy` fijan su integridad.
Un reemplazo de dependencia no hereda esa capacidad automáticamente. La entrada
productiva sigue exigiendo permiso, consentimiento, selección, verificación, lease
vigente y comprobación DTLS nativa; el hash no autoriza una llamada por sí solo.
Pruebas de micrófono/hardware, IPv6, TURN TLS y recorrido de voz R8 quedan pendientes.

## Regresión R8 detectada al iniciar video (2026-09-21)

El primer recorrido de voz con código optimizado abortó al cargar WebRTC porque
R8 eliminaba `org.jni_zero.JniZero`, fuera de la regla org.webrtc. El APK release
anterior también carecía de esa entrada. Se conserva ahora únicamente la superficie
anotada CalledByNative de JNI Zero y se exige su presencia en el DEX connected.
El primer audio sintético R8 pasó con Engine/Signal/HTTPS/TURN en dos AVD; el arnés
separado conserva las APIs que referencia, con cuerpos/nombres optimizables.
No equivale al APK productivo exacto, micrófono físico, video o Keystore hardware.
Consultar [evidencia](validation/2026-09-21-turn-video-core.md) para CI y pendientes.

## Extensión de video en validación (2026-09-21)

La propuesta remota no autoriza captura local. Las direcciones enviar/recibir se
consienten por separado y quedan ligadas al cambio/generación/dispositivo confirmado.
Apagar invalida captura antes de persistir STOP; un fallo de disco no debe mantener
la cámara activa. Reactivación exige consentimiento nuevo; bloqueo/caducidad no se
renuevan. No se añaden permisos ni WebRTC a offline.

Una superficie remota puede estar atrasada: se distingue último frame del estado de
transporte. No hay grabación ni persistencia de imágenes; esto no impide copias por
el receptor. TURN/ISP siguen viendo metadatos. Las pruebas locales sintéticas no
validan cámaras físicas, Keystore hardware ni todas las familias/transporte de red.
Ver evidencia de fallos, límites y resultados parciales en la sexta entrega.

La señalización ICE incremental conserva el digest de descripción y la generación;
no permite cambiar TURN ni la política RELAY. La autorización de captura exige
relay en ambos candidatos seleccionados y certificado DTLS efectivo autenticado.
Las pruebas de huella exigen al menos un rechazo nativo por binding y cero captura
en ambos extremos; la cancelación del otro extremo no se presenta como una segunda
comprobación de certificado. Timeout o falta de evidencia no cuentan como éxito.

## Evidencia posterior de video — 2026-09-22

Código `171324bf` pasó las matrices debug/R8 y regresiones en Actions35661260991,
35661261035 y35661261036. Ver [aceptación delimitada](validation/2026-09-22-turn-video-acceptance.md).
TURN/TLS y trayecto cliente-TURN IPv6 tienen evidencia nueva; no se extiende a
asignaciones relay IPv6, hardware físico o APK productivo exacto. El cierre de
superficies ya no depende del listener reemplazable; los límites de cancelación
medidos no demuestran retiro de paquetes ni borrado de copias del destinatario.

## Modulación local de voz (entrega en curso)

El efecto modifica el timbre saliente, no acredita anonimato ni impide reconocer
a la persona. PCM natural existe transitoriamente en captura/APM; no se conserva
un historial ni se promete borrado forense de RAM. TURN, DTLS, libsignal, selección
de dispositivo, permisos, autolock y límites de sesión no cambian. Error del
procesador silencia; OFF desde MODULATED requiere confirmación local y no quita
mute. El límite atómico protege bloques admitidos tras el cambio; audio ya
encolado en Opus o en tránsito puede llegar después. El receptor puede guardar
lo que recibe. Ver [ADR](adr/ADR-voice-modulator.md) y
[contrato](protocol/VOICE_PROCESSING.md). Pruebas remotas y R8 deben constar en
evidencia propia del commit; una prueba de DSP no certifica el pipeline completo.
