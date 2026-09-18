Continúa UMBRA a partir de este repositorio existente; no vuelvas a crear una maqueta ni
sustituyas la aplicación por un ejemplo. Lee AGENTS.md y docs/CODEX_HANDOFF.md completos.

Trabaja sobre Android nativo y el backend del relay, seguridad, persistencia, comunicaciones,
interfaz propia de mensajería y operación. Sigue docs/ROADMAP_CODEX.md y registra evidencia
por cada paquete de trabajo. Usa exclusivamente datos sintéticos.

Primero reproduce las pruebas existentes, resuelve la compilación Android con libsignal real,
ejecuta sus pruebas, genera ambos APKs debug y comprueba sus permisos. Después implementa
las pruebas instrumentadas y la estrategia sin teléfonos de docs/TESTING_WITHOUT_PHONES.md.
Investiga y valida dos emuladores con Bluetooth/netsim compatible; un enlace simulado de bytes
no es una prueba de radio. No debilites el Keystore ni el cifrado de producción para usar AVD.

Cierra backend y cliente por etapas pequeñas con pruebas adversariales, dependencias revisadas,
CI, documentación de operación y criterios de aceptación. No ocultes errores, no elimines tests
para conseguir un resultado verde y no anuncies que una función está probada solo por escribirla.

Entrega PRs con cambios reales, comandos ejecutados, logs, artefactos y bloqueos restantes.
No habilites servicios pagos, no despliegues en producción ni firmes/publicites una release sin
las autorizaciones y revisiones correspondientes. El objetivo es completar la app verificablemente;
no prometer secreto absoluto ni inventar resultados físicos o de auditoría.

Empieza por el paquete P0-01 y avanza en las tareas independientes que puedas ejecutar realmente.
