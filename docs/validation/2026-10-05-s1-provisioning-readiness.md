# S1 — preparación de aprovisionamiento e infraestructura

Base revalidada: `86ce3ec8e6ed70d104cd57761307ee3520fe2acb`, árbol
`914605114b02608c601a63d05e5ba4c2838a01b7`. G1/PR24 cerrados. Rama
`codex/s1-provisioning-readiness`, worktree independiente `UMBRA_S1`.
La referencia final y sus recibos deben consultarse en el PR de esta rama;
este documento describe cambios y verificaciones locales, no un despliegue.

## Cambios

Compose exige el realm público y deriva el origen de su dominio. Caddy está
fijado por digest; ambos índices Python/Caddy listan ARM64 y sus hashes se
comprobaron contra los bytes recibidos del registro. Dieciséis wheels ARM64
CPython3.12 se resolvieron con hashes del lock; no se ejecutaron en ARM64.

`AdmissionService.provisioning()` reutiliza sus propios Records y conectividad.
La revisión exige un lease y confirmación de la fuente de configuración fuera
del relay; origen y realm se guardan atómicamente. No hay nuevo formato firmado,
claves maestras, cambio de admisión o concesión implícita de red. Contrato en
`docs/contracts/S1_PROVISIONING_V1.md`; la UI sigue siendo responsabilidad de
Claude y no fue modificada.

Herramientas nuevas: `s1_preflight.py` valida el JSON Compose sin red;
`s1_sqlite_backup.py` crea una copia consistente en destino nuevo y valida una
restauración aislada. La copia local NO está cifrada; requiere almacenamiento
protegido y política de cifrado/retención antes de usarla en infraestructura real.
No reemplaza bases ni elimina volúmenes. El directorio debe ser confiable.

## Revisión causal y resultados rojos conservados

- El preflight inicial aceptaba override root y socket Docker adicional.
  Reproducidos por revisión cruzada, corregidos con rechazo y regresiones.
- El backup inicial sincronizaba el archivo, no el directorio de publicación.
  Reproducido con observación de fsync; ahora confirma ambos y falla cerrado
  conservando una copia publicada si la durabilidad no puede confirmarse.
- Un WAL huérfano de otro destino podía superponer datos a la copia publicada.
  Reproducido con SQLite real: lectura obtenía registros del destino anterior.
  Ahora se rechazan auxiliares preexistentes/concurrentes sin borrarlos y se
  comprueba también el destino publicado. Pruebas preservan archivos ajenos.
- El primer build del adaptador reveló JSONException comprobada sin manejar;
  el siguiente reveló uso de JSONObject.keySet no disponible en Android.
  Corregidos mediante error tipado sin contenido y el iterador keys admitido.
- La revisión encontró que un registration no numérico imprimía su valor en
  NumberFormatException. Se convierte únicamente ese error de formato a
  INVALID_CONFIGURATION sin causa; errores de acceso y disco se conservan.

No se eliminó ninguna prueba, aserción, shard o control. No se elevaron límites
ni timeouts para obtener pases. Regresiones observadas antes y después del cambio.

## Ejecución local registrada

Entorno Python3.13 con venv aislado y `relay/requirements-test.lock` con hashes;
JDK21 y Gradle8.14.4 mediante el bootstrap verificado del repositorio, SDK36.
`bash scripts/test_local.sh`: 244 backend aprobadas, suites Core/Security/sintaxis
y trece comprobaciones de fuente aprobadas. La advertencia de Starlette sobre
TestClient/httpx se conserva; no se suprimió ni se cambió una dependencia para
ocultarla. La suite de herramientas integrada ejecutó
415 pruebas aprobadas (43,462 segundos, cero fallos).
Los seis controles originales de preparación no se suman a estos conteos.

Las 18 pruebas JVM nuevas pasan por separado en connected y offline, sin
fallos, errores ni omisiones, tras recompilar la corrección de diagnóstico.
Se conservó el rojo previo ejecutado contra la compilación anterior. Cubren formato, autoridad/origen, rollback, sesiones
antiguas, independencia de admisión/registro/conexión y diagnósticos redactados.
`DeviceProvisioningTest` contiene dos casos de SQLite/Vault Android aislados.
El laboratorio password ejecuta esos dos casos también en R8 y exige presencia
real del adaptador en mapping; mantiene optimización y sus otras suites.
No contar estas pruebas Android como ejecutadas hasta revisar sus recibos de CI.

## Pendientes externos y de integración

No se desplegó OCI, cambió DNS/firewall, generó costo cloud ni instaló APK.
Acceso autenticado a OCI y dominio siguen pendientes del propietario. El código
no demuestra TLS público, capacidad, carga, backup operativo cifrado ni hardware.
El issuer TURN productivo y la integración visual del aprovisionamiento siguen
separados. Conservar PRIVATE_STARTUP_STRICT, Nearby explícito, Signal,
VERIFIED_ONLY, Vault/Keystore, emergencia y exclusiones offline.

La excepción humana de PR24 ya se agotó. Este cambio sensible nuevo requiere
revisión humana conforme a AGENTS antes de merge; CI verde y revisión asistida
por IA no constituyen esa aprobación. No declarar producción ni cierre físico.

## Addendum: inventario de instrumentación y entorno local

Primer candidato publicado `edacb6d300977fee158fd608dab0979123913a26`,
árbol `edaff0e7ecb2fa12951a55d7631001b4cca403b1`. Verify run
37416234272, intento1, job Android112117007529 rechazó la suite por exigir
127 casos cuando Android ejecutó y aprobó129. Artifact11391688855, ZIP
SHA-256 `f6ebeca3d224aedd34e65b819757861e9111a2aca5147e4a35182977d41f122a`,
contiene `OK (129 tests)` y terminal -1, sin fallos ni omisiones; los dos
nuevos casos pasaron. Tiempo Android303,199s y host304,022s, bajo345s.
Offline no llegó a ejecutarse en ese job. No interpretar el rojo como fallo
de admisión/SQLite ni contar Offline como aprobado.

Se reprodujo el rechazo del runner sobre el recibo real antes del cambio.
Ahora exige exactamente129 connected y126 offline, dos casos adicionales
por flavor. Trece tests focales del runner pasan y rechazan conteos viejos,
conteos desviados, crashes y skips incluso con resumen del conteo correcto.
El replay del recibo pasa; no constituye otra ejecución Android. Preservado
el timeout345s y todas las demás condiciones. El nuevo SHA necesita CI propia.

Durante este diagnóstico GitHub registró un intento2 del mismo run/HEAD;
esta tarea no lo inició ni lo canceló. Preservar ambos resultados.

La JVM completa local aprobó491 connected y430 offline, sin fallos, errores
ni omisiones. El primer build agregado local falló en `readdir` de DrvFS
(`Cannot allocate memory`) durante lint; no se demostró OOM ni causa precisa.
El lint aislado y luego el build completo con un worker pasaron (269 tareas,
10m19s), incluidos APK debug/release, R8, lint e instrumentación compilada.
Guardas de APK, permisos y JNI pasaron para ambos flavors y builds.
El cambio de concurrencia es una medida local, no una corrección productiva
ni una modificación de CI. Mantener ese fallo histórico.

El guard de historial local compartido devolvió exit2 por un subprocess Git
sin diagnóstico de comando. Inventario, metadata y lectura completa actual
de objetos no reprodujeron el error; no se tocaron refs/objetos. La copia
restaurada propia aprobó fsck y el guard completo (2218 blobs). El checkout
limpio de CI aprobó repository-guard. No declarar resuelta la causa local.

Respaldado el primer candidato en bundle completo, restauración aislada y
SHA-256 verificados; el siguiente recibo debe conservarlo y añadir otro
respaldo del candidato corregido, sin sobrescribirlo. Ninguna APK fue
instalada en teléfonos ni se desplegó infraestructura.
