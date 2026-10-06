# UMBRA — Product design V1

Base: `11539d4af1762aee11e77a8119b7fb9532439343` (Pairing P0). Rama: `claude/product-convergence-premium`.
Contratos consumidos: `ACCESS_READINESS_V1`, `PAIRING_PRODUCT_V1`, `UI_SECURITY_CONTENT_API_V1`.
Principio: **simple por fuera, estricto por dentro.** Se quitan decisiones técnicas del usuario, nunca
comprobaciones del dominio. Un estado mostrado es observación; cada operación vuelve a validar.

## 1. Arquitectura de información

| Edición | Pestañas | Notas |
|---|---|---|
| connected | Chats · Llamadas · Ajustes | «Conexión cercana» es una pantalla (Ajustes → Conectividad, Agregar contacto) |
| offline | Chats · Cerca · Ajustes | Sin llamadas ni red; Cerca es su transporte |

- **Chats**: lista real de conversaciones 1:1. Acción principal única «Nueva conversación» (botón
  flotante extendido); icono «Agregar contacto». Banners solo cuando cambian lo que se puede hacer
  (llamada entrante, acceso privado pendiente, servidor no disponible). Búsqueda solo con listas largas.
- **Nueva conversación**: «Agregar contacto» + contactos verificados y los que requieren atención.
- **Agregar contacto** (hoja): Escanear QR · Ingresar código · Mostrar mi QR · Mostrar mi código;
  «Más opciones»: Archivo de vinculación · Conexión cercana. Nunca invite/request/ack, realm,
  capability ni prekeys.
- **Ajustes** (lista agrupada, sin tarjetas anidadas): Perfil e identidad · Privacidad y seguridad
  (Seguridad, Privacidad, Dispositivos) · Conectividad y datos · Configuración avanzada (Acceso privado,
  Acerca de) · Bloquear ahora.

## 2. Sistema de diseño

Tokens únicos en `ui/design/UmbraTokens` (espejo en `res/values/dimens.xml` e `integers.xml`):
espaciado 4/8/12/16/24/32/40/48, radios 8/14/18/24/pill, alturas (toque mínimo 48, botón/campo 52,
fila 64, barra 56), opacidades (deshabilitado 0.5) y movimiento (120/200/280 ms). Paleta oscura
oliva existente (`UmbraColors`, contraste AA comprobado por `UiDesignTokensTest`); sin neón.
Tipografía del sistema (`UmbraType`: Display, Title, Heading, Body, Body secondary, Label, Caption,
Security label, Monospace) en sp.

Componentes (`Ui`): botones primario/secundario/destructivo/fantasma, botón flotante extendido, botón
de icono (48 dp), campo, campo de contraseña, código técnico, chips de estado, banner, fila de lista,
**fila de ajuste**, estado vacío, estado de error, **esqueleto**, **cargador en línea**, **cargador de
página**, **progreso por fases**, hoja inferior y hoja de confirmación (`SecureDialogs`), barra superior,
navegación inferior, burbuja, tarjeta de ubicación.

## 3. Carga y ocupado

| Patrón | Uso | Regla |
|---|---|---|
| Esqueleto | listas al abrir (chats, dispositivos, admisión) | nunca para decisiones criptográficas |
| Botón ocupado (`Ui.busy`) | desbloquear, crear/cambiar contraseña, verificar código, autenticación Android | deshabilitado, fase real («Abriendo…»), sin doble toque, spinner |
| Progreso por fases (`Ui.stepProgress`) | vinculación | solo fases que el dominio reporta: Preparando… → Esperando al otro dispositivo… → Contacto agregado |

Sin porcentajes inventados; sin éxito antes de que el dominio lo reporte.

## 4. Movimiento

`Motion` respeta la escala de animación del sistema (si está desactivada, el estado final se aplica de
inmediato). Entrada suave de contenido (200 ms), confirmación breve (280 ms), spinner. Ninguna
operación de seguridad espera a una animación.

## 5. Errores

Inline (campo/estado), banner (estado que limita acciones), pantalla (resultado terminal). Copy:
qué pasó + qué hacer, sin detalles de implementación. Fallos tipados (`FailurePresentation`,
`PairingPresentation.failure`, `AccessPresentation.result`); nunca se leen mensajes de excepción.

## 6. Acceso (ACCESS_READINESS_V1)

- `vault.access()` es el coordinador canónico. La autenticación Android es una acción externa
  (`beginExternal(ANDROID_AUTHENTICATION)`); solo el callback real de éxito llama a
  `androidAuthenticationSucceeded`. Un ticket viejo no abre una época posterior.
- Contraseña: `createPassword/unlock/changePassword` con `OperationResult`. Fallo genérico
  indistinguible («No se pudo abrir. Revisa la contraseña.»); crear/cambiar terminan bloqueados;
  `COMMITTED_CLEANUP_FAILED` nunca se reintenta solo.
- Ciclo de vida: `background()` al pausar; `lock(causa)` con causas reales (USER_REQUEST,
  BACKGROUND, ANDROID_AUTH_EXPIRED…); selectores, ajustes y avisos de permiso son acciones externas;
  al volver solo se consume el contexto y se pide autenticación nueva.
- Bloqueo automático: «1 min / 2 min / 4 min máx.»; en Seguridad, «Bloqueo en m:ss» observado (no
  se renueva al tocar ni se persiste).
- Emergencia CLOSED: ticket preparado tras CLOSED; el Vault cerrado por el coordinador se sustituye,
  nunca se reutiliza.

## 7. Vinculación (PAIRING_PRODUCT_V1)

- Solo `PairingProduct` (ninguna llamada a `PairingService` desde UI).
- QR de vinculación solo de `PairingQrCodec` sobre la invitación firmada; distinto del QR de seguridad.
  Descripción accesible «Código QR para agregar este contacto»; el payload nunca se muestra ni se lee.
- Código de un uso en cuatro grupos, desde `char[]`, no seleccionable, borrado al salir/bloquear.
- Escáner (solo connected): camera2, una sola lectura aceptada, sin guardar fotogramas ni registros,
  cerrado al pausar/bloquear/éxito/cancelar. Offline: sin cámara (sin permiso CAMERA).
- Progresión online solo en primer plano con retroceso acotado; se detiene por pausa, bloqueo,
  cancelación, vencimiento, estado terminal o cambio de generación. Sin servicio ni WakeLock.
- Sin conexión privada lista: «Conexión privada no disponible» + Configurar; nada se finge.
- Resultado: «Contacto agregado · Verificación pendiente» → «Verificar ahora / Más tarde». Nunca
  «seguro» ni «verificado» desde la vinculación.
- Archivo: Más opciones → Archivo de vinculación (crear/seleccionar/guardar respuesta), mismo
  protocolo firmado mediante `importFile/createPairing`.

## 7b. Primer uso, acceso privado y medios

- **Primer uso** (después de crear la contraseña): 1) «UMBRA protegido», 2) «Tu identidad» (alias),
  3) «Listo»: Configurar conexión (connected) · Agregar contacto · Ir a chats. Crear la identidad no admite,
  no conecta y no verifica; los contactos empiezan sin verificar.
- **Acceso privado** (antes «Admisión»), lenguaje normal: Configuración pendiente · Acceso pendiente ·
  Solicitando acceso · Acceso no aprobado · Acceso activo · Solicitud vencida / Acceso vencido · Acceso
  revocado · Configuración incompatible. Huellas, entorno y autoridad quedan bajo «Detalles»; la
  Administración (autoridad) está en «Configuración avanzada».
- **Llamadas**: solo estados del dominio (Llamando…, Conectando…, En llamada, finalizada/rechazada/sin
  respuesta…, No se pudo conectar). Un toque prepara una sola revisión; sin «Reconectando» ni seguridad
  inventada. Si falta la lista de dispositivos del contacto: «Llamada no disponible: falta su lista de
  dispositivos» (brecha de producto, no se fuerza).
- **Ubicación**: hoja Para · Precisión (Aproximada/Precisa) · Duración · Revisar; permisos solo tras la
  confirmación; el estado inicial es «Midiendo…» hasta la primera medición real (no «En vivo»).
- **Visor protegido**: fase real con spinner («Abriendo…»), cierre por bloqueo, segundo plano,
  emergencia, caducidad y pérdida de autorización (ya existente); sin miniaturas, URI ni exportación.

## 8. Verificación

Código de seguridad / QR de seguridad / Comparar. Solo `engine.verify` marca VERIFIED. Confirmación
breve y regreso. El QR de seguridad no se escanea (no implementado): se compara.

## 9. Permisos

Nunca al iniciar. Cámara solo al pulsar «Permitir cámara» en el escáner; ubicación al compartir;
micrófono al grabar/llamar; Bluetooth al usar Conexión cercana. El aviso del sistema se declara como
acción externa solo cuando toma la ventana; volver nunca abre la bóveda ni enciende sensores.

## 10. Diferencias offline

Sin INTERNET, ACCESS_NETWORK_STATE, CAMERA ni RECORD_AUDIO. Sin llamadas, escáner, QR/código online
ni captura protegida. Vinculación por archivo y Conexión cercana disponibles.

## 11. Accesibilidad

48 dp mínimos; etiquetas solo donde aportan; estados en palabras además de color; regiones vivas
para fases; escalado de fuente sin cortes; datos técnicos (huellas, códigos, versión) marcados como
valores, sin truncar, con lectura agrupada; el código humano se lee por grupos.

## 12. Brechas de producto conocidas (no simuladas)

| Brecha | Estado en UI |
|---|---|
| Grupos E2E | Oculto (sin filtro, sin vista previa) |
| Aprobación/vinculación de dispositivos, revocación distribuida | Lista de solo lectura; sin botones muertos |
| Notificaciones con contenido configurable | Estado fijo «Sin contenido» |
| Escaneo del QR de seguridad | No ofrecido; comparar código |
| Estados entregado/leído | No mostrados si el dominio no los acredita |
| Credenciales TURN automáticas | Llamadas exigen aprovisionamiento existente |
| Relay de producción | No configurado aquí; la UI termina en «Conexión privada no disponible» |
| Respuesta, no leídos, silenciar | No mostrados |
| Lista de dispositivos del contacto para llamadas/ubicación | Mensaje explícito, sin forzar |

## 13. Auditoría de acciones (segunda pasada)

FUNCTIONAL: todas las acciones visibles de chats, conversación, contacto, verificación, agregar contacto
(QR/código/archivo/cercana), ajustes, acceso privado, administración, red, cercana, llamadas, ubicación,
contenido protegido, emergencia, contraseña. CONDITIONALLY_AVAILABLE (deshabilitado con razón dicha):
red sin acceso privado, cercana sin activar, páginas del visor en los extremos, botones durante una
operación. HIDDEN_PRODUCT_GAP: grupos (pantallas preparadas inalcanzables), agregar/revocar dispositivos,
eliminar contacto, notificaciones, escaneo del QR de seguridad. DEBUG_ONLY: ninguno en la UI ordinaria.
Sin callbacks vacíos alcanzables ni avisos «Próximamente» en recorridos visibles.
