# Compilación debug verificable

Requisitos fijados: Python 3.12+, JDK 21, Gradle 8.13, AGP 8.13.2,
plataforma Android 36 y Build Tools 35.0.0. libsignal sigue en 0.102.3.
El AAR requiere core library desugaring; se utiliza `desugar_jdk_libs:2.0.3`.

Desde la raíz, con el JDK 21 instalado seleccionado en `JAVA_HOME` y `PATH`:

```bash
source .venv/bin/activate
java -version
javac -version
python --version
export ANDROID_HOME=/ruta/al/Android/Sdk
sdkmanager "platforms;android-36" "build-tools;35.0.0"
bash scripts/test_local.sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/build_android.py --check-only
python scripts/build_android.py
```

El script utiliza exclusivamente la distribución Gradle 8.13 de `.umbra-tools`,
descargada por `bootstrap_gradle.py` y contrastada con el checksum oficial publicado.
Un Gradle diferente presente en `PATH` no altera la selección. `--check-only`
verifica Python, Java/javac 21, la plataforma y aapt; no descarga Gradle ni demuestra
resolución de dependencias. En CI, `setup-gradle` selecciona la misma versión.

La compilación ejecuta los 30 métodos JUnit existentes para **cada** variante con
libsignal JNI real, ensambla los dos APK debug y ejecuta lint. Después comprueba
ambos manifiestos combinados y usa `aapt dump permissions` sobre los APK finales:
connected debe declarar INTERNET y ACCESS_NETWORK_STATE; offline debe carecer de ambos.
También comprueba las cabeceras ELF de `libsignal_jni.so` para arm64-v8a,
armeabi-v7a, x86 y x86_64, y rechaza JNI de escritorio en recursos del APK y
`libsignal_jni_testing.so` (APIs auxiliares de testing de Signal, no utilizadas).
Esto verifica empaquetado, no ejecución de JNI en esas cuatro arquitecturas Android.

Salidas locales (ignoradas por Git):

```text
android/app/build/outputs/apk/connected/debug/app-connected-debug.apk
android/app/build/outputs/apk/offline/debug/app-offline-debug.apk
android/app/build/test-results/testConnectedDebugUnitTest/
android/app/build/test-results/testOfflineDebugUnitTest/
android/app/build/reports/tests/
android/app/build/reports/lint-results-*
```

CI conserva APKs debug y reportes durante siete días en los artefactos de la ejecución.
No son releases. Los hashes debug pueden diferir entre máquinas por sus claves de
firma locales; este procedimiento no promete reproducibilidad binaria bit a bit.
La fijación completa de transitivas, hashes y contenedores sigue en P0-09.

Las pruebas JVM utilizan almacenamiento sintético `MemoryRecords`; la criptografía
no se simula. No prueban Keystore, SQLite Android, navegación, permisos revocados en
un dispositivo ni Bluetooth físico. Esas validaciones siguen en los paquetes de
instrumentación/emulación/hardware del roadmap.

Fuentes consultadas para las correcciones:

- [Empaquetado libsignal 0.102.3](https://github.com/signalapp/libsignal/blob/v0.102.3/README.md#use-as-a-library).
- [Desugaring de APIs Java](https://developer.android.com/studio/write/java8-support).
- [Callback nativo de navegación atrás](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).
  La app ya registra OnBackInvokedCallback para API 33+; la excepción lint puntual
  en onBackPressed conserva la compatibilidad API 31–32, sin desactivar el control global.
