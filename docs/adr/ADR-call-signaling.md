# ADR: señalización 1:1 autenticada, sin multimedia — 2026-09-21

Decisión: reutilizar Signal y sobres opacos v1; contenido v2 `call` con contrato
CALL_SIGNALING v1. El dispositivo iniciador selecciona atómicamente la primera
aceptación válida del conjunto aprobado. Los receptores esperan SELECT; nunca
infieren selección de ACCEPT. La selección persiste con su outbox y ratchet.
Un reinicio interrumpe, no reanuda ni elige otro ganador. No hay transferencia.

Amenazas: relay hostil, contacto autorizado abusivo, duplicados, demora, lista vieja,
reemplazo de identidad, bloqueo y disco fallido. Se fijan identidades, versiones de
roster, destinatarios y consentimiento. Un cambio de roster cancela conservadoramente
la sesión; no amplía destinos. Un administrador comprometido y rollback privilegiado
de toda la bóveda quedan fuera de la garantía. Bytes ya transmitidos no se retiran.

RELAY_ONLY predeterminado; DIRECT_ALLOWED queda reservado e INACTIVO en esta entrega; se rechaza local y remotamente. No hay ICE agent ni conexiones multimedia en
esta entrega. SDP/ICE son blobs acotados cifrados, ligados a generación, digest de
la descripción y huella SHA-256 declarada. No se escribe un parser SDP casero:
el futuro adaptador nativo mantenido debe parsear, comprobar TODAS las huellas y
el certificado DTLS efectivo, política ICE y datos de direcciones en SDP antes de
aplicar nada. El almacenamiento de señalización no acredita su validez multimedia.
Cambiar huella requiere una llamada nueva; restart/renegociación conserva huella,
incrementa generación y descarta candidatos previos. Solo iniciador ofrece.

Fuentes primarias consultadas:
- https://www.rfc-editor.org/rfc/rfc8827.html (identidad, consentimiento y DTLS).
- https://www.w3.org/TR/webrtc/ (iceTransportPolicy relay/all y candidatos).

TURN futuro revela IP/tiempos al operador, cuesta recursos y puede no estar disponible.
Sin TURN, RELAY_ONLY fallará cerrado; no elegimos servidores ni credenciales ahora.
P2P puede revelar IP al interlocutor. No anonimato, multimedia ni protección
poscuántica del audio demostrados. No micrófono, cámara, permisos nuevos o servicio
background. Offline rechaza llamadas, conserva ubicación/chat/RFCOMM.

El contrato TURN obligatorio, las API Android upstream comprobadas y la matriz de
pruebas reales pendientes se especifican en [CALL_SIGNALING](../protocol/CALL_SIGNALING.md).
No se instala un AAR WebRTC ni se despliega TURN en esta PR.
