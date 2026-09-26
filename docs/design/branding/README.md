# UMBRA — símbolo, app icon e iconografía

Estado 2026-09-26: recursos implementados y verificados estáticamente; las capturas Android reales
salen de la CI (`UiScreensRenderTest`, archivos `21-*`, `22-*`, `23-*`). Los PNG de esta carpeta son
maquetas renderizadas en un navegador desde los mismos XML, **no capturas de Android**.

## Tres propuestas (se conservan para revisión)

| Variante | Idea | Archivo |
|---|---|---|
| **A — Monograma angular (seleccionada)** | U angular con base en punta y muesca central en V (trazo medio de la M); sugiere U/M/A sin escribir letras | `umbra-symbol-a.svg` |
| B — Umbra · eclipse | Media corona (cuenco de sombra) que contiene un disco | `umbra-symbol-b.svg` |
| C — Minimal táctico | Dos columnas y una cuña central, estilo plantilla | `umbra-symbol-c.svg` |

Comparación a 16/20/24/32/48 px, en blanco/negro, oliva y con cinco máscaras: `umbra-symbol-proposals.png`.

### Por qué A

- **24dp:** es la silueta con más área rellena y la única cuya forma exterior se reconoce a 16px.
  B pierde el hueco entre disco y corona por debajo de 20px y puede leerse como un ojo o una cara;
  C se lee como letras («M»/«II») y sus columnas se funden a 16px.
- **Máscaras:** A queda compacta (su punto más lejano está a ~30dp del centro, dentro del radio de
  33dp de la zona segura) y no se corta en círculo, squircle, cuadrado redondeado, gota ni cuadrado.
- **Monocromo:** es una sola forma rellena sin detalles finos, válida para icono temático y notificación.
- **Riesgo a revisar (decisión humana):** la base en punta puede recordar a un emblema/chevron.
  No es un escudo cerrado ni usa candados, armas o insignias, pero conviene validarlo con usuarios.

## Implementación Android (única fuente de verdad)

- Geometría: `res/values/brand.xml` → `@string/umbra_symbol_path` (rejilla 24×24, rellena, sin trazos,
  degradados ni transparencias). Colores `@color/umbra_symbol` (#A0AD93) y `@color/umbra_launcher_background` (#0E120F).
- `drawable/umbra_symbol.xml`: logo interno (24dp, se tiñe desde el sistema de diseño con `Ui.logo()`).
- `drawable/ic_launcher_foreground.xml`: capa frontal adaptativa de 108dp (escala ×2.4, centrado).
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
