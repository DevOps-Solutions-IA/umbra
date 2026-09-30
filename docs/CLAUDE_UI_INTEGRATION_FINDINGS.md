# Hallazgos de la integración final de UI (contrato `UI_SECURITY_CONTENT_API_V1`)

Base técnica: `e0024f091d29dc15c2d788b430c5ea11204e5060`. Ningún hallazgo se resolvió con un rodeo
inseguro ni con cambios en dominio, criptografía, protocolo o almacenamiento. La UI se adapta al
comportamiento real y lo documenta aquí para coordinación con Codex.

## F-1 · `AdmissionService.pendingRequest()` — CORE_BUG_CANDIDATE (documentación del contrato)

- Contrato (`UI_API_CONTRACT.md`, tabla de firmas): «Current local request, or null».
- Implementación en `e0024f0`: sin solicitud pendiente lanza `AdmissionCodec.invalid()`
  (`AdmissionException`, código `INVALID`).
- Entrada mínima: dispositivo con entorno instalado y sin solicitud (`NOT_ADMITTED`), llamar a
  `pendingRequest()`. Esperado según contrato: `null`. Real: excepción.
- Efecto en UI: ninguno. `AdmissionFlow.read` consulta `pendingRequest()` solo cuando
  `status().requestExpiresAt()` no es nulo; no usa la excepción como estado.
- Acción sugerida: corregir el texto del contrato o la firma; no requiere cambio de UI.
- **Resuelto en el núcleo** por Codex `4c92a1d` (integrado en la convergencia con `91d7aeb`): devuelve `null`
  solo si no existe solicitud; corrupción y bloqueo siguen fallando cerrado. Los usos internos exigen la
  solicitud con `requirePendingRequest()`. Sin cambio de UI.

## F-2 · Decodificadores sin dimensiones intrínsecas — CONTRACT_GAP

- `RestrictedImages.Decoder.render(Canvas, Rect)` y `RestrictedDocuments.Decoder.render(int, Canvas, Rect)`
  dibujan escalando al rectángulo destino; no hay API pública de ancho/alto (correcto: no se exponen bytes
  ni `Bitmap`).
- Efecto: la UI no puede conservar la proporción; el marco protegido usa el área del visor
  (fotos y páginas pueden verse estiradas). No se intentó deducir tamaños por otros medios.
- Propuesta: exponer solo `width()/height()` de la sesión decodificada (metadato no sensible).

## F-3 · `RestrictedContentService.Status` sin duración de sesión — CONTRACT_GAP

- `Status(id, format, mode, expires, consumed, expired)` no incluye `sessionSeconds`.
- Efecto: la UI distingue caducidad del objeto (valor real) y límite de sesión, pero muestra este último
  como «Sesión limitada» sin cifra. El dominio sigue siendo la única fuente de verdad; la UI no cuenta.

## F-4 · `PrivateClipboard.reviewMessage` exige hilo principal y lee almacenamiento — CONTRACT_GAP

- El adaptador exige `Looper` principal y foco de ventana; `OrdinaryTextExport.review` consulta la bóveda
  en ese mismo hilo. Contradice la regla general «storage en worker» del contrato.
- Efecto: la UI respeta el requisito del adaptador (una lectura acotada de un mensaje) y ejecuta la copia
  al recuperar el foco tras la confirmación, porque con el diálogo abierto la Activity no tiene foco.

## F-5 · Sin estado de objetos restringidos enviados — CONTRACT_GAP

- No hay API para listar u observar objetos restringidos salientes; `send` devuelve el id y significa
  «en cola cifrada». La UI muestra solo ese hecho y no inventa entregado/abierto.

## F-6 · `AdmissionService.status()` lanza con credencial almacenada corrupta — CORE_BUG_CONFIRMED

- Evidencia real: CI de PR #19 (HEAD `dcc8152`), Verify UMBRA run `36653888664`, prueba JVM
  `UiAdmissionFlowTest.invalidStoredAdmissionIsReportedNotRepaired`.
- Entrada: credencial de admisión almacenada inválida/corrupta. Esperado: `status()` informa un estado
  tipado (credencial inválida) sin reparar ni borrar nada. Real: `status()` lanza.
- Propietario: Codex, PR #18. **Sin rodeo en UI:** la UI no captura la excepción para presentarla como
  `NOT_ADMITTED`, no borra ni repara la credencial y la prueba no se modifica ni se elimina. Queda en
  rojo hasta integrar un HEAD de #18 completamente verde.
- **Resuelto en el núcleo** por Codex `4c92a1d` (integrado en la convergencia con `91d7aeb`): `status()`
  informa `INVALID` ante registros corruptos, no publica fechas no confiables y no repara ni borra nada.
  La UI y la prueba no cambiaron; la prueba debe pasar en la CI del SHA combinado.

## CI real de PR #19 (`dcc8152`) — fallos de la UI corregidos

Runs: Verify UMBRA `36653888664`, Claude UI integration `36653888747`.

- **Concisión frente a datos técnicos (debug).** `assertConcise` medía huellas y códigos de seguridad como
  texto humano. Corrección: `Ui.code()` y el detalle técnico de `errorState` se marcan `Ui.TECHNICAL`; la
  política los valida aparte (solo grupos hex/identificadores, sin elipsis, con `contentDescription`,
  cada línea ≤ 64 caracteres con salto de línea). El texto humano mantiene los mismos límites (botón 24,
  línea 64, español). No se subió ningún límite global ni se trunca ningún código.
- **Icono de notificación en R8.** `ic_notification_umbra` no tiene referencia de producción (reservado para
  `PrivateAndroidSurface.notification`), así que `shrinkResources` lo quitaba. Corrección: raíz exacta
  `res/raw/umbra_resource_keep.xml` (`tools:keep` solo de ese drawable). R8 y `shrinkResources` siguen
  activos; `run_ui_integration.py` exige el recurso en el APK optimizado (`aapt2 dump resources`).
- **ZXing en R8 (`NoClassDefFoundError MultiFormatWriter`).** Causa: la prueba llamaba a ZXing directamente
  y el trazado del fixture solo conserva clases de la app; R8 renombraba/eliminaba la librería. Corrección:
  ruta de producción única `ui.design.QrCodes.render` (la usan `MainActivity` y la prueba); sin keep de
  ZXing. La prueba R8 verifica el QR real (patrones de localización en tres esquinas, zona silenciosa,
  solo blanco/negro) y la pantalla con el `ImageView` accesible; el runner exige `QrCodes`
  renombrado y el paquete codificador `com.google.zxing.qrcode.*` en el mapeo (las fachadas sin estado
  `MultiFormatWriter`/`QRCodeWriter` pueden quedar inlineadas legítimamente por R8), y falla con
  `-dontoptimize/-dontobfuscate`.
- Conteos sin cambios: 35 debug / 32 R8 por sabor.
- El registro de CI no es legible desde el entorno de Claude (API de GitHub no habilitada): el cuarto
  fallo de debug se confirmará en la siguiente ejecución.

## CI real del SHA combinado `2313e54` — correcciones

- Verify connected 102/102 y UI debug connected 35/35 (87 capturas): PASS.
- **Offline (Verify 99 ejecutadas y UI debug offline):** único fallo
  `UiScreensRenderTest.settingsSectionsAreHonestAboutPendingControls`; `assertConcise` leía la etiqueta
  «UMBRA 0.2.0-dev-offline» como copy en inglés por el sufijo de sabor. Corrección de presentación: el
  encabezado humano es «UMBRA» y la versión real (`BuildConfig.VERSION_NAME`, sufijo incluido) se muestra
  entera con `Ui.identifier` (etiqueta `Ui.TECHNICAL`, lectura «Versión …»). No cambia la versión, el sufijo
  ni el conteo; la prueba ahora exige la versión completa, etiquetada y con lectura en español.
- **R8:** `Required library class removed by R8: com.google.zxing.qrcode.QRCodeWriter` — R8 conservó
  `MultiFormatWriter` e inlineó `QRCodeWriter` (fachada sin estado). El runner exige ahora el paquete
  codificador `com.google.zxing.qrcode.*` (sin keep de ZXing; R8 y `shrinkResources` activos); la prueba
  de instrumentación R8 sigue demostrando el QR real por `QrCodes.render` y su pantalla.
- Admission offline y Emergency Nearby: fallos de infraestructura (`ZipFile unknown archive` al descargar
  imagen de sistema/emulador); sin cambios de producto.

## Límites del entorno de esta integración

- Sin KVM ni acceso a Maven/Google en el entorno de Claude: no se ejecutó Gradle, lint, R8, APK ni AVD
  localmente. La verificación local fue compilación de tipos contra android-36 (sin errores nuevos
  respecto a `e0024f0`; los 34 errores restantes son deriva de versiones de libsignal/BouncyCastle del
  verificador local y existen igual en la base) y pruebas JVM de presentación.
- Las pruebas JVM de dominio (`UiAdmissionFlowTest`) y la instrumentación se ejecutan en CI al publicar
  la rama (`verify.yml`, `ui-integration.yml` y los flujos técnicos existentes).
- Pruebas físicas: MANUAL_PENDING. Casos de dos teléfonos: NEEDS_SECOND_PEER.
