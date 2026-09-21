# ADR — extensión de video 1:1 (2026-09-21)

Estado: IMPLEMENTACIÓN EN VALIDACIÓN; aceptación final y CI del código de video pendientes.
Ver [trabajo y resultados](../validation/2026-09-21-turn-video-core.md).

## Base que se conserva

La misma identidad, autorización DevicePolicy, CallService, Engine, Signal, relay
HTTPS y PeerConnection. Solo connected; offline no recibe permisos CAMERA/audio/red
ni WebRTC. TURN local autorizado y política nativa RELAY exclusivamente, incluyendo
el rechazo de ALTERNATE-SERVER antes de I/O. No conexión secundaria ni DataChannels.
Invitación 60 s, llamada 180 s, primer plano y bloqueo actual, sin renovaciones.

Video requiere otra autorización explícita: aceptar audio o ver video no autoriza
capturar ni enviar cámara. Un mensaje remoto puede proponer un cambio, nunca arrancar
captura, elevar confianza, cambiar TURN o ampliar destinatarios de ubicación.

## Decisión

Extensión versionada y negociada sobre los controles cifrados existentes. Mantener
la llamada inicial de voz y agregar como máximo un transceiver video. El iniciador
original sigue siendo la única autoridad de ofertas SDP; ambos extremos pueden
solicitar un cambio. Debe arbitrar una solicitud por vez y confirmar un identificador
de cambio y generación únicos. Una petición concurrente pierde explícitamente y
necesita nuevo consentimiento; no se mezclan direcciones de dos solicitudes.

Cada extremo consiente por separado enviar y recibir. La autorización efectiva
de A→B es A.send Y B.receive; la de B→A es B.send Y A.receive. Los permisos nunca
se deducen del alias, de una pista remota o de SEND_RECV declarado en SDP.
Solicitud/revisión local ≤30 s, plazo además acotado por el fin original. Guardar
transcripción/digest/estado con outbox y ratchet; no persistir un lease de captura
que pueda sobrevivir a bloqueo o reinicio.

Solo después de completar la nueva oferta/respuesta autenticadas, comprobar la
generación, DTLS efectivo, par relay y autorizaciones vigentes se permite capturar.
La huella DTLS permanece igual; un certificado diferente exige sesión nueva.
Generaciones máximas existentes 1–4: no ampliarlas para una demostración. Apagar
cámara es inmediato y local, aunque falle el control remoto. Reactivar requiere
otro consentimiento y cambio confirmado; si se agotaron generaciones, rechazar.

Cliente de voz anterior conserva el flujo v1. Una extensión desconocida se rechaza
explícitamente por versión/tipo; nunca se interpreta como DESCRIPTION v1 o texto.
La sección audio sigue siendo única. La extensión permite una sección video
autorizada; el parche del parser nativo rechaza DATA, secciones repetidas y varias
pistas por sección antes de aplicar la descripción. Su reconstrucción está pendiente.

## Adaptador y superficie

APIs inspeccionadas en la fuente WebRTC fijada 73cb818: Camera2Enumerator,
CameraVideoCapturer.startCapture/stopCapture/switchCamera, VideoSource,
VideoTrack.addSink/removeSink y factorías de codec de PeerConnectionFactory.
Inspección de API NO demuestra codecs presentes ni frames ejecutados.
Perfil inicial 320×240 a 15 fps, máximo 400 kbit/s de video; factorías software de la
revisión fijada. La captura sintética entra antes del encoder nativo y el analizador
remoto consume frames decodificados con patrones distintos por lado. Las ejecuciones
locales debug y R8 están registradas por separado y no validan el futuro commit final.

Captura, consentimiento y transporte separados de Activity. Un sink visible mínimo
debe reflejar antigüedad del frame; limpiar superficie al apagar/bloquear/terminar.
No guardar frames, miniaturas ni trazas de SDP/credenciales. No webcam/micrófono del
host en pruebas. No prometer impedir capturas/copias hechas por el destinatario.

## Aceptación todavía pendiente

Consentimiento directo por API, simultaneidad, generación anterior, cámara denegada,
revocación, callbacks tardíos, cierre contra cambio de cámara, disco lleno/rollback;
frames y audio bidireccionales reales en debug y R8; cámara sintética AVD separada;
red IPv4/IPv6 y UDP/TLS por combinación, caída/redirección TURN sin P2P; cancelación
desde evento monotónico explícito. TLS a TURN no sustituye DTLS-SRTP extremo a extremo.
No anonimato frente a TURN/ISP/señalización. Hardware y auditoría siguen pendientes.
