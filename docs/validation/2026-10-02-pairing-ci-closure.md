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

## 2026-10-03 — observación acotada de privacidad en bd10e64

HEAD `bd10e642810fdaafa6783acd2ab5297c134ff488`, tree
`950df1694c1b9c3391fe76d1d0ad45dec62c0835`.
Suite JVM repetida: connected443/offline385, cero fallos/errores/skips,
50 tareas ejecutadas, JDK21/Gradle8.14.4, exit0 en3m34s.

Privacidad primaria37100252900: debug/R8 SUCCESS. Una sola ejecución adicional
acotada37100779953, con diagnóstico nuevo y mismo SHA: debug/R8 SUCCESS.
No se programaron reintentos hasta conseguir verde. El recorrido sintético desde
PREPARE_ENTER hasta VIDEO_OBSERVE_DONE duró en la primaria11,466/11,267ms
(debug connected/offline),7,726/7,330ms(R8); en la adicional12,377/11,826ms y
7,536/7,491ms. El límite45s no se alteró. Son mediciones AVD, no físicas.

Artefactos adicionales11265976720 SHA256
`0b846e73ee391251eb4be98b9a42632dda662809132bd68af9f002d801fd0b79`
y11265926949 SHA256
`04cb9d32e17d5bc07ac8f47abfe94fec3ac0b39ec900b1d36d7e5d2e04d80668`.
La causa interna del incidente72d3d21 sigue HISTORICAL_UNCONFIRMED:
estos pases no demuestran una corrección del codec ni descartan intermitencia.

## 2026-10-03 — colisión UDP demostrada en el checkpoint72d3d21

Video37098623723 terminó con debugshard1 FAILURE en tls-unreachable antes
de iniciar media; los otros tres shards terminaron SUCCESS. Artifact11265861968
SHA256 `fab30c7428303834e075b03268043dfdc1c76fa742e39aa9e16895d1febb8f8a`.
`direct-route-2.json` registra listenerExit1, receivedBytes0 y
`nc: bind: Address already in use`. La tabla UDP ANTES y DESPUÉS ya contiene
el puerto40918, inode6943, UID1020. El arnés eligió aleatoriamente ese puerto
ocupado y confundió la presencia del socket ajeno con su propia escucha.
No fue fallo TLS/TURN ni decodificación: era una colisión de preparación del
laboratorio. No se identifica un servicio concreto solo a partir del UID.
La ejecución roja se conserva; una corrección requiere propiedad verificable
del socket, sin reintentos ciegos, timeout mayor ni sustitución por TCP.

Corrección del arnés UDP: se elimina la elección aleatoria de un puerto fijo;
`toybox nc -l` sin `-p` pide al kernel un puerto y conserva el socket reservado.
Antes de enviar se comprueban PID, cmdline exacto e inode del socket a través
de sus FD. La presencia de un puerto ajeno nunca constituye readiness. Se
conservan los presupuestos2s/5s, el desafío exacto y el fallo ante errores;
no hay retry ni TCP alternativo. El runtime debe anunciar explícitamente soporte
para puerto asignado. La API se contrastó con Android15
[netcat.c](https://android.googlesource.com/platform/external/toybox/+/refs/heads/android15-release/toys/net/netcat.c)
y el texto help de la imagen local API35rev2(AE3A.240806.019/12368160).
No se declara ejecución del binario Android local: queda para AVD/CI.

Regresión RED:19tests/1failure, SHA256 del log
`144926a8717877cc882d5039e0e91d4042305b52138a4c8dc3f748fe7d80f0fb`.
GREEN:23tests, SHA256
`757be69c09e1978552f53b9bc92e79526218d7eff039c626c7dbcd7f32b5bf34`.
Los pipes reales prueban lectura acotada y preservación de datagrama; los dobles
prueban rechazo de socket/PID ajeno y ausencia de envío. No son aceptación de red.

## 2026-10-03 — presupuesto acumulativo Verify, no timeout de prueba

Verify37100252917 del HEADbd10e64/checkout478d619deebb2cbece38e9a5e2baa7676a3fa9ef
terminó CANCELLED. GitHub annotation: `The job has exceeded the maximum execution
time of 40m0s`. Artifact11266258054 SHA256
`2d84b4272185ed57914b179c5b644678798c376b8d68c12392fc0c8f899475f6`.
Connected123PASS(289.198s),offline120PASS(279.716s). El log termina los15casos
nativos, RFCOMM positivo/negativo en ambos flavors, y `PASS finalized network
evidence for 15 scenarios` a06:21:14.554Z. Hashes empieza06:21:14.575Z y se
cancela06:21:16.122Z. Job iniciado05:41:10Z: controles terminados a40m04s.
Build/JVM/lint8m12s, paso de dispositivos29m44s; no hay test colgado demostrado.

Se cambia exclusivamente el presupuesto GLOBAL del job Android40→45min:
aproximadamente4m56s adicionales tras la duración observada para hashes, subida
y variación del runner. No cambia timeout de una prueba, duración productiva,
aserción, inventario ni número de jobs. Mover hashes antes no elimina el exceso
acumulativo; dividir el job alteraría el inventario solicitado. La cancelación
histórica permanece sin aprobar. Un nuevo HEAD requiere CI propia completa.

## 2026-10-03 — checkpoint ddd6dca y coordinación causal de stopVideo

HEAD `ddd6dcaacc2d922a3e93507eb241278acccab3c3`, tree
`27bd68aaf66bfcd4abbd261dcf4d58b78989cb54`, checkout
`94b6492cdfd2571afbe354863ca78ec4a20d7fce` con el mismo árbol:
10/11 workflows SUCCESS, 28/29 jobs SUCCESS, cero pendientes. Verify37102944687
SUCCESS completo; artifact11268073595 SHA256
`c4625eccee70fe1a6381e791fa44b2447af1cd196060fbb55560e1dc9338e936`.
No constituye cierre03B: Video37102944679/R8shard1/job111148582661 falla en
`degraded-network`, después de audio/video positivos en ambos extremos.
Artifact11267811562 SHA256
`acb17b24dc9318dce8317994c4a369a65d41e8f62b8e45e0d146a5b706115211`.
A observa22imágenes remotas/2patrones/1156buffers audio; B58/2/1487.
B registra `videoBeforeStop=ACTIVE:none:OFF` y falla el límite monotónico de
cancelación. El recibo anterior no imprimía qué desigualdad falló: esa causa
numérica retrospectiva NO está demostrada.

Defecto de coordinación reproducido determinísticamente: el host ordenaba OFF
A y B secuencialmente. A podía emitir VIDEO_STOP, invalidando la captura de B
antes de que B registrara su propia solicitud local. NativeVideoCapture conserva
correctamente la primera invalidación; compararla con la solicitud POSTERIOR
produce intervalo negativo. La regresión modela ese orden first-CAS y demuestra
RED; no se presenta como reproducción nativa de la desigualdad histórica exacta.

Corrección exclusivamente en el APK/test driver de laboratorio: después de que
cada `stopVideo()` LOCAL termina, se conserva inmutable su VIDEO_STOP pendiente
hasta que el host observa ambas solicitudes. La liberación está ligada a nonce,
generación e IDs exactos de envelopes. Antes de permitir reactivación, cada
fixture exige que `Engine.receive` haya aplicado el STOP exacto del otro extremo.
Se cubre también recepción previa al archivo de liberación local. IDs/nonce solo
en archivos temporales del UID sintético; el artifact de barrera contiene únicamente
booleano/generación/conteo. La detención local nunca espera la barrera ni al relay.
No se retienen controles automáticos anteriores a la acción local, ni otras
sesiones/generaciones. No cambia ciphertext, ratchet, producto o autorización.

Permanecen los mismos límites500ms invalidación,2s cierre,1,5s último callback,
medidos desde la solicitud local real (no desde la liberación remota). La ventana
positiva posterior sigue comprobada. Se añaden deltas numéricos seguros ANTES de
fallar para identificar cualquier repetición. Sin sleeps nuevos ni timeouts mayores.
La lista R8 de referencias añade solo la clase del gate de test, sin reglas amplias.

Validación local:324tests de tooling PASS (8,014s), incluyendo7nuevos tests de
barrera/helper y una regresión de recibo degraded; repository_guard745archivos,
source_policy13controles, diff --check. Un comando inicial de build mezcló
variant mediaLab con una tarea AndroidTest debug que no existe bajo esa propiedad:
falló ANTES de compilar; se separan ambos builds, sin cambio de versiones.
Un intento inicial `scripts/source_policy.py` falló porque la ruta correcta es
`scripts/check_source_policy.py`; esta última ejecutó13controles PASS.

R8 focalizado añade degraded-network a expired-auth e ipv6-tls antes de la matriz
completa: exige codec/audio/video, impairment80ms/2%/128kbit/cola20 con paquetes
procesados y descartados, barrera local y captura finalizada. No sustituye ningún
escenario de los shards originales ni altera los29jobs. La aceptación nativa de
esta corrección y toda CI de su nuevo SHA permanecen pendientes hasta ejecutarse.

Build local posterior: mediaLab+AndroidTest R8 SUCCESS48s,79tareas ejecutadas/1up-to-date;
log `/tmp/umbra-03b-stop-r8-build.log`. Debug AndroidTest compiló; comando con ambas
suites JVM SUCCESS2s,3ejecutadas/59up-to-date: NO se cuenta como nueva ejecución JVM
completa (la CI del nuevo HEAD debe ejecutarla). `check_optimized_media.py` exit0:
medialab no-debuggable, Engine/CallService/NativeVoiceSession ofuscados; APK
`bc74758111e179aa0f8a67ca75257095ad332601de1e9cf72b06d1ef5fd78579`, mapping
`010589031b950b441f4d1d42f33eef6486e2836e209ec417951fe3ca8a6226b6`.
No acredita ejecución multimedia. El primer nombre intentado
`check_optimized_voice_apk.py` no existe; el verificador real es
`check_optimized_media.py`. Todos los fallos de comandos anteriores se conservan.
Verifyddd6dca Android:123connected(296,644s),120offline(287,057s),0fallos.

## 2026-10-03 — rechazo incorrecto de multiplicidad en el fixture0e27cfe

Focused37107049833, R8job111157518442, artifact11268792240 SHA256
`cdde7e01053f5361408ddb430794ccf50975d29fce69ef2aeb751e22aeeb0a4c`:
`camera-permission-revoked-2`(B) y `credential-expiry-2`(A) fallan ANTES de la
revocación/expiración, con video ACTIVE y `Ambiguous synthetic local stop envelope`.
El helper nuevo asumía un único STOP por llamada/generación. El protocolo no
establece esa unicidad: dos solicitudes autorizadas pueden producir dos eventos
firmados/cifrados distintos con el mismo efecto de parada. No es evidencia de
fallo de cámara, credenciales ni cifrado.

Reproducción mínima con Engine/libsignal reales, ConnectedVideoStopMultiplicityTest:
dos `CallService.stopVideo` autorizados en la misma generación; la aserción de un
único envelope falla(RED1test/1failure). Log SHA256
`4360c43f19167e9227d7c5fce36d6a7f15b2bf99d293452ed53607d9e35de423`.
GREEN1/1 confirma ambos envelopes distintos, misma llamada/generación/change,
autorización de entrega, recepción de ambos, retry inmutable, ambos STOPPED y
posterior consentimiento generación3. Log SHA256
`4b96574e1f1090c86ff4846d6ff3d1382e859d7cccdd06e3b9cd25420edd67e6`.
No demuestra cuál callback concreto produjo el segundo STOP en el AVD histórico.
Una carrera entre stop local asíncrono y solicitud explícita es posible por código,
pero esa intercalación exacta se mantiene como no observada.

El arnés ahora conserva el conjunto COMPLETO de1..128IDs distintos, acotado por
el límite de controles existente. No elige el primero, elimina mensajes ni ignora
excepciones. Requiere aplicar TODOS los IDs del otro extremo antes de reactivar.
Rechaza conjuntos vacíos, repetidos, excesivos, compartidos entre extremos o de
otra generación. Si aparece otro STOP de esa generación después del recibo,
falla con diagnóstico fijo: no se oculta un productor tardío no observado.

La prueba nativa existente `video-stop-race` conserva su rendezvous de activación
bloqueada y añade una segunda parada local explícita, sin reiniciar el reloj ni
cambiar500ms/2s/1,5s. Esto obliga al recorrido nativo debug/R8 a comprobar la
multiplicidad; no se quita ninguna repetición ni escenario. Producción intacta.

Host RED ante conjunto válido no admitido por formato anterior: log SHA256
`a5ed7f4d3acafc274141f7beb34d328efd1310bd3143970e11f964a5e7e4d784`.
Host GREEN5tests: `9a53d38596c712e401c2563f2d03fcd250f090a1b468b88d93af9ace5fc5ec8e`.
Helper Java4tests PASS. Falta ejecutar aceptación nativa y CI completa del commit
que incorpora esta corrección; los resultados anteriores no la sustituyen.

## 2026-10-03 — red de inicio privado R8: precondición del fixture

Candidato c78f9105b146c40dc4488db715d38d19c1cc5a8e, run37108831679,
job111162580752, artifact11268484244 SHA256
`6b45fe720d83bf11a35e160938c73fc880712830ff9819a26a4a1c4901f164e3`.
El primer `AndroidConnectivity.connect` falla con `No default network available`.
Las cinco ventanas previas (incluido unlock) registraron cero egreso UID/DNS y
cero adquisiciones de sensores. Los tres DeviceSignalTest pasaron; el listener
falló antes del recibo online. No se atribuye a producción una conexión exitosa.

La preparación inicial había probado ruta Wi-Fi, pero después el caso cold
Activity deshabilita/restaura radios. El fixture intentaba su PRIMER connect sin
observar de nuevo la red predeterminada de Android; solo la recuperación posterior
tenía esa espera. La guarda productiva rechazó correctamente. El artefacto no
conservó el estado de asociación/netd posterior a esa restauración: la causa OS
exacta de que faltara la red sigue sin observarse, no se inventa.

Corrección exclusiva de androidTest: ambos puntos usan la misma observación
acotada de15s/50ms ya existente en recuperación. En cada observación se comprueba
que relay/DNS continúen denegados; una red disponible NO otorga consentimiento.
Solo después hay una llamada explícita connect. No se reintenta connect, solicita
red ni amplía timeout. Se registra INITIAL/RECOVERY, tiempo monotónico, presencia
de default network y READY/TIMEOUT/FAILED antes de continuar o fallar.
El host guarda diagnóstico read-only de rutas/Wi-Fi del AVD propio ANTES de
limpieza; fallo diagnóstico jamás convierte el resultado en PASS. No exporta el
texto de excepciones del diagnóstico. R8 incluye únicamente este helper de test
en la enumeración TraceReferences existente, no una regla keep global.

Regresión RED de wiring sin espera inicial: SHA256
`e28cd1b6a1a9b0cc5bc7bd346c4c167feab36e0a6265b0f58b5991b4a489500e`.
Seis harnesses Java controlados + una comprobación de wiring PASS; log SHA256
`f12eb9d11d519db1afd169bd95fce197261984ee09cbd36f1f671ac67d7c4b44`.
Cubren disponibilidad inmediata/tardía, ausencia hasta límite exacto, denegación,
interrupción, fallo de consulta/diagnóstico sin aceptación. No son pruebas AVD.
Tooling completo335tests PASS12,512s (Python3.13.12,JDK21.0.11); focal17PASS;
repository_guard748archivos y source_policy13controles PASS; diffcheck PASS.
Comandos: `python -m unittest discover -s scripts/tests -p 'test_*.py' -v`,
`python scripts/repository_guard.py`, `python scripts/check_source_policy.py`.
Producción/AccessGate/Pairing/Claude UI permanecen intactos. El nuevo SHA necesita
su propia instrumentación y las11ejecuciones completas; no hereda verdes anteriores.
Build local `gradle -p android --no-daemon -PumbraVaultLab=true
:app:assembleConnectedVaultLab :app:assembleOfflineVaultLab
:app:assembleConnectedVaultLabAndroidTest :app:assembleOfflineVaultLabAndroidTest`:
exit0,1m16s,46tareas ejecutadas/111up-to-date. Esto comprueba compilación/R8,
no ejecución Android. SDK36,Gradle8.14.4,JDK21.0.11. KVM local no autorizado:
la aceptación Android corresponde a Actions, no a este build.

## 2026-10-03 — inventario STOP publicado antes de terminar el worker

c78f910 Video37108831683/R8shard0 job111163137547, artifact11269601678
SHA256 `229c69d22cf0d5bd340ddcd41d5733870d1eb81210586331d68aceae6a118e94`:
turn-loss falla ANTES de cortar TURN, con ambos extremos ACTIVE, al encontrar
`Synthetic stop inventory changed after issued receipt`. El resto de esa matriz
no se acepta; su captura pendiente no se transforma en cero paquetes ni PASS.
Los tres controles focales anteriores sí tienen recibos finalizados, distintos:
expired-auth, ipv6-tls y degraded-network.

El inventario del fixture se tomaba inmediatamente tras stopVideo síncrono.
NativeVoiceSession.stopVideoLocally puede tener en su worker un snapshot CONFIRMED
ya leído, y emitir otro STOP después del commit foreground. CallService admite
esos controles distintos. Reproducción determinista con executor real y snapshot
retenido: RED por STOP que llega después del inventario; log SHA256
`ac763099d19a9d8ee7a0e354279b5151ea79e2bc1c1304a8dca79f79c7203a7d`.
Esto demuestra el defecto de coordinación; no conserva la traza exacta de threads
del incidente Android histórico.

Corrección solo del APK de test: observar que el thread conocido del executor
nativo alcanzó su cola de scheduler inactiva después del STOP foreground, antes
de publicar el inventario completo. No basta WAITING: se exige la secuencia
DelayedWorkQueue.take → ThreadPoolExecutor.getTask/runWorker → Worker.run →
Thread.run, admitiendo el bridge genérico. HTTP/latch/cola dentro de una tarea no
califican. Identidad de thread distinta, muerto, stack desconocido o límite
agotado fallan. No reflexión de campos privados, API productiva nueva ni keep
global. La observación usa el reloj de solicitud ORIGINAL y su presupuesto de
2s existente; no lo reinicia. En video-stop-race el latch se libera antes de
observar, conservando las dos paradas y los350ms originales dentro del presupuesto.
Toda emisión ya en curso termina antes del inventario; futuras tareas leen STOPPED.

Cinco regresiones JVM controladas PASS; GREEN SHA256
`005fac89fafd96326091052de78f37cb4c123e8b59f209023997188669611db9`.
15focalizadas de stop/barrier PASS. BuildR8 media+test exit0,25s,12ejecutadas/
68up-to-date. Primer build detectó variable local `original` duplicada en nuevo
bloque diagnóstico; se renombró `videoEvidenceFailure`; rojo conservado.
La forma real del stack ART y el flujo nativo quedan pendientes de CI propia.

## 2026-10-03 — otros rojos preservados, sin atribución falsa

- c78f910 debugvideo shard1 job111163137560, artifact11269232858 SHA256
  `89850b5eeb52a8615a6bd6d46c0f3c3f1b03081101bddf5e3c45e7cf9919ef60`:
  video-stop-race, IOException EOF antes de cabecera HTTPS. Servidor vivo; conexión4
  registró respuesta, keepalive a+5,0008s y cierre55ms después con transportError.
  Es compatible con carrera de cierre/reutilización; no existe correlación segura
  conexión4↔clienteA ni categoría TLS para demostrarla. HISTORICAL_UNCONFIRMED.
  No retry, keepalive ampliado, Connection:close artificial ni TLS relajado.
- fb635c0 modulación debug job111165339370/run37109800796, artifact11269636651
  SHA256 `b1cb709a12e4b4b6dcd0e2c27d0d2f9b115e8fac10fcb3ac73b1b898ff84bf39`:
  caso device-revoked se detuvo ANTES de revocar, en video-off; A stage3,B stage6.
  Ambos OFF sin fallo nativo. El artefacto no distingue peerStopApplied pendiente
  de decodedAudioDelta<50. Se añade diagnóstico fijo de esos predicados antes del
  error existente, sin cambiar condición, límites o resultado. No se declara
  corregido por un pase posterior ni se llama fallo de revocación.
- fb635c0 contraseña debug job111165339873/run37109800812, artifact11269736965
  SHA256 `8989b7952fc4f71e92255e484cd26f79c53e85b8c16064817d31fd3397dbb9a1`:
  offline DevicePairingPersistenceTest productQrAndCodeUseEncryptedVaultAndBoundedRealCodecs
  rechazó decodeLuminance del QR512 recién renderizado.4/5PASS,1FAIL. Investigación
  separada; no se atribuye a contraseña, cámara física ni interacción del usuario.

Los verdes propios de fb635c0 (incluido inicio privado debug/R8) no cierran el
encargo mientras estos fallos y la validación del candidato acumulativo sigan
pendientes. Su checkout9db87ec2c2594b5a2a4233e62695090b896a0bac tiene exactamente
su treee2dfaaa36c863fab141245137d83c6a7a76e49f2. Verifyc78 fue cancelado por ser
candidato propio sustituido para liberar su grupo: conserva3jobsPASS/android
CANCELLED, nunca se presenta como Verify completo aprobado.

## 2026-10-03 — lectura de auditoría durante publicación de cancelación

fb635c0 Focused37109800885/R8job111165496888, artifact11269033917 SHA256
`75f20c31f78fd2229c9ccfa2b1c1a81139da8df5646ebc966fbd43ea8472bc8e`:
credential-expiry-3 lanza CallService$Interrupted desde session mientras el
fixture todavía observa ACTIVE. Las otras8filas son PENDING_CAPTURE_FINALIZATION,
no recibos PASS finales. SqliteDeviceRecords propaga la denegación, no es evidencia
de fallo SQLite ni EOF.

Regresión con Engine/libsignal real: después de maintain y antes de la segunda
lectura/lease, cancelPending publica cancelled y queda retenido en su callback.
La lectura recibe Interrupted mientras el estado sintético del adaptador sigue
ACTIVE. No demuestra la intercalación exacta del watchdog histórico, sí que esa
ventana legítima existe y el fixture anterior la rechazaba. RED SHA256
`d1d199730b65bc2116b874569efef8643a12488da2727020a6be8aa1ace94348`.

checkSnapshot reconoce exclusivamente el error fijo Call interrupted cuando la
prueba solicitó credential-expiry y el plazo ya venció. Se contabiliza como
expiredSnapshotRejections en el recibo, nunca como lectura, entrega o media
exitosa. Antes del plazo, sin solicitud o con otra causa se relanza el error
original. La prueba todavía debe demostrar cierre/captura quieta dentro de sus
límites, sin retry ni extensión del plazo. La aserción existente de escrituras
rechazadas sigue exigiendo estado nativo terminal.1regresión Engine y2helpersPASS.
No cambia CallService, autorización, estado nativo ni política de credenciales.

## 2026-10-03 — regresión productiva demostrada del detector QR existente

Investigación acotada128invitaciones válidas reales renderizadas512: el detector
ZXing habitual rechazó3/128; cada imagen fallida volvió a fallar3/3, con
NotFound/Format. TRY_HARDER también falla3/128; el lector estándar PURE_BARCODE
lee128/128 símbolos idénticos. No se guardaron payloads vigentes ni claves; solo
conteos, longitudes y clases fijas. No es evidencia de cámara o pairing físico.

PairingQrDetectorRegressionTest conserva una receta pública sintética firmada,
PERMANENTEMENTE CADUCADA, sin clave privada ni capacidad utilizable. Reproduce
el fallo del detector de forma determinista: antes decodeLuminance devuelve
INVALID_FORMAT, aunque decode(text) comprueba firma y devuelve EXPIRED. La
corrección mínima ejecuta el lector estándar de símbolos puros sobre el MISMO
BinaryBitmap acotado solamente si el detector general lanza ReaderException.
Ambas rutas pasan después por la validación original de protocolo, firma y TTL.
Un fallo de validación PairingException NO activa otra ruta. No algoritmo propio,
truncado, nuevo QR, cámara, API, dependencia o permiso.

RED3tests/2failures (determinista+corpusválido); GREEN3/3 (receta determinista,
32invitaciones vigentes, firma/protocolo/oclusiones/ruido/tamaño inválidos). Los
valores de QR no se imprimen en asserts. El caso Android original se conserva y
ahora añade solo diagnóstico fijo de decoder si falla; nunca sustituye un fallo
por éxito obtenido en otro intento.

PAIRING_P0_CHANGED=YES, exclusivamente corrección del decoder QR ya existente,
permitida por la excepción de regresión demostrada del encargo03B. Los transcripts,
HKDF/AEAD, entropía, rendezvous, admisión, VERIFIED_ONLY y AccessGate no cambian.
CONTRACT_CHANGE_REQUIRED=NO. Sin cambios visuales ni instalaciones físicas.
Estos resultados JVM no sustituyen la instrumentación debug/R8/ambosflavors ni
las11ejecuciones propias del próximo HEAD acumulativo.
QR RED SHA256 `84890148bf211e73e216977424b5c32eb8dc07246703fef147788531f9271a7d`;
GREEN `63b8e83880892aa41b48f5a263eec6271e7862800a8fdce91c3f6491ae791ba3`.
Validación acumulativa local posterior a todas estas correcciones: JVMconnected
448/448,offline388/388,0failures/errors/skips; Gradle8.14.4/JDK21.0.11,
`--rerun-tasks :app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest`,
exit0,3m36s,50tareas ejecutadas. Tooling343PASS15,066s; guard753archivos y
source_policy13PASS. Son capas JVM/estáticas, no aceptación Android/hardware.
Builds acumulativos: debug/release+instrumentación+lint ambosflavors exit0,36s
(48ejecutadas/201up-to-date); mediaLabR8+test exit0,38s (15/65); vaultLabR8+test
ambosflavors exit0,58s (36/121). APKpolicy debug/release4PASS. R8media no-debuggable
con Engine/CallService/NativeVoiceSession ofuscados; APK
`bc74758111e179aa0f8a67ca75257095ad332601de1e9cf72b06d1ef5fd78579`, mapping
`010589031b950b441f4d1d42f33eef6486e2836e209ec417951fe3ca8a6226b6`.
Este control estático no ejecuta media. No se cambian minificación, ofuscación ni
reglas generales para hacerlo pasar. Las advertencias de dependencias se mantienen.

### 2026-10-03 — aa0ccfe: recibo estricto y asociación Wi-Fi del laboratorio

El candidato aa0ccfe859c006e8d3a2a3f9cc9d85c670733dc3 NO está aceptado.
Emergency run37112222457 falló en media debug/R8 porque el recibo agregó
`expiredSnapshotRejections` pero sus validadores de claves exactas no lo reconocían.
Los cierres nativos observados fueron CLOSED, 43.399864ms/79.041889ms,
quiet1000ms/observación500ms/callbacks tardíos0. Artifacts11269409884
SHA256 cb3aea34c2e7c51a0b601b15184741bc4f4853bfe236281ccafc10b0800c4ac7;
11269834709 SHA256 6f9f10627ea48a85fbf4f60cf63d9cda6e9af093bc19dc2b2fda0a6b7ba63306.
Modulation debug job111172191977 falló por el mismo rechazo de esquema en lock:
artifact11270825479 SHA256 4138badc8c08723bf16210a3b822caba378d61395a681743bc0acad33f4b747b.
Su recibo confirma quiet1000ms/observación500ms/callbacks0/rechazos0.
No se atribuye este error de validación a captura activa ni se convierte el run en PASS.

La corrección incluye el campo obligatorio, int estricto, cero en cierre normal;
solo expiración esperada admite rechazos acotados por 140s/100ms del fixture.
Campos desconocidos, faltantes, bool, negativos y exceso siguen rechazados.
Regresión sobre recibo real: RED antes/GREEN después; todos los consumidores
incluido el fixture de emergency_execution se actualizan juntos.

Emergency-lock R8 job111172191931 falló ANTES de connect: durante15001ms no hubo
red predeterminada. Tras el retorno de cold-activity, Wi-Fi estaba habilitado pero
sin asociación/IP/ruta; habilitar el radio no seleccionaba el AP virtual propio.
Artifact11270275450 SHA256 9746963801d73a44bebc7b665f97159028c30766ec2fd02d02a701fcc5277cdd.
El motivo interno Android de las entradas permanentemente deshabilitadas no está
confirmado. Se reutiliza restore_startup_wifi, con selección explícita del AP
propio, los5s existentes de asentamiento y diagnósticos separados cold-restore/.
No se añade fallback celular, reintento connect, nuevo deadline ni cambio productivo.
Regresión causal del estado enabled/disconnected: RED1/11, GREEN11/11; este modelo
no sustituye la asociación Android real pendiente en CI del siguiente SHA.

Validación local: `python -m unittest discover -s scripts/tests -p 'test_*.py'`
345PASS,14.839s,exit0; repository_guard753archivos, source_policy13checks y
`git diff --check` exit0. Un comando inicial usó por error scripts/source_policy.py
(inexistente,exit2); el comando correcto scripts/check_source_policy.py pasó13checks.
Estos cambios posteriores a aa0ccfe son exclusivamente tooling/tests/documentación.
AccessGate productivo, duración240s, UI, criptografía y políticas permanecen intactos.
La CI nueva todavía es necesaria; no se reutiliza el verde previo.

### 2026-10-03 — e32b6dd: cierre no observado, diagnóstico sin relajar aceptación

Emergency media debug run37112963243/job111174282539 falló en video después de
observar ambos extremos activos (28/34frames remotos,1425/1478bloques audio).
`NativeVideoStopQuiescence.await` alcanzó el presupuesto2s sin confirmar worker
idle. El artefacto NO distingue trabajo pendiente, presupuesto consumido antes
de entrar, o stack ART no reconocido. Causa todavía NO CONFIRMADA.
Artifact11270841454 SHA256 a1ab0d50892d0c08b9d4f041fa6b1f7f4f1a0522b08710395fcecf0c1992db63.
Se añade únicamente diagnóstico al AssertionError existente: elapsedNanos,
observaciones,Thread.State y hasta24frames de lista cerrada (resto OTHER_FRAME).
No nombres de threads, rutas, payloads, mensajes arbitrarios ni aceptación nueva.
Presupuesto2s, reloj inicial y clasificador idle no cambian.

Pruebas del helper7PASS; tooling completo347PASS15.251s. Build debug2s(6/69)
y mediaLabR8+test19s(9/80) exit0. El primer comando no activó
`-PumbraMediaLab=true` y no encontró la tarea (exit1); corregir la invocación no
requirió cambios de Gradle ni de producto. No se presenta diagnóstico como fix.

Otros rojos aa0ccfe confirmados por recibos archivados: seis credential-expiry
focused debug/R8 y modulation R8 lock coinciden con el esquema ya corregido.
Resumen SHA256 66c6c0db186e6bc62c46d627b03578121d28d5214f32c85e9fd9af238e780139.
Optimized turn-loss también: artifact11271195632 SHA256
744a44a7c7b0fa27c6ac2f9f4391fc2581735e9ef3179ebe32e172d6ae98f0c3.
La corrección acepta esos recibos válidos; no convierte sus ejecuciones rojas en PASS.

### 2026-10-03 — distinguir inventario de controles y cierre de captura

6d7b64b modulation debug job111176750442 vuelve a fallar en video. El diagnóstico
nuevo registra elapsed2000355823ns,35observaciones y worker RUNNABLE dentro de
SqliteDeviceRecords.transaction/CallService.MediaLease.snapshot. Artifact11270292793
SHA256 4ebbc8ecde4cf9784ffda2076ecc9d90c4eec06a454c2b38a69e9d5c3ddb4584.
Esto demuestra trabajo de autorización al muestrear, NO cierre de captura tardío
ni un bucle permanente. El presupuesto añadido al arnés confundía inventario de
controles en el executor con liberación de cámara en el watchdog independiente.

Modelo causal RED: captura invalidada100ms/último callback900ms/cierre1.5s,
control pendiente hasta2.1s antes del deadline original10s. El helper anterior
rechaza un cierre válido. RED SHA256
0285fa66fed059656afa07b1ed0935417a3eec6791cb544a27a251db0bcf30c0;
GREEN8tests SHA256 c4578c21070769b1341ab158dc2bda2a9c5e010a22a5b1cde309295fd29ccb42.

Corrección SOLO fixture: el inventario espera hasta el deadline absoluto ya
existente del escenario, sin reiniciarlo/extenderlo. Sigue exigiendo identidad del
worker, scheduler realmente idle y todos los STOP autenticados; no acepta un
control tardío fuera del inventario. La aceptación posterior de captura conserva
el reloj original: invalidación<=500ms,último callback<=1.5s,cierre<=2s. El test
nuevo prueba que cierre>2s sigue fallando. No se amplían límites multimedia ni
presupuesto total del escenario; no se toca producción.

Tooling348PASS15.715s, debug+instrumentación1s(6/69),R8+instrumentación16s(9/80),
exit0. La reproducción controlada no reemplaza los escenarios Android pendientes
sobre el siguiente SHA. El fallo e32 sin diagnóstico suficiente se conserva;
no se afirma que toda intermitencia histórica comparta necesariamente esta causa.
Focused debug6d job111176739602 también falla ANTES de la barrera stop-issued:
video-stop-race-1(A):2.000186691s/26observaciones;credential-expiry-3(B):
2.000454785s/36observaciones. Ambos RUNNABLE en transacción SQLite; el segundo
no llegó a provocar expiry. Artifact11270444418 SHA256
e7e1e8a120dedf75726ae49114a0ea66cd7e5cd31c2ff5a965c6db2efdb7274a.

### 2026-10-03 — 04bf936: nuevos fallos localizados, causas aún abiertas

Focused debug job111179764960/artifact11271935211 SHA256
e6673c960d926606a31e55654b6001e7c09907d73386a8ed045fc606c1c914e7:
credential-expiry-1 completó video activo y parada en ambos extremos (cierre
48.194/18.521ms,cero callbacks posteriores), pero falló en reactivación ANTES de
la acción de caducidad. A:local-watchdog/NEGOTIATING;B:CallService.Interrupted
al autorizar entrega. Expiración de TURN60s durante preparación es una hipótesis,
no causa demostrada. El rechazo sigue fatal porque no había fase de expiry aceptada.
credential-expiry-2 falló en ACK HTTP403, también antes de expiry. La credencial
de admisión dura3600s; no se confunde con TURN60s. Faltaban motivo de rechazo y
observaciones temporales. No se cambia admisión, ACK, TTL ni política de retries.

Video R8 shard0 job111179689042/artifact11271457068 SHA256
92c72636d365846ec6b8f93528421fed7b6d3bb3b38ab3955669a1c5b60cb956:
device-revoked falló ANTES de revocar. B llegó a stage4 con video ACTIVE y
1263frames decodificados, pero audioDelta0 al deadline120s. Parada previa válida,
STOP aplicado1/1. Causa de la ausencia de audio reconocido NO CONFIRMADA. A
finalizó tras la limpieza del host; no equivale a completar el escenario conjunto.

Se agrega únicamente observabilidad de laboratorio: reloj TURN que devuelve
exactamente cada valor delegado y registra primer cruce de expiry/readCount;
el informe no fabrica un cruce al leerse. Ese cruce no se presenta por sí solo
como causa primaria. Contadores PCM agregados (nunca muestras) y paquetes de audio
se adjuntan a diagnósticos existentes, en ambos extremos. Un fallo de diagnóstico
se adjunta al fallo primario o hace fallar un resultado que iba a ser exitoso,
siempre después de limpieza. No se ignora para aprobar.

El servidor HTTPS sintético registra como máximo128 rechazos, categorías cerradas
de ruta/motivo/status, tiempos monotónicos y booleano del header exacto de desafío
fresco. Sin rutas, headers, IDs, cuerpos o credenciales persistidos. El wrapper
ASGI reenvía mensajes intactos; ninguna modificación del relay productivo.

Validación local: tooling354PASS16.570s; debug+test1s(6/69),R8+test15s(9/80),exit0.
Un build anterior detectó cinco líneas diagnósticas insertadas accidentalmente en
callback sin variable Bundle; RED preservado y corregido antes de commit. La
regresión impide repetir esa inserción; no cambió el callback productivo.
Smoke HTTPS real con TLS verificado conserva403 ADMISSION_UNAVAILABLE, sin
fresh-challenge; cierre explícitoSIGTERM,exit-15 del servidor. Primera aserción
del smoke esperaba erróneamente0; se conserva su fallo y se distingue del cierre
PCAP, donde sí se exige0. No se amplía ninguna aceptación multimedia.
Estas mejoras de diagnóstico NO son correcciones de los tres fallos anteriores.

### 2026-10-03 — cc9f744: EOF Android y límite global de instrumentación

Video R8 shard0 job111185912061 falla en degraded-network ANTES de aplicar
la degradación: ambos extremos ACTIVE y STOP confirmado (12.418/22.571ms,
cero callbacks posteriores); B recibe EOF esperando cabeceras HTTPS, A observa
desconexión después. TURN no expiró (41.225/46.335s de180s, ningún cruce observado).
No hubo rechazo HTTP contemporáneo. Servidor vivo, conexión5 con respuesta
524.513s, cierre idle529.514s y callback cerrado529.748s, sin respuesta incompleta.
Artifact11271888619 SHA256
40fe6f3ac3b126f0dbfd712e13a455ef914e2d5893cf759f0bc8b510ee13ec98.
La correlación endpoint/socket histórica todavía no está demostrada.

Una reproducción local TLS verificada contra uvicorn0.38.0 demuestra EOF al
reutilizar el mismo socket después de su cierre idle por defecto; NO demuestra
por sí sola el pool Android. Se añade una sonda exclusivamente androidTest/R8:
GET real del realm, única conexión TLS propia, bloqueo de la siguiente escritura
POST después de selección del socket y observación host de su cierre idle real.
Exige EOF Android, misma conexión y revocación de conectividad; si no reproduce,
falla. Después solo una acción sintética explícita puede crear consentimiento
nuevo antes de ejecutar el escenario multimedia sin cambios. No se modifica
RelayClient, TLS, keepalive, admisión ni políticas de retry. El wrapper de socket
delega trust/hostname/session/ciphers; oculta el tipo concreto Conscrypt para las
extensiones opcionales, por lo que esta diferencia del arnés se hace explícita.
La sonda nativa queda PENDIENTE hasta ejecutar el siguiente SHA.

Verify job111187317731 agotó300s:120tests completados y test121
UiSecurityFlowTest.lockDuringOrAfterUnlockInvalidatesOldEnginesAndSnapshots
iniciado, sin aserción fallida registrada. Artifact11272187631 SHA256
c0901876b5d470a5c6172ea20ba4962faa81a885aad5fbc120fa6bff646b0405.
04bf936 sí terminó123connected en293.238s y120offline en279.014s; margen
insuficiente es plausible, no causa confirmada. Se añaden tiempos monotónicos
host por status de prueba, acotados a256 entradas, sin campos arbitrarios.
Se conserva timeout300s, conteos123/120 y fallo obligatorio por timeout.
Un status auxiliar Keystore no termina una prueba. La mejora no demuestra fix.

Compilación local de la sonda: mediaLabR8+instrumentación46s(16/80),
debug+instrumentación9s(6/69),exit0. Pruebas de coordinación6PASS y temporización
3PASS; warning de pipe no cerrado detectado y corregido sin suprimirlo.
No instalación física, cambio productivo ni afirmación de cierre de CI.
Tooling acumulativo363PASS16.618s. La primera ejecución tras sustituir
subprocess.run por el lector temporal falló34 casos del mock de orquestación
porque aún interceptaba la API anterior (intentaba ejecutar synthetic-adb).
Se actualiza únicamente ese doble al punto de llamada real, manteniendo todas
las aserciones y añadiendo comprobación explícita de timeout300s. El test del
lector sí ejecuta subprocesos reales, preserva exit no-cero y timeout como fallo.

### 2026-10-03 — a2867e5 final y presupuesto de expiración sintética

HEAD a2867e5347b9fcd3f17d187ded2159af1fc5746e terminó9/11 workflows,
26/29jobsSUCCESS,3FAIL,0pending. Verify AndroidSUCCESS; no se reutiliza como
aceptación de un SHA posterior. Focuseddebug credential-expiry falló durante
la preparación previa a la acción de expiración: ambos extremos habían probado
video y STOP; la credencial de60s caducó antes de completar la renegociación.
Artifact11273081807 SHA25639d7345c8d91bab8fe89dda78219a2be6283f2e4153ca35d8f56ba58d45cc3c8.
Video debugshard1 falló también credential-expiry. Debugshard0 falló
device-revoked antes de revocar, con Delivery expired; causa todavía en análisis.
Artifact11273302291 SHA2560eee849dbfd050e6f892b1f5dd4926d6847acd0a0921a64e1599c59e6cc0aa16.

La sonda AndroidR8 reprodujo el mecanismo EOF controlado: artifact11272618614
SHA2562dff140f8d227c4e1ef2e8ae30d29b263c0f6bc12268fc07c1090ebc914f63a2,
http-idle-probe.json REPRODUCED, androidEof=true, networkRevoked=true; cierre
idle del socket propio5.000481868s después de respuesta. Esto confirma el
mecanismo, NO identifica retroactivamente el socket de los fallos históricos.

Corrección exclusivamente laboratorio credential-expiry con video: ambos
Engines informan presupuesto restante después de selección/consentimiento.
El emisor limita TTL por el mínimo conservador entre esos plazos nativos y el
deadline absoluto host140s. Resta10s para cierre y1s para emisión; emite una
vez por extremo, falla si emisión tarda más de1s o el reloj retrocede, sin retry
ni renovación. Los deadlines nativos120s y productivos permanecen intactos.
La reserva10s se refiere al expiry absoluto emitido; su observación nativa puede
ocurrir menos de1s después por resolución del timestamp. Una escritura demorada
no garantiza entrega antes del expiry: la validación nativa sigue rechazando
credenciales caducadas. Casos de audio mantienen su TTL sintético30s.

Regresión previa de helper: keyword no implementado produjo2errores; primer
GREEN encontró import math ausente, corregido conservando ambos logs. Revisión
adversarial detectó hostdeadline no incluido; corregido antes de publicar.
Tests focalizados22PASS y tooling completo367PASS16.785s. Debuginstrumentación
51tasks(6ejecutados)8s exit0; R8instrumentación80tasks(9ejecutados)24s exit0.
El intento inicial de build desde directorio incorrecto no encontró task;
se conserva /tmp/umbra-03b-expiry-budget-build.log y no se cuenta comoPASS.
Repository/source guards y diff --check exit0. CI del siguiente SHA pendiente.
No instalación física, UI ni cambio de AccessGate productivo.

### 2026-10-03 — corrección mínima del transporte HTTP tras reproducción

La reproducción Android controlada del mecanismo pooled/idle-close permite una
corrección mínima: RelayClient establece Connection: close en cada solicitud,
incluyendo GET, para impedir que una escritura posterior tome ese socket idle.
No hay retry automático del envelope/proof ni relajación TLS, admisión o lease.
El coste es un handshake adicional por solicitud; se validará con los mismos
límites existentes en la matriz nativa. No se atribuyen todos los EOF históricos
a esta causa. El riesgo separado de callback de timeout antiguo sigue fuera de
esta corrección y no se presenta como un fallo histórico demostrado.

El control negativo conserva el blob productivo a286fe6ac9be27019cdb6ff1b66472ce6713ac8a9d7d
con solo rename/documentación en androidTestConnected/LegacyPooledRelayClient.
La misma prueba exige EOF+revocación del control negativo; únicamente después
de una acción sintética explícita crea autorización nueva y exige que el cliente
productivo corregido complete GET+POST con dos sockets TLS diferentes y sesión
vigente. El host exige freshSocketVerified=true; si falta, falla. La aceptación
nativa del código corregido queda pendiente hasta CI del nuevo SHA.

Se añade rechazo explícito de ambas clases de sonda/control negativo en DEX y
orígenes de mapping R8 productivos. RED4fallos por permitir esos orígenes;
GREEN7tests. No regla keep global: solo inclusión estrecha para TraceReferences
del APK de instrumentación. Tooling completo369PASS17.223s; JVM448connected y
388offline,0fail,0skips; debugbuild y JVM3m34s, R8build45s exit0.

Delivery expired del escenario device-revoked cruzó caducidad durante la
revalidación del challenge, después de autorización inicial; no demuestra un
fallo TURN. Se añade diagnóstico seguro del tipo fijo de control, generación,
TTL restante de entrada/rechazo y duración monotónica, sin identificadores ni
payloads. El error sigue fallando: no se marca transportado ni se descarta STOP.
Si escribir diagnóstico falla, se conserva el rechazo original y un fallo
suprimido de texto fijo. También se mide duración hasta sellar inventario STOP.
La falta de este diagnóstico impide atribuir todavía el tipo concreto vencido.
Validación adicional del wiring final: debuginstrumentación8s y R8instrumentación
24s exit0; HTTPS real contra SQLite/libsignalJNI y certificado verificado exit0.
El primer intento HTTPS usó un classpath diagnóstico con directorios y fue
rechazado antes de compilar; repetición con el classpath exacto resuelto del
proyecto pasó, conservando ambos logs. Comparación del control negativo con su
blob de origen: iguales tras retirar únicamente rename y comentario de procedencia.

### 2026-10-03 — focalizadas del presupuesto y cierre de diagnósticos

683b598e0c70aec668cca9ba57db98c7c49c7e92: Focused media regressions
run37134770337SUCCESS. Tres credential-expiry por debug y tres por R8,
con recibos completos/capturaFINALIZED revisados; emisión única98s por extremo,
1ms observado para emitir, budgets nativos114861–116390ms, sin renovar.
Artifact11278223935 SHA25652f01bd159ebc07b4c3dd7c953b5022df9e32f98fd7124d24a3eb8a9baa65e0e;
R8artifact11277728927 SHA2566887ec5001b2c63d7c5274fdc816026430d2dd0433463727a8affb93082e3d26.
Esto valida esas seis ejecuciones del fix; no identifica por sí solo la causa
de otras carreras ni acepta un SHA posterior.

Una segunda revisión del diagnóstico exige fallar también si no puede escribirse:
se adjunta fallo fijo suprimido y se relanza el mismo rechazo original ANTES de
cualquier rama de expiración esperada. No se permite PASS sin ese diagnóstico
por fallo de storage. Debug/R8 de este último cambio8s/24s exit0.
Build release+lint connected/offline38s y debugAPKs9s exit0. Políticas APK de
los cuatro binarios PASS, incluidos DEX/mapping y permisos; JNI/empaquetado y
contador JVM exit0. No ejecución física y aceptación CI acumulativa pendiente.


## Candidato4708c0b — diagnóstico exportable de cierres (2026-10-03)

HEAD `4708c0bc497b5afc27d7df98dd5ca601bc4296bc`, árbol
`ca40023bb8f531729796e7e4a8a490745cc75258`. Checkout de los artefactos
`c0897404c7e77eb716e72aa2cf6abd5c60f30e92`; equivalencia de árbol pendiente
hasta consulta Git. Estado consultado:11workflows,29jobs,26SUCCESS,2FAILURE,
1pendiente; no aceptación final.

Video37136521627 debug0/job111242552549: expired-auth falla la aserción de
estabilidad terminal después de350ms; el motivo inicial no fue exportado.
El motivo final `negotiation-timeout` no demuestra retrospectivamente cuál
campo cambió. También device-revoked falla `Delivery expired` antes de aplicar
la revocación prevista. Artifact11279287463 SHA256
`cb538034d4ce5dc67497ad242af03aced295eff32e637225a9b614aa2d5ba302`.
Debug1/job111242552519: wrong-fingerprint falla con `CallService.Interrupted`
en la autorización de un control enumerado previamente, antes de iniciar HTTP.
El diagnóstico final nativo indica `native-certificate-binding`,4357ms.
Artifact11279003586 SHA256
`bf08ba2e32c1d236170933457f53978dd304f6fa6f5861a51e9a099197d72a0f`.
Esto demuestra cancelación que alcanza una entrega pendiente; no fallo de TURN.

La información de caducidad y espera de inventario se escribía en archivos
privados que el runner no exportaba. Se corrige solamente la observabilidad:
status de instrumentación con tipos cerrados de control, tiempos monotónicos,
TTL restante y booleanos, sin IDs, payloads ni secretos. La observación terminal
incluye estado/motivo inicial y final, contadores cero/no-cero y cierre pendiente,
completo o excepcional. La espera350ms y todas las aserciones permanecen.
La espera de quiescencia conserva criterio, periodo50ms y deadline; exporta
número de muestras y frames de stack mediante allowlist acotada, nunca nombres
arbitrarios, rutas o thread names. No se declara corregida la causa mediante
este cambio diagnóstico.

Validación local del diff diagnóstico:370tooling tests,18.355s,exit0;
9regresiones de quiescencia incluidas. Fuente13controles y repository_guard761,
exit0; `git diff --check` exit0. Debug androidTest build5s/exit0.
Un comando inicial combinó debug y mediaLab bajo el flag que deshabilita debug:
falló antes de compilar por tarea inexistente (exit1); se preserva
`/tmp/umbra-03b-observability-build.log` y se separan ambas construcciones.
No cambia AccessGate, TTL, consentimientos, producción, UI ni permisos.


Cierre consultado de4708c0b:10/11workflows,27/29jobsSUCCESS,2FAILURE,
0pendientes. Verify Android sí terminóSUCCESS en su propio SHA; los dos rojos
son video debug antes descritos. Fetch del checkout c0897404 confirma el mismo
árbol ca40023b,exit0. Artifact R8shard0/11279652110 SHA256
`3d6e4d3257d51e74648edad3b24f8cd86897dbd62bcda1b7f648416d5db2cbef`:
16/16casos PASS y el control focalizado con cliente Android legacy reproduce EOF;
la conexión propia tuvo idle5.0017s. Tras conectar explícitamente una sesión
sintética nueva, el transporte corregido reporta `freshSocketVerified=true`.
El JSON exige además `androidEof=true` y `networkRevoked=true`.
Esto prueba el mecanismo controlado y el arreglo mínimo de pooling; no identifica
la conexión de los incidentes históricos. No se subieron capturas brutas.

## Regresión del control pendiente tras rechazo de huella (2026-10-03)

Reproducción con Engine/libsignal real: crear ICE en sesión autorizada,
comprobar autorización, cancelar la sesión, ejecutar la misma autorización;
se obtiene exactamente `CallService.Interrupted`. El ciphertext no cambia y no
se marca upload. El método antiguo de clasificación, exclusivo de expiración,
relanza ese mismo error: RED/exit1. Clasificador de test nuevo, condicionado a
wrong-fingerprint+FAILED+native-certificate-binding+cero tres contadores+
misma sesión+mensaje exacto, acepta únicamente abandonar el pump: GREEN/exit0.
Los estados del adaptador en esta reproducción JVM son sintéticos; la evidencia
nativa del motivo viene del artefacto4708c0b, no de este test JVM.
Comando `python /tmp/umbra-native-rejection-semantic-proof.py`; logs
`/tmp/umbra-native-rejection-semantic-{red,green}.log`. No petición, retry ni
transporte exitoso. El helper está exclusivamente en androidTestConnected;
TraceReferences incorpora solo esa clase de test para el laboratorio R8.

El consumidor retorna a las comprobaciones existentes de350ms, estabilidad
terminal y contadores cero. No reconoce desconexión, watchdog, expiración,
identidad cambiada, otro callId ni otro error como esta cancelación.
`closureObservation` es diagnóstico, no una aserción nueva de disposición
completa; se mantienen las validaciones de cierre existentes sin sobreafirmar.
Revisión independiente del diff no encontró un bloqueante nuevo.

Validación acumulativa local:371tooling tests,17.588s,exit0;10tests focalizados
(clasificación estricta+quiescencia),4.490s,exit0. JVM449connected/388offline,
0failures/errors/skips, debug androidTest+unit suites build1m46/exit0.
Offline unit task UP-TO-DATE: se conservan los XML388/0 del árbol productivo
sin cambios, no se presentan como nueva ejecución física. Construcción R8 de
observabilidad previa31s/exit0; R8 del helper integrado se registra al terminar.
AccessGate productivo y pairing productivo no cambian en este diff. Los rojos de
caducidad y motivo terminal aún requieren la nueva observación nativa: no se
convierten en PASS ni se incrementan TTL/plazos.

R8 del helper integrado: `assembleConnectedMediaLabAndroidTest` con flag
`umbraMediaLab=true`,23s/exit0. Repository_guard final764fuentes/exit0;
source_policy13/exit0;diffcheck/exit0. Ningún archivoUI ni AccessGate cambió
respecto a95aabb6. La publicación siguiente es candidato en validación, no
cierre29/29 ni autorización de instalación física.

## 2026-10-03 — final 7063b41 receipt and bounded observability correction

Candidate `7063b414453026598639ecaeb0d2437dad25d367`, tree
`77ac9ea85e4faae7f7ca2f19d8f7e1a6e109f8a3`: 11 workflows/29 jobs,
8 workflows/26 jobs SUCCESS, 3 jobs FAILURE, no pending. This supersedes the
intermediate observation that all video shards passed; debug shard 1 completed
later with a failure. Historical reds are retained, not accepted as green.

* Verify run 37139669744, Android job 111252296272: build, JVM and Android
  instrumentation passed before the two-AVD audio scenario failed waiting for
  resumed decoded audio after mute. Initial decoded audio and quiet mute barriers
  had already passed. B reported 143 decoded buffers, native failureStage=none,
  TURN not expired. These observations do not prove microphone/decoder failure
  or establish why post-unmute audio did not meet the unchanged threshold/deadline.
  Artifact 11280437509 SHA-256
  `a067a797e4bc87af1f56835714eb90b4e59bcf8b949ddaf42965599b3b38aab7`.
* Focused run 37139669648, debug video job 111251305152: credential-expiry-3
  failed HTTP 400 after STOP inventory settling took 27,966,348,661 ns on A
  and 5,381,701,156 ns on B. Both had positive decoded audio/video first.
  Artifact 11280172130 SHA-256
  `3c4642c9e22477d72ddd68163cc2ed5be225cccc99aa65c6eac1e88d61150012`.
* Video run 37139669640, debug shard 1 job 111251305056: storage-failure B
  rejected an invalid STOP envelope set before storage-failure injection. Its
  settling took 40,867,472,417 ns/793 samples; A took 5,964,578,573 ns/112 samples.
  Artifact 11280263113 SHA-256
  `c145cbdabc1cac0914f6ba5810ee0225de32ba8dead3db1577a2b0c0bfda829b`.
  Engine.outbox filters TTL-expired controls; expiry alone does not delete them.
  Missing initial inventory/expiry observations prevent definitive attribution.

Proven lab diagnostic defect: DenialDiagnostics classified actual PUT message
item paths as OTHER and the nonexistent POST collection route as MESSAGE_SEND.
It also lacked the exact two message PUT HTTP-400 categories: Invalid expiry and
Message id mismatch. New tests first failed (7 tests, 3 assertion failures), then
passed after the fixed closed-category mapping. ASGI delivery remains transparent;
unknown/oversized/extra-field responses remain OTHER, with no raw details retained.
Commands: `python -m unittest discover -s scripts/tests -p test_voice_http_diagnostics.py -v`.
Preserved local logs: `/tmp/umbra-03b-http-denial-{red,green}.log`.

Audio failure diagnostics now distinguish API return from native queued execution:
initial/mute/quiet/resume booleans, counter baseline/delta, native state, processing
state, admission to transmission, monotonic phase times and deadline reached.
The existing final assertion still fails. No 70-second scenario deadline or 50
post-unmute decoded-buffer requirement changes. STOP diagnostics read raw synthetic
Records in a transaction (no Engine.outbox maintenance), exporting only count,
expired count and monotonic time before/after settling. No IDs, payloads, SDP,
passwords, credentials, PCM or transport addresses are exported.

Independent controlled executor experiment confirms global executor-idle is stronger
than completion of a pre-STOP producer: unrelated work can block observation after
that producer finished. This is a laboratory design finding, not proof of the
historical Android workload. No fence/TTL/production scheduling change is made here;
additional actual-CI observability is required before choosing the correction.

Production AccessGate blob remains `14973c4e3626f39c8c21e1334db563c4a8bbdd9c`.
No production files, Pairing semantics, admission, UI, policy or cryptography change
in this checkpoint. No physical installation/execution. CONTRACT_CHANGE_REQUIRED=NO.
These diagnostic changes do not close the three media failures.

Local validation of this diagnostic checkpoint: tooling 375 tests PASS in 17.599s
(includes two new source-inspection guards; those are not Android behavior tests).
The earlier tool run failed one source guard because a new Bundle variable reused
`diagnostic` before the audio callback; the diagnostic variable was scoped/named
`inventory` and the existing assertion remained unchanged. Red log preserved at
`/tmp/umbra-03b-denial-diagnostic-tooling-final.log`.
Connected debug test APK rebuilt successfully in 2s. Initial combined relevant JVM
command passed with both unit test tasks UP-TO-DATE (unchanged production/test-JVM
sources); it is not a newly executed 449/388 behavioral run. R8 media-lab build and
its instrumentation APK are compiled locally; runtime requires Actions/KVM.
Repository guard 765 source files PASS; 13 source policies PASS; diff --check PASS.
A first read/patch command used the android directory with a root-relative path
and failed FileNotFoundError before writing; corrected with absolute path. It did
not alter a different worktree or suppress a failed validation.

## 2026-10-03 — FIFO STOP inventory fence candidate (after b55900e)

A controlled real ScheduledExecutor experiment reproduced the incorrect global-idle
precondition: an earlier STOP producer completed, but unrelated later work kept the
worker busy and the old helper exhausted its scenario deadline. The direct RED
command exited 1 with the original helper's deadline assertion; source and log are
preserved under `/tmp/umbra-stop-global-idle-proof/`.

The proposed correction is test-APK-only: submit a FIFO marker to the actual native
worker after synchronous STOP. Markers on the independent watchdog cannot satisfy
it. The marker proves earlier tasks finished; it does not assert capture/resources
are closed. Existing strict late-STOP inventory, real decoded media, monotonic
capture bounds, peer delivery, TTL and final assertions remain authoritative.
No later task may reactivate video before inventory: the current asynchronous STOP
producer reads the persistent CONFIRMED/STOPPED state inside its worker task.

The fixture locates the two app-owned ExecutorService instance fields by runtime
interface type, not field name, and accepts only the previously observed worker
thread. It verifies exact session runtime class, identity, alive thread and the
original scenario deadline. It cancels only its own marker futures without
interrupting or shutting down either executor. Reflection/discovery failure is a
failure, never success. No new product API or R8 keep/deoptimization rule is added.
The explicit TraceReferences fixture inventory includes only the new test helper.
Actual optimized local mapping retains worker/watchdog as two ScheduledExecutorService
fields, obfuscated to x/y; this is not Android runtime proof of reflection.

Five real-executor tests PASS: FIFO ordering with busy later work; watchdog cannot
satisfy blocked worker; wrong owner/thread; stale worker and exact deadline;
missing executor inventory. A first deadline test incorrectly offered only 50ns
of real latch waiting and failed; its controlled-clock domain now provides a real
bounded wait and returns exactly the virtual deadline on confirmation, which still
must be rejected. No product deadline changed. The earlier failed log is retained.

Full local tooling 380 PASS in 19.476s; connected test APK build 3s PASS; R8 media
and test APK build 16s PASS. Independent relevant JVM execution with --rerun-tasks
on published b55900e: connected449/offline388, zero failures/errors/skips, 3m30s.
The new fence alters no JVM/product source. Backend/local command with explicit
JDK21 PASS (244 relay tests plus core/85 security scenarios, syntax/source policy).
The unpinned invocation mixed Java25 with javac21 and failed JavaSyntaxCheck with
`release version 21 not supported`; that failure is preserved, not counted PASS.
Production AccessGate, admission, Pairing, TLS/TURN, UI and offline policy unchanged.
Native debug/R8 execution of this fence still required on its own published SHA.

### b55900e native R8 receipt exposed a second proven fixture mismatch

Video run 37142843558, R8 shard0 job111260670415 failed in the existing focused
receipt validator after successful PCAP finalization, not in tcpdump/media transport.
Artifact11281461446 SHA-256
`3f37e78c7e9afee146d490f026769abbd3b51accfd84c8b63797154f38f1aeff`.
Re-evaluating each exact artifact receipt against the unchanged validator:
expired-auth=True, degraded-network=True, ipv6-tls=False. IPv6/TLS decoded remote
patterns20/24 and audio702/748, but A reported capturedFrames19. The validator
requires capturedFrames>=20; the fixture's publish predicate previously required
remote readiness/audio but omitted the local source threshold for bidirectional video.
This explains this particular finalized-receipt rejection conclusively.

The new regression compiles/executes the actual fixture predicate and first failed:
it could publish a sending phase below20 source frames. The minimum fixture fix
waits for the same existing local20-frame threshold as the unchanged validator.
Receive-only consent still requires zero local frames (the existing explicit
zero-capture assertion remains). No decoder/source threshold or deadline is reduced
or increased. Both native phase reactivation and active phases use this predicate.
The predicate test then passed across direction roles and 0/19/20/21 boundaries.
Logs `/tmp/umbra-03b-video-phase-{red,green}.log` retained. This unit test proves
predicate alignment, not hardware/native decoding. Own new-head native CI required.

Combined FIFO/readiness candidate local result: 381 tooling tests PASS20.270s;
debug instrumentation APK build PASS; optimized media/test APK build PASS15s.
Independent review confirmed FIFO worker matching/fail-closed behavior and
receive-only zero-capture semantics. Review is not native acceptance. No workflow
is cancelled to publish this follow-up; the previous b55900e run must finish first.

### b55900e native diagnostics confirm the STOP inventory expiry chain

Focused run37142843489, debug job111260669787, camera-permission-revoked-1 failed
BEFORE permission revocation, at STOP inventory. Artifact11281132722 SHA-256
`67ec6267ff17b3e992bc566cf64cc232de90837789f4c4858fc53dd716b9f1e7`.
B immediate raw inventory: count1, expired0, observed413,992,469,082 ns.
After global-idle observation: elapsed29,743,179,381 ns/578 samples; raw inventory
count1, expired1, observed443,455,604,917 ns. Engine.outbox then returned count0.
A immediate/settled count1, expired0 after3,928,212,375 ns. This directly establishes
that B's valid stored control expired while the fixture waited for global executor
idle, and the unchanged production expiry filter correctly excluded it. The second
was floor-based absolute expiry; a 30-second contract can have under30s remaining.
No revocation, camera capture continuation or production TTL bug is inferred here.

This actual Android evidence, together with the real-executor ordering RED/GREEN,
supports replacing the overly broad idle precondition with the FIFO fence. It is
not evidence that the new fence already executed on Android; its own CI remains
required. The earlier HTTP400 in706 is still individually unclassified (the prior
observer emitted OTHER), not retroactively declared INVALID_EXPIRY.

### b55900e Verify timeout demonstrated a too-tight whole-suite lab watchdog

Verify run37142843514 Android job111260837488 failed before media execution:
123-case connected instrumentation exhausted300s. Artifact11280973264 SHA-256
`bea95e90cf385caecbe5349862ae38f8c165432c16168b37ff2a08a2de69d2fc`.
The actual timing receipt records119 completed methods with no failure/skip;
test120 started296.444172262s into the suite and had3.556s remaining. No whole
suite PASS is claimed. Heavy real-crypto test-class totals: pairing persistence
65.166s, access readiness51.655s, vault password40.810s, emergency25.240s,
restricted content25.098s. This is host timing, not device CPU or a product lease.

The earlier706 actual timing receipt completed all123 at299.233399312s, only0.767s
below the watchdog. Its final four methods took8.285+2.794+1.425+1.032=13.536s;
the current test120 start plus that observed tail requires approximately310s.
The selected whole-suite lab watchdog is330s (310s inferred workload estimate plus20s
bounded host variability). It is fixed before evaluating the new execution.
No per-test assertions, case count, selection, skip policy or product timeout
changes. Splitting was considered but rejected in favor of keeping the exact
full123/120 inventory and invocation unchanged. AccessGate remains240s; no old
lease is renewed. Timed-out execution still fails and retains its timing receipt.

A new orchestration regression simulated that estimated310s workload: RED original
300s watchdog TimeoutExpired; GREEN configured330s passes11 orchestration tests,
including zero-count, skip, missing completion and failed-adb rejection. This is
not Android acceptance. Native full-suite execution on the new SHA remains required.
Logs `/tmp/umbra-03b-suite-budget-{red,green}.log` preserved. No further watchdog
increase is authorized by these observations; a new failure needs fresh diagnosis.

Final local tooling check of the cumulative laboratory corrections:382 tests PASS
in20.315s. Review confirms no inventory filtering, assertion relaxation or product
lease change. The estimated310s tail is explicitly inferred across two runs, not
a measured completion time of the failed suite.
