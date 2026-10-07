# DFSha — Hito 2: cambios sobre el diseño original

Versión corregida de `dfsha.proto`, `schema.sql` y `docker-compose.yml`, más las secciones del documento que cambian.

## Cómo arrancar

```bash
cp env.example .env
bash gen-secrets.sh              # contraseñas, clave de registro y claves Ed25519
docker compose up --build       # ejecutar desde la carpeta hito2
```

```text
dfsha/
├── docker-compose.yml
├── env.example
├── schema.sql
├── gen-secrets.sh
├── src/main/proto/dfsha.proto
├── controlnode/   (Dockerfile + código Java)
└── datanode/      (Dockerfile + código Java)
```

## 1. Qué se corrigió

| # | Problema | Solución |
|---|---|---|
| 1 | Los bloques de un archivo podían caer todos en el mismo nodo | Reparto por rondas (sección 2) |
| 2 | El heartbeat borraba las reservas de espacio | Columna `reserved_bytes` que solo toca el ControlNode; `available_bytes = free_bytes - reserved_bytes` |
| 3 | La lectura no tenía `read_token` | Mensaje `BlockLocation` con `read_token`, `checksum_sha256` y expiración; los tokens ya no son de un solo uso (expiración de 15 min) |
| 4 | Los DataNodes no podían verificar tokens | Firma Ed25519: clave privada solo en ControlNode, pública en los DataNodes; todo por Docker secrets |
| 5 | `CommitFile` confiaba en el cliente | `ReportBlockStored` (DataNode → ControlNode). `CommitFile` solo lleva `file_id` y devuelve `missing_block_ids` |
| 6 | Identidad de DataNodes ambigua y heartbeat sin auth | `RegisterDataNode` (upsert por `node_name`) con clave del cluster; devuelve `datanode_id` y `node_token` |
| 7 | Los DataNodes no eran alcanzables desde el host | Puerto publicado y `ADVERTISED_ENDPOINT` por nodo; se usan anclas YAML en vez de `extends` |
| 8 | Esquema: una sola réplica, paths no reutilizables, sin directorios | Índice único parcial para un solo `PRIMARY`; índice único parcial `WHERE status <> 'DELETED'`; tabla `dfs_directory` |

También se agregaron: `operation_id` como clave de idempotencia (`UNIQUE (owner_id, operation_id)`), `pending_expires_at` para limpiar archivos `PENDING`, `parent_path` generado para `ls`, directorio raíz automático por usuario (trigger), `DeletePathRequest.recursive`, y los parámetros que faltaban (`RESERVED_BYTES`, `MAX_TRANSFERS_PER_NODE`, `TARGET_BLOCKS_PER_NODE`, tiempos de heartbeat). Se quitó `pgcrypto`: `gen_random_uuid()` es nativa desde PostgreSQL 13.

## 2. Asignación de bloques (sección 3 corregida)

La puntuación no cambia (0.60 espacio libre + 0.25 carga + 0.15 bloques almacenados). Lo nuevo es la regla de reparto por rondas: solo compiten los nodos que tienen menos bloques **de este archivo**, y entre ellos gana el mayor `score`. Así un archivo de 3 bloques con 3 nodos elegibles siempre queda en 3 nodos distintos (RNF4).

```text
allocate(path, size, operation_id, user):
    if existe archivo (user, operation_id): return su plan guardado      # idempotente
    n = ceil(size / block_size)          # size = 0 -> archivo COMMITTED sin bloques

    begin transaction
      crear dfs_file PENDING con pending_expires_at = now + TTL
      assigned = {}                                    # nodo -> bloques de ESTE archivo
      for cada bloque:
          eligible = nodos ALIVE con available_bytes >= block.size + RESERVED_BYTES
          si vacío: rollback, RESOURCE_EXHAUSTED
          min_count = min(assigned[nodo] for nodo in eligible)
          pool = [nodo in eligible if assigned[nodo] == min_count]
          nodo = max_score(pool)                       # empate: node_name
          UPDATE datanode SET reserved_bytes = ...     # ver comentario final de schema.sql
          si 0 filas: recalcular este bloque (máx. 3 intentos)
          insertar dfs_block y block_location (PLANNED, PRIMARY)
          assigned[nodo]++
    commit
    return plan ordenado con write_token por bloque
```

## 3. Flujo de subida y lectura (sección 4.1 corregida)

```text
SUBIDA
Cliente -> ControlNode : Login                        -> access_token
Cliente -> ControlNode : AllocateFile(path, size, operation_id)
ControlNode -> Cliente : plan ordenado (BlockPlan + write_token)
Cliente -> DataNodes   : WriteBlock, EN PARALELO (un stream por bloque, pool de hilos)
DataNode -> ControlNode: ReportBlockStored(checksum, size)   # ANTES de responder al Cliente
DataNode -> Cliente    : BlockWriteResponse(checksum, bytes)
Cliente -> ControlNode : CommitFile(file_id, operation_id)
ControlNode            : un reporte válido por bloque -> COMMITTED, si no missing_block_ids

LECTURA
Cliente -> ControlNode : GetFileManifest(path)         -> BlockLocation[] con read_token
Cliente -> DataNodes   : ReadBlock, EN PARALELO
Cliente                : verifica SHA-256 por bloque y escribe cada uno en su offset (FileChannel)
```

## 4. Matriz de comunicaciones (los cinco canales de la guía)

| Canal | Protocolo | Hito |
|---|---|---|
| Cliente → ControlNode | `ControlService` (gRPC unario) | 2 |
| Cliente → DataNode | `DataNodeService` `WriteBlock`/`ReadBlock` (gRPC streaming) | 2 |
| DataNode → ControlNode | `NodeRegistryService` (registro, heartbeat, `ReportBlockStored`) | 2 |
| ControlNode → DataNode | `DataNodeService.DeleteBlock`, disparado por un worker de RabbitMQ | 2 |
| DataNode ↔ DataNode | Pipeline de replicación con `WriteBlock` sobre `internal_endpoint` (ya reservado) | 3 |
| ControlNode ↔ ControlNode | Replicación de metadatos (PostgreSQL standby) y elección de líder | 3 |

**RabbitMQ (uso definido):** exchange `dfsha.events` (topic, durable) con dos colas de trabajo: `q.block.delete` (mensaje `block.delete.requested` por bloque cuando un archivo pasa a `DELETED`; el worker llama `DeleteBlock` con reintentos y una cola de mensajes fallidos `q.block.delete.dlq`) y `q.audit` (eventos `audit.#`). El job de expiración de archivos `PENDING` publica `file.pending.expired`, y su consumidor libera las reservas.

## 5. Criterios de aceptación ajustados

Cambian dos:

- **Criterio 1:** un archivo de 20 MiB produce 3 bloques (8, 8 y 4 MiB) en 3 DataNodes distintos.
- **Checksum:** el SHA-256 de cada bloque coincide entre DataNode y ControlNode, y el archivo reconstruido tiene el mismo SHA-256 que el original.

Se agregan:

- `AllocateFile` repetido con el mismo `operation_id` devuelve el mismo plan y no crea otro archivo.
- Un heartbeat no reduce `reserved_bytes`.
- `CommitFile` sin todos los `ReportBlockStored` devuelve `missing_block_ids` y el archivo sigue `PENDING`.
- Un DataNode rechaza un token firmado con otra clave.
- Un archivo de 0 bytes queda `COMMITTED` sin bloques.
- Tras `rm`, se puede subir de nuevo el mismo path.
- Un `PENDING` vencido se limpia y libera sus reservas.

## 6. Pendientes para el Hito 3 (ampliados)

Los del documento original (replicación, re-replicación, failover, lectura desde réplicas, cifrado en reposo, rebalanceo) más:

- **TLS en todos los canales gRPC** y mTLS entre nodos. Hoy `Login` y los tokens viajan sin cifrar; RNF6 lo exige.
- **Grupos y ACL** por archivo o directorio (hoy solo existe `owner_id`).
- Renovación del `access_token` (30 min pueden vencer en una subida larga).

## 7. Estado de validación

- El contrato `src/main/proto/dfsha.proto` ya está incluido y es la fuente de verdad para los stubs Java.
- `schema.sql` incluye reservas, idempotencia, directorios, estados de bloques y soporte de réplicas futuras.
- El Compose ya usa las rutas reales de esta carpeta, anclas YAML para los tres DataNodes y contextos de build compatibles con los Dockerfiles.
- Se agregó un bootstrap Java en memoria para registro de DataNodes, asignación, escritura, `ReportBlockStored` y `CommitFile`.
- **Pendiente de validar en el equipo:** `mvn -DskipTests package`, `docker compose config` y `docker compose up --build`. En este entorno no están disponibles Maven ni Docker y Bash no pudo ejecutarse por permisos del sistema.
- La persistencia JDBC, RabbitMQ, TLS/mTLS y firma Ed25519 real todavía deben conectarse al bootstrap antes de considerar terminado el Hito 2.
