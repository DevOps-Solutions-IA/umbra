# Product reality audit / Execution 01 — 2026-09-30

## Dictamen y límites

**La integración no es un mensajero completo listo para dos instalaciones nuevas.** Hay núcleo criptográfico, persistencia, transportes y codecs con evidencia de laboratorio; existe una UI productiva que conecta numerosas operaciones reales. También hay funciones desconectadas, pasos administrativos manuales y precondiciones sin una entrada productiva. La CI verde no demuestra el recorrido de primera utilización.

No se implementó ninguna corrección. No se cambió UI, dominio, contrato, permisos, CI ni semántica de bloqueo. Este informe no reemplaza la aceptación técnica anterior ni reabre sus fallos como regresiones; mide otro alcance: el producto accesible al usuario.

- Base exacta: `ffb4475b0091438ba3e016d9ce4bb700c0f5155d`.
- Árbol: `f0d991d859ec02f9d70aed6e63ced018688f6ea2`.
- Rama nueva: `codex/product-reality-audit`; worktree aislado `/tmp/umbra-product-reality-audit`.
- PR #19 consultada: OPEN / DRAFT, HEAD exacto anterior, base `codex/security-content-completion`.
- No se cambiaron checkout del propietario, otros worktrees, ramas de Claude, main ni PRs.
- Variables AGENT_MEMORY_* no estaban configuradas. No se atribuye estado a memoria compartida ausente. Se inspeccionaron worktrees/procesos antes de aislar esta auditoría.
- **Evidencia física USER_REPORTED:** APKs exactos del CI de esta base arrancaron en Xiaomi 2606FRN72L (Android16/API36/arm64) y Motorola edge30 (Android14/API34/arm64). El usuario no consiguió pairing QR y reportó dificultades de Cerca, controles sin resultado y feedback/password/autolock confuso. Se conserva como resultado del producto, no se contradice con pruebas sintéticas.
- **NOT EXECUTED en esta ejecución:** ADB, instalación, radio física, biometría, micrófono/cámara, prueba manual de dos teléfonos, build/AVD nuevo, despliegue o conexión a un servicio productivo. No se midió una nueva latencia física.
- No se demostró un SECURITY_BLOCKER nuevo. Esto no es certificación ni una revisión exhaustiva de todos los posibles fallos criptográficos. Los hallazgos siguientes son cortes de integración, semántica y observabilidad; no justifican debilitar controles.

La [matriz de capacidades](product-capability-matrix.md) contiene una clasificación única por alcance, todos los 29 `Feature` y capacidades adicionales. Una fila de núcleo emulado no concede automáticamente el mismo resultado al recorrido UI.

## Método y referencias reproducibles

Lectura de AGENTS y documentos de seguridad/handoff/roadmap, testing y release; reconstrucción de callbacks UI → dominio → persistencia/transporte; búsquedas de consumidores y rutas ausentes; inspección de suites/harnesses/claims; consulta GitHub por SHA; descarga y lectura de recibos UI debug/R8; comprobación SHA-256. Las conclusiones negativas se limitan a las fuentes productivas `main/connected/offline` de esta base, no a ramas futuras.

Referencias principales, rutas desde `android/app/src/` salvo indicación:

| Ref | Fuente y punto de inspección |
|---|---|
| R1 | `main/java/app/umbra/ui/MainActivity.java`: onCreate/onResume/onPause 154–180; autenticación 193–347; lockState 389–408; tick 448; syncNow; startNearby 626; nearbyActions 895; connectNetwork 929; chooseBluetooth 942; createInvitation 973; chat 1011; verify 1086; grupo 1120; dispositivos 1131; settings 1145; admisión/admin 1200–1395; llamada 1424; ubicación 1520; documentos 1569–1640; contenido 1739 en adelante |
| R2 | `main/java/app/umbra/pairing/PairingService.java`; `crypto/Engine.java` createCard/importCard 100–175, requiredContact 237, transport gates 246+, sendDeviceRoster 302, recepción roster 519, handshake Nearby 593+ |
| R3 | `main/java/app/umbra/ui/design/QrCodes.java`; `verification/Verification.java`; `ui/model/FeatureAvailability.java` y `Feature.java` |
| R4 | `main/java/app/umbra/transport/BluetoothLink.java`: Stage/Listener, listen, connect, HELLO, prueba mutua; `connectivity/ConnectivityService.java` startNearby 173+ |
| R5 | `main/java/app/umbra/data/Vault.java` unlock/policy/schedule 345–406; `core/AccessGate.java`; `data/VaultSessionRegistry.java`; `ui/flow/VaultFlow.java`; `ui/model/PasswordPolicy.java`, `AccessStep.java`, `Help.java` |
| R6 | `main/java/app/umbra/devices/DeviceService.java` migrate 36+, review/approveRoster 174+, revoke 189+; `DevicePolicy.java`; consumidores UI de device-index |
| R7 | `main/java/app/umbra/ui/flow/AdmissionFlow.java`; `admission/AdmissionService.java`; `transport/RelayClient.java`; `relay/umbra_relay/{app,pairing,devices,admission_http,admission_store}.py` |
| R8 | `connected/java/app/umbra/media/VoiceControls.java`: permiso, datos TURN manuales, prepareMedia, NativeVoiceSession; `CallService`, `NativeVoiceSession`, `VideoSurface`; `ui/model/FeatureAvailability.java` embedded surface |
| R9 | `main/java/app/umbra/ui/flow/RestrictedFlow.java`; `content/RestrictedContentService.java`; `RestrictedPlayback.java`; `privacy/{ImagePreparation,PrivateAndroidSurface,PrivateClipboard,OrdinaryTextExport}.java`; adaptadores PNG/AAC/AVC/PDF |
| R10 | `androidTest/java/app/umbra/{UiScreensRenderTest,UiSecurityFlowTest,UiContentIntegrationTest,LockedActivityTest}.java`; `test/.../PairingTest.java`; `scripts/run_ui_integration.py`; `.github/workflows/ui-integration.yml` |

Los números de línea son de la base auditada; los nombres de método son la referencia estable. No confundir un archivo helper de UI probado directamente con toda la Activity desbloqueada mediante una persona.

## Hallazgos priorizados

### H01 — No hay emparejamiento QR productivo

**NOT_IMPLEMENTED (P08).** El recorrido de alta real es `createInvitation(3600)` → `stageExport` → `ACTION_CREATE_DOCUMENT`, archivo `umbra-vinculacion.txt`. Importación `ACTION_OPEN_DOCUMENT`:

1. A crea/exporta invite; B lo obtiene por un medio externo.
2. B importa invite: `PairingService.request`, exporta request; A debe obtenerlo externamente.
3. A importa request: `accept`, consumo/alta y exportación ack; B debe obtenerlo externamente.
4. B importa ack: `complete`, alta; los contactos siguen sin verificación humana.
5. Ambos comparan código completo fuera de banda y lo confirman.

No hay consumidor productivo que renderice esa invitación como QR, scanner de cámara, decoder de cámara conectado a PairingService, ni retorno request/ack por QR o rendezvous automático. Las URI de protocolo son formatos de payload, no prueba de un intent-filter de deep link (el manifest productivo de MainActivity solo declara launcher).

`QrCodes.render(Verification.qr(...))` muestra **QR del código de seguridad de un contacto existente**. `QR_SCAN` queda PENDING_BACKEND en FeatureAvailability. No deben confundirse render y escaneo. La ruta alternativa `Engine.importCard` acepta tarjetas firmadas compatibles; no tiene consumo de invitación de un uso y no debe anunciarse como tal.

`PairingTest` puede codificar una invitación con QRCodeWriter; `UiScreensRenderTest.contactAndVerificationUsePlainLanguage` usa el encoder productivo, verifica dimensiones/finder patterns y que el bitmap del safety QR aparece. Sus callbacks de pantalla son vacíos. R8 verifica que sobrevivan encoder y clases de presentación, no lectura de cámara ni alta entre peers. Cualquier generalización a “pairing QR funciona” es **SCOPE_OVERCLAIM**. La observación física del usuario concuerda con la ausencia de esa ruta.

**Futuro:** especificar el transporte/retorno y el flujo entero antes de construir un scanner; mantener consumo, firma, TTL, realm y verificación humana independientes. No se implementó aquí.

### H02 — Cerca requiere provisioning y coordinación que la pantalla no resuelve

**PHYSICALLY_FAILED para el recorrido reportado (P11); E2E_PROVEN_EMULATED para el protocolo acotado (P12).** No se infiere un defecto del radio físico.

`startNearby(true)` autoriza una sesión Nearby separada y construye BluetoothLink; no inicia automáticamente escucha, descubrimiento ni conexión. Esa transición no exige por sí misma admisión; la prueba autenticada y operaciones protegidas sí la exigen. El catch UI que presenta “Requiere admisión” no identifica necesariamente la causa de toda excepción.

`chooseBluetooth` usa exclusivamente `getBondedDevices()`: adaptador habilitado, permiso CONNECT y bond Android previos. Sin ellos pide emparejar primero en Android. El botón Settings sale de UMBRA; `onPause` bloquea y cierra Nearby. Al regresar hacen falta autenticación/password y **otra acción explícita Nearby**. No hay descubrimiento UMBRA completo ni guía de retorno que encadene los hitos.

“Visible” abre `ACTION_REQUEST_DISCOVERABLE`; si el diálogo provoca pausa de Activity, ocurre el mismo cierre. No se declara que todos los OEM entreguen el mismo lifecycle: esa secuencia necesita traza física. Conceder un permiso tampoco inicia el enlace: el callback exige repetir la acción y puede encontrar la aplicación bloqueada.

Dos teléfonos nuevos deben:

1. Tener bloqueo Android seguro, bóveda e identidad propias.
2. Compartir la misma autoridad/realm, obtener cada uno su admisión válida. Crear dos realms independientes no constituye un entorno común.
3. Habilitar/emparejar en Android, aceptar permisos; volver, autenticar y reactivar Nearby en cada uno.
4. Seleccionar **ambos** vinculación nueva: uno Esperar, otro Conectar. El protocolo exige el mismo modo enrollment en ambos; conectar-verificado no sirve para un desconocido.
5. Intercambiar tarjetas y pruebas de identidad/admisión; aparecer como contacto UNVERIFIED.
6. Comparar código humano; verificar ambos; si se cerró el enlace, volver a conectar como verificados.
7. Enviar texto: Engine exige admisión propia y del peer, trust y autorización del dispositivo; Signal crea/usa la sesión con las prekeys importadas; syncNow entrega; receptor persiste y genera ACK.

No se encontró un ciclo criptográfico imposible para ese camino con provisioning previo. Sí una dependencia administrativa/manual indispensable y reinicios de consentimiento que la experiencia actual no hace claros. Las fases CONNECTING/SOCKET_CONNECTED/HELLO_SENT/HELLO_RECEIVED/PROOF_SENT/AUTHENTICATED existen en BluetoothLink; el listener de MainActivity no consume `stage`. Una etiqueta de estado genérica no indica dónde quedó la operación.

### H03 — Falta bootstrap/aprobación de roster en producto

**IMPLEMENTED_NOT_CONNECTED (P20–P23).** Búsqueda en main/connected/offline no encuentra consumidores productivos de `DeviceService.migrate`, `reviewRoster`, `approveRoster` ni `Engine.sendDeviceRoster` fuera de sus definiciones. `Engine.initialize` crea identidad/perfil; no migra afiliación.

La UI lee lista propia y tamaños; añadir solo informa FEATURE_PENDING. Revocar tiene un handler real pero availability impide ejecutarlo porque falta distribución del roster. No confundirlo con revocar **admisión**, que sí tiene flujo administrativo por archivo.

MainActivity.startCall y preparación de ubicación requieren `device-index` del peer; si falta, indican “Aprueba primero sus dispositivos”. Sin entrada de aprobación, el usuario nuevo no puede cumplir esa precondición desde el producto actual. Importar tarjeta/contacto o marcar VERIFIED no crea ese roster. La recepción de actualizaciones de roster tampoco es el bootstrap: necesita el índice de autoridad existente.

Texto/archivos directos heredados no deben declararse imposibles por esa misma razón: DevicePolicy permite el caso sin roster cuando no existe membresía que contradiga el índice; sigue comprobando trust/admisión. No se propone eliminar la guarda de llamadas/ubicación ni aprobar listas silenciosamente.

### H04 — “4 min” es un techo de autorización foreground, no cuatro minutos garantizados

**PHYSICALLY_FAILED de expectativa (P02); comportamiento de seguridad por código.**

| Componente/evento | Semántica real |
|---|---|
| PasswordPolicy | Opciones 60/120/240 s; índice predeterminado 4 min; estado de proceso, no preferencia durable |
| AccessGate | 240 s monotónicos desde autenticación Android exitosa; ingresar password/invalidateAuthorizations no renueva ese inicio |
| Vault.scheduleAutoLock | Tras unlock toma `min(intervalo elegido, remainingNanos(gate lease))`; invalida al vencer |
| MainActivity authAt/tick | authAt se fija tras autenticación Android; tick cada 8 s comprueba >240 s y bloquea UI. No mide inactividad de teclado |
| onPause | `if (!authenticating || unlocked) lock()`; una sesión abierta se bloquea inmediatamente, sea 1/2/4 min |
| externalUi | Conserva contexto de retorno de selector, NO exime del bloqueo |
| Document picker/Settings | Si pausan, bloquean; resultado se guarda temporalmente y solo se procesa después de nueva autenticación/Engine y controles |
| BiometricPrompt inicial | La condición distingue autenticación en curso; no demuestra que se pueda mantener una sesión ya abierta detrás de cualquier prompt |
| Permisos | No reanudan captura/enlace automáticamente; si hubo pausa, lease anterior inválido y nueva acción necesaria |
| onResume | Puede solicitar autenticación; desbloquear no conecta ni reinicia Nearby/media/location |
| Force-stop/reinicio | Gate nuevo bloqueado; no restaura llamada/conectividad; contexto Activity pendiente no es un workflow persistente |
| VaultSessionRegistry | Ownership/denegación de reapertura concurrente, NO un temporizador adicional de cuatro minutos |

Hay dos comprobaciones temporales visibles en dominio/UI más invalidación por lifecycle; no un idle timeout renovado al tocar la pantalla. Tardar en escribir password consume la vida de autenticación Android. La ayuda existente **sí** dice “Vale solo mientras UMBRA siga abierta”, “Máximo 4 minutos”, “Salir de la app siempre bloquea”; no sería correcto afirmar que no lo advierte. Pero el selector no comunica el deadline efectivo ni que un selector/permiso puede interrumpir la tarea. `AccessStep.from` proyecta UNLOCKING/LOCKING a password prompt y usa busy locales para diferenciar progreso.

Contrato recomendado para una ejecución posterior: exponer deadline efectivo/causa de interrupción y estado de operación; explicar techo desde autenticación y pausa inmediata antes de lanzar pasos externos. Mantener confidencialidad background, invalidación de claves, emergency y fail-closed. Cambiarlo a “mantener abierto cuatro minutos aun en background” queda **RECHAZADO** en esta auditoría.

### H05 — Llamadas requieren más que un botón disponible

Voz/video/modulación tienen implementación nativa y controles productivos; no son solo modelos. Sin embargo, desde instalaciones nuevas cortan primero por roster (H03), después por provisioning online. VoiceControls pide manualmente URL TURN autorizada, usuario y credencial temporal; crea TurnConfiguration y prepareMedia tras consentimiento/selección. No hay emisión automática de credenciales TURN en los endpoints del relay inspeccionado. Los coturn/credenciales efímeros de laboratorio no constituyen un servicio desplegado.

El servidor de sobres debe tener TLS/configuración realm, admisión y buzones; conectado localmente no prueba que el servidor responda. El snapshot distingue NOT_OBSERVED/RESPONDED/UNREACHABLE, lo cual es correcto y debe conservarse. No se comprobó un deployment productivo. Se clasifica dependencia de servicio **BLOCKED_SERVER**, además del corte UI de roster. No se intentó contactar servidores arbitrarios.

La pantalla de llamada y el diálogo de controles usan la misma sesión; `EMBEDDED_VIDEO_SURFACE` pendiente no significa que no exista renderer remoto: `VideoSurface.show` abre otra superficie interna. Audio/video mantienen 180 s, foreground, consentimientos, permisos, DTLS y TURN-only. No hay evidencia de una conversación humana física entre los teléfonos de este informe.

### H06 — Hay contenido restringido conectado; falta demostrar el recorrido usuario completo

No clasificar PNG/AAC/AVC/PDF como “no implementados”. MainActivity usa RestrictedFlow review → prepare → send; importación bounded por SAF, captura connected, confirmación de destinatario/política; receptor review → consume persistente → Viewer. Hay frame protegido PNG/PDF, páginas en la misma sesión, audio con salida explícita, video con Surface protegida y cierre por lock/emergency. ONCE consume antes de presentar: fallo posterior del decoder no devuelve la apertura. UMBRA_ONLY y expiry no conceden export, URI externo, forward ni impresión.

`RestrictedPresentation.SENT` dice correctamente **“En cola cifrada. No indica entrega.”** No es un claim de recepción. UiContentIntegrationTest ejercita flow, Signal/SQLite y presentación PNG/PDF; no hace toda la selección de documentos y el intercambio con dos Activities nuevas. AAC/video tienen laboratorio nativo separado. La ruta productiva existe, pero el éxito físico desde esos botones no está acreditado en esta base.

`ImagePreparation.sanitize` sí existe y es usado por RestrictedImages. El feature **genérico** PHOTO_METADATA_CLEANING permanece pendiente y `Files` ordinario no lo invoca. No afirmar ni que falta todo saneamiento ni que adjuntar cualquier archivo elimina metadatos.

### H07 — Availability no representa readiness

FeatureAvailability comprueba compilación/servicio y flavor, no realm, trust, roster, permisos, servidor, credenciales TURN, hardware ni resultado. PENDING_BACKEND es también el valor por defecto de carencias de UI (device linking, renderer incrustado, notification adapter); no atribuye correctamente su propietario. Los pendientes son en general explícitos; no todo botón visible engaña. En particular grupo tiene formulario pero crear solo notice FEATURE_PENDING; device add y revocation están pendientes/gated.

Mantener separados **capacidad del build**, **precondiciones actuales**, **estado de una operación** y **evidencia de aceptación**. No cambiar enums/contratos en esta ejecución.

## Inventario de acciones visibles y consumidores

Este inventario agrupa callbacks equivalentes por pantalla; junto a la matriz cubre capacidades reales, placeholders y navegación. Navegación/help/selección no se contabilizan como operaciones de dominio.

| Entrada/callback | Efecto real / límite |
|---|---|
| Lock: desbloquear / settings seguridad | BiometricPrompt / Settings; no acceso sin gate+Keystore+password cuando configurada |
| Crear password / mostrar-ocultar / repetir / legacy defer | VaultFlow creación real; reveal local; defer solo camino legacy explícito, no bypass de password configurada |
| Unlock / selector 1/2/4 / cambiar password | VaultFlow y política; final de create/change bloquea; busy locales |
| Onboarding pasos/crear alias | Pasos locales; Engine.initialize real, después admisión; no concede red |
| Home filtros/tabs/abrir/new message | Filtrado/navegación/contactos persistidos; no unread inventado |
| Nuevo grupo seleccionar/nombre/crear | Selección local; creación FEATURE_PENDING |
| Añadir: crear/importar/Cerca | Archivos transcript / SAF / navegar Cerca; no scanner |
| Cerca activar/detener | Sesión explícita/close; no garantiza socket ni admisión |
| Cerca escuchar/conectar verificado | RFCOMM con peer conocido/verificado; conectar elige solo bonded |
| Cerca vincular → esperar/conectar | Enrollment mutuo + tarjetas + prueba; no VERIFIED automático |
| Cerca visible / settings | UI Android externa, permisos/lifecycle; no continuidad silenciosa |
| Chat enviar/reintentar/borrador | sendText/outbox; sync; borrador local se limpia lock; no prueba de entrega al pulsar |
| Chat adjuntar archivo/restringido/ubicación | Tres rutas distintas; no exportar restringido por la ordinaria |
| Mensaje texto copiar / archivo exportar | Confirmación y revisión ordinaria; SAF para export; salida de bóveda explícita |
| Contacto verificar/método QR/técnico | Código completo comparado; QR render; detalles desplegados; sin scan |
| Contacto bloquear/desbloquear/vaciar | Engine.block / clearConversation local, no borrar remoto |
| Contacto llamada/mensaje | startCall con roster / navegación chat |
| Tus dispositivos revocar/añadir | Revocar gated por availability; añadir notice pendiente; lista solo lectura |
| Ajustes registrar/sync/eliminar buzón/conectar/desconectar | RelayClient/Connectivity reales con gate; registro pide configuración y token de servidor |
| Ajustes contraseña/admisión/dispositivos | Navegación a flujos reales o lista; no concesión de permisos en navegación |
| Admisión crear realm/importar/solicitar/exportar/cancelar | Dominio y archivos; realm público no admite; cancelar ID exacto |
| Administrador revisar/aprobar propia/otra/rechazar/renovar/revocar | Autoridad validada en dominio; compartir resultados manual; alias no acredita identidad |
| Ubicación modo/manual/precisión/duración/enviar/detener | Domain review/roster + proveedor; stop real; live no prueba fix reciente por el mero start |
| Llamada iniciar/aceptar/rechazar/finalizar/minimizar | CallService; minimizar no prueba continuidad fuera de Activity |
| Autorizar mic / mute / salida / modulación/natural/retry | Sesión nativa; permiso y TURN manual; confirmar voz natural no hace unmute |
| Video solicitar/responder/stop/switch/ver remoto | Mismos gates/media, superficie interna; embedded layout pendiente |
| Contenido modo/TTL/elegir/grabar/confirmar enviar | Consent review, adapters acotados; connected-only captura; outbox no entrega |
| Contenido abrir/salida/página/zoom/cerrar | Consumo/Viewer protegido; no seek/replay de ONCE; PDF páginas misma sesión |
| Emergencia | Invalidación inmediata y estados de cierre; visual no equivale a CLOSED |
| Ayuda, textos técnicos, navegación/back | UI local. Back fuera de flujo puede bloquear; no nueva funcionalidad de dominio |

## Estados asíncronos disponibles, sin diseñar UI nueva

No existe una máquina universal de operación con los seis estados pedidos. Eso no significa ausencia total de feedback. Se identifica lo que Claude puede consumir hoy y el vacío restante.

| Operación | IDLE / WORKING actual | SUCCESS real | FAILED / REQUIRES_USER_ACTION / UNAVAILABLE | Gap |
|---|---|---|---|---|
| Password crear/unlock/change | accessBusy/changeBusy; worker; Vault estados | OPEN o LOCKED_AFTER_CREATE/CHANGE | Error genérico de unlock, CORRUPT/KEY_UNAVAILABLE; Android auth/legacy/confirmación | Estado busy Activity no recibo durable; pause/generation descarta callback y limpia feedback |
| Abrir Vault/Engine | accessStep, engine nulo hasta completion | Engine construido y bóveda abierta | Registros ilegibles bloquean; no autorreparación | AccessStep no representa UNLOCKING/LOCKING explícitamente |
| Admisión/administración | adminBusy + snapshots, Review | Estado dominio/credencial/archivo preparado | Estados INVALID/REVOKED/EXPIRED/pending; archivo a repartir | Aprobar local ≠ entregado/importado por otro dispositivo ni relay actualizado |
| Conectar relay | connectBusy + CONNECTING/CONNECTED | Autorización local; RESPONDED tras roundtrip separado | UNREACHABLE/denegación; configuración/admisión requerida | No unificar CONNECTED con server listo; no progreso detallado de todas las fases |
| Nearby activar | nearbyActive/transportStatus | Sesión permitida, no enlace | Permisos/bond/Android Settings; rechazo genérico | Falta distinguir sesión/listener/socket/prueba/ready para persona |
| Bluetooth listen/connect | Stage tipado en adapter, status strings en Activity | AUTHENTICATED y peer | Modo mismatch, desconocido, admisión, timeout, socket | Main no consume Stage; causa específica no observable en UI |
| Pairing | action worker; estados persistidos PairingService | Invite/request/ack según paso, contacto aún UNVERIFIED | Archivo y comparación humana; caducidad/formato | Sin operación multipaso/retorno QR; documento creado no significa paired |
| Import/export | externalUi/pendingResult + action | Lectura/escritura termina, luego notice | SAF/cancelación/reauth/expiry | No receipt durable de entrega externa; process death pierde contexto Activity |
| Enviar texto/archivo | cola Engine; worker action | ID/outbox y luego transporte/ACK | Trust/admisión/dispositivo/TTL, enlace ausente | General action no estado de progreso por ID expuesto uniforme |
| Sync | AtomicBoolean syncBusy; generación | Ronda HTTP/responded y actualización | UNREACHABLE; backoff; stale callback denegado | No progreso por mensaje uniforme; ACK no lectura humana |
| Preparar restringido | review/prepared/pending, worker | Commit outbox explícitamente rotulado | Error tipado ContentException; permiso/input/output/consent | Codecs no publican porcentaje de progreso; UX de task externa pendiente prueba |
| Presentar restringido | Viewer/session; Playback READY/ROUTING | PLAYING/primer frame observado; COMPLETED | INTERRUPTED/FAILED/CLOSED; ONCE ya consumido | No inferir atención humana; no reabrir tras error consumido |
| Capturar nota | Recording/NoteCapture + input seleccionado | Prepared, después send independiente | Permiso/foco/route/lock y cancelación | Física no probada; cambiar pantalla corta autorización |
| Ubicación | Location sessions/capture; label Activity | Update recibido RECENT/LAST_KNOWN/terminal | Permiso/proveedor/roster/expiry | Label “En vivo” tras start no prueba primera medición recibida |
| Llamada/media | CallService y VoiceControls.Snapshot | Seleccionado ≠ media ACTIVE; native state separado | Busy/reject/expiry/failure; permiso/TURN/roster | No se puede completar provisioning desde nuevo usuario sin pasos faltantes |
| Emergencia | REQUESTED/denial/closing | CLOSED confirmado | INCOMPLETE; autenticación nueva solo cuando permitido | Consumir estados reales, no reemplazar por un toast “todo cerrado” |

La observación física sobre password sigue válida aunque haya un texto “Comprobando…” y un test de render: no se ejecutó aquí una traza humana de duración/callbacks que permita identificar su causa específica. El exceso de copy es evidencia del usuario; `assertConcise` es un límite mecánico por vista sintética, no una evaluación de carga cognitiva de todo el recorrido.

## Reconstrucción de primer uso

### Base común y admisión (ambos flavors)

Fresh install → pantalla bloqueada → Android secure authentication/Keystore → CREATE_PASSWORD → creación termina LOCKED → autenticar de nuevo + password → Engine → alias/identidad local → UNLOCKED_OFFLINE.

Importar RealmConfig no admite. Administrador A crea realm y solicitud propia, revisa/aprueba propia; B importa mismo realm, crea/exporta solicitud; A importa/revisa huellas/aprueba/exporta credencial; B importa su credencial. Son archivos y consentimiento, no un administrador descubierto automáticamente. Password y claves no se comparten. Dos autoridades independientes no son una solución al primer contacto. El usuario puede necesitar resolver varios selectores y nuevas autenticaciones antes de llegar a dos dispositivos admitidos.

### Local Nearby / offline

Provisioning común → bond Android → volver y desbloquear ambos → Nearby explícito → enrollment Esperar/Conectar coincidente → tarjetas/prekeys/credenciales autenticadas → contactos UNVERIFIED → comparar y verificar ambos → sesión Signal y texto → recibir/persistir/ACK.

El flujo por archivos también puede crear esos contactos antes de conectar como verificados. No requiere servidor para texto por RFCOMM. Offline no incorpora INTERNET/ACCESS_NETWORK_STATE, micrófono/cámara/WebRTC de llamadas. Abrir UMBRA y desbloquear no inician radio por sí solos. **Corte real de uso reportado:** usuario no completó pairing útil; la secuencia anterior está reconstruida por código, no ejecutada físicamente aquí. Settings/permisos pueden cancelar el intento previo y requerir reentrada explícita.

### Internet / relay

Provisioning común → servidor HTTPS de realm disponible y token de registro de buzón → registro explícito/online grant → tarjetas/contactos con buzones y credenciales de ambos → verificación humana → Connect explícito → sendText → Signal/outbox → RelayClient.sendAuthorized → poll del receptor → persistencia y ACK.

No hay directorio público ni push oculto. Sin relay desplegado/configurado y permisos del realm, se corta en registro/conexión. Sin contact admission/VERIFIED, se corta en Engine antes del envío. La existencia de un servidor de prueba no resuelve ese corte. Calls/video además requieren roster aprobado, selección y TURN autorizado; ubicación requiere roster, incluso si usa Nearby.

## Claim audit

| Claim a evaluar | Lo que la evidencia acredita | Lo que NO acredita | Redacción exacta sustitutiva |
|---|---|---|---|
| “QR funciona” / “QR real demostrado por R8” | Encoder productivo y safety QR renderizado en pantalla AVD optimizada; mapping real | Scanner, invitación QR, request/ack QR, dos usuarios paired | “Safety QR se renderiza en debug/R8; pairing QR no implementado”. SCOPE_OVERCLAIM si se generaliza |
| “Pairing implementado” | Protocolo firmado invite/request/ack y consumo; UI SAF manual | Descubrimiento, QR o intercambio sin archivos | “Pairing por archivos implementado; primer uso físico no aceptado” |
| “Bluetooth PASS” | Stack RFCOMM emulado con peers/realm/keys preparados, desafíos/Signal/ACK | Bond físico OEM, Settings/retorno UI, primer contacto humano | “RFCOMM de laboratorio aprobado; recorrido físico reportado sin éxito” |
| “Cerca disponible” | Gate y adapter real, botones listen/connect | Dispositivo visible, bonded, admitido, trust y ready | “Disponible en el build; requiere provisioning y acciones explícitas separadas” |
| “Vault probado” | Criptografía/SQLite/keys fixture; UiSecurityFlowTest invoca flow directamente | Prompt Android real y experiencia completa del usuario en ambos teléfonos | “Vault/flow probados en capas declaradas; autenticación física productiva independiente” |
| “Autobloqueo 4 min” | Máximo de autenticación y plazo Vault, pausa bloquea | Cuatro minutos desde cada actividad/desde password o permanencia en Settings | “Límite foreground acotado por autenticación; salir/pausar bloquea antes” |
| “Llamadas/video funcionando” | Audio/frames sintéticos nativos por TURN en laboratorios debug/R8 | Servicio desplegado, usuarios nuevos, mic/cámara/acústica físicos | “Media nativa validada en laboratorio; producto requiere roster, relay/TURN y validación física” |
| “Modulación funciona” | DSP saliente antes de Opus, señal remota sintética y fallo silenciado | Anonimización, voz humana inteligible físicamente | “Modulación local probada con audio sintético; sin claim biométrico” |
| “Contenido restringido aceptado” | Domain/adapters/transport/lifecycle y UI flows seleccionados | Recorrido completo de nuevos usuarios/SAF/route física; anti-copia absoluta | “Núcleo y presentación seleccionada validados; envío/apertura física por UI pendientes” |
| “Fotos sin metadatos” | ImagePreparation en ruta restringida PNG | Saneamiento de todos los archivos enviados por Files | “Saneamiento en foto restringida; función genérica no conectada” |
| “Grupos / añadir dispositivo” | Formularios/lista, y dominio dispositivos por separado | Grupo creado o ceremonia de linking desde UI | “Grupo UI_ONLY; linking de dispositivos IMPLEMENTED_NOT_CONNECTED” |
| “35/32 tests UI” | 24 render +3 security flow +5 content; debug añade3 Activity bloqueada | 35/32 workflows humanos E2E ni Activity desbloqueada completa | “Pruebas de render/flow/Activity bloqueada, ambas variantes; no aceptación manual completa” |
| “Accesibilidad/concisión PASS” | Controles/tamaños/semántica y texto de vistas sintéticas inspeccionadas | Facilidad del flujo completo o comprensión por usuarios | “Guardas mecánicas aprobadas; problemas de uso físico reportados siguen vigentes” |
| “Dos teléfonos arrancan” | Instalación/launch reportados por propietario, versión/ABI conocidas | Keystore productivo confirmado, pairing/mensajes/media E2E | “Launch físico de la base confirmado por usuario; otras capacidades requieren evidencia propia” |
| “API gaps = 0” | Contrato de núcleo entregado para integración del alcance técnico | Todos los consumidores productivos conectados o features fuera de ese contrato completas | “No equivale a integration gaps = 0; ver roster, QR y readiness de esta auditoría” |

Los informes fechados `2026-09-29-claude-final-ui-integration.md` y `2026-09-30-claude-codex-convergence.md` sí acotan parte de sus claims a QR/render/R8. Se conserva ese mérito; no se les atribuye una afirmación textual de scanner que no hacen. La sobreafirmación surge cuando se usa ese resultado como prueba de pairing/producto completo.

Además, SECURITY/ROADMAP/CODEX_HANDOFF conservan bloques antiguos como “video no implementado”, “master abierto” y recibos pendientes. En CODEX_HANDOFF incluso la cabecera “Estado vigente” referencia 6093d03, no el SHA aceptado posterior. Son deuda de orientación documental: **no revocan** los recibos autoritativos previos e0024f0 ni el código actual. No se reescribió historia en esta auditoría; futura corrección debe enlazar estado vigente sin borrar informes históricos.

## CI y evidencia del SHA auditado

Consulta GitHub por `headSha=ffb4475...`: **11/11 workflows y 29/29 jobs SUCCESS**. No es el conteo histórico 10/27 del núcleo: la UI añade dos jobs. No se lanzó ni se canceló ninguna ejecución.

| Workflow | Run | Jobs | Resultado |
|---|---|---:|---|
| Focused media regressions | [36728247490](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247490) | 3 | SUCCESS |
| Local voice modulation laboratory | [36728247530](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247530) | 2 | SUCCESS |
| Emergency lock laboratory | [36728247617](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247617) | 5 | SUCCESS |
| Optimized media laboratory | [36728247523](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247523) | 1 | SUCCESS |
| Video media laboratory | [36728247699](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247699) | 4 | SUCCESS |
| Private device admission laboratory | [36728247631](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247631) | 2 | SUCCESS |
| Claude UI integration | [36728247569](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247569) | 2 | SUCCESS |
| Personal vault password laboratory | [36728247481](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247481) | 2 | SUCCESS |
| Privacy adapters laboratory | [36728247633](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247633) | 2 | SUCCESS |
| Private startup no-network laboratory | [36728247537](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247537) | 2 | SUCCESS |
| Verify UMBRA | [36728247588](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36728247588) | 4 | SUCCESS |

### Artefactos UI inspeccionados

Run `36728247569`, jobs `109930637953` (debug) y `109930638085` (R8). Los dos ZIP fueron descargados y su SHA-256 local coincide con el digest de GitHub:

| Artefacto | ID | SHA-256 ZIP |
|---|---|---|
| ui-integration-false-fcab7cda5dbd95fa02349a3d16ef2d89d16d1ee8 | 11104083390 | `03adcae59d459e76e5eb1c309b8fec2b1ee357b3313261fed09d6dd98b32f966` |
| ui-integration-true-fcab7cda5dbd95fa02349a3d16ef2d89d16d1ee8 | 11103953232 | `8ac1448b721bad7498e55aa5227966fb2b41b973ae3f3bed438e47e830058272` |

`commit.txt`: `fcab7cda5dbd95fa02349a3d16ef2d89d16d1ee8` (checkout de integración, **no** ffb4475). `tree.txt`: `f0d991d859ec02f9d70aed6e63ced018688f6ea2`, exactamente el árbol requerido. Recibos connected/offline: debug **35 cada uno**, optimized **32 cada uno**, PASS, `exactProductionApk=false`, `physicalDevice=MANUAL_PENDING`. Esas etiquetas se conservan, aun cuando el propietario haya instalado otros artefactos exactos de la misma CI y observado launch.

Los ZIP contienen screenshots sintéticos y mappings/recibos; no se publicaron imágenes ni datos nuevos del teléfono. La consulta de conclusión de los otros workflows no equivale a reinspección byte a byte de todos sus artefactos. No se inventan hashes de APKs físicos: el propietario atribuye su origen a esta CI, esta auditoría no extrajo ni comparó sus APK instalados.

## Controles ejecutados aquí

Entorno: shell bash, worktree Linux/WSL aislado; herramientas `git`, `rg`, `gh`, `python3`. No se actualizaron SDK/JDK/Gradle ni dependencias. Operaciones de lectura que encontraron rutas inexistentes se resolvieron mediante `rg --files`; no son fallos del producto.

Comandos representativos reproducibles (rutas relativas al worktree):

```bash
git worktree list --porcelain
git status --short
git rev-parse HEAD HEAD^{tree}
git worktree add -b codex/product-reality-audit /tmp/umbra-product-reality-audit ffb4475b0091438ba3e016d9ce4bb700c0f5155d
gh pr view 19 -R DevOps-Solutions-IA/umbra --json state,isDraft,headRefOid,baseRefName
gh run list -R DevOps-Solutions-IA/umbra --commit ffb4475b0091438ba3e016d9ce4bb700c0f5155d --limit 30 --json databaseId,name,status,conclusion,headSha
gh run view 36728247569 -R DevOps-Solutions-IA/umbra --json headSha,jobs
gh api repos/DevOps-Solutions-IA/umbra/actions/runs/36728247569/artifacts
gh api repos/DevOps-Solutions-IA/umbra/actions/artifacts/11103953232/zip > /tmp/umbra-product-audit-evidence/ui-r8.zip
gh api repos/DevOps-Solutions-IA/umbra/actions/artifacts/11104083390/zip > /tmp/umbra-product-audit-evidence/ui-debug.zip
rg -n '\.migrate\(|reviewRoster\(|approveRoster\(|sendDeviceRoster\(' android/app/src/main/java android/app/src/connected/java android/app/src/offline/java
rg -n 'FEATURE_PENDING' android/app/src/main/java/app/umbra/ui/MainActivity.java
python3 -m unittest discover -s scripts/tests -p test_ui_integration.py -v
```

- Git/gh consultas y descarga: exit 0. Se consultó `gh run view --json jobs` para **cada** run de la tabla, no solo UI.
- Prueba local existente del runner UI: **8/8 PASS**, exit 0. Prueba el validador de mappings/conteos/recursos; no ejecuta Android ni producto. Una primera invocación terminó sin conservar su salida por el polling del comando; la ejecución citada es la segunda, observada completa. No se utiliza esa repetición como resolución de un fallo.
- Inspección de ZIP mediante `zipfile`, recibos mediante `json`, digest mediante `hashlib.sha256`: exit 0, valores anteriores.
- Inspección estática de cobertura de matriz y fuentes: confirma 29 enum features presentes, 41 filas con una clasificación válida y sin cambios de producto. No se cuenta como 41 tests de comportamiento.
- Repository guard y diff whitespace: resultados añadidos al pie tras su ejecución. No se disparó CI para convertir documentos en aceptación del producto.

## Próximas ejecuciones propuestas, NO realizadas

| Orden | Alcance pequeño | Propietario | Criterio de aceptación que falta |
|---|---|---|---|
| 1 | Especificar readiness y semántica operacional de acceso/autolock/retorno externo | CODEX para datos/contrato, CLAUDE para uso/copy | Reloj efectivo y causa; create/unlock/change WORKING y resultado inequívoco; pausas no pierden causalidad; no extender autorizaciones |
| 2 | Cerrar ceremonia de dispositivo y roster que ya existe | CLAUDE integra APIs; CODEX valida gates | Dos identidades nuevas migran explícitamente, intercambian y aprueban roster; tercero/cambio no se autoaprueba; llamada/ubicación ya no piden un paso inaccesible |
| 3 | Diseñar e implementar pairing QR completo, con fallback por archivos honesto | CODEX protocolo/adapters, CLAUDE interacción | Display/scan/parse/canonicalización y request/ack completos, replay/expiry/cancel; QR de seguridad claramente distinto. No dar VERIFIED por scan de invite |
| 4 | Orquestación Nearby sobre stack existente | CLAUDE flujo, CODEX estado/errores, PHYSICAL TEST | Realm/admisión/bond/permisos/roles visibles; retorno seguro Settings; fases del enlace observables; primer texto/ACK entre los dos teléfonos |
| 5 | Provisioning del entorno relay/TURN | SERVER; revisión CODEX | TLS/realm/buzones/credenciales temporales con admisión; pruebas de indisponibilidad; Connect no miente; TURN-only intacto. Despliegue requiere encargo propio |
| 6 | Aceptación manual integrada de mensajes, restringidos y media | PHYSICAL TEST con Claude | APK/sha por teléfono; timeline; selección/consumo/expiry/route/lock; audio/frames remotos reales; sin confundir capturas sintéticas con humanos |
| 7 | Readiness de features y documentación vigente | CLAUDE presentation; CODEX evidencia | AVAILABLE condicionado correctamente; grupos/reply/unread/mute siguen pendientes sin simular; referencias actuales enlazadas sin borrar historia |

No abrir grupos, recovery, notificaciones/push o nuevas funcionalidades en nombre de esta auditoría. Si se reordena el trabajo, conservar dependencias: pruebas físicas de llamadas desde usuarios nuevos dependen de roster y servicios, no solo de tener dos teléfonos.

### Invariantes de las recomendaciones

Se rechazan expresamente: desactivar onPause/Keystore para evitar reautenticaciones, admisión por mero APK/QR/bond, VERIFIED automático, DevicePolicy permisiva, desbloquear/conectar/activar Nearby automáticamente, saltarse selección exacta de receptor, llamar sin TURN, exportar restringidos por Files, renovar leases desde callbacks antiguos o declarar CLOSED por ocultar una pantalla. La alternativa es **hacer visible la precondición y conducir una nueva acción consentida**, reutilizando gates del dominio.

## Cierre de esta ejecución de auditoría

- IMPLEMENTED E2E: launch físico **reportado**; RFCOMM/Signal y media/contenido integrados en las capas de laboratorio documentadas. No primer chat humano aceptado.
- IMPLEMENTED BUT DISCONNECTED: bootstrap/approval roster, device linking/revocation UI, saneamiento ordinario genérico, notification adapter y superficie incrustada de llamada.
- PHYSICALLY FAILED: experiencia Cerca/primer emparejamiento y expectativa de autobloqueo reportadas; causa radio/timing física no medida. Pairing QR se clasifica NOT_IMPLEMENTED, no decoder averiado.
- PENDING: scanner/pairing QR; grupos reales, reply, unread, mute conversación; aceptación humana de flujos completos.
- BLOCKED BY SERVER: recorrido relay y llamadas/TURN operativos; tests backend no son deployment.
- CLAIM OVERSTATEMENTS: render QR→pairing QR, AVD RFCOMM→first-use físico, tests flow→Activity humana completa, AVAILABLE→ready, API gaps=0→sin gaps de integración.
- CODEX NEXT WORK: estados/contratos de operación y soporte técnico de ceremonias, respetando este diagnóstico y pruebas causales.
- CLAUDE LATER WORK: conectar dominio ya existente, reducir ambigüedad del flujo/retorno/feedback, no rediseñar seguridad.

Esta auditoría concluye con documentación; no declara producto completo ni producción, no cambia el contrato y no autoriza implementar las recomendaciones en esta misma ejecución.

### Resultado de los controles documentales

2026-09-30, Python `3.13.12`, Git `2.53.0`, gh `2.46.0`:

- `python3 scripts/repository_guard.py`: exit 0; 695 archivos actuales revisados en la primera comprobación; sin patrones prohibidos detectados. La propia guarda aclara que no es una auditoría de seguridad.
- Inspección Python de enum/matriz: exit 0; 29 features presentes, 41 IDs únicos, 17 columnas por fila, una clasificación permitida; HEAD/base/tree exactos comprobados.
- `git diff --check`: exit 0. Antes de stage solo existían los dos documentos nuevos de esta auditoría. La revisión staged debe seguir mostrando exclusivamente esos dos paths.
- No nuevos tests de producto, cambios de comportamiento, supresiones, exclusiones ni relajaciones. Las ocho pruebas ejecutadas localmente son del tooling existente; las pruebas Android citadas proceden de los recibos del SHA auditado.
