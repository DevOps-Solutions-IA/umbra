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

## Candidato6ee636e — rojos nuevos preservados (2026-10-03)

HEAD `6ee636e50db5a925cd317089532d925c0fbaa916`, árbol
`2733c364d778bd71797270989f1e7ac7b2a60721`, checkout Actions
`9900a362302a0486fe91d91287869351fdf925a5`.
Verify37097880810:301tooling tests/1error `ModuleNotFoundError: yaml`.
La dependencia accidental de la nueva inspección de workflows existía solo en
el host, no en repository-guard. Se reemplaza por inspección stdlib de bloques
shell literales; no se agrega dependencia ni se elimina aserción.
`python -S -m unittest discover -s scripts/tests -p 'test_*.py'`:301PASS,exit0.

Video37097880827 shardR8-1 completó las fases nativas expired-auth e ipv6-tls,
pero NO aceptación de red: cierre daemon exit0/captureErrorCount4/FAILED.
Artifact11265730196, SHA256
`45b9216f68194b72fa9309a6b8b32ffbb4ebb85bacdbc46b1867a31093e7071c`.
Emergency media debug111131430236 yR8111131430252 también fallaron el recibo
de cierre, no se convierten en pases. Artifact debug11264673344 SHA256
`7dc0f3316f039b9ef36154c4f53d39ece4175c3b0934e7971bc5f4403cf14321`.

El recibo antiguo contiene contador, no categorías; no permite identificar
retrospectivamente sus cuatro líneas. Una reproducción local independiente con
el mismo binario reveló un falso positivo del clasificador: INFO de root-canal
sobre capacidad WRITE_DEFAULT_ERRONEOUS_DATA_REPORTING, sin escritura de PCAP.
La corrección distingue exclusivamente la línea informativa exacta demostrada,
conserva su contador diagnóstico y sigue bloqueando errores de captura, flush,
transporte y errores desconocidos. Los nuevos recibos incluyen categorías fijas
y ubicación de fuente permitida; nunca líneas, direcciones ni payloads crudos.

Comprobación local adicional: backend244PASS. El primer `test_local.sh` falló
al combinar java25/javac21 del PATH; repetido con JAVA_HOME y PATH explícitos JDK21,
terminó exit0, incluidos sintaxisJava y13políticas. No se cambió toolchain del repo.
Un millón de registros PCAP sintéticos/78MB: framing0.193s, tcpdump0.663s,
conteo exacto1,000,000; sin aumentar el timeout existente.

Reproducción exacta de dos clientes HCI: salida0, dos PCAP finales67bytes,
ambos tcpdump0; contador anterior4, diagnóstico nuevo no-capture4/error0.
Facts SHA256 `6b82e6b6581c1c0819a30f78263fd1c5baebb6666d1d1e008c954acff16230a6`;
diagnóstico SHA256 `68582f78e9976b29aee791c3be8d1aa855e22b415ee4d4e08b1093ba986dd39f`.
Nuevas pruebas exigen INFO, módulo, línea y mensaje exactos; variantes y errores
reales siguen siendo bloqueantes. Esto no acredita Wi-Fi/AVD/multimedia.

## Validación Android72d3d21 y consumidores indirectos

Verify37098623671 artifact11265339008, SHA256
`71de26ef6e57a651972fd77e572c36ac682727595b08fdb38e96475a5d47a405`:
connected `OK (123 tests)` en248.236s, offline `OK (120 tests)` en239.027s,
ambos instrumentation code-1. La autoridad sintética ya supera el plazo global
sin modificar AccessGate. Verify falló DESPUÉS, al invocar la voz desde
`ci_bluetooth.sh`: faltaba opt-in al propietario de captura. Se añade al workflow
Verify y se finaliza después de todos sus escenarios de voz y RFCOMM. La regresión
inspecciona también este consumidor indirecto; no se elimina ni mueve ningún caso.

## Privacidad debug72d3d21 — diagnóstico pendiente, no reparación atribuida

Run37098623693, artifact11265328342 SHA256
`3193e07ee1373d8565ba29d55293bfb1e5dcd672b425def39bb95637fcfe4b29`:
el listener alcanzó SYNTHETIC_MUX_DONE, pero no la barrera positiva de consumo.
El runner falló aproximadamente45s después de instalar/iniciar. El log final
`Process crashed` se obtiene después de un finally que fuerza cierre del paquete;
NO prueba por sí solo un crash espontáneo. No existe diagnóstico previo al cierre
en ese artefacto; causa interna NO CONFIRMADA. R8 del mismo workflow pasó.

Se agrega diagnóstico test-only ANTES de cleanup: DEADLINE vs PROCESS_EXIT,
existencia del proceso, memoria, causas de salida históricas identificadas como
históricas, y ubicaciones nativas de una lista fija. Recolección acotada5s/64KiB
por comando, solo en memoria; no dumps crudos, mensajes, rutas ni secretos en
artefactos. Marcas elapsedRealtime del listener identifican cada fase. Se mantiene
45s, no hay reintento automático y un diagnóstico nunca convierte un fallo en PASS.
Esto es una mejora de observabilidad; no se presenta como corrección del codec.
