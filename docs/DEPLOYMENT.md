# Despliegue y operación del relay

**Configuración suministrada; no equivale a un despliegue validado.** Renderizar Compose no prueba ejecución de contenedores, HTTPS, admisión ni Android. Primero cerrar los requisitos de revisión y usar cuentas y datos de prueba.

## Requisitos

Un servidor Linux administrado, Docker Engine con Compose, dominio controlado por el operador, DNS apuntando al servidor y puertos 80/443 accesibles para HTTPS. No publicar el puerto 8080 ni exponer SQLite. El operador debe definir la ubicación de los datos, permisos de administración, actualizaciones, alertas sin contenido, retención y respuesta a incidentes.

```bash
cp .env.example .env
# Sustituir el dominio de ejemplo e importar el realm público de la autoridad.
docker compose config
# Solo tras autorización explícita de despliegue y revisión del operador:
docker compose up -d --build
```

`UMBRA_DOMAIN` es exclusivamente el nombre DNS público (sin esquema, puerto, ruta, query ni fragmento). `UMBRA_ADMISSION_REALM` debe contener el objeto público canónico `umbra:realm:1:<realmId>:<authorityPublicKey>:<authorityKeyId>` exportado por la autoridad. El ejemplo lo deja vacío deliberadamente: Compose rechaza un realm ausente o vacío y un dominio ausente o vacío. El backend valida el formato del realm al arrancar; `docker compose config` no verifica su estructura ni DNS. No colocar semillas privadas, contraseñas de bóveda ni credenciales en estas variables.

Compose suministra al relay `UMBRA_ADMISSION_ORIGIN=https://${UMBRA_DOMAIN}`, exactamente el origen público servido por Caddy en el puerto HTTPS estándar. No toma un override de `UMBRA_ADMISSION_ORIGIN` desde `.env`: así no divergen dominio y origen de las pruebas de posesión. Android debe utilizar ese mismo origen. Un dominio mal formado requiere corrección del operador; no desactivar validaciones para arrancarlo. El realm público queda fijado en SQLite; un cambio inesperado se rechaza, sin recrear la base ni sustituir silenciosamente la autoridad.

Caddy proporciona terminación TLS. El cliente Android acepta solamente un origen `https://host[:port]`, sin credenciales, ruta adicional, query o fragmento. No admite HTTP, certificados de usuario como alternativa ni redirecciones. Para un laboratorio local Android hace falta un certificado válido según esa configuración; no desactivar la validación TLS para resolverlo.

## Alta de dispositivos

La autoridad de admisión se crea mediante una acción deliberada del administrador en la app con la bóveda abierta. Su clave Ed25519 dedicada permanece en registros cifrados de esa bóveda; el relay recibe solamente el realm público y objetos firmados. Importar el realm debe realizarse mediante provisioning explícito y autenticado fuera del relay. Obtener `/v1/admission/realm` por HTTPS no instala ni cambia el pin. No hay recuperación ni rotación de la autoridad implementadas; ver [admisión](ADMISSION.md).

Con `PRIVATE_STARTUP_STRICT`, desbloquear deja el dispositivo offline. El bootstrap de un dispositivo no admitido importa realm, solicitud y credencial mediante provisioning offline; los métodos de RelayClient, incluidos los de admisión pública, exigen consentimiento de conexión y admisión vigente antes de abrir red. La autoridad revisa y firma cada solicitud de dispositivo; instalar una APK, copiar un realm o vincular otro dispositivo no concede admisión. La autoridad también necesita su solicitud/credencial propia; existe una operación explícita de aprobación e instalación de su propia solicitud pendiente. Después de la admisión, HTTPS requiere conexión explícita al origen configurado; Nearby exige su consentimiento separado. No se restaura una conexión tras desbloquear o reiniciar. Ver [inicio privado](PRIVATE_STARTUP.md) y [API de admisión](ADMISSION_API_CHECKPOINT.md).

```bash
docker compose exec relay python -m umbra_relay.admin invite --ttl 3600
```

Una invitación de administración autoriza un registro de buzón y vence según su TTL; no concede admisión. Las APIs privadas exigen además credencial vigente, desafío y prueba de posesión del dispositivo. Compartirla por un canal apropiado; no publicarla en un chat general. La app genera su propio buzón y sus capacidades de lectura/escritura y los registra mediante la invitación. La invitación administrativa no debe confundirse con una tarjeta de contacto ni una credencial de admisión. La admisión no verifica la identidad humana: mantener la comparación fuera de banda y el bloqueo ante cambios de identidad.

Cada teléfono requiere su propia invitación. Ambos interlocutores deben configurar el mismo relay: no hay federación ni relay remoto tomado de una URL suministrada por un contacto. El cliente permite borrar su buzón autenticándose; eso no borra las copias del interlocutor ni los registros externos de infraestructura.

## API suministrada

| Método/ruta | Función |
|---|---|
| GET `/healthz` | Salud básica sin conversaciones |
| GET `/v1/admission/realm` | Realm público configurado; no concede admisión |
| POST `/v1/admission/*` | Solicitudes/decisiones firmadas, desafíos, resultados y renovaciones; rutas exactas en `admission_http.py` |
| POST `/v1/boxes` | Registro autorizado por invitación |
| PUT `/v1/boxes/{box}/messages/{message_id}` | Almacenar sobre usando la capacidad de escritura |
| GET `/v1/boxes/{box}/messages?after=0` | Obtener hasta cinco sobres usando la capacidad de lectura |
| DELETE `/v1/boxes/{box}/messages/{message_id}` | Confirmar/eliminar un sobre con autorización de lectura |
| DELETE `/v1/boxes/{box}` | Eliminar el buzón propio y sus datos |

Los headers, cuerpos exactos y validaciones están en `relay/umbra_relay/app.py`, `admission_http.py` y `RelayClient.java`. Un `/healthz` sano no demuestra un realm configurado ni acceso privado admitido. La prueba de salud del contenedor y la CI que solo comprueba esa salud no validan el bootstrap o la admisión. No introducir tokens reales en historiales de comandos o herramientas compartidas.

## Cupos y caducidad

SQLite y un worker: despliegue de pequeña escala, no cluster. Cada buzón está limitado a 128 mensajes pendientes y 16 MB de datos de mensajes; se rechazan sobres superiores al límite configurado. La retención máxima es de siete días y hay una limpieza periódica aproximadamente cada minuto, además de limpiezas asociadas a operaciones. Almacenamiento, reinicios, backlog y carga deben medirse antes de fijar un nivel de servicio.

La aplicación limita solicitudes por la dirección del peer HTTP directo a 240 por minuto. **En este Compose el peer directo del backend es Caddy y se desactivó la confianza en headers de proxy: el límite se comparte de hecho entre usuarios que pasan por ese proxy.** No sirve como defensa por usuario/IP de origen ni como dimensionamiento de muchos usuarios. Antes de escalar, diseñar un límite en el borde y otro por credencial, con una configuración precisa de proxies confiables. No habilitar confianza indiscriminada en `X-Forwarded-For`.

La revisión 0.2 añade máximo 1.024 buzones, 8.192 identificadores retenidos por buzón (cola más deduplicación), 32 solicitudes simultáneas en el middleware y plazo absoluto de 10 segundos para recibir cada cuerpo. Son límites configurados, no una capacidad operativa medida. La cola migra a secuencias monotónicas; probar recuperación y presupuesto de disco en el despliegue real. El contador temporal de frecuencia guarda HMACs de IP en memoria, sin ocultar la IP al proxy/sistema.

## Datos que ve el operador

Buzones aleatorios, identificadores pseudónimos de remitente/destinatario incluidos en los sobres, momentos, expiración, tamaños, ciphertext, hashes de capacidades y registros de deduplicación. El borde de red ve IP y tiempos. El servidor no recibe las claves privadas del protocolo; aun así, es capaz de observar tráfico y denegar servicio. No anunciarlo como «cero metadatos».

## Endurecimiento pendiente

Python y Caddy están fijados por digest en `relay/Dockerfile` y `compose.yaml`. El digest de Caddy identifica un índice multi-arquitectura que incluye ARM64; antes de operar, revisar CVEs/SBOM y el manifiesto de la arquitectura del host. Siguen pendientes probar reconstrucción desde cero, restringir SSH y accesos de operador, comprobar permisos del volumen, revisar reglas de firewall y políticas de logs de hosting/DNS, medir carga, ensayar apagado/recuperación, establecer borrado de copias de infraestructura y diseñar monitoreo sin contenido. Los digests fijan el contenido de las imágenes base; no prueban seguridad ni reproducibilidad completa del build.

No respaldar indiscriminadamente bases con capacidades o colas. Definir primero una política de retención y cifrado/gestión de claves para respaldos operativos. Una copia del volumen puede prolongar la existencia de metadatos y ciphertext más allá del vencimiento del servicio.

## Detener y borrar

`docker compose down` detiene servicios sin borrar normalmente los volúmenes. No usar `docker compose down -v` como operación rutinaria: destruiría volúmenes, incluidos buzones y estado de TLS. Esa acción necesita una decisión explícita del operador y no equivale a eliminar todos los registros externos o copias recibidas por usuarios.
