#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
(cd "$ROOT/relay" && PYTHONPATH=. python -m pytest -q)
javac -d "$OUT" "$ROOT"/android/app/src/main/java/app/umbra/core/*.java "$ROOT/scripts/CoreSelfTest.java" "$ROOT/scripts/SecuritySelfTest.java" "$ROOT/scripts/JavaSyntaxCheck.java"
java -cp "$OUT" CoreSelfTest
java -cp "$OUT" SecuritySelfTest
java -cp "$OUT" JavaSyntaxCheck "$ROOT/android/app/src"
python "$ROOT/scripts/check_source_policy.py"
echo "These checks do NOT compile or run the Android/libsignal integration. Run scripts/build_android.py separately."
