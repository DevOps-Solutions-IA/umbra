# Fuentes de la preparación

Consultadas el 18 de septiembre de 2026. Son referencias técnicas, no una auditoría de UMBRA.
- Codex / AGENTS.md: https://developers.openai.com/codex/guides/agents-md
- Codex / entornos: https://developers.openai.com/codex/cloud/environments
- Codex / conexión del repositorio: https://developers.openai.com/es-419/docs/cloud
- Android Emulator: https://developer.android.com/studio/run/emulator
- Capacidades de red/emulación: https://developer.android.com/studio/run/emulator-networking
- Bumble Android/netsim: https://google.github.io/bumble/platforms/android.html
- Android Device Streaming: https://firebase.google.com/docs/test-lab/android/android-device-streaming
- GitHub CLI, creación privada: https://cli.github.com/manual/gh_repo_create

Los SHAs en `.github/workflows/verify.yml` se resolvieron desde los refs de las cuentas
upstream mediante GitHub. `docs/ci-action-pins.json` registra esos refs. Fijar un SHA reduce
la mutabilidad de una dependencia; no certifica su código ni fija todos los binarios que
ella instala. SDK, toolchains, pip y contenedores requieren verificación adicional.

- Autenticación GitHub CLI y almacenamiento: https://cli.github.com/manual/gh_auth_login
