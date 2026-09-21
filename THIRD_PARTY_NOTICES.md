# Dependencias y avisos

No se incluyen bibliotecas descargadas, APK, artefactos Gradle, imágenes Docker ni claves de publicación en este archivo fuente. Los gestores de paquetes descargan las dependencias al compilar/desplegar.

## libsignal

La compilación referencia `org.signal:libsignal-client:0.102.3` y `org.signal:libsignal-android:0.102.3` desde el repositorio oficial de Signal.

El proyecto upstream declara: Copyright 2020–2026 Signal Messenger, LLC; GNU AGPLv3. Véanse:

- https://github.com/signalapp/libsignal
- https://github.com/signalapp/libsignal/blob/v0.102.3/LICENSE
- https://www.gnu.org/licenses/agpl-3.0.html

La licencia MIT de los archivos originales de UMBRA no sustituye la AGPL ni autoriza ignorar los requisitos que correspondan al distribuir una aplicación enlazada. Incorporar el texto íntegro de las licencias de terceros y los avisos exigibles en la distribución final, junto con el código fuente correspondiente que proceda. Revisar el modelo de distribución con asesoría de licencias antes de vender/publicar un binario. No se ha realizado una revisión jurídica del producto.

Upstream advierte que el uso fuera de Signal no cuenta con soporte y que las APIs pueden cambiar. UMBRA no es un producto oficial ni un servicio aprobado por Signal.

## Otras dependencias directas

El proyecto referencia ZXing Core para generar el QR del código de seguridad, JUnit y org.json para pruebas, FastAPI/Pydantic/Uvicorn para el servidor y herramientas de construcción y test. Las versiones están en los archivos Gradle y `requirements*.txt`/`requirements.lock`. Los archivos de bloqueo y este aviso **no son un inventario completo ni una auditoría de licencias de todas las dependencias transitivas**.

Antes de distribuir: resolver las versiones exactas, generar un SBOM, recoger las licencias completas de esas versiones, comprobar los requisitos de cada componente y revisar las vulnerabilidades actuales. Las imágenes de Python y Caddy y las acciones de CI no se fijaron por digest/commit inmutable en esta entrega; es una tarea pendiente de publicación.

## WebRTC SDK Android (connected únicamente; quinta entrega en curso)

`io.github.webrtc-sdk:android:150.7871.01`, distribución del fork WebRTC SDK.
Fuente declarada `73cb8180f7258ee292878d6edd05177f41883962`, licencia BSD-3-Clause:
https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/LICENSE
Concesión PATENTS y dependencias nativas tienen avisos propios. El inventario de
hashes está en `android/webrtc-artifact.json`; no es un inventario completo de
licencias transitivas ni certifica reproducibilidad desde fuente. Ver
`docs/adr/ADR-webrtc-voice.md`. No publicar binarios sin completar esos avisos.
