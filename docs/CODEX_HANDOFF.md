# Transferencia técnica a Codex

## Quinta entrega — voz — en curso, 2026-09-21

PARTIAL — rama `codex/turn-voice-core`, base #7 abierta `e495793`.
[Estado y comandos ejecutados](validation/2026-09-21-turn-voice-core.md).
Distribución WebRTC/guardas/configuración RELAY verificadas. Dos AVD han ejecutado
Engine/SQLite/libsignal/HTTPS y Opus sintético bidireccional por coturn real. Adaptador
connected y controles mínimos integrados; no conversación humana ni hardware validado.
Corregida la redirección TURN nativa mediante fuente fijada: cuatro ABI compiladas
(run35577083313), cero paquetes al destino alternativo en dos AVD x86_64; Opus y rechazo
DTLS repetidos con ese AAR. La capacidad productiva se liga al hash revisado y conserva
la autorización Engine. Código c325788: CI35580903584, cuatro SUCCESS; checkout
b9d5256b47ffeb3ce4c6aa9bbc8342d4f611085c. Recibo JSON/Markdown en docs/validation.
La entrega documental posterior debe comprobar su propia CI y conservar esta distinción.
Caducidad de credenciales/asignaciones y rutas UDP reales ejecutadas en laboratorio.
Hardware, IPv6, TURN TLS y recorrido de voz R8 siguen pendientes.
No se modifica señalización anterior ni se reducen Keystore/permisos offline.


## Cuarta entrega — señalización — 2026-09-21 UTC

USER_DECISION — rama `codex/authenticated-call-signaling`, dependiente de #6.
Base verificada `78af23b`; no modificar ramas previas. Señalización Signal, selección
única y consentimiento; RELAY_ONLY obligatorio, DIRECT_ALLOWED inactivo.
Evidencia y resultados: [informe](validation/2026-09-21-authenticated-call-signaling.md).
Recibo final de PR distingue HEAD/checkout/CI. Siguiente bloque: WebRTC voz 1:1
con TURN de laboratorio y pruebas reales; después video. No multimedia ejecutada.

## Tercera entrega — ubicación cifrada — 2026-09-21 UTC

USER_DECISION — Rama `codex/encrypted-location-core`, dependiente de #5 hacia
`codex/device-linking-core`; base abierta comprobada `c538b68`. No modificar ramas previas.
Implementación, comandos y estado de validación en el [informe](validation/2026-09-21-encrypted-location-core.md).
Consultar el recibo final de la nueva PR para HEAD/checkout/CI; no atribuir CI histórica
al código nuevo. Hardware GPS/Keystore/radio y auditoría siguen pendientes.
Siguiente bloque: señalización autenticada de llamadas, después audio/video; no están
implementados por la entrega de ubicación. Recuperación exportable sigue bloqueada.


## Segunda entrega de dispositivos — 2026-09-20

USER_DECISION — Rama `codex/device-linking-core`, PR dependiente de #4 hacia
`codex/secure-identity-media-core`. Base remota #4 comprobada OPEN en `5193b73`;
no se modifica main ni las ramas previas. Consultar el nuevo informe antes de
atribuir resultados de CI al commit actual.

VERIFIED — implementación `ef1106f` y CI `35544189738`: cuatro trabajos SUCCESS.
92 JVM por variante, 136 backend, 18 instrumentadas por variante; autoridad A1, claves Signal por dispositivo,
ceremonia con consentimiento de sesión, lista firmada/versionada, aprobación de
conjunto, fanout independiente y revocación local/capacidad delegada del relay.
[Protocolo](protocol/DEVICE_LINKING.md), [ADR](adr/ADR-device-model.md),
[evidencia](validation/2026-09-20-device-linking-core.md). Harness A1/A2/B1 real
HTTPS/JNI y SQLite de instrumentación aislado; no interfaz definitiva de dispositivos.

El HEAD posterior de evidencia/fixtures y su CI propia se registran en la
[PR #5](https://github.com/DevOps-Solutions-IA/umbra/pull/5); comprobarlos antes de continuar.

BLOCKED — hardware Keystore, Bluetooth físico, recuperación exportable y revisión
independiente. Ubicación/voz/vídeo/TURN siguen fuera de esta entrega. Consultar los
límites de revocación mientras no se haya instalado/recibido delegación del buzón.


## Núcleo de identidad — 2026-09-20

VERIFIED — PR #3 continúa abierta en `757027020a4a2da51e3f69196f1514eccf2c10c9`.
CI `35532763825`: cuatro jobs SUCCESS. El fallo histórico del AVD está resuelto.
Repositorio PUBLIC. Rama nueva `codex/secure-identity-media-core`, PR dependiente
[#4](https://github.com/DevOps-Solutions-IA/umbra/pull/4) hacia la rama de #3.
No modificar la rama de estabilización ni asumir fusión de las PR anteriores.

VERIFIED — Correcciones Vault en `b1a865c` (CI `35535038549` SUCCESS),
identidad en `d1598e2` (CI `35535786431`, cuatro jobs SUCCESS),
emparejamiento firmado de un uso, consumo local/relay atómico, verificación humana
separada, estados de confianza y suspensión por cambio de identidad. Misma identidad
libsignal, sin teléfono ni correo. UI mínima por archivos; no rediseño gráfico.
[Evidencia del bloque](validation/2026-09-20-secure-identity-media-core.md).

PARTIAL — La misión completa NO está terminada. Dispositivos múltiples, ubicación,
señalización, voz/video y TURN todavía no están implementados en este bloque.
RECOVERABLE bloqueado por los requisitos del ADR; sin mecanismo de recuperación
central. MAXIMUM conserva pérdida de identidad si se pierden todos los dispositivos.
Hardware físico, bóveda cifrada sobre hardware y revisión independiente pendientes.
Los informes históricos siguientes no representan el estado actual de CI.

## Continuidad de estabilización — 2026-09-19 UTC

VERIFIED — Rama `codex/repository-audit-fixes`; código validado
`8fdea14efb3c8a78a0faf66d01f9d254a8dc8a20`. PR #1 permanece abierta en
`0f4d566880454939dda130167c42de3ac8b4fe77`; se preservó además el HEAD de #2,
`47b26169717fcf57cfd9e01d79e10b68562ded56`. No partir de main suponiendo merge.
Consultar GitHub antes de continuar; entregar la corrección hacia la rama de #1.

VERIFIED — Backend 108, core 105, JVM 55 por variante, instrumentación 8 por variante,
integración HTTPS real y Bluetooth RFCOMM emulado ambas variantes; cuatro APK/lint y
arranque R8 bloqueado verificados. [Evidencia integral y hashes](validation/2026-09-19-integral-stabilization.md).
Los documentos siguientes describen etapas históricas y no sustituyen ese inventario.

BLOCKED — CI remota por pagos/límite de gasto según anotación GitHub; hardware físico
no disponible. NOT_VERIFIED — Bóveda Android productiva con hardware, migraciones/
fallos/cancelación Android completos, matriz UX y reconexión adversarial Bluetooth.
Siguiente trabajo: revisión humana de los cambios de identidad/almacenamiento/dependencias,
resolver por el propietario el bloqueo de Actions y repetir CI; continuar P0-03 y
las matrices pendientes sin relajar Keystore. No se autoriza auto-merge.

## Actualización P0-01 — 2026-09-19 UTC

VERIFIED — Repositorio privado publicado; base main comprobada:
`3716f09e415c69f59102e74cffa6a1bbb154dec6`. Cambios de código en
`5a8f9b368225372a80c5150a37d33737138216c9`, rama `codex/android-build-validation`.
Build debug real, 30 tests libsignal por variante, lint y permisos/JNI de APK
verificados localmente. Backend (80 tests) y contenedor aislado también verificados.
Consultar [evidencia y límites](validation/2026-09-19-p0-01.md) y
[procedimiento](ANDROID_BUILD.md). La PR requiere revisión; no se autoriza auto-merge.

NOT_VERIFIED — Siguen pendientes instrumentación/Keystore, Bluetooth emulado y
físico, release/R8, cadena de suministro completa y auditoría. Siguiente trabajo:
completar los controles restantes de P0-02 y continuar P0-03 según el roadmap.
La descripción de preparación que sigue es histórica, no el estado actual de publicación.

Fecha: 2026-09-18. Revisión de aplicación: 0.2.0-dev, sin cambios de runtime en esta preparación.
Objetivo remoto: `devopssolutionsia/umbra`, **privado**. La preparación local no demuestra que
el repositorio exista en GitHub. No hay una tarea Codex iniciada ni servicio desplegado.

## Empezar
1. Publicar con `python scripts/publish_github.py` desde esta carpeta, con Git y GitHub CLI
   instalados y autenticación local del propietario. El publicador verifica la cuenta, crea un
   repositorio privado, comprueba la visibilidad antes del push y verifica el SHA remoto.
2. En Codex, autorizar el acceso al repositorio exacto y crear/seleccionar su entorno. La conexión
   de GitHub en un chat no demuestra automáticamente el acceso del entorno Codex al nuevo repo.
3. Configurar `bash scripts/codex_setup.sh` como setup y el mismo comando como mantenimiento
   para refrescar el entorno Python tras cambios. Los exports de un setup no se deben suponer
   persistentes: cada sesión debe activar `.venv/bin/activate`.
4. Usar `PROMPT_CODEX.md`. Trabajar en ramas `codex/<tarea>` y entregar PRs revisables.

## Estado comprobado en la preparación
Las suites existentes se ejecutaron de nuevo: 80 pruebas pytest del relay, 20 escenarios de
CoreSelfTest y 85 de SecuritySelfTest. Se registraron por separado 12 comprobaciones de fuente y
la lectura sintáctica de 20 archivos Java. Ver `docs/validation/handoff-local-tests.txt`.

La compilación Android, los 30 métodos JUnit/libsignal, el APK, el Bluetooth real, el Keystore
real, la instrumentación y una auditoría independiente siguen pendientes. Ver el informe nuevo
`docs/validation/handoff-summary.md` para las pruebas del publicador y de la preparación.

## Qué se conserva y qué se añade
Se conservó el código Android y del relay. Se añadió AGENTS.md, instrucciones y backlog para
Codex, estrategia de laboratorio sin teléfonos, publicador privado, guardas de publicación,
pruebas de esas herramientas y configuración de CI reforzada. No se incrementó la versión de
aplicación para insinuar funciones nuevas. La licencia MIT original y las advertencias de
licencias de terceros se mantienen; no resuelven automáticamente obligaciones de libsignal.

## Entornos separados
- **Codex/CI de código:** Python/JVM, compilación Android, lint, pruebas de integración real
  disponibles y contenedor del relay. No requiere dos teléfonos ni un emulador para compilar.
- **Laboratorio de emulación:** máquina con SDK, imágenes compatibles y virtualización. Medir
  capacidades antes de asumir KVM, Bluetooth, Keystore respaldado por hardware o dos AVD.
- **Pruebas con hardware:** servicio externo compatible o dispositivos del equipo. Usar solo
  datos sintéticos; coste, cuenta y permisos requieren autorización. Un device farm no asegura
  que dos equipos estén físicamente próximos o puedan emparejarse.
- **Publicación:** independiente de los agentes. No hay claves de firma de release, dominio,
  credenciales de infraestructura ni presupuesto configurados en esta entrega.

## Primera entrega exigible a Codex
Reproducir las pruebas, corregir compilación real sin alterar invariantes, ejecutar los tests de
libsignal y generar APKs debug de las variantes `connected`/`offline`. Verificar manifiestos
combinados y permisos de APK. Guardar logs, versiones, commit y artefactos. La falta de hardware
no bloquea las tareas anteriores ni autoriza afirmar que Bluetooth ya funciona en teléfonos.

## Backend
Conservar API de buzones/capacidades y administración por CLI hasta justificar un cambio con ADR.
No crear un dashboard que permita leer conversaciones. Cerrar integración cliente-servidor,
cuotas por capacidad, límites en el borde/proxy, migración, recuperación, TTL y operación segura.
La base usa SQLite y un worker: no anunciar alta disponibilidad. Los tags de contenedores y los
locks sin hashes siguen siendo deuda de cadena de suministro, no una garantía de reproducibilidad.

## Fuentes operativas
Consultar `docs/HANDOFF_SOURCES.md`. Codex debe verificar versiones y capacidades actuales antes
de instalar herramientas. No descargar scripts arbitrarios con `curl | sh` ni copiar secretos a
mensajes, issues, artefactos de CI, capturas de pantalla o servicios de análisis.
