# ADR: ubicación con consentimiento local y destinatarios fijos

Fecha: 2026-09-20. Decisión de la tercera entrega; resultados en el informe de validación.

La ubicación usa el contenido v2 de Engine y las sesiones libsignal existentes.
No cambia el sobre del relay ni incorpora tablas, mapas o servicios de geocodificación.
La autoridad autenticada de dispositivos y VERIFIED_ONLY se aplican también a las
APIs directas. Autenticar al emisor no acredita la verdad física de una coordenada.

El propietario revisa contacto, dispositivos concretos, precisión y duración. La
confirmación captura la época de desbloqueo; no puede reutilizarse ni recuperarse
tras reiniciar. Las nuevas claves nunca amplían una sesión existente. Revocar una
clave reduce sus entregas; suspender la confianza de la raíz interrumpe la sesión.
El dispositivo capturador es siempre local: ninguna recepción activa un proveedor.

Se conserva el bloqueo al pausar MainActivity. Captura solo con Activity visible,
sin servicio ni ACCESS_BACKGROUND_LOCATION. Incluso una duración de ocho horas es
un máximo, no una excepción al bloqueo automático de cuatro minutos. Salir, rotar,
bloquear, perder permisos/proveedor o reiniciar exige una nueva acción. No hay
arranque desde notificaciones, boot receivers o mensajes remotos. Controles mínimos
visibles permiten detener; FLAG_SECURE y bloqueo impiden coordenadas en lockscreen.

LocationManager de AOSP evita depender de Google Play Services. GPS requiere permiso
preciso; con permiso aproximado se usa únicamente NETWORK_PROVIDER si existe y
está habilitado, sin intentar obtener precisión mediante GPS. Ausencia de proveedor
es un error explícito. Offline carece de permisos de red; el SO/proveedor podría usar
sus propios servicios. No se promete disponibilidad en interiores o GPS desactivado.

APPROXIMATE/ZONE reducen localmente a centros de celdas angulares de 0,01/0,1 grados,
antes de persistir/cifrar. Incertidumbre del sensor y tamaño de celda son campos
separados. Celdas no equivalen a radios métricos uniformes: meridianos convergen y
las trayectorias repetidas permiten inferencias. No se conserva la medición original.

Consentimientos/leases y plazos monotónicos viven en memoria; estado mínimo y
terminales viven en Records protegido. No se restaura captura ni backlog al abrir.
Actualizaciones reemplazan pendientes anteriores sin modificar ciphertext ni rebobinar
ratchets. Revalidación por ID de entrega inmediatamente antes de escritura. Bytes ya
emitidos, copias del receptor y archivos externos no se pueden retirar.

Fuentes oficiales consultadas el 2026-09-20 (target 36 conservado, mínimo 31):
- https://developer.android.com/develop/sensors-and-location/location/permissions/runtime
- https://developer.android.com/reference/android/location/LocationManager
- https://developer.android.com/reference/android/location/Location
- https://developer.android.com/develop/sensors-and-location/location/background

El ciclo de vida elegido no necesita foreground service ni permisos de ese servicio;
una futura captura en segundo plano requeriría otro ADR, consentimiento y revisión.
Hardware GPS/Keystore, destrucción real durante commit y auditoría se registran por
separado: ni inyección AVD ni reapertura SQLite los demuestran.
