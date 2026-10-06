# Preparación de emparejamiento con dos teléfonos físicos

`scripts/run_physical_pairing.py` implementa únicamente preflight de lectura.
No existe modo de instalar, actualizar, limpiar, desinstalar, reiniciar, abrir
la aplicación ni ejecutar instrumentación. La preparación no autoriza una
instalación: cada APK requiere una decisión nueva del propietario sobre su
package, build y SHA-256 exactos, mediante un flujo autorizado aparte.

Ejemplo para ejecutar solo tras conectar y autorizar RSA de dos teléfonos USB
(sustituir variables en privado; no publicar seriales ni historial del shell):

```bash
python scripts/run_physical_pairing.py \
  --adb "$ADB" --server-port 5038 \
  --serial "$PHONE_A" --serial "$PHONE_B" \
  --package app.umbra.privatechat.offline.dev \
  --app-apk "$APP_APK" --apk-sha256 "$APK_SHA256" \
  --signer-sha256 "$LAB_SIGNER_SHA256" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --reports "$NEW_PRIVATE_REPORT_DIRECTORY"
```

Se exigen exactamente dos filas distintas de `adb devices -l`, estado `device`
y metadata `usb:`. Sin metadata USB se bloquea, incluso si el operador cree
que el dispositivo es físico. Se rechazan seriales de red/AVD, qemu, API menor
que 31, paquetes productivos y diferencias entre el hash fijado y el APK local
o instalado. Solo se admiten los paquetes debug aislados connected/offline
`.dev`. `aapt` y `apksigner` comprueban package, versión y certificado local.
No se considera que las propiedades de arranque sean atestación hardware.

El informe privado `preflight.json` registra hash de serial, modelo, API,
package, versión del APK local y hashes de APK local/instalado. No registra
salida ADB, seriales en claro, tarjetas, QR, direcciones Bluetooth, secretos,
contenido o datos de usuario. El hash de serial es correlacionable, no una
promesa de anonimato. Cada intento necesita un directorio nuevo. Se usan los
mismos bloqueos locales por dispositivo que el runner físico existente; no
impiden que otras herramientas sin ese bloqueo operen sobre el teléfono.

`PREFLIGHT_READY` significa únicamente que ambos dispositivos presentan los
bytes exactos del APK inspeccionado. `PENDING_INSTALL_APPROVAL` significa que
al menos uno no tiene instalado el paquete. Ninguno acredita emparejamiento.
El script no importa recibos históricos ni inventa resultados: importación QR,
verificación humana bilateral, Signal bidireccional, rechazo de replay,
persistencia tras reinicio, RFCOMM y autenticación de bóveda permanecen
`MANUAL_PENDING`. Un futuro arnés necesita recibos específicos de cada caso,
ambos dispositivos y los APK exactos antes de poder emitir aceptación física.

Las pruebas Python cubren decisiones del runner con respuestas sintéticas;
no cuentan como pruebas físicas, Bluetooth, criptográficas ni Android.
