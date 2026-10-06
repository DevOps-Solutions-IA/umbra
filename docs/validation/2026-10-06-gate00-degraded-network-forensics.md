# GATE-00 — degraded-network, diagnóstico causal pendiente

## Referencias verificadas

Base main: `4fdd338f3fff4c6864ea820ca85cba6cf284b8da`.
PR #26, HEAD inspeccionado: `0d06f10943c23489394553364602e0243ba769e9`.
Checkout de CI: `b1792c9f4cf89c51abee504857bf4dd345bfa931`.
Ambos árboles: `7e79c2207c20530237de22cdd9892528f3f61101`.
Run `37509430480`, job `112433973634`, artefacto `11436043541`.
ZIP SHA-256: `4e87a6ae6556213fd19937060e91d601ae7f9b82877042ca7b68b0e7e1512929`.
Log de job SHA-256: `fae3f00d16a5d6d4a0f07f48465f615f328a719132d9669d5f1b9c8cc41d9e8e`.

## Qué falló y qué no está demostrado

La fixture A reportó `native-connection-disconnected`, 28.165 ms desde
configuración TURN, 1.724 buffers decodificados y 832 paquetes de audio.
El estado de video era ACTIVE, pero no existe recibo de imágenes remotas
aceptado: la espera de `video-active` falló antes de VIDEO_STOP.
La primera lectura de expiración seguía en -1, con presupuesto inicial de
180.000 ms. No hay evidencia de fallo por expiración.

Los dos probes UDP iniciales funcionaron. El escenario focalizado anterior
pasó con los mismos APK; ello no establece una causa resuelta ni convierte
el fallo posterior en éxito. El log B no contiene diagnóstico nativo útil.
No hay recibo final del caso fallido ni contadores de cola de ese instante.

El laboratorio configura netem sobre toda la salida wlan0: 128 kbit/s,
20 paquetes de cola, 80 ms y 2% de pérdida. El límite del sender de video
es 400 kbit/s. El caso anterior aprobado procesó 3.363/3.485 paquetes y
registró 239/284 descartes. La hipótesis de congestión/colas es concreta,
pero no está confirmada para el caso fallido. Los tres errores de transporte
HTTP registrados tampoco demuestran causalidad; no se reportaron respuestas
incompletas. No se atribuye el fallo a IPv6, PCAP, certificados o APP_SERVER_READY.

## Cambio de observabilidad exclusivamente de laboratorio

Se conservan contadores numéricos de `tc -s qdisc show dev wlan0` en
`impairment-failure.json`, antes de borrar netem o detener las fixtures.
La colección tiene cinco segundos como máximo por endpoint y se intenta en
ambos. No persiste stderr, direcciones, comandos, paquetes, SDP o secretos.
Contadores inválidos quedan UNAVAILABLE; nunca se interpretan como cero
paquetes ni como un recibo de aceptación. El error original sigue propagándose,
incluso si fallan la colección o el almacenamiento del diagnóstico.

Se mantiene el inventario, deadlines, netem, aserciones y la política nativa
DISCONNECTED/FAILED terminal. No hay cambios productivos de media.
La suite nueva fue observada RED (cuatro fallos por diagnóstico inexistente)
y luego GREEN (cuatro casos). El verde de tooling no demuestra transporte nativo.

## Revisión y autorización

Dos subagentes independientes revisaron forense y seguridad. La revisión está
asistida por IA, no es revisión humana especializada. El propietario autorizó
el modelo owner+AI para GATE-00 en el master de convergencia local. No se elimina
la regla general de revisión humana ni se habilita producción. El merge continúa
condicionado a CI exacta, cierre causal del fallo y main limpio.

## Límite actual

La sesión no tiene acceso a /dev/kvm. JDK 21 y SDK 36 existentes pasan el precheck,
que no equivale a compilación ni emulación. La nueva ejecución exacta en Actions
es diagnóstica; no se presenta como corrección del fallo. GATE-00 sigue abierto;
no comenzar Y01 ni infraestructura externa hasta CLOSED_GREEN.
