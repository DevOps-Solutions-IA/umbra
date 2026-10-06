# Reanudar R01 — Message Reply, carril de ChatGPT

Estado: R01-A fundamento aislado; R01 sigue parcialmente implementado y sin merge.
Base: `4fdd338f3fff4c6864ea820ca85cba6cf284b8da`.
Rama: `chatgpt/r01-message-reply`.
Worktree propietario: `UMBRA_R01_REPLY` junto a `UMBRA_MAIN`.

## Entrada y estado persistente

En el PC del propietario, leer en este orden:
1. `../UMBRA_CONTROL/MASTER_MULTIAGENT_CONVERGENCE_20261006/PARALLEL_LANES_POLICY.md`.
2. `../UMBRA_CONTROL/MASTER_MULTIAGENT_CONVERGENCE_20261006/R01_PARALLEL/CHECKPOINT.json`.
3. El `JOURNAL.md`, `INTEGRATION_PLAN.md` y `evidence/` de esa misma carpeta.
4. `AGENTS.md`, este archivo, `docs/contracts/R01_MESSAGE_REPLY_V1.md` y el informe
   fechado de R01 en `docs/validation/`.

Si se perdió el historial de chat, los archivos anteriores y Git son la continuidad.
No asumir que el agente mantuvo memoria ni siguió ejecutando acciones fuera de sesión.
Si solo está disponible la rama Git, usar este documento/contrato/evidencia versionada
como guía; no inventar resultados de logs locales no disponibles.

## Comprobación antes de escribir

Verificar `git status --short --branch`, HEAD/árbol, remoto y diff contra la base real.
Preservar cambios posteriores; un SHA distinto no autoriza reset, clean o force-push.
No cambiar el branch de otro worktree ni tocar `UMBRA_APP_READY`, `UMBRA_MAIN`,
`UMBRA`, S1 o INTEGRACION. Codex es propietario de GATE-00 y amarillos.
No editar el mismo archivo/worktree entre agentes. Documentar superficies compartidas.

## Hecho por R01-A

Dos archivos nuevos independientes: `ReplyReference` y `ReplyTargetPolicy` bajo
`android/app/src/main/java/app/umbra/messaging/reply/`, más sus pruebas JVM y contrato.
La referencia no copia una cita; valida UUID/autor/destinatario. El policy no autoriza
ni envía: verifica metadatos de un original ordinario local ya autenticado/autorizado.
MESSAGE_REPLY continúa PENDING_BACKEND. Engine, Wire, UI y manifiestos no están cableados.
El contrato actual soporta solo mensajes ordinarios v1 por dos dispositivos; no fanout v2.

## Siguiente unidad concreta

Antes de R01-B, acordar con el carril amarillo el contrato de compatibilidad y roster:
- Wire actual exige campos exactos; no agregar un campo a text ni un kind nuevo sin
  recepción/negociación explícitas y pruebas con el parser anterior.
- Definir soporte del peer, mensajes fuera de orden y fanout lógico v2 sin sustituir
  dispositivo/autor ni convertir formato silenciosamente.
- Planificar cambios acotados de Engine/Wire en commits separados, desde la rama R01,
  y avisar al integrador de los hunks que se compartirán con Y02/Y03.
- Implementar transacción real de autorización+ratchet+historial+outbox; no reusar el
  policy puro como permiso. Incorporar regresiones con libsignal real antes de habilitar UI.
- R01-C cablea UI/borrador/cancelación con revalidación de sesión, conversación y original.

No iniciar R02 ni declarar R01 listo por estas clases. Integración a main: amarillos y
sus dependencias primero, actualización normal de base, revisión del diff combinado,
pruebas/CI propias del SHA final y autorización de merge vigente. No auto-merge.

## Pruebas, publicación y seguridad

JVM/tooling/build/lint corren en host; Android solo en móviles físicos autorizados.
Nunca AVD/emulador/simulador. No instalar app/test APK sin aprobación individual de hash.
No tocar VPS/DNS, claves, datos de usuarios, cachés globales ni secretos.
No lanzar builds sin comprobar CPU/disco y no eliminar evidencia para obtener espacio.
La rama puede publicarse solo después de revisar triggers: abrir PR actualmente activa
laboratorios AVD, por lo que el PR queda bloqueado hasta acordar el encaminamiento de CI.
Los tests seleccionados son evidencia focalizada, no sustituyen la suite completa.
Un runner exitoso debe tener exit real y XML/contadores; no heredar CI de otra rama.

Guardar checkpoint con SHA/árbol, pruebas/exit/log hashes, límites y siguiente paso
tras cada unidad/commit. Un proceso pendiente se revalida por PID y log, no se reinicia
por suposición. Revisión externa/independiente se distingue de autorrevisión; ninguna
se presenta como revisión humana especializada.
