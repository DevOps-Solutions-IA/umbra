# ADR — WebRTC Android y voz privada 1:1

Fecha: 2026-09-21. Estado: EN IMPLEMENTACIÓN; audio sintético integrado ejecutado, aceptación completa pendiente.
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

## Integración implementada en este bloque

`NativeVoiceSession` reside solo en connected. `CallService.reviewMedia/prepareMedia`
entrega un lease de consentimiento de un uso (revisión <=30 s, sesión desbloqueada,
selección y contexto exactos). El método antiguo sin consentimiento sigue rechazando.
El lease se invalida con lock, END, cancelación y pérdida de autorización. El adaptador
revalida a intervalos de 100 ms y antes de aplicar negociación. No persiste autorización
multimedia ni reinicia llamadas. Persistencia de señalización/outbox/ratchet sigue en Engine.

Cada extremo crea un certificado ECDSA efímero nativo. El SDP producido y parseado por
WebRTC viaja por Signal; el digest de descripción y huella SHA-256 están autenticados.
Antes de crear la pista y habilitar grabación/reproducción se obtiene `RTCStats` del
transporte DTLS conectado, su certificado remoto y par relay/relay, y se contrasta con
`MediaLease.verifyRemote`. El motor WebRTC valida DTLS contra la descripción remota;
la comprobación de estadísticas añade el enlace al contexto UMBRA. No se exportan claves.
ACTIVE local acredita ese canal autenticado, no audio humano: el test analiza PCM aparte.

Cancelación y activación/unmute comparten una barrera local sin transacción de base de
datos: un callback tardío no puede deshacer el mute. Cleanup nativo y END son posteriores;
fallar END no mantiene captura. Desconexión falla cerrado; todavía no se implementa una
reconexión automática ni ICE restart de voz. Una generación nueva requiere otra sesión y
consentimiento; no ampliar los 180 s. La revocación remota solo puede aplicarse al conocerse.

Ruta Android productiva: ADM AudioRecord/AudioTrack, permiso RECORD_AUDIO connected,
MODIFY_AUDIO_SETTINGS, foco transitorio de comunicación y `setCommunicationDevice`
(API31+, [AudioManager](https://developer.android.com/reference/android/media/AudioManager),
[restricción de foco target35+](https://developer.android.com/media/optimize/audio-focus)).
Perder foco/salida detiene. Controles mínimos locales para TURN temporal, consentimiento,
mute y salida; no directorio TURN remoto ni credencial permanente. Pedir permiso no inicia
media y una pausa mantiene el bloqueo existente. Sin servicio oculto o background exception.

Coturn 4.18.0-r0 está fijado por digest en `scripts/turn_lab.py` y Dockerfile. Red Docker
interna, sin puertos publicados, nobody, sin capabilities, solo peers de sus propias
asignaciones; cuotas, intervalo de puertos y duración acotados. REST HMAC-SHA1 estándar
proporciona credenciales efímeras de prueba; secreto maestro solo en temporal 0700 del
host y archivo del contenedor, nunca en APK/logs. UDP hacia TURN en este laboratorio;
DTLS-SRTP sigue cifrando media extremo a extremo. TURN TLS no está probado aquí.

El fixture de integración usa dos AVD independientes, SQLite de laboratorio, identidades
sintéticas, libsignal y relay HTTPS real. Se desactiva AudioRecord físico antes de crear
la factoría, se inyectan tonos PCM de 1/2 kHz a ritmo real y se detecta energía espectral
en PCM decodificado Opus. Generadores, CA y configuración se encuentran en androidTest,
fuera de release. No equiparar SQLite de laboratorio con Keystore hardware.

Pendiente: batería adversarial de media completa, observación de red y ausencia de
fallback bajo fallos, IPv6, ruta acústica física, recorrido R8 de voz y revisión independiente.
Consultar evidencia fechada; no declarar terminada la quinta entrega por este ADR.

## Observación de red del laboratorio

En Emulator37, la captura de consola antigua no observa la ruta Wi-Fi de netsim:
una captura sin paquetes TURN se rechaza, no acredita ausencia de fuga. Se usa
`-netsim-args --pcap`, con `ANDROID_TMP` privado bajo RUNNER_TEMP y borrado en el trap
propietario. [Documentación oficial de captura por radio](https://developer.android.com/studio/run/emulator-networking-advanced).
`tcpdump`/libpcap analiza el pcap Wi-Fi de cada AVD durante la ventana de la prueba;
solo se guardan contadores sanitizados. Se exige tráfico TURN positivo, ningún STUN
UDP fuera de TURN ni otro UDP ajeno al tráfico de sistema explícito (DNS, DHCP, NTP,
mDNS, LLMNR). Los dos AVD deben responder ping entre sí antes del escenario para
comprobar disponibilidad de una ruta directa IPv4. Esto no acredita IPv6 ni TCP media.
PCAPs de ejecución anteriores y fallos nunca se suben como artefactos públicos.
