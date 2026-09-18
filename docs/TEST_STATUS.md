# Validación de UMBRA 0.2.0-dev

**Fecha de entrega: 18 de septiembre de 2026. Estado: código fuente modificado, con validación local parcial; no publicado ni apto todavía para secretos reales.**

Los resultados siguientes fueron ejecutados en esta sesión sobre el código de esta entrega. No son una copia de las cifras de 0.1. Los informes históricos se movieron a `legacy/0.1/`.

## Pruebas ejecutadas

Orden principal desde la raíz del proyecto:

```bash
bash scripts/test_local.sh
```

| Suite | Resultado real | Qué comprueba / qué no |
|---|---:|---|
| pytest del relay | 80 aprobadas | Rutas ASGI/HTTP, autorización/capacidades, cuotas, validación, migración SQLite, cursor, deduplicación, plazos y concurrencia. No comprueba despliegue público ni TLS real. |
| CoreSelfTest, JDK 21 | 20 escenarios aprobados | Utilidades originales, encuadre, codificación y padding. No ejecuta Android. |
| SecuritySelfTest, JDK 21 | 85 escenarios aprobados | Revocación y caducidad de autorización, JSON/UTF-8/Base64, límites, nombres Unicode, reintentos, transcripciones Bluetooth y JCA AES-GCM/HMAC. Claves de software solo para pruebas. |
| Política fuente | 12 comprobaciones aprobadas | Declaraciones de permisos, backups, confianza TLS y variante offline. No comprueba permisos de un binario. |
| Analizador sintáctico Java | 20 archivos analizados | Sintaxis de las fuentes y pruebas. No compila contra SDK ni comprueba los contratos de libsignal/Android. |

**Total de pruebas de comportamiento ejecutadas: 185 (80 + 20 + 85).** Las 12 comprobaciones estáticas de configuración se informan por separado. No se suman a ese total los 30 métodos de integración no ejecutados, la lectura sintáctica ni los casos internos de fuzzing.

`SecuritySelfTest` incluye una muestra de 500 IV aleatorios y 2.000 prefijos de trama malformados; cada bucle cuenta como un escenario, no como miles de pruebas independientes. La muestra de IV no demuestra unicidad universal. El descifrado JCA rechaza modificaciones de ciphertext, nonce, contexto, índice o clave; eso no valida automáticamente la bóveda Android completa.

Salida íntegra: `validation/local-tests.txt`. Entorno observado: `validation/environment.txt`.

## Bloqueos comprobados

```bash
python scripts/build_android.py --check-only
python scripts/check_merged_permissions.py
```

El preflight informó **Android SDK: NOT FOUND** y terminó sin compilar. El segundo script informó ausencia de manifiestos combinados reales: no certificó la ausencia de permiso INTERNET en un APK inexistente. Salidas: `validation/android-preflight.txt` y `validation/merged-permissions.txt`.

No hay APK debug, APK release, certificado de publicación, ejecución CI ni servicio desplegado que esta entrega pueda presentar como resultado.

## Integración incluida, sin ejecutar

`android/app/src/test/java/app/umbra/SignalIntegrationTest.java` contiene **30 métodos JUnit** que deben utilizar libsignal real, no una implementación simulada. Se añadieron 12 para orden de cola con TTL distinto, pruebas de identidad Bluetooth y rechazo de replay/rol/contactos no verificados, rechazo de coerciones/campos, rollback por capacidad local, reintentos inmutables y exportes cifrados con vencimiento.

Incluso después de que esos métodos pasen, seguirán pendientes pruebas físicas de Bluetooth, Keystore, migración Android, proveedores de archivos, autenticación/pausa/reanudación, memoria, backups, batería, interfaz, accesibilidad, R8/JNI y recepción tras fallos.

## Verificaciones de empaquetado

Se incluyen un manifiesto SHA-256 de los archivos y un parche respecto al ZIP base. El manifiesto comprueba consistencia, no autenticidad de autor ni auditoría. Las comprobaciones adicionales de sintaxis Python/XML, enlaces locales y parche están registradas en `validation/package-checks.txt` cuando corresponde a la entrega final.

## Criterio de uso

El código fuente avanza respecto a 0.1, pero los resultados locales **no sostienen una promesa de secreto absoluto, anonimato ni superioridad frente a mensajeros auditados**. Usar solo identidades y mensajes de prueba hasta completar los criterios de `RELEASE_CHECKLIST.md`.
