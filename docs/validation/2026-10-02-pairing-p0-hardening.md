# Execution03 — pairing P0, evidencia acotada de 2026-10-02

Estado: candidato local sobre base `c117051ac5353c84ef840d9b5f1eafa81f86ddb4`,
rama `codex/pairing-p0-hardening`, árbol de trabajo con modificaciones. No es
un SHA final revisado, CI validada, instalación ni aceptación física. El contrato
aditivo está en [PAIRING_PRODUCT_V1](../contracts/PAIRING_PRODUCT_V1.md).

## Evidencia verificada al redactar

- Log local `/tmp/umbra-pairing-full-jvm.log`: `BUILD SUCCESSFUL in 3m 43s`;
  incluye tests connected/offline debug y compilación androidTest. XML de
  `android/app/build/test-results/testConnectedDebugUnitTest`: 426 tests,
  0 failures/errors/skipped; offline: 368, 0 failures/errors/skipped.
- Esos XML contienen 29 casos focalizados por variante: PairingTest 8,
  PairingAdversarialTest 8, PairingForensicsTest 7 y PairingProductTest 6.
  Son pruebas JVM con libsignal real y Records sintéticos, no SQLite Android.
  Los casos focalizados están incluidos en los totales; no se suman otra vez.
- `python -m unittest discover -s scripts/tests -p test_physical_pairing.py -v`:
  7 PASS. Validan selección/rechazo/privacy del runner con respuestas simuladas;
  no se ejecutó ADB ni instalación para estas pruebas.
- El equipo reportó suite relay 238 PASS. Hasta anexar su recibo de ejecución,
  se distingue ese resultado comunicado de los XML inspeccionados directamente.
  Las filas R de abajo identifican pruebas reales del source, sin convertirlas
  en aceptación física ni prueba criptográfica de ciphertext sintético.
- Ejecución focalizada posterior: `/tmp/umbra-pairing-boundary-final.log`,
  BUILD SUCCESSFUL en 13 s; XML connected: 39 casos, 0 failures/errors,
  con Boundary 9 y Product 7. No es un nuevo full suite offline/connected;
  los 426/368 anteriores pertenecen al corte previo y no incluyen esta expansión.
- `/tmp/umbra-pairing-product-all-https.log`: 139 aserciones PASS, incluidas
  41 nuevas `PASS pairing HTTPS:`. Verifica TLS/SQLite relay/libsignal reales,
  QR de píxeles sintéticos y código humano, mensajes y ACK bidireccionales,
  retries y confianza UNVERIFIED previa a verificación explícita. MemoryRecords
  cliente no es Vault Android. Este recibo precede a las últimas APIs de recovery
  `humanCode/retryPairing`, que tienen prueba JVM local pero aún no HTTPS propio.
- Android/R8 sigue pendiente. DevicePairingPersistenceTest tiene cinco casos
  escritos, incluido retorno de picker; compilación no implica ejecución.

Los archivos de `/tmp` son evidencia local efímera, no artefactos publicados.
Cambios posteriores a estos recibos requieren nueva validación pertinente; no
atribuir automáticamente resultados anteriores al árbol final ni a APK instalados.

## Hallazgo y decisión conservadora

El núcleo original crea contacto del invitador en accept y del receptor en
complete, no al importar una invitación. Realm/admisión y confianza humana son
independientes. Los casos mixtos pueden terminar asimétricamente: invitador
admitido crea UNVERIFIED pero receptor sin realm rechaza complete. Se conserva
ese rechazo ante credencial no vacía/autoridad distinta, sin degradarlo a tarjeta
vacía. La futura presentación debe explicar el bloqueo; esta ejecución no cambia
MainActivity ni reclama resolver la experiencia humana Cerca/QR.

Los nuevos wrappers no conceden VERIFIED. Relay nuevo conserva admisión y cuotas,
recibe blobs opacos, persiste selección atómica e impide cambiar bytes en retry.
El first claimant de un código puede negar disponibilidad sin completar pairing.
El informe no certifica secreto absoluto, anonimato, deniability ni borrado físico.

## Matriz de 60 requisitos

IDs conservan el orden pedido. **JVM** significa recibo XML positivo (29 iniciales en ambas
variantes; Boundary y Product ampliado únicamente en el focalizado connected); **PARCIAL** identifica cobertura menor que el requisito completo;
**PENDIENTE** no cuenta como PASS. **R** identifica `relay/tests/test_rendezvous.py`,
con suite reportada por el agente de relay y recibo global pendiente de anexar.
No convertir estas 60 filas en 60 pruebas ejecutadas: varios requisitos comparten
un método, y otros no tienen ejecución suficiente.

Abreviaturas: **F** = PairingForensicsTest, **T** = PairingTest,
**A** = PairingAdversarialTest, **P** = PairingProductTest,
**B** = app.umbra.pairing.PairingBoundaryTest; **V** = DevicePairingPersistenceTest (source/compilación, ejecución pendiente).

| ID | Requisito | Estado y evidencia exacta / límite |
|---|---|---|
| 1 | Same realm MemoryRecords | JVM — F.sharedRealmCreatesContactsOnlyAtAcceptAndComplete |
| 2 | Same realm Vault | PENDIENTE — V, no recibo Android en este corte |
| 3 | Different realm | JVM — F.differentRealmsRejectBeforeContactAndDoNotConsume |
| 4 | No admission | JVM — F.noAdmissionPairsButCannotConverseEvenAfterVerification |
| 5 | Mixed admission | JVM — F.admittedInviterUnconfiguredJoinerStopsAtComplete, unconfiguredInviterAdmittedJoinerStopsAtAccept, admittedAndUnadmittedWithSamePinnedRealmCanPair |
| 6 | Self pairing | JVM — F.selfPairingLeavesInvitationAndPrekeysUnchanged |
| 7 | Lock after invite | PENDIENTE Android — V.exchangeAcrossReopens cierra/reabre ambos Vault tras invite |
| 8 | Lock after request | PENDIENTE Android — V.exchangeAcrossReopens tras request |
| 9 | Lock after accept | PENDIENTE Android — V.exchangeAcrossReopens tras accept |
| 10 | Engine recreation every stage | PARCIAL — A.reconstructedServicesPreserveExactTranscriptAndRevocation recrea servicios; T.realSignedPairingRequiresHumanVerificationAndSurvivesReopen recrea Engine al final |
| 11 | Vault recreation every stage | PENDIENTE — V.exchangeAcrossReopens, ejecución pendiente |
| 12 | Process recreation | PENDIENTE CI — PairingRestartFixtureListener/run_pairing_restart: cuatro force-stops después de commit, sin simular muerte durante commit |
| 13 | Invite retry | JVM + HTTPS — P.locallyFailedPublicationRetainsOneRecoverableCodeWithoutSnapshotLeak, resumeFile y retryPairing reales preservan operación/ciphertext. createPairing inicia otra operación por contrato |
| 14 | Request retry | JVM — T.oneUseAndExactTranscriptRetries |
| 15 | Ack retry | JVM — T.oneUseAndExactTranscriptRetries, A.reconstructedServicesPreserveExactTranscriptAndRevocation |
| 16 | Competing requesters | JVM — A.eightConcurrentRequestsHaveOnlyOneWinner |
| 17 | Wrong request | JVM — T.tamperingNoncanonicalVersionsAndWrongPeerFail, accept en invitador equivocado |
| 18 | Wrong ack | JVM — T.tamperingNoncanonicalVersionsAndWrongPeerFail, complete en peer equivocado |
| 19 | Expired invite | JVM — A.correctlySignedExpiredFutureAndNoncanonicalTimestampsAreRejected |
| 20 | Exact expiry | JVM — B.expiryIsInclusiveForRequestAcceptAndCompletedReplayWithoutMutation y expiryWhileWaitingForTransactionRejectsRequestAndAcceptance; tres casos adicionales B prueban rollback al vencer en la escritura final |
| 21 | Revoked invite | JVM — T.revocationRejectsNewAndConsumedRequests |
| 22 | Request after revoke | JVM — T.revocationRejectsNewAndConsumedRequests rechaza accept del request; un receptor sin noticia de revocación aún puede construir request local |
| 23 | Ack after revoke | PARCIAL — B.revokeConsumedInvitationSuppressesImmutableAckAndCancellationSuppressesComplete; revocación local del invitador no borra un ack ya entregado ni cancela remotamente al receptor |
| 24 | Invalid signature | JVM — T.tamperingNoncanonicalVersionsAndWrongPeerFail altera byte de firma ack |
| 25 | Card substitution | B.signedRequestRejectsCompleteValidCardBelongingToAnotherIdentity; 43/43 focalizados en cada flavor, log final abajo |
| 26 | Identity substitution | B.signedRequestRejectsOnlyCardIdentityFieldSubstitution; 43/43 focalizados en cada flavor, log final abajo |
| 27 | Prekey substitution | JVM — B.validOuterRequestCannotHideInvalidCardOrPrekeySignatures altera signedSig y kemSig con firma externa válida, conserva estado |
| 28 | Truncated input | JVM — A.signedNoncanonicalLinesAndBoundedMalformedCorpusNeverWrite |
| 29 | Oversized input | JVM — A.signedNoncanonicalLinesAndBoundedMalformedCorpusNeverWrite, P.qrDecodesRenderedPixelsButRejectsSafetyQrAndOversizedFrames |
| 30 | Unknown version | JVM — T.tamperingNoncanonicalVersionsAndWrongPeerFail |
| 31 | Bad base64 | JVM — A.signedNoncanonicalLinesAndBoundedMalformedCorpusNeverWrite |
| 32 | Noncanonical input | JVM — A.signedNoncanonicalLinesAndBoundedMalformedCorpusNeverWrite |
| 33 | No partial contact | JVM — A.acceptStorageFailureRollsBackContactPrekeysAndConsumption; SQLite pendiente |
| 34 | Atomic rollback | JVM — B.eachPersistenceFailureRollsBackAllRecordsAndAllowsExactRetry cubre request/accept/complete; B comprueba expiry tras última escritura; V SQLite aún pendiente |
| 35 | Both UNVERIFIED | JVM — F.sharedRealmCreatesContactsOnlyAtAcceptAndComplete y P.fileFlowSnapshotsKeepSecretsOutAndRequireHumanVerification |
| 36 | Exact identities | JVM — T.numericAndQrVerificationBindBothIdentities |
| 37 | Safety QR rejected | JVM — P.qrDecodesRenderedPixelsButRejectsSafetyQrAndOversizedFrames |
| 38 | Pairing QR roundtrip | JVM — P.qrDecodesRenderedPixelsButRejectsSafetyQrAndOversizedFrames, píxeles sintéticos, no cámara |
| 39 | Malformed QR | JVM — P.boundedQrParserFuzzRejectsMalformedInputWithoutCreatingState |
| 40 | QR maximum | JVM — P.qrDecodesRenderedPixelsButRejectsSafetyQrAndOversizedFrames, límites de caracteres/frame |
| 41 | Code entropy | JVM + construcción inspeccionada — P.humanCodesHaveSixteenBase32SymbolsAndCanonicalEquivalentInputs; 10 bytes SecureRandom representados sin pérdida en16 símbolos de32=80 bits. No se infiere entropía desde una muestra de unicidad |
| 42 | Code normalization | JVM — P.humanCodesHaveSixteenBase32SymbolsAndCanonicalEquivalentInputs |
| 43 | Code expiry | R.test_expiry_rejects_exact_retry_and_purges_children; B.authenticExpiredCodeInvitationDecryptsButIsRejectedAsExpired (AEAD auténtico, reloj/fixture controlado), 43/43 focalizados en cada flavor, log final abajo |
| 44 | Code replay | R.test_code_claim_single_claimant_exact_retry y HTTPS code retry real: solo el mismo claimant reintenta; reserva no equivale a validación de identidad |
| 45 | Wrong code | JVM — P.courierAeadBindsInvitationDirectionHeaderAndCiphertext |
| 46 | Code cancel | HTTPS real: revokeOnline, rechazo tipado del joiner, ninguna solicitud/contacto nuevo y relay saludable; SQLite confirma tombstone revocado |
| 47 | Rate limit | R — test_global_quota_and_ingress_rate_limit; conserva ingreso existente |
| 48 | Invite tamper | JVM — P.courierAeadBindsInvitationDirectionHeaderAndCiphertext altera header de invite cifrada; A/T cubren formato firmado |
| 49 | Request tamper | JVM — P.courierAeadBindsInvitationDirectionHeaderAndCiphertext altera ciphertext request |
| 50 | Ack tamper | P.tamperedAckCourierCiphertextCannotCompletePendingPairing; 43/43 focalizados en cada flavor, log final abajo |
| 51 | Wrong direction key | JVM — P.courierAeadBindsInvitationDirectionHeaderAndCiphertext intenta abrir REQUEST como ACK |
| 52 | Wrong AAD | JVM — P.courierAeadBindsInvitationDirectionHeaderAndCiphertext altera dirección/id/digest/expiry/nonce |
| 53 | Mixed sessions | JVM — P.courierAeadBindsInvitationDirectionHeaderAndCiphertext abre request con otra invitación |
| 54 | Relay retry | R.test_candidates_local_owner_selection_retry_and_capability_isolation y52aserciones HTTPS pairing finales, incluidos retryPairing/humanCode y clientes nuevos |
| 55 | Relay competing claim | R — test_code_race_reserves_exactly_one_request_id y test_owner_race_has_one_selection |
| 56 | Relay restart | R — test_candidates_local_owner_selection_retry_and_capability_isolation recrea app sobre mismo SQLite; no crash durante commit |
| 57 | Quota | R — test_candidate_quota_and_failure_rollback, test_global_quota_and_ingress_rate_limit, test_revocation_tombstone_quota_and_cascade |
| 58 | Server DB no plaintext transcript | HTTPS/SQLite real:6wrappers INVITE/REQUEST/ACK cifrados inspeccionados; no transcript/alias en claro. Un candidato basura deliberado nunca se selecciona. No auditoría global de OS |
| 59 | Server DB no plaintext code | Código generado en cliente; inspección schema/requests y SQLite real recibe solo locator/capability derivados y ciphertext, no el código. Metadatos públicos no son secretos |
| 60 | No sensitive snapshots/logs | JVM redaction y runner.test_failure_receipt_never_logs_serial_or_tool_error; assertions nuevas redactadas, sin logging sinks añadidos en dominio. No claim de barrido global de Android/OS |

## Preparación física y autorización

[Runner y procedimiento](../PHYSICAL_PAIRING.md): exactamente dos USB distintos,
rechazo qemu/emulador/TCP/filas duplicadas, package aislado permitido y hash local
más instalado. Evidencia solo hash de serial, modelo/API, package/versión/hashes;
no serial, QR, código, request/ack ni stderr en informes. Hash de serial permanece
correlacionable; no se promete anonimización. Directorio privado nuevo por intento.

No se ejecutó aquí preflight real, instalación, borrado, reset, force-stop, cámara
ni Bluetooth. `PREFLIGHT_READY` únicamente identifica bytes instalados exactos,
nunca aceptación. `PENDING_INSTALL_APPROVAL` requiere autorización nueva del
propietario por cada APK exacto, en otro flujo; este runner no instala. Todos los
casos físicos conservan MANUAL_PENDING. No se simulan recibos humanos.

## Pendientes antes de aceptación

Revisión humana de la composición criptográfica, cambios de autenticación/wire y
migración; extensión HTTPS para las APIs de recovery posteriores; Vault/SQLite Android debug/R8;
interleavings de bloqueo y sustituciones completas de tarjeta/identidad;
inspección de DB con secretos sintéticos conocidos; muerte de proceso real;
integración de presentación de Claude y dos teléfonos con verificación humana,
mensajes/ACK bidireccionales y persistencia. Deployment relay y APK productivo
no están autorizados ni acreditados por este documento.

### Preflight disponible, 2026-10-02

Consulta read-only a ADB Windows con `ADB_LIBUSB=1`: exit 0, cero dispositivos
autorizados, cero transportes USB, cero dispositivos unauthorized. No se instalaron
APKs. `PHYSICAL_BLOCKED_ENVIRONMENT=YES` en esta sesión; no contradice la
disponibilidad histórica comunicada de los dos teléfonos. `/dev/kvm` existe pero
el usuario actual no tiene acceso de lectura/escritura; la instrumentación deberá
ejecutarse en Actions. No se cambiaron permisos del host.

El límite estructural calculado de invite v1 es 402 caracteres con timestamps
decimales máximos de 12 dígitos; el ejemplar firmado actual de 10 dígitos mide397.
El cálculo no acredita un QR físico ni un ejemplar con fecha futura válida.

### Recibo local ampliado

`/tmp/umbra-pairing-relay-final.log`: `PYTHONPATH=relay python3 -m pytest -q relay/tests`,
exit0,238PASS,21.97s. `/tmp/umbra-pairing-tool-tests.log`:260testsPASS,6.048s.
`/tmp/umbra-pairing-build-final.log`: build debug/release, lint debug/release,
androidTest packaging y fullJVM, exit0,5m8s; connected436/offline378,
cero failures/errors/skipped. `/tmp/umbra-pairing-apk-policy.log`: cuatro políticas
APK PASS. Esto acredita compilación/R8 y empaquetado, NO ejecución R8 del pairing.
El helper aditivo `resumeFile` y las aserciones que lo ejercitan se añadieron
después de arrancar ese build; requieren el próximo corte de validación.
No se atribuye ese resultado a un SHA futuro.

### Recuperación causal del cliente de admisión

El HTTPS ampliado detectó403 al crear un cliente nuevo con desafíos sin usar del
cliente anterior. Causa: `AdmissionStore.batch()` intentaba emitir siempre ocho
nonces nuevos aunque el dispositivo ya ocupase parte de su cuota de ocho.
Regresión previa al fix: `/tmp/umbra-pairing-admission-batch-RED.log`,3FAIL/3PASS,
SHA256 `9b3eecf97e5be9e75047688c5e3fa955dd57c4555526a6b95a556f2f6dce7074`.
Ahora reutiliza desafíos públicos no consumidos de operación genérica y rellena
solo el espacio libre, sin cambiar wires, TTL/deadlines, cuota o consumo único.
Desafíos ligados a una operación específica conservan el rechazo. No cambia la
autoridad ni la firma de admisión.38pruebas focalizadas PASS; suite backend244PASS
en116s, log `/tmp/umbra-pairing-relay-final.log`, SHA256
`286836e57e3d2e001ec3f9e79ff9396b8f8a7657ede21676ec979ee7c4f4873e`.
Este último log reemplazó involuntariamente el recibo local238 anterior; aquel
resultado sigue identificado como corte previo, no como contenido actual del log.
El rojo se conserva.

`/tmp/umbra-pairing-product-latest-https.log`, SHA256
`c58688442035d8a4f4bfb82d9cf00384e42459c4e06f5e8519ac9e528e25b28b`:
48aserciones pairing + salud + inspección SQLite PASS. QR/code con clientes HTTPS
nuevos, recovery explícito y ciphertext inmutable. La base real del relay contiene
5wrappers válidos(INVITE1/REQUEST2/ACK2) y un candidato inválido deliberado no
seleccionado. Se inspeccionaron forma/ciphertext y ausencia de transcript/alias en
claro sin imprimir blobs. No oculta locator, tamaños, digest, horarios ni IP al
operador. La inspección no es garantía forense universal de no filtración.

Runner de force-stop añadido a laboratorio de password debug/R8, ambos flavors:
cuatro paradas después de commits independientes y nueva autenticación para leer
los dos Vault. Presupuesto de fase90s y presupuesto exterior300s antes de observar
resultados; no son ampliaciones de vigencia de invitación ni leases. Tres pruebas
del verificador del runner PASS; ejecución Android pendiente del recibo CI.

### Último HTTPS antes de publicación

`/tmp/umbra-pairing-product-revocation-20261002.log`:52aserciones pairing más
salud e inspección de SQLite (54 comprobaciones PASS), SHA256
`5c979144d9f25f4138e93ed2db45d8f09c4b5d9dc2081b6192d49342f964ba60`.
Incluye cancelación de código sobre endpoints reales; SQLite contiene un tercer
rendezvous revocado, nunca reclamado ni seleccionado, sin ack/candidatos.
Tests adicionales de sustitución completa de tarjeta/identidad, caducidad
cifrada auténtica y tamper ACK se incorporaron después del full JVM anterior:
requieren su recibo nuevo, no heredan los436/378.

### Corte focalizado final

`/tmp/umbra-pairing-all-focused-20261002.log`: Gradle exit0,26s;43tests
connected y43offline, cero failures/errors/skipped. Incluye los cuatro casos
adversariales añadidos al último full. No sumar este corte al full anterior.
Revisión independiente read-only del diff: sin bloqueante nuevo identificado;
no equivale a auditoría criptográfica externa. UI/res/manifiestos productivos sin
cambios. Publicación/CI posteriores deben identificarse por SHA y checkout.


### CI roja del candidato 15dc062 y corrección de fixture

HEAD `15dc0623a97cd1b1c4cfdf4bf8cfccced857c278`, árbol
`409552f5209e9205ffdad177a77daad687c5e5d2`; PR22 draft contra PR21.
Checkout de integración `e83d0c3ace3197d64e5d4c17bc495bed89bf35ea`
con el mismo árbol. Personal vault password run37054269798 terminó FAILURE
(debug y R8). Los cinco DevicePairingPersistenceTest pasaron en ambas matrices.
El nuevo listener de force-stop falló antes de READY: eliminaba solamente el
placeholder de identidad creado por DeviceVaultPasswordTest.before, dejando el
placeholder session/ratchet. Engine.initialized rechazó correctamente una bóveda
no vacía sin identidad. Ningún force-stop nuevo se acepta como ejecutado en ese run.

Corrección exclusiva de test: verificar bytes exactos de ambos placeholders y
eliminarlos juntos dentro de la transacción del fixture antes de inicializar las
dos identidades reales. Si cambia el fixture, falla antes de borrar. Engine y su
rechazo permanecen intactos. No se borran registros productivos ni se cambian
permisos/Keystore. Requiere ejecución Android nueva para validar la corrección.
Artifacts rojos preservados: 11247841670 (R8),11248251918 (debug).

Full JVM local del HEAD15dc062:440connected/382offline, cero failures/errors/skips;
build VaultLab R8 y test APK de ambos flavors SUCCESS,4m9s. Compilar no prueba
reinicio Android. Log `/tmp/umbra-pairing-15dc062-local.log`.

Decisión de alcance pendiente: admisión vigente revela la clave pública Signal
al relay. El courier no revela transcripts ni claves privadas; no puede prometer
ocultar esa metadata pública sin un cambio incompatible de admisión. Se solicitó
aclaración al propietario y no se implementó ese cambio.

SHA256 artifacts rojos descargados:
-11247841670:`f506e089157f98639908cb032b85f944ec5c24a8969f035a678bacc295a17077`.
-11248251918:`b756c37739c1722bffddaff3d36e661f9e1a2692c3e18bcd071e7b032697031f`.

Diagnóstico local de comandos: intentar tareas compileConnectedDebugAndroidTest
con `-PumbraVaultLab=true` falló antes de compilar porque esa configuración expone
el testBuildType VaultLab. Se conservó el log y se corrigieron los nombres de tarea;
no es un error Java ni se modificaron dependencias para resolverlo.
