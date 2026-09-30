# Matriz física posterior a aceptación — 2026-09-29

Estado: **NOT_EXECUTED / sin teléfono disponible en esta sesión**. Este documento
prepara una sesión futura; no autoriza ADB, instalación ni cambios en teléfonos.
Base inspeccionada: `e0024f091d29dc15c2d788b430c5ea11204e5060`.
Los recibos físicos históricos no validan estos cambios posteriores.

## Puertas comunes obligatorias

- Consentimiento nuevo para cada instalación: presentar paquete, variante, SHA-256
  del APK de app y tests, firmante y propósito. Respetar
  [PHYSICAL_DEVICE_TESTING](PHYSICAL_DEVICE_TESTING.md). No inventar aprobación.
- Evidencia por caso: HEAD/árbol, hashes app/tests y runner, mapping/configuración
  R8 cuando corresponda, API/ABI/modelo/parche, fecha, pasos, resultado esperado y
  observado, número exacto de casos y fallo íntegro redactado. Seriales y direcciones
  Bluetooth quedan en recibo privado, nunca en Git. No logs globales ni secretos.
- Solo datos sintéticos y paquetes aislados. Offline debug:
  `app.umbra.privatechat.offline.dev`; offline R8:
  `app.umbra.privatechat.offline.vaultlab`. Connected equivalentes:
  `app.umbra.privatechat.dev` y `app.umbra.privatechat.vaultlab`.
  Verificar target del APK de instrumentación, no inferirlo del nombre del archivo.
  APK offline final debe carecer de INTERNET y ACCESS_NETWORK_STATE.
- Preflight futuro únicamente mediante runner seguro: USB explícito, estado `device`,
  no emulador ni serial TCP, API >=31, batería >=20%, temperatura <40 °C;
  rechazar readiness desconocido. No desactivar políticas de seguridad.
- Limpieza: cerrar sesiones y superficies, detener solo paquete poseído por runner,
  borrar solo fixtures/alias creados por el caso cuando el harness lo contemple.
  Conservar instalaciones y datos existentes; no `pm clear`, uninstall, reboot ni
  restauración de ajustes por suposición. Reportar limpieza incompleta como fallo.

## Puerta adicional de dos pares físicos

Antes de cualquier RFCOMM/llamada/duplicación entre terminales, exigir **dos
seriales USB distintos y dos dispositivos físicos independientes**. El propietario
confirma que corresponden a dos teléfonos presentes; recoger perfiles por separado,
rechazar `ro.kernel.qemu=1`, selecciones ausentes/no autorizadas/ambiguas, aliases de
un mismo dispositivo y selecciones por red. Dos paquetes, perfiles de Android o
usuarios en el mismo teléfono **no satisfacen** este requisito. Dos seriales solos
no prueban dos radios: confirmar ambos equipos físicamente y conservar asociación
privada A/B. Obtener permiso y locks para ambos; una selección inválida bloquea el
caso completo antes de cualquier mutación. Preferir fabricantes distintos.

No hay runner físico revisado de dos pares en esta base. El script
`run_bluetooth_emulation.py` exige emuladores y cambia radios/permisos: **no usarlo
ni quitarle sus protecciones para ejecutar en teléfonos**. Los listeners existentes
son candidatos de reutilización, no un harness físico aprobado. Implementar/revisar
ese runner en trabajo futuro antes de automatizar estos casos.

| Caso / estado actual | Precondición, APK y permisos | Acción humana | Harness disponible y evidencia específica | Limpieza y límites |
|---|---|---|---|---|
| Sintéticos PNG/AAC/PDF/AVC; NOT_EXECUTED | Un equipo; par app/tests offline debug o R8 exactos. Sin sensores ni concesiones automáticas. | Selección USB y aprobación de cada APK nuevo; aceptar diálogo normal si aparece. | `run_physical_tests.py`, diez `CASES` fijos. Capturar nombres/métodos exactos, rechazo/éxito y nivel del alias Keystore sintético. | Runner detiene paquete propio y fixture borra su alias. No valida Vault autenticado, acústica ni dos radios. |
| ONCE y consumo tras force-stop; NOT_EXECUTED | Mismos APK/selección; datos sintéticos de cuatro formatos. | Consentimiento al alcance de force-stop del paquete aislado. | Opción existente `--restart-content`; recibos de consumo persistido antes de force-stop y rechazo tras reinicio por formato. | Finalizar fixture según runner; no prueba muerte durante commit ni borrado forense. |
| Contraseña/Vault/biometría/cancelación; MANUAL_PENDING | APK aislado que mantenga requisitos productivos de hardware/auth. No fallback software. Credenciales solo en equipo. | Propietario autentica, cancela y bloquea; consentimiento separado para cambios de credencial. | No runner físico automatizado aprobado para este flujo humano. Procedimiento manual futuro debe identificar pantalla/API y fallo esperado antes de ejecutarse. Registrar rechazo, persistencia y recuperación sin contenido. | Bloquear app; no cambiar PIN/biometría ni borrar datos como limpieza. Alias sintético sin auth no valida Vault productivo. |
| Captura de pantalla/recents/superficie; MANUAL_PENDING | Build UI integrado identificado, contenido sintético; FLAG_SECURE observado en pantalla concreta. | Captura/recents y transición de app con consentimiento. | Tests de configuración existen; falta procedimiento de UI física integrado. Recibos por pantalla/estado/transición y capturas sintéticas autorizadas. | Cerrar presentación; retirar únicamente capturas de prueba con acuerdo. No afirma protección ante cámara externa/SO comprometido. |
| Audio, focus, auriculares y rutas; MANUAL_PENDING | Connected aislado para captura si requerida; RECORD_AUDIO/BLUETOOTH_CONNECT según caso, permiso humano. Contenido sintético y entorno sin terceros. | Confirmar ruta, volumen y cuándo se permite sonido; denegar/revocar permiso, quitar auricular o perder focus. | `nativeNotePlaybackConfirmsRouteThenLockClosesWithoutReplay` y `nativeNoteFocusLossClosesAndNeverResumes` existen; excluidos del runner físico seguro. Falta runner físico revisado para ruta/acústica. | Detener audio/captura, cerrar recursos; restauración de ruta solo acordada. Callback no demuestra silencio acústico. |
| Cámara/micrófono/location; MANUAL_PENDING | Connected aislado, permisos mínimos por caso y consentimiento nuevo; no producción ni secretos. | Iniciar/detener captura sintética y denegar/revocar permiso conscientemente. | Suites Android existen; ninguna seleccionada por el runner físico seguro para sensores. Falta procedimiento físico por sensor. Recibos de inicio/cierre y denegación. | Verificar fin de uso sensor sin cambiar ajustes globales. No grabación ambiente ni equivalencia entre ubicación sintética y GPS real. |
| RFCOMM mensajes/contenido y pérdida/reconexión; NEEDS_SECOND_PEER | Puerta de dos equipos; offline aislado ambos; permisos Nearby/Bluetooth según API revisados, sin INTERNET/ACCESS_NETWORK_STATE. | Emparejamiento/huella fuera de banda; conceder/denegar; apagar radio o separar equipos solo al paso aprobado. | `NearbyFixtureListener` y framing/AVD no son harness físico. Falta runner seguro de dos pares. Evidencia roles, ambos extremos, ACK, duplicado, expiración, ciphertext inmutable y reconexión. | Cerrar ambos enlaces/fixtures. Sin cambios automáticos de red; registrar y acordar restauración. TCP no cuenta como Bluetooth físico. |
| Voz/video bidireccional real; NEEDS_SECOND_PEER | Dos equipos; Connected aislado ambos, permisos humanos camera/mic/Bluetooth según caso; endpoints sintéticos aprobados. | Consentimiento de participantes/rutas, iniciar llamada y bloquear/perder focus/desconectar. | Harness AVD/media existente requiere revisión/adaptación física; no disponible como runner seguro. Evidencia llegada/decodificación por ambos extremos, cancelación y rutas observadas. | Cerrar sesiones/sensores/conexiones; no equiparar video de archivo a videollamada ni callbacks a privacidad acústica. |
| Duplicación HTTPS/RFCOMM y cambio de transporte; NEEDS_SECOND_PEER | Dos equipos Connected, relay sintético autorizado y TLS válido; consentimiento explícito a red y Bluetooth. | Verificar identidades y activar transportes en pasos definidos. | No runner físico dual aprobado. Registrar mismo objeto/ciphertext, una sola apertura, reintentos y ACK en ambos extremos sin plaintext/logs de tokens. | Terminar fixture/credenciales sintéticas por alcance aprobado. No desplegar servicios ni usar proveedores pagos. |
| Ausencia de red por UID; BLOCKED_OBSERVABILITY | APK offline inspeccionado; herramienta de observación por UID disponible sin root ni cambio global. | Acordar método local revisado si existe. | Guards de APK prueban ausencia de permisos; no existe observador físico por UID demostrado aquí. | No VPN/certificado/root impuesto. Silencio del relay no prueba ausencia global de paquetes. |

Los métodos existentes citados identifican código, no resultados nuevos. Ni esta
matriz ni un APK compilado cierran aceptación física o auditoría independiente.

## Validación local de selección, sin ejecución

`python scripts/validate_physical_pair_plan.py /ruta/privada/plan.json` valida
únicamente un plan local. No importa ADB, no enumera equipos, no instala y no
produce comandos de ejecución. El índice 0 de `peers` es el rol A y el índice 1 es el rol B; el orden
se conserva. JSON rechaza claves duplicadas a cualquier profundidad y requiere exactamente:

```json
{
  "owner_confirmed_two_physical_devices": true,
  "peers": [
    {"serial":"SYNTHETIC_A","physical_asset_id":"PHONE_A","transport":"usb","state":"device","qemu":"0","api":36},
    {"serial":"SYNTHETIC_B","physical_asset_id":"PHONE_B","transport":"usb","state":"device","qemu":"0","api":36}
  ]
}
```

Los IDs físicos son etiquetas de inventario **de los teléfonos**, confirmadas por
el propietario, no nombres de paquetes/perfiles. Rechaza serial repetido o ID de
hardware repetido aunque los seriales difieran, emulador, TCP, falta de autorización
y evidencia desconocida. Rechaza campos `package` en lugar de identidad física.
El operador no debe afirmar dos equipos si solo hay uno. La herramienta no puede
detectar una declaración humana falsa ni autenticar hardware desde JSON: devuelve
`PLAN_VALID_ONLY`, con `physical_identity_attested=false` y
`fresh_preflight_required=true`. Por tanto sigue pendiente un runner físico dual
revisado que vuelva a obtener/verificar selección y locks inmediatamente antes de
mutar. No se promociona este plan a aceptación o autorización de instalación.
