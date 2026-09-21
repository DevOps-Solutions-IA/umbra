# VIDEO_MEDIA — especificación propuesta, aún no implementada

Fecha 2026-09-21. [ADR](../adr/ADR-webrtc-video.md).
CALL_SIGNALING/VOICE_MEDIA v1 siguen activos y rechazan video. Este documento NO
registra un protocolo ya desplegado ni un recorrido multimedia probado.

La extensión deberá versionar los controles de modificación de medios. No puede
agregar campos opcionales ambiguos a v1. Cada transcripción conserva contexto de
llamada, emisor/dispositivo autenticado, seleccionado, versiones de membresía,
eventId, changeId UUID aleatorio, generación, plazos y propósito específicos.
Identidad declarada debe coincidir con el contexto Signal validado por Engine.

## Estados del cambio propuestos

AUDIO_ONLY → REQUESTED → CONSENT_PENDING → CONFIRMED → NEGOTIATING → VIDEO.
Rechazo/timeout antes de negociar retorna a audio sin permiso de cámara. Fallo de
autorización, DTLS o transporte termina la llamada. El iniciador original arbitra
el único cambio pendiente y genera la oferta; una respuesta tardía no modifica
un cambio posterior. Request/confirm/SDP deben ligar exactamente el mismo changeId,
generación y direcciones aprobadas. No procesar ICE de otra generación.

Direcciones locales independientes send/receive, enteros booleanos estrictos o enum
canónico fijado por la implementación, nunca coerción de cadenas. La aceptación de
receive no concede send. Al apagar, detener capturador y envío antes de notificar;
rechazar callback anterior mediante lease/epoch local. Reactivar exige otra revisión
y confirmación, no un callback remoto. STOP de video no termina audio autorizado.

## Límites e invariantes que debe cerrar la implementación

- Mismos límites 60/180 s, generaciones 1–4, SDP ≤24.000 bytes, ICE ≤2.048 caracteres,
  32 candidatos/generación, 128 controles/sesión; no timers o colas ilimitados.
- Solo una pista audio y una video por extremo; sin pantalla o DataChannels.
- Cero ampliación de targets/membresía; cambios relevantes interrumpen la sesión.
- Consentimiento local ≤30 s y ligado al lease actual, precisión de propósito y
  dispositivo seleccionado. No autorización restaurable al reiniciar.
- Persistencia/ratchet/outbox atómicos, ciphertext inmutable por entrega; idempotencia
  por evento+digest; identificador repetido con contenido distinto rechazado.
- TURN exclusivamente local, RELAY antes de crear PC y durante cada modificación.
  DTLS efectivo coincide con huella autenticada, sin cambio de certificado implícito.
- Parser SDP nativo mantenido; no reparaciones textuales. Validar secciones/direcciones
  antes de habilitar captura. Las API inspeccionadas no acreditan esta validación.

Los nombres exactos de controles, esquema canónico y migración deben fijarse con
pruebas antes de habilitar v2. No aceptar v2 parcialmente ni reclasificar este diseño
como implementación. TLS/IPv6, codecs, frames remotos, cámara AVD, cancelación medida
y recorrido R8 requieren recibos de ejecuciones propias.
