# Revisión correctiva del repositorio — 19 de septiembre de 2026 UTC

## Alcance y procedencia

Revisión del cliente Android, relay, pruebas, construcción, permisos y flujo CI.
Base remota: PR #1, `0f4d566880454939dda130167c42de3ac8b4fe77`; árbol
`2ce53d45e95fc0c0d2abcc2d838c7a26fb7bf39e`. No se fusiona ni modifica `main`.
La PR correctiva se apila sobre `codex/android-build-validation` para separar cambios.

Se recuperó la base local del bundle entregado previamente: su árbol coincide con
`main` (`1e3a63799146b0bfee45bbbdf1f9222bcefbaef1`), aunque los metadatos de commit
locales difieren. Se aplicaron las fuentes ejecutables de PR #1 obtenidas del conector
GitHub. Antes de modificar, el subárbol Android coincidía exactamente con el remoto
`08705f102a1f0b8e0af414d8eac8f3d3952316a5`; scripts recuperados verificados por blob SHA.
La publicación parte del árbol remoto completo y conserva sus documentos históricos.

Esto es revisión de ingeniería con pruebas, no auditoría independiente ni certificación
criptográfica. Un resultado verde cubre los controles ejecutados, no todas las amenazas.

## Defectos corregidos

### 1. Conversión de enteros de tarjetas firmadas

`Engine.importCard` validaba mediante `getInt`, permitiendo que un entero de 64 bits
se truncara a un registro, identificador o versión aparentemente válidos. Ahora se
validan valores `long` antes de convertir o alimentar libsignal. La firma sigue
siendo obligatoria: no se afirma que fuera posible falsificar una identidad.
También se rechazan intervalos de invitación no positivos y capacidades de escritura
Base64URL no canónicas, evitando discrepancias con el servidor.

### 2. Preclaves eliminadas antes de recibir mensajes demorados

El vencimiento de la tarjeta eliminaba inmediatamente todas las preclaves asociadas,
aunque un primer mensaje cifrado durante su vigencia pudiera seguir en tránsito.
Se retienen las preclaves no consumidas durante un MAX_TTL adicional (siete días).
Las de un uso consumidas se siguen eliminando inmediatamente mediante SignalStore.
El vencimiento de mensajes y la prohibición de iniciar sesiones con tarjetas vencidas
no cambian. Compromiso explícito: claves no utilizadas permanecen disponibles más
tiempo, con retención máxima acotada; esta decisión necesita revisión humana.
No se recuperan claves ya borradas por versiones anteriores ni se cambia wire format.

### 3. Adopción o degradación silenciosa del esquema del relay

El arranque podía reescribir `user_version=2` sobre una base futura o ajena y recrear
tablas v2 perdidas. Ahora se verifica la versión, conjunto de tablas y columnas de
mensajes antes de migrar o cambiar WAL. Se conserva la migración legacy admitida y se
rechaza lo desconocido. Las pruebas verifican versión, esquema, journal y datos
sintéticos conservados. Esto no es una comprobación exhaustiva de corrupción física.

### 4. Limpieza periódica detenida con salud falsamente positiva

Una excepción SQLite podía terminar el sweeper; `/healthz` seguía respondiendo 200.
La limpieza reintenta errores SQLite tras una espera de 60 segundos y conserva un
estado degradado hasta completar un ciclo real. `/healthz` devuelve 503 sin detalles
internos mientras ese estado persiste. Errores de programación se propagan, no se
ocultan. La cancelación de cierre se respeta. Este endpoint no demuestra la capacidad
de escritura en todo instante ni reemplaza monitorización operacional.

### 5. Controles de APK que aceptaban artefactos inválidos

Se comparte una lista de permisos permitidos entre fuente, manifiesto combinado y
APK. Se reconocen las declaraciones SDK-23 en XML. Variantes desconocidas, permisos
sensibles añadidos, entradas ZIP duplicadas y cabeceras ELF de arquitectura/clase
incorrectas son rechazados. Se mantienen los controles de JNI de escritorio/testing.
Una cabecera válida no demuestra integridad criptográfica ni ejecución correcta de JNI.
Los fixtures ELF/XML/ZIP son sintéticos y no se cuentan como pruebas Android.

## Evidencia local ejecutada

Entorno: Linux, Python 3.13.5 y JDK 21. Comandos desde la raíz:

```bash
bash scripts/test_local.sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/repository_guard.py --git-history
python -m compileall -q relay/umbra_relay scripts
python scripts/build_android.py --check-only
```

- Base `main`: 80 pruebas backend, 105 escenarios JVM y 39 pruebas de herramientas.
- Base ejecutable PR #1: 46 pruebas de herramientas.
- Antes de corregir: ocho fallos de esquema, cuatro de controles APK y un falso 200
  de salud reproducidos por las nuevas pruebas. No son trece vulnerabilidades distintas.
- Tras corregir: **94 pruebas backend, 105 escenarios JVM y 57 pruebas de herramientas
  aprobados**. Los 12 controles estáticos se contabilizan por separado.
- Se añaden nueve métodos JUnit con libsignal real. Se esperan 39 métodos por variante
  sumando los 30 anteriores; la ejecución Android corresponde a GitHub Actions, no
  a esta comprobación local. Consultar el run asociado al SHA de la PR para su resultado.
- El preflight local devuelve 2: no hay Android SDK. No se presenta como compilación.
- Se ejecutó higiene de fuente/historial local: no halló patrones soportados; no equivale
  a un detector exhaustivo de secretos ni a revisar el historial remoto completo.
- CodeRabbit no se ejecutó: su instalación falló con `curl: (6) Could not resolve host:
  cli.coderabbit.ai`. No se atribuye esta revisión a CodeRabbit. Requiere resolver red e
  iniciar su autenticación en un entorno autorizado para una revisión complementaria.

Los resultados actuales de compilación, JNI, lint y contenedor deben comprobarse en
los cuatro jobs de la PR, sobre su SHA exacto. No se reutiliza el verde histórico de PR #1
para dar por aprobado este código nuevo. La descripción de la PR registra el run validado.

## Límites que siguen abiertos

Sin pruebas instrumentadas de Keystore/ciclo de vida, Bluetooth emulado o físico,
release/R8 ni auditoría independiente. No se ejecutó un escaneo actualizado y exhaustivo
de CVE/licencias. Las advertencias de lint no se han desactivado. Ningún cambio de esta
PR cierra automáticamente P0-02 a P0-12 ni habilita uso con secretos reales.

Revisar especialmente la competencia del worker de UI/criptografía/transporte ante un
relay lento: la inspección muestra tiempos de espera distintos y falta una prueba de
instrumentación que descarte bloqueo de Bluetooth. No se declara ese escenario validado.

No se alteraron permisos del repositorio, visibilidad, firma de release, Keystore,
versiones criptográficas, controles de revisión o políticas para obtener verde.
Mantener revisión humana de almacenamiento y retención de claves antes de fusionar.
