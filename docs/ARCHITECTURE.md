# Arquitectura actual — 0.2.0-dev

```text
MainActivity (interfaz, autenticación, permisos, bloqueo)
   ├─ AccessGate (épocas y autorización por operación)
   ├─ Engine (identidad, sesiones, inbox/outbox y confirmaciones)
   │    ├─ Wire / StrictJson / FileNames (fronteras de entrada)
   │    ├─ SignalStore → libsignal real (integración por ejecutar)
   │    └─ Records → Vault → SQLite + Android Keystore
   ├─ BluetoothLink (RFCOMM, saludo v2, tramas y colas acotadas)
   └─ RelayClient (HTTPS, cancelación y límites)
         └─ RequestLimits → FastAPI → SQLite (relay)
```

Las operaciones de protocolo de la UI se serializan en un trabajador. Las tareas de transporte tienen colas y plazos; no ejecutan cambios de estado criptográfico fuera del camino transaccional. El hilo visual no prepara claves Keystore. Al bloquear se revoca la autorización local, se invalidan callbacks antiguos y se cancelan los transportes propios. El relay guarda solo sobres cifrados y metadatos necesarios; no se representa como infraestructura anónima.

La variante `offline` comparte componentes de UI/protocolo/almacenamiento con `connected`, deshabilita el relay al compilar y elimina permisos de red en su overlay. La ausencia de permiso debe verificarse después del merge y en el APK de publicación. Ambas variantes tienen identificadores de aplicación distintos y datos separados.

Los cambios de esquema, protocolo Bluetooth, límites y asuntos aún no resueltos están en `HARDENING_0_2.md`. La presencia de estos componentes en el código no demuestra ejecución en Android: revisar `TEST_STATUS.md`.
