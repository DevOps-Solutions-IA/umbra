# PR #12 — accesibilidad — 2026-09-27

Base inspeccionada: `claude/android-ui-foundation` en
`96e9a351de9301019ba5c020f6165b884d4bc8ae`, PR #12 OPEN/DRAFT hacia
`codex/local-voice-modulator` (`6b0a844aa0a97d4984d04a58330621f37c5291d1`).
Worktree separado; no se modifica main, las ramas de seguridad ni trabajo ajeno.

## Fallo preservado y corrección

Se descargó el artefacto Android de Verify36305947467. `connected.log` confirma
43 pruebas y tres fallos: lista de conversaciones sin descripción accesible;
campos de relay y alias con altura46px, inferior al mínimo del test.

- La lista principal ahora tiene `contentDescription="Conversaciones"`; no oculta
  hijos ni cambia navegación, filas, branding o contenido visible.
- `Ui.field()` conserva el mínimo visual previsto52dp también mediante
  `View.setMinimumHeight()`. `TextView.setMinHeight()` no basta cuando después
  `setSingleLine(true)` sustituye su mínimo de píxeles por el de líneas.
  No se cambian padding, tipografía, paleta o iconos.

Las tres pruebas instrumentadas existentes son las regresiones; se mantienen
intactas sus aserciones y el mínimo48dp. No se eliminan pruebas ni se cambia
la importancia de accesibilidad para esconder controles.

## Validación de este cambio

Preflight local con Python3.13.12, JDK21.0.11, Gradle8.13, AGP8.13.2,
SDK36/build-tools35.0.0. Compilación/lint/JVM/debug/release en ejecución al escribir
este recibo. KVM local no disponible para este usuario; instrumentación en Actions.
No se atribuye el verde histórico a este cambio. Resultados finales, HEAD,
checkout, artefactos y hashes se registran en el resumen de PR #12 tras comprobarlos.

Los fallos históricos36305947453 y36305947470 son precondiciones UDP del
laboratorio anteriores a media. No se modifica ese arnés con esta corrección UI.
Solo se investigará si la nueva CI reproduce la misma precondición. No se retira
el probe UDP ni la comprobación de ruta directa; TURN-only queda intacto.

No cambia Engine, Signal, Vault, Keystore, admisión, WebRTC o permisos offline.
No se afirma validación TalkBack manual con hardware, radio física ni producción.
