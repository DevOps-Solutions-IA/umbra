# Transferencia técnica a Codex

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
