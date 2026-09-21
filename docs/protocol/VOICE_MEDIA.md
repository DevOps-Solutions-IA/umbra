# VOICE_MEDIA — contrato en implementación

Estado: NOT EXECUTED como llamada; [ADR](../adr/ADR-webrtc-voice.md).
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

Criterios aún pendientes de ejecución:
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

No señalizar ACTIVE ni habilitar `prepareMedia` hasta integrar y verificar el
adaptador. No hay video, transferencia, TURN productivo ni UI definitiva.
