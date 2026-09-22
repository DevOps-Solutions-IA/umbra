# Video 1:1 — aceptación de laboratorio, 2026-09-22 UTC

**Integración de audio/video sintético comprobada en dos AVD, debug y R8.**
No es una prueba con personas, cámaras físicas, radios o Keystore hardware. La PR
#9 continúa en borrador hacia `codex/turn-voice-core`; sin fusión ni auto-merge.
La UI definitiva queda fuera. Se preserva el informe histórico del 21 de septiembre.

## Identidad del código y ejecuciones

HEAD publicado y compilado localmente: `171324bf4d280925f87d1bb6689c78be018fd4c8`.
Checkout de integración de las tres ejecuciones de Actions:
`dca789944fabd2947ae6adab5da6fd3cce51d3ee` (verificado en sus `commit.txt`).
Este recibo y las actualizaciones de documentación posteriores no modifican el código
probado; la CI del cierre documental se consulta separadamente en la PR.

| Ejecución | Resultado comprobado |
|---|---|
| [35661260991](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/35661260991) | repository-guard, relay-and-core, relay-container, android: SUCCESS |
| [35661261035](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/35661261035) | Voz R8: SUCCESS, 15 escenarios con recibos reales |
| [35661261036](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/35661261036) | Video debug y R8: SUCCESS, 31 casos por matriz, sin fallidos ni omitidos |

Artefactos descargados; matrices completas y hashes debug cotejados con los APK.
[Recibo JSON](2026-09-22-video-ci-acceptance.json) contiene IDs/digests de artefactos,
casos, hashes APK, medios decodificados, tráfico observado y mediciones de captura.
Los fallos de 35646143351/35646143377 se conservan en el informe previo; no fueron
ocultados ni se sustituyeron por un rerun del mismo código.

## Implementación y correcciones

- Extensión v2 sobre CallService/Engine/libsignal existentes, con consentimiento por
  dirección, arbitraje de solicitudes cruzadas, generaciones y cambios terminales.
- Misma PeerConnection, TURN autorizado y RELAY obligatorio; una pista audio y como
  máximo una video. Huella DTLS efectiva y descripciones/candidatos autenticados.
- Camera2 frontal/trasera, cambio, apagado y reactivación consentida; superficie
  mínima que distingue frame atrasado. Sin almacenamiento de imágenes.
- Cierre de diálogo independiente del listener reemplazable de MainActivity:
  regresiones Android rojo→verde. La limpieza libera renderer/EGL una sola vez.
- Invalidación de captura anterior al STOP persistente, sin doble escritura; uso y
  liberación de pista sincronizados sin mantener ese monitor durante acceso a disco.
- ICE incremental autenticado evita esperar todas las interfaces tras disponer de
  una asignación válida. No modifica SDP con reemplazos ni habilita candidatos directos.
- Entradas JNI Zero conservadas específicamente para R8; el arnés externo preserva
  solo firmas que referencia, permitiendo optimización y ofuscación.

ADR/protocolo: `ADR-webrtc-video.md`, `VIDEO_MEDIA.md` y extensión de CALL_SIGNALING.
Compatibilidad: voz v1 conservada; versiones desconocidas rechazadas. Estado video
opcional en registros existentes, sin reinicialización de identidad ni cambio DDL.

## Pruebas, herramientas y versiones

Python local 3.13.12 (CI 3.12), JDK21.0.11 local, Gradle8.13, AGP8.13.2,
compile/target36, min31, build-tools35.0.0, libsignal0.102.3. Sin actualización masiva.
Venv activado antes de Python y JDK21/SDK configurados explícitamente.

| Comando/capa | Resultado |
|---|---|
| `bash scripts/test_local.sh` | exit0: 149 backend,105 escenarios Java; 12 guardas estáticas separadas |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | exit0: 140 pruebas de herramientas |
| `python scripts/repository_guard.py --git-history` | exit0; no equivale a auditoría |
| `python scripts/build_android.py --release` | exit0; 157 JVM connected/117 offline, debug/release, lint, JNI, permisos y DEX |
| `assembleConnectedMediaLab assembleConnectedMediaLabAndroidTest` con `-PumbraMediaLab=true` | exit0, optimización/ofuscación conservadas |
| `run_camera_provider.py --optimized` | exit0, Camera2 sintética frontal/trasera/cambio/cierre y dos regresiones de diálogo |
| `run_voice_integration.py --optimized --video --scenario force-stop` | exit0, Opus/VP8 remotos y reinicio sin reanudar |
| CI instrumentada | 27 connected/25 offline ejecutadas; SQLite de laboratorio, no MemoryRecords como durabilidad |
| CI regresiones | HTTPS real, 15 escenarios de voz debug +15 R8, RFCOMM emulado ambas variantes, ubicación/reinicio, políticas release |
| CI video | 31 casos debug +31 R8: positivos, rechazos, pérdida/revocación/bloqueo, force-stop, disco, direcciones, red degradada, TLS y trayecto IPv6 |

Los 31 casos incluyen Camera2, no son 31 llamadas positivas. Por variante hay 30
recibos de transporte y 18 de frames decodificados; las rutas rechazadas no se
cuentan como audio/video exitoso. Patrones diferentes y cambiantes por extremo
entran antes del encoder y se comprueban tras el decoder remoto; audio Opus continúa
al apagar video y la reactivación requiere consentimiento nuevo.

## Procedencia nativa y laboratorio

WebRTC upstream `73cb8180f7258ee292878d6edd05177f41883962`, AAR `.3`:
`5743b0e47574a7d8bad047b00fdef8f49e56e41c944a12542282e2b91ccf9433`.
Receta, licencias, parche y hashes por ABI en `native/webrtc/` y
`android/webrtc-artifact.json`. Reconstrucción 35646136452: cuatro ABI byte-idénticas,
83 TURN y 12 pruebas C++ de modelo/política; NO toda la suite upstream. Incluye
rechazo de TURN300 antes de contactar ALTERNATE-SERVER y regresiones pertinentes.

Coturn4.18.0-r0 fijado por digest
`sha256:bbefd3e1fdfdc0d58770fe01b581fd8b00d9f3a5580d00acb77cf719a6bc78e3`.
Docker aislado, sin puertos públicos, cuotas/destinos acotados y credenciales efímeras.
Dos AOSP AVD Android35 con KVM, relay HTTPS real e identidades sintéticas independientes.
CA y fuentes sintéticas solo en APK de pruebas; sin fallback Keystore productivo.
Limpieza de procesos y material temporal, sin subir PCAP/SDP/credenciales.

| Red probada | Alcance |
|---|---|
| IPv4/UDP | Pares nativos relay, tráfico observado; ruta directa disponible/bloqueada, TURN caído/redirigido y credenciales inválidas/caducadas |
| IPv4/TLS | Nombre/cadena correctos; rechazo nombre erróneo, vencido, CA no autorizada, auth, caída y redirección; sin downgrade UDP observado |
| IPv6 cliente→TURN UDP/TLS | Tráfico dual-stack observado y medios positivos; **asignación relay IPv4**, no recorrido completamente IPv6 |

TURN no termina DTLS-SRTP; su operador sigue viendo IP/tiempos/volumen. Estas pruebas
no demuestran anonimato ni ausencia de filtraciones en combinaciones no ejecutadas.

## Cancelación y límites pendientes

En 35 capturas autorizadas por matriz, desde petición monotónica de apagar video:
- Debug: máximo invalidación 7.131µs; cierre 32.418ms; observación mínima 2502ms.
- R8: máximo invalidación 12.849µs; cierre 27.442ms; observación mínima 2501ms.

Son medidas de esta ejecución de captura sintética, no SLA de hardware ni retirada
de frames ya enviados. **NO EJECUTADO:** timestamp independiente del último datagrama
video multiplexado con audio; el observador actual solo atribuye tráfico al TURN y
TLS no expone el medio individual. No se infiere ese instante del cierre de cámara.

**BLOQUEADO por hardware ausente:** cámaras/micrófonos/altavoces/headsets físicos,
radio Bluetooth físico y Keystore respaldado por hardware. Camera2 AVD se probó aparte.
**NO EJECUTADO:** media en el APK productivo exacto (el arnés R8 es otro applicationId,
firma sintética y APIs de test conservadas); asignaciones relay IPv6; muerte durante
commit SQLite; auditoría independiente. Reinicio/force-stop no equivalen a esa muerte.

Se mantienen 60s de invitación/180s de sesión, primer plano y bloqueo al pausar.
No promesa de llamadas prolongadas en segundo plano. Permiso revocado puede hacer
que Android termine el proceso; no se elude esa política. Advertencia Starlette/httpx
existente visible y aviso API de tests de ubicación: sin supresión global.

Offline continúa sin INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO, CAMERA ni WebRTC.
La siguiente etapa de UI puede apoyarse en estas APIs, preservando estos límites;
esta evidencia no certifica UMBRA para secretos reales ni cierra la auditoría.
