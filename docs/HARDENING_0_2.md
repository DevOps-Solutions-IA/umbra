# Endurecimiento de UMBRA 0.2

Este documento describe código incorporado, no una certificación. La evidencia ejecutada está en `TEST_STATUS.md`. La revisión conserva el diseño Android nativo y el protocolo de mensajes basado en libsignal; prioriza fallos de integración y límites operativos.

## 1. Bóveda y autorización

`AccessGate` utiliza reloj monótono, época de autenticación y permisos de acceso de corta duración. Bloquear invalida permisos anteriores incluso si el usuario se autentica otra vez. `Vault` verifica esos permisos durante lecturas y en la confirmación transaccional; no permite escrituras independientes fuera de transacción. Un resultado antiguo no se debe presentar como si perteneciera a una sesión nueva.

Las claves de protección AES y HMAC se crean en Android Keystore con autenticación requerida y se solicita StrongBox cuando está disponible. La política admite respaldo TEE si StrongBox no está disponible, pero rechaza software-only según el nivel declarado por Keystore. No se recrea silenciosamente una clave perdida para una bóveda existente.

Los registros se sellan con AES-256-GCM. El contexto autenticado liga categoría e índice. Los índices de claves lógicas se obtienen mediante HMAC-SHA-256 sobre campos con prefijos de longitud, en lugar del SHA-256 no secreto de la versión anterior. La base sigue revelando categorías, cantidades y tamaños aproximados; no es cifrado integral de SQLite. Un rollback completo de almacenamiento por un atacante privilegiado no está resuelto mediante un contador externo antirrollback.

La migración v1→v2 descifra y autentica cada fila anterior, verifica su identidad lógica y vuelve a cifrarla con el nuevo índice/contexto dentro de la transacción de actualización. No borra y recrea la bóveda ante un error. **Código de migración Android no ejecutado en un teléfono.** Las pruebas JCA verifican el formato criptográfico y las condiciones de rechazo, no Android Keystore.

El PIN/biometría del sistema autoriza el acceso, pero no constituye una prueba de ausencia de malware. El sistema o un proceso comprometido puede observar secretos mientras se usan. Borrar referencias y algunos arrays es una reducción de exposición, no una garantía de eliminación de todas las copias en la JVM o JNI.

## 2. Bluetooth: vinculación y reconexión

`BluetoothLink` distingue un acto explícito de vincular un contacto de la reconexión normal. Solo durante vinculación se envía la tarjeta con identidad, alias, preclaves y capacidad de escritura del buzón. Esa tarjeta debe tratarse como información privada de contacto, no como publicación pública.

La reconexión normal requiere un contacto conocido/verificado y prueba de posesión de su clave de identidad. Cada extremo firma una transcripción con un dominio de protocolo fijo, rol de conexión, identidades y dos nonces de 32 bytes. Cambiar el rol, destinatario o nonce invalida la prueba. No se ofrece una API general que firme bytes arbitrarios.

La firma usa la primitiva existente de libsignal; **la composición de este saludo es nueva y no está auditada**. No reemplaza el cifrado extremo a extremo de mensajes ni la comparación humana del código de seguridad. El emparejamiento del sistema y el consentimiento explícito de vinculación siguen siendo requisitos.

El código limita el saludo antes de reservar memoria, impone plazos de saludo/escritura/inactividad, limita el ritmo de tramas y las colas, y revoca conexiones antiguas mediante generaciones. Se resuelven las promesas de envío pendientes cuando se cierra el enlace. La interfaz de envío transporta copias de sobres cifrados inmutables.

No hay malla, anonimización de Bluetooth, relay entre terceros ni conectividad de larga distancia. Los identificadores de aplicación presentes en el saludo son vinculables para quien observe ese intercambio. Las pruebas de desafío en el núcleo verifican bytes y separación de contexto; las pruebas adicionales de firmas reales y la radio están pendientes.

## 3. Entradas y archivos

`StrictJson` rechaza claves duplicadas incluso si se escriben con escapes equivalentes, UTF-8 inválido, surrogates sin pareja, comentarios, valores no JSON y exceso de profundidad/nodos. `Wire` exige campos y tipos concretos, no conversiones permisivas de JSONObject. Verifica el vínculo entre sobre exterior y contenido autenticado.

`FileNames` elimina separadores, controles y caracteres de formato de nombres salientes, limita longitud sin cortar un par Unicode y rechaza nombres no canónicos al recibirlos. No se usa un nombre recibido para construir una ruta privada. No se abren adjuntos automáticamente: un archivo exportado aún puede ser malicioso para otra aplicación.

Los exportes pendientes se guardan como registros cifrados con vencimiento de diez minutos y cupo de cuatro. Al abrir selectores externos se bloquea UMBRA y se exige autenticación para continuar. El proveedor elegido recibe el archivo cuando el usuario confirma el exporte y queda fuera de nuestro control. Un proveedor no cooperativo puede bloquear una operación de archivo en el trabajador; esta revisión no garantiza cancelación inmediata de todos los proveedores Android. Una copia ya escrita no se retira mediante bloqueo.

## 4. Entrega, duplicados y cuotas

El orden de salida utiliza una secuencia persistente de creación, no el vencimiento. Los elementos antiguos sin secuencia conservan la compatibilidad de lectura, pero no se reconstruye con certeza su orden original; probar actualización con colas anteriores y limpiar solo datos de prueba durante validación.

Un envío al relay no equivale a entrega. Los mensajes del usuario conservan su mismo ciphertext hasta una confirmación cifrada, vencimiento o acción de limpieza. Los reintentos de error usan backoff exponencial acotado con jitter. Los duplicados auténticos pueden volver a generar la entrega de la misma confirmación guardada sin mostrar otra copia del mensaje.

El descifrado, estado de sesión, almacenamiento recibido, deduplicación y encolado de confirmaciones comparten una transacción. Un error de capacidad local no autoriza borrar el mensaje pendiente del relay. Se separan los cupos de mensajes del usuario y reserva de confirmaciones. Las cuotas limitan recursos, pero no garantizan disponibilidad frente a saturación, interferencia de radio o abuso de un contacto autorizado.

## 5. Relay y privacidad operativa

El middleware ASGI limita encabezados, ruta, cuerpo, concurrencia, plazo de recepción y frecuencia. Rechaza cabeceras ambiguas, cuerpos comprimidos no admitidos, longitudes contradictorias, números no enteros y JSON con duplicados. Responde sin contenido sensible y con instrucciones de no almacenamiento en caché. El contador temporal de frecuencia usa identificadores HMAC en memoria en vez de retener IPs literales en ese contador; esto no oculta la IP al sistema, proxy o alojamiento.

La cola SQLite usa una secuencia AUTOINCREMENT; borrar un mensaje no recicla el cursor de paginación. La migración desde la tabla anterior es transaccional y está cubierta por pruebas reales de SQLite. Se acota el total de buzones a 1.024 y los identificadores de mensajes retenidos a 8.192 por buzón. Los cupos de cola anteriores siguen vigentes.

El límite de frecuencia por peer directo es **agregado detrás del Caddy suministrado**, no por usuario final. No se confía en encabezados forwarded arbitrarios. Debe rediseñarse y probarse el dimensionamiento en el borde antes de una operación de muchos usuarios. El relay ve remitentes/destinatarios pseudónimos, buzones, tamaños y tiempos; no se anuncia «cero metadatos».

## 6. Lo que debe bloquear una publicación sensible

Faltan: compilación de ambos sabores y resolución de dependencias; 30 pruebas JUnit reales con libsignal; pruebas de Keystore/SQLite y migración; teléfonos físicos y radio; ciclo de vida, diálogos, teclados, accesibilidad y proveedores de archivos; comprobación del manifiesto/APK final; revisión de bibliotecas/JNI, hashes, imágenes y acciones; auditoría del saludo Bluetooth y del uso del ratchet; pentest móvil y operación del servidor; firma estable y canal de actualizaciones.

Los scripts y CI separan verificaciones ejecutadas de bloqueos. El archivo de integridad verifica consistencia de la entrega, **no es una firma de autor ni una auditoría**. Ninguna cantidad de pruebas locales equivale a declarar invulnerable el producto.
