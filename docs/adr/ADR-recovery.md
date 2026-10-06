# ADR: recuperación de identidad

Fecha: 2026-09-20. Estado: recuperación criptográfica BLOQUEADA para este bloque.

## Decisión

El modo funcional conserva identidad y sesiones en la bóveda protegida por Android
Keystore. Perder la clave de la bóveda, la identidad o material indispensable causa
un rechazo explícito; no se crea un reemplazo para intentar descifrar datos antiguos.
No existe clave maestra, recuperación administrativa ni copia de claves del usuario
en el relay. El servidor no puede reconstruir conversaciones.

No se ofrece un modo «recuperable» como si estuviera implementado. Reiniciar Engine
sobre los mismos registros intactos es continuidad de almacenamiento, no recuperación
tras pérdida de claves. Exportar un adjunto tampoco es un respaldo de identidad.
Una identidad nueva requiere vinculación y verificación nuevas.

## Motivo del bloqueo

No hay formato de respaldo autenticado y versionado, custodia aprobada, mecanismo de
protección de contraseña/semilla, política de revocación ni resolución de clonación de
sesiones especificados y auditados. Copiar estado del ratchet entre dispositivos puede
crear dos emisores sobre el mismo estado. Presentar una semilla o exportar secretos sin
resolver ese conflicto ampliaría el riesgo y contradiría el alcance de esta entrega.

## Requisitos previos a reabrir la decisión

Un ADR posterior deberá definir amenaza y custodia, cifrado mediante una construcción
estándar revisada, autenticidad del respaldo, compatibilidad/migración y rechazo de datos
manipulados; distinguir restauración de identidad de restauración de historial/sesiones;
y resolver duplicación, revocación del dispositivo anterior y confirmación del contacto.
Las pruebas deberán incluir fallo de disco, interrupciones, credenciales incorrectas,
material perdido y restauración repetida con datos sintéticos. La revisión humana y de
seguridad sigue siendo necesaria antes de activar o distribuir ese modo.

No hay una fecha ni una promesa de recuperación a partir del material actual.
