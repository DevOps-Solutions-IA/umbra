# UMBRA — símbolo, app icon e iconografía

Símbolo oficial: **C3 «Cheurón»** (Variante C refinada).

Estado 2026-09-26: recursos implementados y verificados estáticamente; las capturas Android reales
salen de la CI (`UiScreensRenderTest`, archivos `21-*`, `22-*`, `23-*`). Los PNG de esta carpeta son
maquetas renderizadas en un navegador desde los mismos XML, **no capturas de Android**.

## Decisión vigente (2026-09-26): Variante C refinada → **C3 «Cheurón»**

El propietario eligió la Variante C (minimal táctico) como identidad. Se hicieron tres ajustes
geométricos del mismo concepto —dos columnas y una cuña central, simétricos— para corregir que
la C original se fundía a 16 px y se leía como «M» o «II»:

| Refinamiento | Ajuste | Resultado | Archivo |
|---|---|---|---|
| C1 — Cuña suspendida | Columnas de 3.5, cuña ancha separada del borde superior, huecos 2.3 | Sin vértice de «M», pero a 16 px la cuña queda en un punto y se lee «H»/«I·I» | `umbra-symbol-c1.svg` |
| C2 — Techos biselados | Bisel exterior de 45° en las columnas, cuña larga anclada arriba | La silueta sigue siendo una «M» redondeada a 16–24 px | `umbra-symbol-c2.svg` |
| **C3 — Cheurón (seleccionado)** | Columnas de 4.2 con base biselada a 45° hacia el centro, hoja central de 4.0 con punta a 45°, huecos de 2.6 | La base de las tres piezas forma un cheurón; ni «M» (la cuña no es un vértice corto), ni «II»/«H» (la hoja es tan ancha como las columnas) | `umbra-symbol-c3.svg` |

Comparación (16/20/24/32/48/72 px, render real de 16 px ampliado ×6, 512 px, negro, claro,
#879676, círculo, squircle, cuadrado redondeado): `umbra-symbol-c-refinements.png`.

### Por qué C3

- **16dp:** los dos huecos miden 2.6 unidades (1.7 px a 16 px) y la hoja 4.0 (2.7 px): en el render
  de 16 px sin suavizado siguen abiertos; en C1 la cuña cae a ~2 px y desaparece, y C2 se funde.
- **No es una letra:** las tres piezas tienen el mismo peso óptico y terminan en un cheurón común;
  no hay vértice superior (M), ni barras finas con hueco dominante (II), ni travesaño (H).
- **Máscaras:** a escala ×2.6 su punto más lejano queda a 30.2dp del centro (zona segura: 33dp);
  no se corta en círculo, squircle, cuadrado redondeado, gota ni cuadrado.
- **Monocromo:** tres formas rellenas sin detalles finos; válido para icono temático y notificación.
- **A revisar por una persona:** el cheurón puede evocar una insignia; no incluye armas, escudo cerrado
  ni rangos, pero conviene validarlo con usuarios.

### Propuestas anteriores (solo documentación, no activas en recursos)

- A — Monograma angular: `umbra-symbol-a.svg` (descartada; fue la primera implementación).
- B — Umbra · eclipse: `umbra-symbol-b.svg` (descartada).
- C — Minimal táctico original: `umbra-symbol-c.svg` (sustituido por C3).
- Hoja de las tres propuestas iniciales: `umbra-symbol-proposals.png`.

## Implementación Android (única fuente de verdad)

- Geometría: `res/values/brand.xml` → `@string/umbra_symbol_path` (rejilla 24×24, rellena, sin trazos,
  degradados ni transparencias). Colores `@color/umbra_symbol` (#A0AD93) y `@color/ic_launcher_background` (#0E120F); alternativa secundaria del símbolo: token `ACCENT_SECONDARY` (#879676), usada en los logos pequeños de invitación y QR.
- `drawable/umbra_symbol.xml`: logo interno (24dp, se tiñe desde el sistema de diseño con `Ui.logo()`).
- `drawable/ic_launcher_foreground.xml`: capa frontal adaptativa de 108dp (escala ×2.6, centrado).
- `mipmap-anydpi-v26/ic_launcher.xml` y `ic_launcher_round.xml`: fondo, frente y **monochrome** separados.
- `drawable/ic_notification_umbra.xml`: silueta blanca 24dp. Preparado: esta versión no publica notificaciones.
- Splash Android 12+: `windowSplashScreenBackground` #0E120F + `windowSplashScreenAnimatedIcon` = capa frontal.
  Sin texto ni cargador (la marca «UMBRA» bajo el símbolo quedó fuera: requeriría una imagen de marca aparte).
- Legacy: `minSdk` es 31, así que todo dispositivo soportado usa iconos adaptativos; no se generan PNG
  mdpi–xxxhdpi porque Android nunca los cargaría. `umbra-play-store-512.png` es solo para la ficha de tienda.

## Iconografía interna

68 VectorDrawable `ic_*` en una sola rejilla: 24dp, viewport 24, trazo 1.8 (puntos de «más» 3),
extremos y uniones redondeados, sin relleno, contenido entre 1.5 y 22.5. Los archivos son blancos y el
color llega por tinte (`StateColors`: normal #C4CBBF, secundario #8E968B, seleccionado #A0AD93,
deshabilitado #697166, aviso, peligro, verificado). `UiIconResourcesTest` comprueba la rejilla.

Nuevos glifos: desbloquear, bloqueo de emergencia (candado dentro de octógono «stop»), bóveda
cerrada/abierta, contraseña, cambiar contraseña, verificado (círculo + check), identidad cambiada
(huella + aviso), contacto bloqueado (persona + círculo tachado), sin internet (globo tachado),
offline con Bluetooth, cámara, contactos, ubicación precisa/aproximada/zona/en vivo/detener,
dispositivo actual/autorizado/pendiente/revocado/sin conexión y onda de voz modulada.

Preparados sin comportamiento: bóveda, contraseña, cambiar contraseña, bloqueo de emergencia,
dispositivo pendiente y sin conexión (el roster actual no informa esos estados).
