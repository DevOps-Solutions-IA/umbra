# UMBRA CALL_SIGNALING v1 — señalización, no multimedia

[Decisión y amenazas](../adr/ADR-call-signaling.md). Connected exclusivamente.
Libsignal cifra cada entrega con sesión/destino independientes. Sobres del relay
v1 sin campos nuevos. Contenido Signal v2 `kind=call`, `logicalId=event`,
`logicalFrom/logicalTo` contrastados con DevicePolicy y contexto autenticado.

## Formato estricto

Objeto `call` con exactamente `v=1`, `purpose=UMBRA-CALL-SIGNALING`, `context`,
`type`, `event`, `device`, `selected`, `generation`, `policy`, `data`.
JSON UTF-8 sin duplicados; enteros reales, no coerción, campos extra rechazados.
Máximo 40.000 caracteres y límites específicos de bytes SDP. UUID canónicos
aleatorios callId/event; identidades y digest SHA-256 son 64 hex minúsculos.

Context contiene exactamente:
- callId; caller (identidad lógica); callerDevice; callee (identidad lógica).
- targets: 1–8 dispositivos de callee aprobados, ordenados, sin repetidos.
- callerVersion/calleeVersion: versiones de listas autenticadas al consentir.
- created, inviteUntil=created+60, ends=created+180: segundos UTC.

Un cambio de cualquiera de las listas interrumpe conservadoramente la sesión.
No se hereda autorización por alias ni se amplía targets. La verificación humana,
la aprobación del conjunto y el lease deben seguir válidos. El dispositivo emisor
se contrasta con `from`; lógica declarada no concede autenticación. Ingress a
CallService requiere un token intransferible que solo Engine emite tras descifrar.

`device`: emisor de ese control. `selected`: vacío antes de selección, Device ID
seleccionado después. `generation`: 0 antes de negociar, 1–4 durante negociación.
`policy`: únicamente RELAY_ONLY en esta entrega. DIRECT_ALLOWED reservado pero
rechazado tanto por API local como desde el peer. No hay campo de servidor remoto.

| type | Datos / efecto |
|---|---|
| INVITE | data vacío; crea INCOMING únicamente desde callerDevice; OUTGOING local |
| ACCEPT | data vacío; consentimiento explícito, estado ACCEPTING hasta SELECT |
| SELECT | callerDevice elige primer ACCEPT válido en una transacción; SELECTED en ganador, NOT_SELECTED en otros |
| REJECT / BUSY | rechazo de un dispositivo; todos rechazados terminan llamada, no descartan otro receptor disponible |
| CANCEL | solo iniciador; terminal CANCELLED, incluso si llega antes de INVITE |
| DESCRIPTION | data: role offer/answer, sdp, digest, fingerprint; pasa a NEGOTIATING, nunca ACTIVE |
| ICE | data: candidate, description (digest), mid; solo descripción/generación/autor correctos |
| END | solo interlocutores seleccionados; terminal ENDED |

SDP es un blob opaco ≤24.000 bytes con digest SHA-256 exacto, huella DTLS SHA-256
declarada y rol. Candidato ≤2.048 caracteres y mid 1–32. No se interpreta SDP con
regex; tampoco se presenta un blob almacenado como SDP semánticamente válido.
El adaptador nativo futuro deberá comprobar parser, huellas declaradas frente a
TODAS las huellas SDP y certificado remoto efectivo, privacidad y tipos ICE antes
de aplicar el blob. `verifyRemoteBinding` prueba ligadura de digest/huella/generación;
no establece DTLS. Fixtures sintéticos no son ofertas multimedia ejecutadas.

Solo callerDevice ofrece; respuesta del seleccionado para la generación corriente.
Generación siguiente requiere oferta/respuesta anterior completas, limpia candidatos
y conserva huellas de ambos extremos. Una huella distinta exige sesión/consentimiento
nuevos. ICE anterior a su descripción se rechaza, sin buffer ilimitado. Reintento
puede llegar después de la descripción y conserva ciphertext. Máximo 32 candidatos
por generación, 128 controles emitidos y 128 eventos recibidos por sesión.

## Persistencia, concurrencia y relojes

Un único intento no terminal por dispositivo. Invitaciones cruzadas devuelven BUSY
a ambos: no hay dos canales incompatibles ni transferencia implícita. Selección es
exclusiva del iniciador, no del relay. Primer ACCEPT que confirma transacción gana;
fallo de almacenamiento revierte selección, ratchet, outbox y ACK. El receptor espera
SELECT; si se pierde, solo retransmisión idéntica o timeout. Un ACCEPT tardío jamás
reemplaza al ganador. Caída del iniciador termina el intento, no elige otro ganador.

Consentimiento revisado ≤30 s, ligado a servicio/lease/conjunto/propósito y consumible.
Toda API revalida dentro de transacción, después de esperar su lock. Reloj monotónico
local acota review, invitación y sesión; reloj UTC acota sobres y comparación remota.
Desfase futuro tolerado 30 s; retroceso local anterior a created−30 interrumpe. Una
actualización/negociación no extiende ends. Reiniciar/force-stop/lock no restaura
leases; primer acceso cancela colas antiguas y marca FAILED o EXPIRED. No timbres ni
captura automáticos en segundo plano. La UI muestra solo estado de señalización.

Cada control usa event UUID, un envelope UUID/ciphertext por destinatario. Colisión
de evento con contenido distinto se rechaza mediante hash canónico; duplicados no
repiten efectos. Los estados terminales no reviven. Retención de tombstones hasta
ends+7 días+300 s, máximo128 sesiones; saturación falla cerrado. Los terminales
eliminan SDP/ICE, conservan contexto y digests para replay. Sin historial central.

TTL de cada entrega ≤30 s y ≤ends; INVITE/ACCEPT/SELECT también ≤inviteUntil.
Relay aplica expires opaco con sus cuotas existentes, sin aprender el tipo. Cliente
revalida justo antes de escribir, nunca sube una invitación vencida. No se muta ni
recicla ciphertext. Cancelar descarta pendientes sin rebobinar ratchet. Lo ya emitido
no se puede retirar: el peer depende de CANCEL/END o plazos de 60/180 s.

Bucket `calls` aditivo en Records cifrados de producción, sin cambiar identidad,
ratchets, SQLite relay o permisos APK. Las pruebas SQLite usan adaptador solo en
androidTest y datos sintéticos; no sustituyen Vault/Keystore hardware.

## TURN obligatorio: contrato de la siguiente entrega

RelayOnlyContract conserva una lista LOCAL inmutable de 1–4 URI TURN autorizadas
con puerto explícito y revisión de configuración. No recibe URLs de señalización.
Falta de TURN, autenticación, asignación, conectividad, revisión distinta o política
directa producen fallo terminal. Mensaje funcional:
“No se pudo establecer la conexión privada mediante el retransmisor.”
`prepareMedia` falla también con configuración válida: esta entrega no incluye
adaptador nativo y no puede declarar audio/video activo. No hay fallback P2P.

API Android upstream consultada: `org.webrtc.PeerConnection.RTCConfiguration`
contiene `iceTransportsType`, enum `PeerConnection.IceTransportsType.RELAY`.
El constructor upstream usa ALL por defecto: el futuro adaptador deberá asignar
RELAY explícitamente ANTES de crear PeerConnection; conservarlo al reconfigurar,
reconectar, cambiar red, renegociar y reiniciar ICE. No se copia la opción JavaScript
como si fuera una API Java. Fuente consultada: [PeerConnection.java](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java),
blob `e87df0054c7cc7c204bad92362fb2643bc468f9d`. AAR/JNI todavía NO seleccionado,
instalado ni ejecutado: esta es preparación de contrato, no configuración aplicada.

Antes de consentir no recopilar candidatos/conectar media. Aceptar solo TURN locales,
credenciales temporales en memoria, validación TLS, sin secretos maestros en APK.
Nunca exportar claves Signal para media. TURN no es un extremo de DTLS-SRTP; TLS
hacia TURN no sustituye cifrado extremo a extremo. No se afirma PQ para audio.

Pruebas posteriores obligatorias, NOT EXECUTED aquí:
- Adaptador mantenido parsea SDP/ICE y verifica salida sin IP directas ni direcciones
  relacionadas; no reemplazos improvisados de SDP. Bloquear emisión si no se acredita.
- Par ICE seleccionado/stats exclusivamente relay, más observación de red que detecte
  tráfico directo (incluidas comprobaciones ICE). IPv4 e IPv6 cuando sea ejecutable.
- Caída/autenticación/asignación TURN, cambios de red y restart sin fallback ALL/P2P.
- Reintentos acotados solo con TURN previamente autorizados; no descubrimiento remoto.
- Certificado DTLS coincide con huella autenticada y descripción/generación exactas.

RELAY_ONLY evita exponer direcciones directas al interlocutor cuando el adaptador lo
cumpla; la señalización sola no acredita esa propiedad. Operador TURN/ISP/relay ven
IP, horarios y volumen. Logs/retención TURN requieren política separada. No anonimato.
