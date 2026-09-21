# ADR — WebRTC Android y voz privada 1:1

Fecha: 2026-09-21. Estado: EN IMPLEMENTACIÓN; audio sintético integrado ejecutado, aceptación completa pendiente.
Base: PR #7, `e495793ba191cce0523db243ab7baac55389ef64`. No sustituir Signal ni
crear otra política de identidad. CALL_SIGNALING conserva consentimiento, selección,
versiones de roster, invitación de 60 s y sesión de 180 s. Video queda fuera.

## Distribución y reconstrucción verificadas

Se utiliza `android/vendor/webrtc-150.7871.01-umbra.1.aar`, construido desde el fork
[webrtc-sdk/webrtc, revisión 73cb818](https://github.com/webrtc-sdk/webrtc/tree/73cb8180f7258ee292878d6edd05177f41883962).
No es un binario oficial de Google ni se sigue utilizando el Maven inicialmente probado.
[Receta y parches](../../native/webrtc/README.md), [inventario completo](../../android/webrtc-artifact.json).
La construcción [35577083313](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/35577083313)
compiló arm64-v8a, armeabi-v7a, x86 y x86_64 con la misma fuente/patch. Se conservan
revisiones gclient/CIPD, opciones y hashes de cada entrada. Sin RBE ni caché remota de
compilación. El empaquetado normaliza ZIP/JAR, comprueba igualdad de clases Java entre
ABI y conserva licencias BSD-3-Clause/PATENTS y licencias transitivas como assets del
APK connected. Esto no acredita por sí solo un SBOM completo o reproducibilidad de
fuente a binario en cualquier máquina.

Parches: rechazo de redirecciones TURN antes de I/O y corrección de un constructor
Java genérico; no cambian DTLS-SRTP ni primitivas criptográficas. Se mantienen warnings
como errores. Las pruebas C++ modificadas no se ejecutaron; el rechazo nativo sí se
observó con coturn real y captura de dos AVD. La auditoría independiente sigue pendiente.

Android utiliza Python3.12+, JDK21, Gradle8.13, AGP8.13.2, min31, compile/target36 y
build-tools35.0.0. La construcción upstream usa además su toolchain hermético fijado
por DEPS/CIPD (incluido javac con salida Java21); no confundir ese toolchain con el
JDK21 de Gradle. El inventario registra las referencias efectivas, no solo README upstream.
Offline no incluye Java/JNI/assets WebRTC ni permisos de micrófono/red.

El binario exige un hash explícito en Gradle y en el control de capacidad del adaptador.
La guarda de repositorio acepta exclusivamente su ruta, tamaño y hash, manteniendo el
límite general de 8 MB. Actualizar exige nuevos recibos/procedencia, comparar APIs,
repetir rechazos, media, red y R8/JNI, y revisar licencias. No actualizar a latest.

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

Ejecutados en laboratorio: batería de rechazo/ciclo de vida y observación IPv4 UDP
con TURN caído sin fallback. Pendientes: IPv6, ruta acústica física, TURN TLS,
recorrido R8 de voz y revisión independiente.
Consultar evidencia fechada; no declarar terminada la quinta entrega por este ADR.

## Observación de red del laboratorio

En Emulator37, la captura de consola antigua no observa la ruta Wi-Fi de netsim:
una captura sin paquetes TURN se rechaza, no acredita ausencia de fuga. Se usa
`-netsim-args --pcap`, con `ANDROID_TMP` privado bajo RUNNER_TEMP y borrado en el trap
propietario. [Documentación oficial de captura por radio](https://developer.android.com/studio/run/emulator-networking-advanced).
`tcpdump`/libpcap analiza el pcap Wi-Fi de cada AVD durante la ventana de la prueba;
solo se guardan contadores sanitizados. Se exige tráfico TURN positivo, ningún STUN
UDP fuera de TURN ni otro UDP ajeno al tráfico de sistema explícito (DNS, DHCP, NTP,
mDNS, LLMNR). Los dos AVD deben intercambiar un desafío UDP comprobado antes del escenario para
comprobar disponibilidad de una ruta directa IPv4. Esto no acredita IPv6 ni TCP media.
PCAPs de ejecución anteriores y fallos nunca se suben como artefactos públicos.

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
