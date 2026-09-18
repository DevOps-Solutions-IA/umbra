# Fuentes técnicas primarias

Consultadas durante la preparación de esta entrega el 18 de septiembre de 2026. Estas referencias justifican decisiones y APIs; no certifican el código propio ni que se haya ejecutado correctamente.

1. Signal, repositorio oficial libsignal: APIs, dependencias Java/Android, JDK, limitaciones del soporte externo y licencia.
   https://github.com/signalapp/libsignal
2. Signal, fuentes de la versión fijada para revisar contratos Java/Kotlin de SessionBuilder, SessionCipher, PreKeyBundle, IdentityKeyStore y KyberPreKeyStore.
   https://github.com/signalapp/libsignal/tree/v0.102.3/java
3. Signal, especificación PQXDH: intercambio de claves y necesidad de verificar identidad por un canal confiable.
   https://signal.org/docs/specifications/pqxdh/
4. Signal, Double Ratchet: especificación del algoritmo y consideraciones de seguridad.
   https://signal.org/docs/specifications/doubleratchet/
5. Android Developers, conexión de dispositivos Bluetooth: RFCOMM, sockets cliente/servidor y emparejamiento.
   https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
6. Android Developers, permisos de Bluetooth.
   https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
7. Android Developers, Android Keystore: claves no exportables, autorización de uso y dependencia del hardware para protección respaldada por hardware.
   https://developer.android.com/privacy-and-security/keystore
8. Android Developers, protección de actividades: FLAG_SECURE, overlays y límites.
   https://developer.android.com/security/fraud-prevention/activities
9. OWASP MASVS, referencia propuesta para la evaluación móvil pendiente.
   https://mas.owasp.org/MASVS/

## Separación de evidencia

El uso de una biblioteca descrita por estas fuentes **no equivale** a haber auditado la aplicación que la integra. El repositorio de Signal no ofrece soporte a UMBRA. Las pruebas realmente ejecutadas y las que siguen pendientes se registran aparte en `TEST_STATUS.md`.

## Referencias adicionales para 0.2

- Android Developers, transferencia Bluetooth y bloqueo de operaciones de socket: https://developer.android.com/develop/connectivity/bluetooth/transfer-data
- Android Developers, riesgos de tapjacking: https://developer.android.com/privacy-and-security/risks/tapjacking
- Android Keystore y sus niveles de protección: https://developer.android.com/privacy-and-security/keystore
- Signal Double Ratchet, tratamiento del estado tras errores de autenticación: https://signal.org/docs/specifications/doubleratchet/

Estas páginas se consultaron para esta revisión. No prueban compilación, compatibilidad final ni propiedades del código propio. No se presenta este listado como inventario de dependencias completo o análisis de vulnerabilidades.
