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
