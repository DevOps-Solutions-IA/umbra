# Pruebas sin dos teléfonos Android

## Decisión
La falta de dos teléfonos no impide desarrollar backend, compilar Android ni ejecutar pruebas
JVM de libsignal. Para la interfaz y el stack Android se utilizará emulación; para conclusiones
sobre hardware o radio física no se sustituirá evidencia por una simulación.

La documentación Android consultada el 2026-09-18 enumera perfiles Bluetooth Classic y BLE
para API 31+. Bumble documenta conexión al controlador virtual netsim. Eso habilita una vía de
laboratorio, **no demuestra que el código UMBRA y la combinación concreta de AVD funcionen**.
Fuentes: docs/HANDOFF_SOURCES.md. Registrar la versión exacta de emulator y system image;
no extender a Bluetooth el requisito 36.5 que la tabla Android asigna a otras capacidades P2P.

| Capa | Herramienta/entorno | Estado de esta entrega | No demuestra |
|---|---|---|---|
| Backend HTTP/ASGI | pytest con almacenamiento temporal | 80 pruebas reejecutadas | TLS público, carga real, Android |
| Núcleo | JDK 21 | 105 escenarios reejecutados | Android Keystore ni radio |
| Protocolo libsignal | Tests JVM existentes | 30 métodos pendientes | Sistema Android o hardware |
| Build y política | Gradle/SDK/lint/APK tools | CI configurada, no ejecutada en GitHub | Que la interfaz o Bluetooth funcionen |
| UI y ciclo de vida | Android instrumentation/AVD | Por implementar/ejecutar | Hardware protegido genuino |
| Bluetooth virtual | Dos AVD/netsim; Bumble auxiliar cuando corresponda | Por validar | RF física, alcance, batería de equipos reales |
| Equipo remoto | Device streaming/Test Lab compatible | No contratado ni ejecutado | Que dos equipos puedan emparejarse entre sí |
| Aceptación física | Dos radios/dispositivos compatibles en proximidad | Pendiente | Auditoría independiente por sí sola |

## Problema de Keystore que Codex debe resolver sin downgrade
La base exige claves compatibles respaldadas por hardware. Un AVD puede no satisfacerlo y la
app debe rechazarlo en modo producción. No modificar ese requisito globalmente. Diseñar un
punto de inyección y una variante `lab` con applicationId propio y una advertencia persistente,
que admita claves de software únicamente para datos sintéticos. No reusar identidad, base de
datos ni directorio de Connected/Offline. Impedir que lab genere una release distribuible y
verificar que sus clases/configuración no aparecen en los artefactos de producción. Esta
variante todavía no está implementada. Un build debug por sí solo no autoriza el fallback.

## Secuencia de laboratorio
1. Obtener APKs mediante compilación real y resolver las pruebas criptográficas existentes.
2. Crear dos AVD **independientes**, no dos copias de una imagen con identidad ya inicializada.
   Verificar API, ABI/JNI, aceleración, versión de emulador y adaptador Bluetooth disponible.
3. Implementar y validar el aislamiento lab. Automatizar identidad/contactos con material de
   prueba, incluyendo comparación de huellas fuera de banda simulada y casos de rechazo.
4. Probar primero contratos de transporte deterministas con inyección de fallos; conservar
   el cifrado real. Llamar a estas pruebas pruebas de transporte simulado, no Bluetooth.
5. Conectar los AVD a la misma instancia netsim compatible. El camino Android RFCOMM de
   BluetoothLink debe ser el que transporte los mensajes en la prueba Bluetooth virtual.
   Verificar emparejamiento, roles, autenticación, bidireccionalidad y reconexiones.
6. Deshabilitar Wi-Fi/datos dentro de los AVD para la prueba Bluetooth y registrar el estado.
   Además comprobar el manifiesto/APK offline: apagar interfaces no reemplaza ese control.
7. Probar la pérdida de conexión, replay, frames inválidos, proceso destruido y bloqueo. No
   incorporar interfaces administrativas no autenticadas en el APK para facilitar el test.
8. Guardar reports, versión de imagen, SHA del APK y logs redactados. No compartir snapshots
   que contengan identidades/claves, aunque provengan del laboratorio.

## Hardware remoto y límites
Android Device Streaming permite interactuar con dispositivos reales desde Android Studio.
Un dispositivo remoto puede aportar evidencia de compatibilidad, UI y ciertas pruebas de
Keystore; hay que comprobar sus capacidades, modelo y restricciones. No asumir proximidad
física, acceso a emparejamiento Bluetooth entre dos dispositivos del proveedor ni autenticación
biométrica automatizable. Si el proveedor no cubre el caso exacto, registrar el bloqueo.

Ninguna suite exime la revisión criptográfica y de seguridad independiente. No incluir
certificaciones, porcentajes de invulnerabilidad ni frases de «seguridad garantizada».
