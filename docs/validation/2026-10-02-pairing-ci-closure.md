# Execution03B — cierre de regresiones, en validación

Base y HEAD inicial: `95aabb684e4166fa03b7070eaa659c912b1d995e`.
Árbol: `5bb1607ef6f050b4b9cf8072ee19e0f77c06e347`.
Rama `codex/pairing-p0-hardening`, PR22 OPEN/DRAFT. No UI, teléfono,
función nueva, cambio criptográfico ni contrato de admisión en esta ejecución.

## Evidencia roja conservada

CI de95aabb6:26/29jobs SUCCESS,3FAILURE. Verify run37055577731,
Android111003874083:123tests,19failures. Artifact11250146630,
digest GitHub `270abd63c4801dbea9262add5c3f7d58aa0380ca200604b389ea08d9d3ea89f1`.
Video run37055577783: R8shard0/job111005150044 expired-auth;
R8shard1/job111005150225 ipv6-tls. Ambos tcpdump exit1 al leer snapshot.
Esto no acredita un fallo de TURN, cifrado o medios: la evidencia de red
no pudo leerse. No había stderr ni captura bruta en artefactos para demostrar
la causa histórica exacta; no se publican capturas brutas nuevas.

## Autoridad sintética y plazo

AdmissionFixture mantenía una autoridad estática con un AccessGate autorizado
una sola vez. La ejecución larga atravesaba240s y el gate rechazaba correctamente
el siguiente enrolamiento. Reproducción JVM con reloj controlado y AccessGate real:
3tests,1failure en segundo enrolamiento después del plazo; las aserciones de
expiración exacta y rechazo del lease viejo pasaron. Logs RED preservados en
`/tmp/umbra-execution03b-admission-fixture-red.log` y XML del mismo prefijo.

Corrección test-only: la autoridad recibe una autorización sintética explícita
por enrolamiento sincronizado. No se autoriza al miembro, no se recuperan reviews
antiguos, no cambia la identidad/autoridad ni AccessGate productivo. Helpers solo
en src/test y src/androidTest; ningún bypass dentro de src/main o APK productivo.
Cuenta Android123connected/120offline conservada; resultados nuevos pendientes.

## Decisión contractual del propietario

Se conserva AdmissionCredential: signalPublicKey es metadata pública preexistente.
No se entregan claves privadas, ratchets ni mensajes plaintext al relay.
Invite/request/ack permanecen cifrados en rendezvous y sus secretos/código humano
no se revelan. Unlinkable/pseudonymous relay admission queda FUTURE PRIVACY WORK.
CONTRACT_CHANGE_REQUIRED=NO. No reabrir PairingP0 funcional.

## Entorno

JDK21,Gradle8.14.4,SDK36. /dev/kvm existe pero usuario actual no tiene acceso
lectura/escritura; no se cambian permisos locales para sortearlo. AVD focalizado
requiere Actions. Publicar en esta PR activa automáticamente los workflows
existentes; no se desactivarán ni ocultarán para imponer secuenciación artificial.
Las regresiones focalizadas deben producir recibo propio antes de aceptación
acumulativa. Sin instalación ni operación sobre teléfonos.

## Corrección de captura y validación local previa a publicación

La reproducción nativa, versión exacta y límites están en
`2026-10-02-pcap-writer-forensics.md`. La copia mientras escribía netsimd produjo
3 archivos truncados de10 observaciones acotadas. El cierre SIGTERM/salida0
produjo captura completa. Esto demuestra el mecanismo; no recupera stderr ausente
de los dos fallos históricos.

El laboratorio ahora posee explícitamente su daemon en runtime privado. Cada
escenario conserva evidencia nativa y queda PENDING_CAPTURE_FINALIZATION, sin
recibo voice-evidence/PASS. Al terminar la tanda: cerrar ambos AVD, cerrar daemon,
esperar salida0, comprobar errores de escritura/flush, validar framing completo,
copiar a snapshot inmutable y ejecutar las mismas aserciones de red. Una captura
ilegible, cierre fallido o recibo incompleto sigue fallando. No se suben PCAP.
Los30s de readiness/cierre son límites de operaciones del arnés, no ampliaciones
de autorización ni de pruebas multimedia; la ejecución nativa local terminó
normalmente dentro de ellos. El daemon compartido se reinicia entre la tanda
focalizada R8 y la matriz completa, con recibos separados. Ningún shard se elimina.

Resultados locales sobre diff aún no publicado:
- Fixture RED:3tests/1failure; GREEN completo JVM443connected y385offline,
  cero fallos/skips. Construcción de ambos APK androidTest exit0.
- `python -m unittest discover -s scripts/tests -p 'test_*.py'`:300tests PASS,
  exit0,6.567s; incluye framing, cero paquetes válido, truncamiento, error de parser,
  writer activo, cierre inmutable, IPv4/IPv6TLS y recibos focalizados.
- `python scripts/repository_guard.py`:741fuentes,exit0.
- `python scripts/check_source_policy.py`:13controles,exit0.
- `git diff --check` y sintaxis shell:exit0.
- Lifecycle daemon real netsimd1.0.23, sinAVD: CLOSED/exit0/captureErrorCount0;
  SHA256 ejecutable `b32f5ff232b86e2b4bd43e0245b04f72a8c19851ba6087b041a770bd24157088`.
  Esto no es prueba Android ni multimedia.

FIXTURE_BUG_CONFIRMED=YES. PRODUCTION_ACCESSGATE_CHANGED=NO.
PAIRING_P0_CHANGED=NO. PHYSICAL_EXECUTED=NO.
Android123/120, escenariosR8 y CI acumulativa del candidato siguen pendientes
hasta ejecutar y revisar sus recibos propios. No se hereda verde95aabb6.

Revisión adicional previa al commit: se migró también el consumidor multimedia
de Emergency Lock al cierre explícito. Una regresión inspecciona los cinco
workflows consumidores para exigir ownership y finalización; los logs de AVD
usan identificador de tanda para no sobrescribir el diagnóstico focalizado.
Suite final tooling local:301tests,6.590s,exit0.
