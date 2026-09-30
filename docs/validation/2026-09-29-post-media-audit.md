# Auditoría posterior: parsers restringidos y preparación física

Base: `e0024f091d29dc15c2d788b430c5ea11204e5060`, árbol de auditoría separado.
Fecha: 2026-09-29. Alcance: lectura de código/tests, regresiones JVM acotadas y
plan físico; sin cambios productivos, UI, ADB, instalación ni ejecución física.
No constituye auditoría independiente ni aceptación de codecs nativos.

## Inventario verificado en fuente

| Superficie | Controles y pruebas existentes | Límite de evidencia |
|---|---|---|
| PNG/JPEG importado | `ImagePreparation`: máximo 4 MiB de entrada, 2048 por dimensión, 4 Mi píxeles, solo JPEG/PNG no animado, rechazo parcial, salida PNG <=262144 bytes. `PrivacyAdaptersAndroidTest` comprueba metadatos/original, inválidos, tamaño y autorización caducada. | No corpus nativo fijo de todas las mutaciones JPEG/PNG ni cobertura de fallos OEM. JPEG es importación; wire restringido solo PNG. |
| PNG recibido | `RestrictedImages.Decoder` limita formato/dimensiones/píxeles y lifetime de sesión. `RestrictedContentAndroidTest` comprueba Signal, SQLite, consumo y cierre tras render. | No fuzzing general de BitmapFactory ni prueba física nueva. |
| AAC ADTS | `RestrictedAudio`: AAC-LC mono/16 kHz, configuración exacta, 156 frames, acceso <=8184 bytes, presupuesto temporal/PCM; recodificación y limpieza. Tests Android de nota sintética, inválidos/consentimiento y cierre de playback/focus. | Decodificación real requiere Android; tests JVM no ejercitan MediaCodec. No corpus nativo exhaustivo de ADTS. |
| AVC MP4 | `RestrictedVideo`: <=320x240, pares, <=45 frames, <3 s, límites de paquetes/timestamps y limpieza. `RestrictedVideoProfileTest` cubre perfil, capacidad/tiempo; `RestrictedVideoAndroidTest` prueba recodificación, inválidos y playback/cancelación. | No corpus sistemático de átomos MP4 ni variantes OEM; llamadas de video son otro producto de prueba. |
| PDF y páginas | Servicio aislado rasteriza PDF; `RestrictedDocumentAndroidTest` cubre PDF inválido, exceso de páginas, raster inválido y aislamiento. `DocumentPagesTest` ya tenía 2.000 entradas aleatorias seed `0x554d4252`, truncación de pack de una página, counts/lengths y bytes extra. | Pack UPG1 no es parser PDF nativo. Azar mayormente falla en magic y no demuestra cobertura profunda. |
| Wire restringido | `RestrictedPayload` valida campos exactos/version/formato/modo, identidad, TTL, sesión, key/nonce y ciphertext. `RestrictedContentTest` cubre manipulación/contexto/consumo. `SecuritySelfTest` contiene framing negativo fijo y controles StrictJson. | Framing, JSON y descriptor no sustituyen parsers de medios ni constituyen fuzzing nativo. |

## Regresiones nuevas

Se amplía el `DocumentPagesTest` existente (sin cambiar inventario Android):

- Pack de cuatro páginas: todos los prefijos truncados; cada offset de longitud
  (`8,20,37,62`) mutado a negativo, cero, 7, exceso y `Integer.MAX_VALUE`.
- Capacidad exacta 262144 bytes aceptada; +1 rechazado tanto en parse como pack;
  rechazo de pack conserva código `CAPACITY`.
- 1.024 mutaciones estructuradas reproducibles, seed `0x554d425241`, 1–4 cambios
  por entrada de 104 bytes. Rechazo debe ser `ContentException.INVALID`; si acepta,
  slices de 1–4 páginas deben ser contiguos, >=8 bytes y consumir exactamente el
  buffer sin rebasarlo. Payload interno arbitrario puede ser válido para framing.

Memoria por entrada limitada; no asignación basada en longitud mutada. Estos son
**dos métodos JVM**, no 1.024 pruebas de codecs ni Android físico. No se detectó ni
corrigió bug productivo en esta ampliación. Resultados de ejecución se registran
por el coordinador en el recibo de la sesión; fuente escrita no equivale a PASS.

## Pendientes explícitos

La ampliación nativa acotada de PNG/JPEG/AAC/AVC/PDF descrita abajo está
implementada y pendiente de ejecución CI por SHA. Una campaña más amplia sigue
necesitando corpus sintético versionado, seeds/input/hash, límites de memoria y
señal estable de crash/timeout; los casos nuevos incluyen controles de recuperación.
No se ejecutó ningún parser nativo nuevo en este subtrabajo. No ampliar el runner
físico fijo sin revisión.

La [matriz física futura](../POST_ACCEPTANCE_PHYSICAL_MATRIX.md) identifica APK,
permisos, acción humana, harness real o ausente, evidencia/limpieza/no-afirmaciones.
Exige dos seriales y dos equipos físicos; dos apps en un teléfono no satisfacen
el segundo par. No hay runner físico dual aprobado: el script de Bluetooth AVD
no puede reutilizarse desactivando su protección de emulador.

## Ampliación nativa preparada para CI posterior

Se amplían cuatro métodos Android existentes, sin aumentar el conteo ni cambiar
selectores. Corpus estructurado determinista generado por fixtures propias:

| Formato | Entradas nuevas y controles |
|---|---|
| PNG/JPEG | Cada formato: prefijos de 1, 2, 4 y 8 bytes de imagen 16x16; rechazo `IOException` de ImageDecoder. Imagen íntegra vuelve a sanitizar/decodificar a 16x16. PNG 2048x1 permitido y 2049x1 rechazado. |
| AAC | Encode real de un segundo de PCM sintético silencioso; prefijos ADTS de 1, 2, 6 y 7 bytes rechazados por IOException/ContentException; preparación completa posterior. PCM de 1023 y del límite máximo +1 rechazado antes de encode. |
| AVC | Clip sintético silencioso; prefijos 1/8/23; tamaños ftyp 0/15/INT_MAX/unsigned UINT_MAX rechazados; ftyp aislado llega al extractor sin tracks y se rechaza; clip íntegro vuelve a prepararse. |
| PDF | PDF propio truncado a 5/8/16 bytes rechazado; cuatro páginas permitidas, cinco rechazadas; control válido posterior existente. |

No bucle abierto ni corpus descargado: ocho prefijos de imagen, cuatro AAC,
siete rechazos MP4 de frontera más un header nativo y tres PDF. Los catch nuevos
aceptan únicamente IOException/ContentException declaradas; errores runtime
inesperados, AssertionError y crashes nativos siguen fallando. Se sustituye el
assert AAC previo de `Exception.class` por esos rechazos específicos. No se cambia
código productivo, inventario ni permisos. Los métodos de audio/PDF pertenecen al
inventario físico sintético histórico: **esta ampliación no se ejecuta en un
 teléfono y no hereda su aceptación anterior**.

`git diff --check` pasó tras la ampliación. Compilación/instrumentación quedan
pendientes de los comandos y recibos por SHA del coordinador. Estos casos son
fuzzing estructurado acotado de fronteras y recuperación, no fuzzing nativo
exhaustivo ni validación de toda mutación truncada (algunos formatos permiten
recuperación de prefijos largos). Se mantienen abiertos corpus de metadatos,
último paquete parcial, dimensiones extremas y variaciones de firmware/codec.

## Revisión adicional de expectativas y selección física

- VERIFIED en producto: `RestrictedVideo.decode` exige box ftyp >=16 y <=input;
  tamaño cero se rechaza deliberadamente aunque BMFF genérico contemple longitud
  hasta EOF. Prefijos <24 se rechazan antes del extractor; ftyp solo llega al
  extractor y debe fallar por IOException/ContentException, no por runtime genérico.
- VERIFIED: `Review.check` vence a 60 segundos; el argumento `sessionSeconds=30`
  pertenece a la apertura posterior, no al trabajo de preparación. Nuevos casos
  PDF/AVC crean review propio por operación y preceden el review del test original,
  evitando expiración acumulativa causada por varios deadlines nativos. Cuatro
  páginas de 64x48 son el borde permitido; el test positivo debe completar bajo
  el deadline real de 12 s y capacidad de 262144 bytes, sin aumentar límites.
  Su tiempo y tamaño real no se presumen verificados hasta instrumentación.
- VERIFIED: faltaba validador físico dual; existe solo selección física individual
  y runner Bluetooth de emuladores. Nuevo `validate_physical_pair_plan.py` valida
  JSON privado offline, exige dos seriales y IDs físicos distintos + confirmación
  humana y rechaza sustitución por paquetes. No ejecuta ADB ni acredita hardware.
- EXECUTED: `python -m unittest discover -s scripts/tests -p
  'test_physical_pair_plan.py' -v`: **4 PASS**, 2026-09-29. Son pruebas de herramienta
  con datos sintéticos, no cuatro pruebas de dispositivos. Cubren duplicación de
  teléfono/alias/paquetes, selecciones inválidas, respuesta redacted y errores CLI.

- EXECUTED tras revisión del validador: mismo comando de unittest, **5 PASS**.
  El caso adicional rechaza claves JSON duplicadas en raíz y peers (serial/ID físico)
  antes de validar schema; orden de peers documentado como A, B. No cambia ninguna
  afirmación de hardware ni autoriza ejecución. Resultados nativos siguen pendientes
  de CI, no se infieren de las pruebas Python.
