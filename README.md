# UMBRA — código fuente de mensajería privada

## Preparación para GitHub y Codex

**Publicación remota pendiente.** Objetivo: `devopssolutionsia/umbra`, privado. Esta carpeta
incluye el código 0.2.0-dev más herramientas e instrucciones de continuidad; no incorpora
funciones nuevas de aplicación ni convierte esta entrega en una release.

Desde esta carpeta, con Python 3, Git y GitHub CLI instalados:
```bash
python scripts/publish_github.py --check-only
python scripts/publish_github.py
```
El segundo comando se ejecuta en el equipo autenticado del propietario. Abre el inicio de
sesión de GitHub CLI cuando hace falta; no solicita pegar tokens en un chat. No reemplaza
repositorios existentes, no hace force-push y comprueba privacidad/SHA remoto. Ante una
interrupción después de crear el repositorio, `--resume` exige un remoto privado vacío o
que `main` ya corresponda exactamente al commit local. Ver `docs/GITHUB_SETUP.md`.

Para continuar en Codex: `AGENTS.md`, `PROMPT_CODEX.md`, `docs/CODEX_HANDOFF.md`,
`docs/ROADMAP_CODEX.md` y `docs/TESTING_WITHOUT_PHONES.md`. La CI está configurada,
no ejecutada en GitHub en esta entrega. Los resultados locales nuevos están en
`docs/validation/handoff-summary.md`.

---

**0.2.0-dev · continuación de 0.1.0-dev · 18 de septiembre de 2026**

Cliente Android nativo con interfaz propia, chats individuales, adjuntos pequeños, identidad local sin teléfono, transporte Bluetooth directo y relay HTTPS. Esta revisión modifica el código anterior: no es una maqueta de pantallas ni un documento de propuesta.

**Entrega de desarrollo, no de una aplicación certificada para secretos reales.** P0-01 ejecuta las pruebas del servidor, núcleo Java y libsignal/JNI, y genera APKs debug de ambas variantes con lint e inspección de permisos. Siguen pendientes las pruebas Android instrumentadas, entre teléfonos y una auditoría independiente. No se afirma que sea más segura que WhatsApp, Signal u otra aplicación auditada.

## Qué cambia en esta revisión

| Área | Código incorporado |
|---|---|
| Bóveda | Índices HMAC-SHA-256, valores AES-256-GCM, contexto autenticado por registro, migración 1→2 transaccional y rechazo de sustitución silenciosa de claves perdidas. |
| Autorización local | Permisos de acceso de duración limitada y por generación; un bloqueo invalida operaciones anteriores. Las escrituras deben formar una transacción y se comprueba la autorización al confirmar. |
| Android | Preparación de Keystore fuera del hilo visual, requisito de claves respaldadas por hardware compatible, bloqueo al abandonar la app, autenticación antes de continuar tras el selector de archivos y protección adicional de diálogos. |
| Bluetooth | Vinculación explícita separada de reconexión, desafío firmado de posesión de identidad, nonces, roles y límites de tamaño, tiempo y colas. No se envía automáticamente una tarjeta de contacto al reconectar. |
| Mensajería | Validación estricta de tipos/campos/UTF-8/Base64, rechazo de claves JSON duplicadas, cuotas locales, orden persistente de salida y reintentos con el mismo ciphertext. |
| Relay | Límites de solicitudes y cuerpos, rechazo de JSON ambiguo, plazos de recepción, límites de concurrencia, cursores SQLite monotónicos, cuotas de buzones y registros de deduplicación. |
| Distribución | Variantes `connected` y `offline`, comprobaciones de política del código y verificación posterior de manifiestos combinados. |

El cifrado de mensajes mantiene la integración con **libsignal 0.102.3**; no se sustituyó por un cifrado casero. El desafío adicional de Bluetooth es protocolo propio de aplicación y **requiere revisión independiente**. La clave que protege los registros se gestiona en Keystore; las claves del protocolo se descifran en memoria cuando se necesitan. No se afirma que todas las claves de mensajería residan siempre dentro del hardware.

## Evidencia de esta entrega

| Validación | Resultado observado |
|---|---|
| Relay, pytest | **80 pruebas aprobadas**: 33 anteriores y 47 incorporadas. |
| Núcleo Java, JDK 21 real | **105 escenarios aprobados**: 20 anteriores y 85 nuevos. Incluyen JCA AES-GCM/HMAC con claves de prueba de software, no Android Keystore. |
| Configuración fuente | **12 comprobaciones aprobadas** sobre permisos declarados, backups, confianza TLS y variante offline. No inspeccionan un APK. |
| Sintaxis Java | 20 archivos analizados; no verifica resolución de tipos/dependencias Android. |
| Integración real libsignal | **30 métodos JUnit aprobados por variante** (60 ejecuciones): JNI real en JVM Linux, sin fallos ni omisiones. |
| Build Android | Gradle 8.13 / AGP 8.13.2 / JDK 21 / SDK 36: ambos APK debug y lint aprobados (4 advertencias por variante). |
| Manifiestos combinados / APK | Permisos y JNI verificados; offline sin INTERNET ni ACCESS_NETWORK_STATE. |

La evidencia actual de P0-01 está en [el informe fechado](docs/validation/2026-09-19-p0-01.md). Los informes históricos están en [docs/TEST_STATUS.md](docs/TEST_STATUS.md) y `docs/validation/`. Los informes anteriores se conservan, identificados como históricos, en `docs/legacy/0.1/`.

## Dos variantes

**Connected** conserva mensajería por relay HTTPS y Bluetooth. Su interruptor «solo Bluetooth» cancela conexiones propias en curso y pausa nuevos intentos de red; no puede retirar bytes que ya se hayan enviado.

**Offline** tiene un identificador de aplicación distinto, `BuildConfig.ALLOW_RELAY=false` y un manifiesto de variante que elimina `INTERNET` y `ACCESS_NETWORK_STATE`. La ausencia de ambos permisos se verifica en los manifiestos combinados y con aapt sobre el APK debug final. El script posterior a la compilación falla si falta un manifiesto o si conserva los permisos prohibidos. Esta variante no comparte automáticamente identidad ni historial con Connected.

La restricción se refiere al proceso de UMBRA: un selector de documentos o aplicación externa puede usar su propia conexión a internet. No convierte el teléfono completo en un dispositivo sin red ni oculta la actividad de radio Bluetooth.

## Estructura

```text
android/app/src/main/      Cliente nativo, protocolo, bóveda e interfaz
android/app/src/offline/   Eliminación de permisos de red para la variante offline
android/app/src/test/      Pruebas JVM de integración real con libsignal
relay/                    FastAPI/SQLite, administrador de invitaciones y pruebas
scripts/                  Pruebas locales, políticas de fuente y compilación Android
.github/workflows/        Flujo CI para backend, contenedor y ambas variantes Android
docs/validation/          Salidas reales de esta sesión
docs/HARDENING_0_2.md      Cambios, amenazas cubiertas y limitaciones
```

## Ejecutar las pruebas locales

Python 3.12+ y JDK 21. Se utilizó Python 3.13.5 en esta entrega.

```bash
cd UMBRA
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r relay/requirements-test.txt
bash scripts/test_local.sh
```

En Windows: activar `.venv\Scripts\Activate.ps1` y utilizar Git Bash/WSL para el script de shell, o ejecutar por separado las órdenes que contiene. Las dependencias de ejecución permanecen fijadas como en la base; no se ha completado una revisión de vulnerabilidades ni generado un lock con hashes de todos los artefactos.

## Compilar Android y ambas variantes

JDK 21, Gradle 8.13, Android Gradle Plugin 8.13.2, plataforma 36 y Build Tools 35.0.0. Android 12/API 31 o superior en los teléfonos. Su resolución conjunta se verificó para debug; ver [el procedimiento reproducible](docs/ANDROID_BUILD.md).

```bash
sdkmanager "platforms;android-36" "build-tools;35.0.0"
python scripts/build_android.py --sdk /ruta/al/Android/Sdk
```

En Windows:

```powershell
python scripts/build_android.py --sdk "$env:LOCALAPPDATA\Android\Sdk"
```

El script ejecuta pruebas JUnit, ensamblado debug y lint de ambas variantes, y después comprueba manifiestos combinados, permisos del APK y JNI. Utiliza Gradle 8.13 descargado de la distribución oficial, con comprobación de su checksum publicado. No incorpora binarios del SDK ni bibliotecas descargadas.

Rutas de los APK debug generados localmente (los binarios no se añaden a Git):

```text
android/app/build/outputs/apk/connected/debug/app-connected-debug.apk
android/app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

No hay claves de firma de publicación. El flujo GitHub Actions no se ejecutó ni se publicó en una cuenta. Las acciones de CI se fijaron por SHA en esta preparación. Las imágenes, toolchains y demás artefactos de terceros todavía requieren fijación/revisión adicional antes de una distribución sensible.

## Prueba de aceptación Bluetooth, pendiente

Instalar la misma revisión en dos teléfonos compatibles, crear una identidad distinta en cada uno y emparejarlos desde Android. Regresar a UMBRA y desbloquear ambas aplicaciones. Para un contacto nuevo, utilizar **Cerca → Vincular un nuevo contacto** en ambos extremos: uno espera y el otro conecta. La vinculación intercambia tarjetas y prueba posesión de claves; **no equivale a verificar a la persona**.

Comparar personalmente el código de seguridad completo en ambos teléfonos y completar la verificación. Después, usar los botones de contacto verificado para reconectar. Mantener ambas apps abiertas/desbloqueadas, Wi-Fi y datos móviles desactivados, Bluetooth activo y enviar solamente datos de prueba. Comprobar mensajes bidireccionales, adjuntos, confirmaciones, desconexiones y reintentos.

La versión 0.2 usa saludo Bluetooth v2 y no conecta mediante el saludo anterior: actualizar ambos extremos. El servidor conserva el formato de sobre v1. El vencimiento de la autorización local, aproximadamente a los cuatro minutos, interrumpe el enlace y exige volver a desbloquear.

## Migraciones y datos

La variante Connected mantiene la continuidad de identificador respecto a la versión anterior del mismo tipo de build. El código migra la bóveda de esquema 1 a 2 conservando la clave AES existente y creando una clave HMAC para índices. **La migración Android no se probó en Keystore/SQLite reales de un teléfono.** No usar datos irremplazables para estrenarla ni intentar revertir posteriormente a 0.1.

El relay migra su cola a secuencias `AUTOINCREMENT`. La migración SQLite del servidor sí tiene pruebas ejecutadas de conservación de datos y rollback ante fallo. Revisar recuperación, retención y almacenamiento antes de una operación real.

## Límites deliberados

Chats individuales, texto hasta 16.000 bytes, adjuntos hasta 256 KiB, 100 contactos y vencimiento máximo de siete días. Se reservan hasta 128 salidas de usuario y 256 entradas totales de salida, incluidas confirmaciones; hasta 4.096 registros de mensajes y 8.192 registros de recepción/deduplicación; cuota local de ciphertext de 64 MiB. El borrado por vencimiento se procesa al ejecutar/desbloquear la aplicación, no mediante una garantía de borrado puntual con la app cerrada.

Sin grupos, llamadas, videollamadas, iPhone, escritorio, malla, Wi-Fi Direct, varios dispositivos, recuperación de identidad, push ni recepción con la app bloqueada. El QR se muestra para verificación; no hay lector de QR integrado. Bluetooth es un enlace de proximidad, no una red anónima ni de larga distancia.

Un destinatario puede copiar lo recibido. Un sistema comprometido puede observar datos cuando están descifrados. Las exportaciones salen de la protección de UMBRA; los buffers de memoria administrada y almacenamiento flash no ofrecen borrado forense garantizado. El relay y la red siguen viendo metadatos. Una app privada no elimina esas limitaciones.

## Operación, licencia y publicación

El modo Bluetooth no necesita servidor. Para HTTPS: consultar [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md); no hay un servidor contratado, expuesto ni desplegado por esta entrega. Las medidas adicionales y pendientes se describen en [docs/SECURITY.md](docs/SECURITY.md) y [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).

El código propio mantiene la licencia MIT de la entrega anterior. libsignal declara AGPLv3 y uso externo sin soporte: esto no convierte automáticamente el APK enlazado en una distribución cerrada. Conservar y revisar `THIRD_PARTY_NOTICES.md` y las licencias de todas las dependencias antes de distribuir. No existe afiliación con Signal ni WhatsApp. La marca UMBRA sigue siendo provisional.
