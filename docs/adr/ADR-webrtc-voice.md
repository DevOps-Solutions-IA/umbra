# ADR — WebRTC Android y voz privada 1:1

Fecha: 2026-09-21. Estado: EN IMPLEMENTACIÓN; no acredita todavía audio ni TURN.
Base: PR #7, `e495793ba191cce0523db243ab7baac55389ef64`. No sustituir Signal ni
crear otra política de identidad. CALL_SIGNALING conserva consentimiento, selección,
versiones de roster, invitación de 60 s y sesión de 180 s. Video queda fuera.

## Distribución examinada

Se fija `io.github.webrtc-sdk:android:150.7871.01` de Maven Central, únicamente en
connected. [Distribuidor](https://github.com/webrtc-sdk/android/tree/7b6390fb098303b31af76906bf15f7decaa4ef95),
[fuente declarada por su changelog](https://github.com/webrtc-sdk/webrtc/tree/73cb8180f7258ee292878d6edd05177f41883962).
Es una distribución de un fork de upstream WebRTC, no un artefacto oficial Google.
El proceso del distribuidor importa artefactos de `webrtc-sdk/webrtc-build` y
publica el AAR; su script ajusta la versión del bytecode Java antes de Maven.
No se presume que el AAR de GitHub sea idéntico al de Maven ni que un hash pruebe
una compilación reproducible. No hemos reconstruido Chromium/WebRTC desde fuente.

[Inventario fijado](../../android/webrtc-artifact.json): SHA-256 del AAR, POM,
classes.jar, manifest y JNI arm64-v8a/armeabi-v7a/x86/x86_64. El POM no declara
dependencias transitivas Maven. El binario incorpora dependencias nativas upstream;
esto NO equivale a un SBOM completo. Licencia BSD-3-Clause, licencias de dependencias
y PATENTS upstream requieren inventario de distribución antes de publicar.

No cambiar JDK21, Gradle8.13, AGP8.13.2, min31 ni compile/target36 por esta integración.
Gradle verifica el digest antes de construir connected; la guarda comprueba el
inventario y los hashes JNI del APK final, además de ausencia Java/JNI en offline.
Compatibilidad de compilación/R8 y ejecución se registra en evidencia, no se infiere
solo de estas versiones. Actualización requiere revisión del changelog/fuente,
nuevo inventario, repetir pruebas de rechazo, audio, red, JNI y release; no `latest`.

## Motor y laboratorio

La API REAL examinada del AAR es `PeerConnection.RTCConfiguration.iceTransportsType`
y `IceTransportsType.RELAY`. Se asigna antes de crear PeerConnection y en cada
configuración nueva; pool de candidatos cero, TLS_CERT_POLICY_SECURE, TURN locales
inmutables. DIRECT_ALLOWED sigue rechazado. Ningún mensaje remoto proporciona URLs.
`TurnConfiguration` acota credenciales a 180 s mediante monotónico, invalida ante
rollback local, expiración, cierre, revisión distinta o fallo; no renueva por restart.
Liberar referencias Java no borra copias nativas: el adaptador debe cerrar y disponer
PeerConnection al invalidar. No confundir esta clase con autorización del Engine.

La fuente fijada ofrece `JavaAudioDeviceModule.setAudioRecordEnabled(false)`,
`Builder.setAudioBufferCallback` y `setPlaybackSamplesReadyCallback`. El laboratorio
puede inyectar PCM antes de codificar y analizar PCM decodificado sin micrófono.
Esos callbacks y generadores sintéticos deben quedar exclusivamente en androidTest;
la ruta productiva usa AudioRecord/AudioTrack soportados por WebRTC, con consentimiento.
No hay prueba de pipeline por inspeccionar estas API.

El allocator nativo, `p2p/base/port_allocator.cc:315`, limpia la dirección relacionada
TURN si el filtro no admite reflexive. Es evidencia de diseño upstream; falta comprobar
salida real SDP/ICE, estadísticas y tráfico. No se modificará SDP mediante reemplazos.
El parser mantenido y la verificación DTLS nativa deben rechazar descripciones inválidas.
Antes de activar audio debe contrastarse certificado efectivo con huella autenticada
por UMBRA. Un callback de verificación TLS no se presume equivalente a verificación
DTLS: hay que seguir su uso nativo antes de emplearlo.

## Amenazas y límites

Contacto/relay hostil, candidatos adulterados, consentimiento viejo, nueva membresía,
revocación, fallo de disco, asignación/credencial vencida, TURN caído y cambios de red.
No habilitar media con mero SELECT/ICE_CONNECTED. Sin TURN, fallar sin P2P ni servidores
alternativos no autorizados. No exportar ratchets o claves Signal para media.
DTLS-SRTP es distinto de TLS hacia TURN; no se afirma audio poscuántico.

TURN observa IP/horarios/volumen. RELAY_ONLY busca evitar exposición de direcciones
directas al interlocutor, no anonimato frente a TURN/ISP/señalización. Bytes ya enviados
no se retiran al revocar. Captura y canal se detienen al bloquear/pausar; no reanudación
tras reinicio, permisos o force-stop. Micrófono/cámara reales y tráfico ajeno prohibidos
en pruebas automáticas. Offline no incorpora dependencia ni permiso de micrófono.

Pendiente: coturn fijado/aislado, proveedor temporal ejecutado, integración Engine,
consentimiento Android, pipeline bidireccional, red sin fallback, IPv6 según entorno,
release ejecutado y revisión independiente. No declarar este ADR entrega funcional.
