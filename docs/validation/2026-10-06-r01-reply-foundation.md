# R01-A — fundamento de referencias de respuesta, validación delimitada

Fecha: 2026-10-06. Base comprobada contra GitHub:
`4fdd338f3fff4c6864ea820ca85cba6cf284b8da`.
Rama local: `chatgpt/r01-message-reply`, worktree `UMBRA_R01_REPLY`.
Estado: **desarrollo paralelo parcial; no merge y no función disponible**.

## Alcance y aislamiento

Nueva referencia id/autor/destinatario y policy de selección/resolución de metadatos.
Ningún cambio a Engine, Wire, Records, Vault, UI, permisos, workflows o dependencias.
El contrato propuesto y la continuidad señalan las obligaciones restantes de envío,
compatibilidad/fanout, recepción, UI y hardware. MESSAGE_REPLY permanece pendiente.

## Pruebas realmente ejecutadas

Entorno local Linux/WSL, JDK21, SDK36, Gradle8.14.4. Solo JVM en host; no Android
instrumentado, AVD, teléfono, red de producción, Bluetooth ni sensores. Gradle ejecutado
con --offline --no-daemon --max-workers=2 y tareas propias del worktree R01.

| Etapa | Connected JVM | Offline JVM | Fallos / errores / ignorados |
|---|---:|---:|---|
| Baseline main antes de clases nuevas | 491 | 430 | 0 / 0 / 0 |
| Nuevas pruebas focalizadas R01-A | 28 | 28 | 0 / 0 / 0 |
| Suite completa después de R01-A | 519 | 458 | 0 / 0 / 0 |

Conteos derivados de XML reales: 71 suites connected y 62 offline en la ejecución
completa. Incremento 28 métodos por variante; no se quitaron tests existentes.
Tooling de esta base main: 417/417, exit0. No son421: las cuatro pruebas diagnósticas
de PR26 todavía no están en main y no se importaron al carril R01.
Source policy:13 controles PASS; distinta de inspección final de APK.
Repository guard PASS en el alcance inspeccionado; no es auditoría criptográfica.
No se ejecutaron assemble release/R8, lint final, CI remota ni aceptación física de R01.

## Evidencia roja preservada

Primero se incorporaron tests antes de las APIs: compilación fallida por clases
ReplyReference/ReplyTargetPolicy inexistentes. No se presenta como28 fallos de
comportamiento: el runner no llegó a ejecutar esos métodos.
El primer intento también detectó un error propio del test: JSONObject.keySet no es
compatible con el stub Android de compilación. Se sustituyó por keys() + comparación
exacta del conjunto. Segundo RED reprodujo exclusivamente APIs ausentes; ambos logs
se conservan. Tras añadir las clases, focalizados y suites completas terminaron exit0.

El wrapper remoto del baseline informó exit1, mientras el código Gradle capturado
fue0, BUILD SUCCESSFUL y XML491/430 sin fallos. Se preserva esta discrepancia de wrapper;
no se afirma su causa ni se reutiliza como prueba de Android. Los siguientes runners
capturan subprocess/exit desde Python y finalizaron con salida0 verificada.

## Revisión y puertas abiertas

Autorrevisión del implementador: límites, IDs/dirección, ausencia de citas persistidas,
sanitización de errores, TTL, originales no disponibles, rechazo de control/restringido
y alcance v1 explícito. **No hubo revisor independiente ni revisión humana especializada.**

R01-A no prueba que se pueda responder desde la app. Falta autorización transaccional
real, binding dentro de libsignal, negociación/compatibilidad de formato, fanout v2,
UI, historial/rollback, pruebas físicas y revisión del SHA integrado con amarillos.
No tocar los worktrees de Codex ni cerrar GATE-00 con esta evidencia.

## Publicación

No push ni PR en esta entrega. Inspección de triggers indica que un PR lanzaría
laboratorios AVD, contrarios a la política actual. Además, GitHub.get_repo y el GET
REST del repositorio informaron private=false / visibility=public; eso difiere de
algunos controles locales que imprimen PRIVATE. No se cambió la visibilidad ni se
publicó R01. La decisión del propietario sobre visibilidad/publicación queda pendiente.

## Integridad de logs locales

Ubicación persistente: `../UMBRA_CONTROL/MASTER_MULTIAGENT_CONVERGENCE_20261006/R01_PARALLEL/evidence/`.
Estos hashes identifican logs observados, no constituyen por sí solos prueba de funcionalidad:

- `baseline-jvm.log`: `509b2a7365bd6c13283905f57cec6c63fd4aa5501d44d65aa1c3953cd025c274`
- `red-jvm.log`: `99aacf5f352f97d83ddb3467f3b626ea367f295533f43697623919e57aa912b6`
- `red2-jvm.log`: `62e64104bf86bc3fb88e2000be31bfc284094b57a38aa7cb4d4f952af69a5d5e`
- `focused-jvm.log`: `81d8e29cfcd3ac004b6f5aca42b62d9271edffe4077ceb52ad61fd25bb2ff568`
- `full-jvm.log`: `2e5abd1a41adf5f7d411469c2abe906740e4e81a921bbc519db63cf76b1ff998`
- `tooling.log`: `ed3c585845546ed079fe551f830b5fafd99cde329d870a321b8348ab7dcff08d`
- `repository-guard.log`: `7edf3589b81bf635f17177a01a344d1c9636d935c37a9e82ad6a36fc004205a6`
- `source-policy.log`: `b01dec6ec8eebdbabadf42fa39d78e431499e5702e4257dfb970eb5653d4c3dc`
