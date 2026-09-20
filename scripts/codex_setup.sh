#!/usr/bin/env bash
# Prepares dependency environment; does NOT claim Android build or device testing.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 -c 'import sys; assert sys.version_info >= (3, 12), "Python 3.12+ required"'
python3 -m venv .venv
.venv/bin/python -m pip install -r relay/requirements-test.lock
printf '\nPython environment ready. Activate: . .venv/bin/activate\n'
if command -v java >/dev/null 2>&1; then java -version; else echo 'BLOCKED for Java: install JDK 21.'; fi
printf 'Android SDK/JNI/emulator not installed or validated by this setup.\n'
printf 'Run build_android.py and CI separately; see docs/CODEX_HANDOFF.md.\n'
