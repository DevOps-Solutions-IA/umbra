# Validación de la preparación GitHub/Codex

Fecha: 2026-09-18. Versión de aplicación: 0.2.0-dev.
**Estado remoto: NO CREADO / NO PUBLICADO en esta sesión. Codex NO iniciado.**

## Trabajo local ejecutado
| Control | Resultado | Límite |
|---|---|---|
| pytest del relay | 80 pruebas aprobadas de nuevo | No despliegue/TLS público |
| CoreSelfTest | 20 escenarios aprobados de nuevo | JVM, no Android |
| SecuritySelfTest | 85 escenarios aprobados de nuevo | Sin Keystore/radio reales |
| Herramientas de publicación | 39 pruebas aprobadas | Casos locales y flujo CLI simulado; NO creación real GitHub |
| Configuración fuente | 12 controles aprobados de nuevo | No inspección de un APK |
| Sintaxis Java | 20 archivos analizados | No resolución de tipos Android/libsignal |
| YAML de CI | Parseado y comprobados 4 jobs, permisos read-only y pins | NO ejecutado en GitHub |
| Integridad de runtime importado | 41 archivos Android/relay iguales byte por byte al ZIP base | No implica que el código esté libre de errores |
| Preflight Android | Exit 2: SDK no disponible | Sin APK ni ejecución JNI/instrumentada |

Python 3.13.5 y OpenJDK 21.0.11 en esta ejecución. Las pruebas del publicador se informan
por separado: no incrementan el número de pruebas criptográficas o de Android.
Se ejecutó una guarda básica de nombres sensibles y patrones de credenciales en el árbol
y blobs históricos; no es una búsqueda exhaustiva de secretos ni auditoría de seguridad.

Comandos:
```bash
bash scripts/test_local.sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/repository_guard.py --git-history
python scripts/publish_github.py --check-only
python scripts/build_android.py --check-only
```

## Evidencia
- `handoff-local-tests.txt`: salida original de las suites de aplicación y políticas.
- `handoff-tools-tests.txt`: 39 pruebas auxiliares, con datos sintéticos.
- `handoff-android-preflight.txt`: bloqueo real por SDK ausente.
- `../ci-action-pins.json`: SHAs obtenidos de los refs upstream de GitHub.

## No ejecutado ni contratado
Creación/push remoto, GitHub Actions, contenedor del relay, compilación Android, los 30 métodos
JUnit/libsignal, emuladores, Bluetooth virtual o físico, Android Device Streaming/Test Lab,
auditoría independiente, despliegue, firma y distribución de producción. La documentación
plantea la vía para Codex; no presenta esos pasos como resultados completados.
