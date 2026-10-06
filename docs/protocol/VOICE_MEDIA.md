# VOICE_MEDIA — contrato en implementación

Estado: PARTIAL; pipeline sintético bidireccional ejecutado en debug; [ADR](../adr/ADR-webrtc-voice.md).
No se modifica CALL_SIGNALING v1 ni se amplían plazos. El contenido SDP/ICE sigue
cifrado por Signal, ligado a callId, dispositivo seleccionado y generación.

El contrato del adaptador requiere autorización vigente del mismo CallService,
selección confirmada, consentimiento local, confianza/roster vigentes y TURN local.
Antes de ofrecer/aplicar SDP, candidato o capturar/reproducir se revalida sesión;
no basta que el peer declare identidad, huella o `typ relay`.

Política única: RELAY_ONLY, aplicada a la configuración ICE nativa antes de crear
PeerConnection. Revisión local inmutable y credenciales temporales; renegociación,
reconexión o ICE restart no activan ALL ni cambian TURN. Expirar credenciales
localmente invalida la autorización: no se presume que coturn destruya por ello
las asignaciones ya abiertas. El propietario debe cerrar los recursos nativos.

Mensaje de fallo: «No se pudo establecer la conexión privada mediante el
retransmisor.» Errores y logs no contienen SDP, tokens, contraseñas ni direcciones.

Criterios de aceptación (el informe fechado distingue cada ejecución):
1. Dos Engines/identidades verificadas, INVITE/ACCEPT/SELECT por relay HTTPS y Signal.
2. SDP generado/parseado por WebRTC nativo; digest y certificado DTLS efectivo ligados
   al dispositivo/generación autenticados. Sustituciones deben fallar.
3. TURN real autenticado y acotado; par ICE relay/relay en ambos extremos. Inspección
   de señalización y observación de red, incluyendo ruta directa disponible y caída
   TURN sin fallback, no solo firewall que impida P2P.
4. PCM sintético diferente en ambos extremos, codificado/decodificado por pipeline
   real; criterio de detección tolerante a pérdidas. Paquetes o ICE no prueban audio.
5. Mute/unmute, revocación, bloqueo/permiso perdido, expiración, fin y cleanup. Ninguna
   señalización dispara captura de ubicación ni amplía sus destinatarios.
6. JNI/R8 y recorrido release separado de pantalla bloqueada; límites físicos/IPv6
   explícitos. Sin prueba acústica ni Keystore hardware por inferencia desde AVD.

El adaptador connected integrado obtiene un lease de `prepareMedia(consent,true)`;
el método legado sin consentimiento no habilita media. ACTIVE es estado nativo local
posterior a DTLS/huella/par relay; el registro de señalización permanece NEGOTIATING,
por lo que un reinicio no restaura ACTIVE ni la captura.

Contrato v1 de esta implementación: solo una sección audio, oferta del iniciador,
respuesta del seleccionado, generación inicial 1, reunión inicial de candidatos sin
pool previo. El SDP nativo incluye candidatos relay; no se reescribe. ICE remoto solo
se aplica tras la descripción y con mid/generación autorizados. Deadline inicial 30 s,
credenciales <=180 s monotónicos y sesión CALL_SIGNALING <=180 s. DISCONNECTED/FAILED,
permiso ausente, autorización perdida o generación nueva cierran la media: reconexión
requiere nueva sesión explícita, nunca ALL/P2P. Expirar credenciales localmente cierra
PeerConnection; no constituye prueba de eliminación inmediata de asignaciones coturn.

Laboratorio PCM: 48 kHz, tonos diferentes 1/2 kHz, >=100 buffers reconocidos por extremo,
>=50 paquetes inbound audio y codec Opus. Correlación espectral normalizada >0,55 y
energía media >100000; no igualdad byte a byte ni prueba acústica física. Los contadores
solo complementan el análisis de PCM decodificado. No hay video, transferencia, TURN
productivo ni interfaz definitiva.

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

## Extensión video v2 en validación

[VIDEO_MEDIA.md](VIDEO_MEDIA.md) define los controles v2 y su consentimiento
direccional. El establecimiento inicial de voz permanece v1. Un cliente antiguo
rechaza v2; no se interpreta como texto ni como una descripción de voz. La extensión
no amplía los 60/180 segundos, no activa DIRECT_ALLOWED ni permite captura remota.
Las afirmaciones históricas de solo voz anteriores describen la entrega previa;
la evidencia de video y sus bloqueos se registran por separado.
