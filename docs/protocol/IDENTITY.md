# Identidad del cliente UMBRA

Fecha: 2026-09-20. Alcance: contrato de identidad de un dispositivo. No describe
recuperación ni compatibilidad multidispositivo.

| Elemento | Significado | Autoridad |
|---|---|---|
| Clave pública libsignal | Extremo criptográfico que firma y participa en sesiones | Posesión de la privada correspondiente |
| Identificador de identidad | SHA-256 de la clave pública serializada, 64 dígitos hexadecimales minúsculos | Debe recalcularse al importar |
| `deviceId = 1` | Dirección de dispositivo usada por SessionBuilder/SessionCipher | Valor local del contrato actual |
| Registro libsignal | Entero de registro del cliente, 1..16380 | No verifica una persona |
| Alias | Nombre de presentación, limitado y sin controles/formato oculto | Nunca autentica ni identifica continuidad |
| UUID del buzón | Destino del relay | Encaminamiento, independiente de identidad |
| Capacidades del buzón | Autorización de lectura o escritura | No prueban posesión de identidad |

La identidad privada se genera con libsignal y se serializa dentro de la bóveda;
la protección de los registros utiliza Keystore. No debe afirmarse que todas las
claves privadas de libsignal permanezcan dentro del hardware: el proceso necesita
acceder a ellas durante la sesión desbloqueada.

El almacenamiento conserva identidad, registro y perfil coherentes. Una identidad
inválida, perfil incompatible o pérdida parcial de registros bloquea el inicio y
la reinicialización. Crear otra identidad exige un flujo explícito; no es una
reparación automática de datos que no se puedan descifrar.

La confianza usa la clave pública fijada por contacto. `SignalStore` rechaza una
clave diferente bajo la misma dirección guardada. Una nueva identidad con el mismo
alias no reemplaza ese contacto ni hereda su verificación. El enlace explícito entre
contacto anterior y nuevo no permite migrar sesiones o ciphertext pendientes.

La tarjeta heredada `umbra-contact-v1` conserva su firma, preclaves y validación.
Las nuevas transcripciones de vinculación deben enlazar una tarjeta válida y la
misma identidad, no inventar un segundo identificador con privilegios equivalentes.
[PAIRING.md](PAIRING.md) describe ese contrato de aplicación; el intercambio E2EE
continúa usando libsignal. [ADR de identidad](../adr/ADR-identity-model.md).
