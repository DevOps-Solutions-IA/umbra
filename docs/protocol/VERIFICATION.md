# Verificación del contacto

Fecha: 2026-09-20. La posesión de una clave y la identidad humana son comprobaciones
separadas.

## Código de comparación existente

Dados dos identificadores de identidad de 64 caracteres hexadecimales minúsculos,
ordenarlos lexicográficamente como `a` y `b`. El código es:

```text
lowercase_hex(SHA256(UTF8("UMBRA-VERIFY-v1:" + a + ":" + b)))
```

Se utiliza el código completo. El mismo par produce el mismo código en ambos
extremos; un alias no participa. La función no sustituye el establecimiento de
sesiones ni la autenticación de mensajes de libsignal.

## Representaciones y validación v1

`Verification.numeric(a,b)` expresa los mismos 256 bits como 78 dígitos decimales,
con ceros iniciales. `Verification.qr(a,b)` produce exactamente
`umbra:verify:1:<a>:<b>:<hex>` con identidades ordenadas. `Engine.verify` acepta
hex completo, decimal completo o esa URI exacta. El QR ata ambos interlocutores;
no se aceptan campos extra ni otra versión. El texto admite espacios de agrupación.
La interfaz conserva comparación textual y muestra el nuevo QR; no se añadió
captura de cámara ni lector QR. La representación en palabras sigue pendiente.

## Transición de confianza

1. Importar y autenticar el material de contacto puede establecer una relación
   pendiente. El handshake firmado prueba posesión de las claves indicadas.
2. Comparar el código completo con la persona esperada mediante un canal externo
   confiable. Escanear un QR recibido por el mismo canal no aporta por sí solo esa
   independencia. La versión base muestra QR y texto; no debe darse por implementado
   un lector de QR por existir ese dibujo.
3. La acción explícita de verificación valida el código y fija la clave pública.
   La confirmación y persistencia deben terminar antes de presentar «verificado».
4. Un contacto bloqueado o no verificado no puede enviar o recibir conversaciones
   mediante Engine. Un claim del relay no cambia ese estado.

El dispositivo cercano, el emparejamiento Bluetooth y una firma correcta no
identifican por sí mismos a una persona. Un atacante que posea una invitación puede
intentar vincular su propia clave. Comparar fuera de banda debe detectar que no es
el interlocutor esperado; la invitación debe revocarse y reemplazarse si se expuso.

## Sustitución de identidad

`identityChanged(oldPeer)` suspende la relación anterior y cancela sus sesiones y
salidas pendientes, conservando mensajes históricos. El usuario debe elegir
explícitamente la identidad anterior y la nueva. La interfaz
debe mostrar el cambio y pedir aprobación. `confirmIdentityChange(oldPeer, newCard, newCode)` exige el código nuevo correcto
y confirma importación, fijación de clave y bloqueo del contacto anterior en una
transacción. La nueva clave permanece sin verificar hasta esa comparación y acción. No se infiere continuidad por alias, no se hereda
`verified`, no se conservan sesiones bajo la identidad nueva y no se reenvían sobres
antiguos cambiando su destinatario. Aprobar una sustitución no recupera una identidad
perdida ni autentica el cambio sin comparación externa.

## Evidencia y límites

Pruebas de libsignal con claves sintéticas pueden verificar firmas, rechazo de
mutaciones, persistencia y estados. No demuestran que un usuario compare realmente
las huellas ni que la otra persona sea quien dice ser. La revisión de UX y pruebas
con dispositivos siguen siendo controles independientes.
