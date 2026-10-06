# S1 — implementación local antes de disponer del servidor

Base: main `86ce3ec8e6ed70d104cd57761307ee3520fe2acb`, árbol `914605114b02608c601a63d05e5ba4c2838a01b7`. G1 está cerrado. El propietario autorizó continuar los frentes independientes de VPS y dominio; no desplegar ni instalar APK.

## Cambios acotados

1. Compose exige un RealmConfig público y deriva el origen HTTPS del dominio configurado. El gate de admisión sigue cerrado sin configuración; no desactivar autenticación para satisfacer health. Caddy se fija por el digest cuyo manifiesto se comprobó en S1.
2. Preflight local del JSON renderizado, sin DNS ni sockets, y respaldo SQLite consistente con destino nuevo. La copia no se publica ni sustituye una base existente; es sensible y necesita almacenamiento protegido y política de cifrado antes de operación real.
3. Aprovisionamiento Android aditivo desde el dominio de admisión: revisión del origen exacto y del RealmConfig existente, confirmación explícita de fuente autenticada fuera del relay, y persistencia atómica usando la misma bóveda. Sin nuevo protocolo firmado, autoridad ni criptografía. Importar no admite al dispositivo, no registra buzón y no conecta. No permitir sustitución silenciosa del origen o la autoridad.

La revisión pertenece a la sesión actual. Bloquear/desbloquear invalida una revisión antigua. Inicialización inconsistente, errores de almacenamiento y formatos corruptos fallan cerrados. La conectividad sigue siendo explícita y separada de Nearby. La presentación no puede interpretar la configuración como disponibilidad del servicio.

## Alcance pendiente

Claude debe conectar estas APIs y reemplazar su formulario mediante el flujo de aprovisionamiento controlado. Esta rama no modifica MainActivity, pantallas, textos, navegación o estilos. La invitación individual de registro del buzón conserva su protocolo; un RealmConfig público no debe contener ese secreto. No se introduce un servicio TURN productivo en esta fase.

OCI, DNS, TLS público, costos, capacidad y dos teléfonos contra el servidor requieren recursos y consentimiento posterior. No declarar esos resultados por pruebas JVM, ASGI o Compose renderizado.

## Integración y revisión

Los cambios sensibles nuevos no heredan la excepción de PR #24. Publicar un PR revisable permite ejecutar la CI real del nuevo SHA, pero no equivale a revisión humana especializada ni autoriza producción. Conservar fallos y distinguir resultados por SHA. Un merge solo procede si todos los controles y requisitos de revisión aplicables se cumplen.
