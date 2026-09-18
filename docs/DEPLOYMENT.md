# Despliegue y operación del relay

**Configuración suministrada, no desplegada ni probada en Docker en esta entrega.** Primero cerrar los requisitos de revisión y usar cuentas y datos de prueba.

## Requisitos

Un servidor Linux administrado, Docker Engine con Compose, dominio controlado por el operador, DNS apuntando al servidor y puertos 80/443 accesibles para HTTPS. No publicar el puerto 8080 ni exponer SQLite. El operador debe definir la ubicación de los datos, permisos de administración, actualizaciones, alertas sin contenido, retención y respuesta a incidentes.

```bash
cp .env.example .env
# Sustituir el dominio de ejemplo por uno real.
docker compose config
# La siguiente orden construye/descarga imágenes: no se ejecutó durante la entrega.
docker compose up -d --build
```

Caddy proporciona terminación TLS. El cliente Android acepta solamente un origen `https://host[:port]`, sin credenciales, ruta adicional, query o fragmento. No admite HTTP, certificados de usuario como alternativa ni redirecciones. Para un laboratorio local Android hace falta un certificado válido según esa configuración; no desactivar la validación TLS para resolverlo.

## Alta de dispositivos

```bash
docker compose exec relay python -m umbra_relay.admin invite --ttl 3600
```

Una invitación de administración autoriza un registro y vence según su TTL. Compartirla por un canal apropiado; no publicarla en un chat general. La app genera su propio buzón y sus capacidades de lectura/escritura y los registra mediante la invitación. La invitación administrativa no debe confundirse con una tarjeta de contacto.

Cada teléfono requiere su propia invitación. Ambos interlocutores deben configurar el mismo relay: no hay federación ni relay remoto tomado de una URL suministrada por un contacto. El cliente permite borrar su buzón autenticándose; eso no borra las copias del interlocutor ni los registros externos de infraestructura.

## API suministrada

| Método/ruta | Función |
|---|---|
| GET `/healthz` | Salud básica sin conversaciones |
| POST `/v1/boxes` | Registro autorizado por invitación |
| PUT `/v1/boxes/{box}/messages/{message_id}` | Almacenar sobre usando la capacidad de escritura |
| GET `/v1/boxes/{box}/messages?after=0` | Obtener hasta cinco sobres usando la capacidad de lectura |
| DELETE `/v1/boxes/{box}/messages/{message_id}` | Confirmar/eliminar un sobre con autorización de lectura |
| DELETE `/v1/boxes/{box}` | Eliminar el buzón propio y sus datos |

Los headers, cuerpos exactos y validaciones están en `relay/umbra_relay/app.py` y `RelayClient.java`. No introducir tokens reales en historiales de comandos o herramientas compartidas.

## Cupos y caducidad

SQLite y un worker: despliegue de pequeña escala, no cluster. Cada buzón está limitado a 128 mensajes pendientes y 16 MB de datos de mensajes; se rechazan sobres superiores al límite configurado. La retención máxima es de siete días y hay una limpieza periódica aproximadamente cada minuto, además de limpiezas asociadas a operaciones. Almacenamiento, reinicios, backlog y carga deben medirse antes de fijar un nivel de servicio.

La aplicación limita solicitudes por la dirección del peer HTTP directo a 240 por minuto. **En este Compose el peer directo del backend es Caddy y se desactivó la confianza en headers de proxy: el límite se comparte de hecho entre usuarios que pasan por ese proxy.** No sirve como defensa por usuario/IP de origen ni como dimensionamiento de muchos usuarios. Antes de escalar, diseñar un límite en el borde y otro por credencial, con una configuración precisa de proxies confiables. No habilitar confianza indiscriminada en `X-Forwarded-For`.

La revisión 0.2 añade máximo 1.024 buzones, 8.192 identificadores retenidos por buzón (cola más deduplicación), 32 solicitudes simultáneas en el middleware y plazo absoluto de 10 segundos para recibir cada cuerpo. Son límites configurados, no una capacidad operativa medida. La cola migra a secuencias monotónicas; probar recuperación y presupuesto de disco en el despliegue real. El contador temporal de frecuencia guarda HMACs de IP en memoria, sin ocultar la IP al proxy/sistema.

## Datos que ve el operador

Buzones aleatorios, identificadores pseudónimos de remitente/destinatario incluidos en los sobres, momentos, expiración, tamaños, ciphertext, hashes de capacidades y registros de deduplicación. El borde de red ve IP y tiempos. El servidor no recibe las claves privadas del protocolo; aun así, es capaz de observar tráfico y denegar servicio. No anunciarlo como «cero metadatos».

## Endurecimiento pendiente

Fijar versiones de imágenes por digest, comprobar CVEs/SBOM, probar reconstrucción desde cero, restringir SSH y accesos de operador, comprobar permisos del volumen, revisar reglas de firewall y políticas de logs de hosting/DNS, medir carga, ensayar apagado/recuperación, establecer borrado de copias de infraestructura y diseñar monitoreo sin contenido. Los tags `python:3.12-slim` y `caddy:2` no garantizan reconstrucción idéntica.

No respaldar indiscriminadamente bases con capacidades o colas. Definir primero una política de retención y cifrado/gestión de claves para respaldos operativos. Una copia del volumen puede prolongar la existencia de metadatos y ciphertext más allá del vencimiento del servicio.

## Detener y borrar

`docker compose down` detiene servicios sin borrar normalmente los volúmenes. No usar `docker compose down -v` como operación rutinaria: destruiría volúmenes, incluidos buzones y estado de TLS. Esa acción necesita una decisión explícita del operador y no equivale a eliminar todos los registros externos o copias recibidas por usuarios.
