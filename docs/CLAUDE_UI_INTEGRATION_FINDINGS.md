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

## Límites del entorno de esta integración

- Sin KVM ni acceso a Maven/Google en el entorno de Claude: no se ejecutó Gradle, lint, R8, APK ni AVD
  localmente. La verificación local fue compilación de tipos contra android-36 (sin errores nuevos
  respecto a `e0024f0`; los 34 errores restantes son deriva de versiones de libsignal/BouncyCastle del
  verificador local y existen igual en la base) y pruebas JVM de presentación.
- Las pruebas JVM de dominio (`UiAdmissionFlowTest`) y la instrumentación se ejecutan en CI al publicar
  la rama (`verify.yml`, `ui-integration.yml` y los flujos técnicos existentes).
- Pruebas físicas: MANUAL_PENDING. Casos de dos teléfonos: NEEDS_SECOND_PEER.
