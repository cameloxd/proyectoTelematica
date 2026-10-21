# ControlNode - Fase 2

Esta fase implementa el flujo obligatorio de subida y descarga coordinado por el ControlNode.

## AllocateFile

La llamada exige un usuario autenticado, un path absoluto, el tamaño y un `operation_id`. PostgreSQL ejecuta la asignación en una transacción `SERIALIZABLE`:

1. Si el usuario ya usó el mismo `operation_id`, se devuelve el plan existente. Si cambió el path o el tamaño, se rechaza.
2. Se valida que el directorio padre exista.
3. Se crea el archivo `PENDING` y sus bloques.
4. Solo participan DataNodes `ALIVE`, con espacio disponible y por debajo del límite de transferencias.
5. El primer criterio es la ronda del archivo, para repartir sus primeros bloques entre nodos distintos. Dentro de la ronda se maximiza `0.60 * espacio + 0.25 * carga + 0.15 * bloques`.
6. Cada bloque reserva espacio con un `UPDATE` condicional y crea una ubicación `PLANNED` en la misma transacción.

Los tokens de lectura y escritura usan el formato `base64url(payload).base64url(firma)`. El payload es `v1|ACCION|block_id|file_id|owner_id|exp` y la firma es Ed25519. El DataNode recibe únicamente la clave pública.

## ReportBlockStored y CommitFile

El registro entrega al DataNode un token aleatorio; PostgreSQL conserva solo su SHA-256. `ReportBlockStored` autentica ese token, verifica tamaño y checksum, marca la ubicación como `WRITTEN` y libera la reserva. La operación es idempotente cuando se repite el mismo reporte.

`CommitFile` devuelve `missing_block_ids` mientras exista algún bloque sin ubicación `WRITTEN`. Cuando no falta ninguno cambia el archivo a `COMMITTED`; solo entonces `GetFileManifest` puede devolverlo.

## Prueba de integración

1. Si ya existe un volumen de PostgreSQL de una versión anterior, recrearlo porque `schema.sql` agrega `node_token_hash`.
2. Generar secretos y levantar PostgreSQL: `cd deploy`, `bash gen-secrets.sh`, `docker compose up postgres`.
3. Crear un usuario de desarrollo en `app_user`; el interceptor temporal usa el nombre `dev-user` para cualquier Bearer no vacío.
4. Iniciar el ControlNode con `DB_URL`, `DB_USER`, `DB_PASSWORD_FILE` y `TOKEN_PRIVATE_KEY_FILE`.
5. Registrar tres DataNodes, asignar un archivo de 20 MiB y verificar bloques de 8, 8 y 4 MiB en nodos distintos.
6. Repetir la asignación con el mismo `operation_id`, reportar los bloques, comprobar el rechazo del commit incompleto y finalmente obtener el manifiesto confirmado.

La autenticación real y el namespace completo corresponden a C1.4; el borrado lógico y su evento corresponden a C2.3.
