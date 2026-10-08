# DFSha — Hito 2: diseño y comunicaciones

## 1. Propósito

Este documento cierra las decisiones técnicas necesarias para iniciar la implementación de DFSha en Java:

- tamaño y particionamiento de bloques;
- algoritmo de asignación de bloques a DataNodes;
- contratos gRPC;
- esquema de metadatos en PostgreSQL;
- autenticación y autorización mínima;
- logging estructurado y trazabilidad;
- despliegue local mediante Docker Compose.

El alcance de este hito no incluye la replicación definitiva de bloques, el failover automático del ControlNode ni el cifrado de bloques en reposo. Esas decisiones permanecen en el Hito 3.

## 2. Decisiones cerradas

| Tema | Decisión para el Hito 2 |
|---|---|
| Tamaño de bloque | 8 MiB por defecto, configurable mediante `DFS_BLOCK_SIZE_BYTES`. |
| Límite de bloque | Mínimo 1 MiB y máximo 64 MiB en esta entrega. |
| Particionamiento lógico | Lo decide el ControlNode. El Cliente únicamente ejecuta el plan recibido. |
| Particionamiento físico | El Cliente lee el archivo por offsets y envía cada bloque al DataNode asignado. |
| Asignación | Filtrado de nodos vivos y con espacio suficiente, seguido de una puntuación por espacio libre, carga y número de bloques. |
| Confirmación de escritura | El DataNode confirma al Cliente; el Cliente envía `CommitFile` al ControlNode. El ControlNode solo publica el archivo como completo después del commit. |
| Estado de archivo | `PENDING` mientras faltan bloques; `COMMITTED` cuando todos los bloques primarios fueron confirmados; `DELETED` después del borrado lógico. |
| Autenticación | Usuario y contraseña solo en `Login`; las demás llamadas usan token Bearer de corta duración. |
| Autorización | El ControlNode valida propietario y permisos del archivo. El DataNode valida un token de operación emitido por el ControlNode. |
| Logging | JSON estructurado con `request_id`, `operation_id`, `user_id`, `file_id`, `block_id` y `node_id` cuando aplique. |
| Despliegue | Un PostgreSQL, un RabbitMQ, un ControlNode y tres DataNodes en Docker Compose. |

### 2.1 Justificación del tamaño de bloque

HDFS usa normalmente bloques de mayor tamaño en despliegues productivos, pero DFSha se ejecutará con recursos limitados y se usará para pruebas académicas. Se fija inicialmente un bloque de 8 MiB porque reduce la cantidad de llamadas gRPC sin generar archivos de prueba demasiado grandes ni una presión excesiva sobre PostgreSQL.

El tamaño se mantiene configurable para comparar posteriormente 4, 8, 16 y 64 MiB. El tamaño efectivo de cada archivo se calcula así:

```text
number_of_blocks = ceil(file_size_bytes / block_size_bytes)
last_block_size  = file_size_bytes - (number_of_blocks - 1) * block_size_bytes
```

## 3. Asignación de bloques

### 3.1 Reglas de elegibilidad

Un DataNode puede recibir un bloque si cumple todas estas condiciones:

1. `status = ALIVE`.
2. `free_bytes >= block_size + RESERVED_BYTES`.
3. No está marcado como `DECOMMISSIONING`.
4. No existe ya una ubicación primaria para el mismo bloque.

En el Hito 2 no se crean réplicas. La tabla de ubicaciones queda preparada para agregarlas en el Hito 3.

### 3.2 Puntuación

Para cada nodo elegible se calcula:

```text
free_ratio       = free_bytes / capacity_bytes
load_ratio       = active_transfers / max(1, max_transfers)
block_ratio      = stored_blocks / max(1, target_blocks)

score = 0.60 * free_ratio
      + 0.25 * (1 - min(load_ratio, 1))
      + 0.15 * (1 - min(block_ratio, 1))
```

Se selecciona el nodo con mayor `score`. En caso de empate se usa `node_id` en orden lexicográfico para obtener un resultado determinista.

El ControlNode debe reservar espacio dentro de la misma transacción que genera el plan. Así se evita que dos clientes concurrentes asignen el mismo espacio libre. Si la reserva falla, se vuelve a calcular el plan con el estado actualizado.

### 3.3 Pseudocódigo

```text
allocate(file_size, user_id):
    require authenticated(user_id)
    blocks = split(file_size, configured_block_size)

    begin transaction
    candidates = alive_nodes_with_enough_space()
    for block in blocks:
        node = max_score(candidates)
        reserve(node, block.size)
        create_block(block, file_id, node.id)
        update_candidate_metrics(node, block.size)
    commit transaction

    return ordered plan(block_id, offset, size, node_id, write_token)
```

## 4. Contratos gRPC

El archivo fuente está en `src/main/proto/dfsha.proto`. La autenticación se envía mediante metadata HTTP/2 (`authorization: Bearer <token>`); no se envían contraseñas dentro de las operaciones de archivos.

```proto
syntax = "proto3";

package dfsha.v1;

option java_multiple_files = true;
option java_package = "co.eafit.dfsha.grpc.v1";

message Empty {}

message Status {
  bool ok = 1;
  string code = 2;
  string message = 3;
}

message LoginRequest {
  string username = 1;
  string password = 2;
}

message LoginResponse {
  string access_token = 1;
  int64 expires_at_epoch_seconds = 2;
  string user_id = 3;
}

message FileRef {
  string file_id = 1;
  string path = 2;
}

message AllocateFileRequest {
  string path = 1;
  int64 file_size_bytes = 2;
  string operation_id = 3;
}

message BlockPlan {
  string block_id = 1;
  int64 offset_bytes = 2;
  int64 size_bytes = 3;
  string datanode_id = 4;
  string datanode_endpoint = 5;
  string write_token = 6;
}

message AllocateFileResponse {
  FileRef file = 1;
  int64 block_size_bytes = 2;
  repeated BlockPlan blocks = 3;
}

message CommitFileRequest {
  string file_id = 1;
  string operation_id = 2;
}

message CommitFileResponse {
  Status status = 1;
  repeated string missing_block_ids = 2;
}

message FileManifest {
  FileRef file = 1;
  int64 file_size_bytes = 2;
  repeated BlockPlan blocks = 3;
}

message PathRequest {
  string path = 1;
}

message DirectoryEntry {
  string path = 1;
  bool directory = 2;
  int64 size_bytes = 3;
}

message ListResponse {
  repeated DirectoryEntry entries = 1;
}

message WriteBlockHeader {
  string block_id = 1;
  string file_id = 2;
  string write_token = 3;
  int64 expected_size_bytes = 4;
}

message BlockChunk {
  oneof payload {
    WriteBlockHeader header = 1;
    bytes data = 2;
  }
}

message BlockWriteResponse {
  Status status = 1;
  string block_id = 2;
  int64 bytes_written = 3;
  string checksum_sha256 = 4;
}

message RegisterDataNodeRequest {
  string node_name = 1;
  string advertised_endpoint = 2;
  string internal_endpoint = 3;
  int64 capacity_bytes = 4;
}

message RegisterDataNodeResponse {
  Status status = 1;
  string datanode_id = 2;
  string node_token = 3;
}

message ReportBlockStoredRequest {
  string datanode_id = 1;
  string node_token = 2;
  string block_id = 3;
  int64 bytes_written = 4;
  string checksum_sha256 = 5;
}

message ReadBlockRequest {
  string block_id = 1;
  string read_token = 2;
  int64 offset_bytes = 3;
  int64 length_bytes = 4;
}

message DataChunk {
  bytes data = 1;
  int64 offset_bytes = 2;
  bool last = 3;
}

message DeleteBlockRequest {
  string block_id = 1;
  string delete_token = 2;
}

message Heartbeat {
  string datanode_id = 1;
  string endpoint = 2;
  int64 capacity_bytes = 3;
  int64 free_bytes = 4;
  int32 active_transfers = 5;
  int64 stored_blocks = 6;
}

service ControlService {
  rpc Login(LoginRequest) returns (LoginResponse);
  rpc AllocateFile(AllocateFileRequest) returns (AllocateFileResponse);
  rpc CommitFile(CommitFileRequest) returns (CommitFileResponse);
  rpc GetFileManifest(FileRef) returns (FileManifest);
  rpc ListDirectory(PathRequest) returns (ListResponse);
  rpc CreateDirectory(PathRequest) returns (Status);
  rpc DeletePath(PathRequest) returns (Status);
}

service DataNodeService {
  rpc WriteBlock(stream BlockChunk) returns (BlockWriteResponse);
  rpc ReadBlock(ReadBlockRequest) returns (stream DataChunk);
  rpc DeleteBlock(DeleteBlockRequest) returns (Status);
}

service NodeRegistryService {
  rpc RegisterDataNode(RegisterDataNodeRequest) returns (RegisterDataNodeResponse);
  rpc PublishHeartbeat(Heartbeat) returns (Status);
  rpc ReportBlockStored(ReportBlockStoredRequest) returns (Status);
}
```

### 4.1 Flujo de subida

```text
Cliente -> ControlNode: Login
ControlNode -> Cliente: access_token
Cliente -> ControlNode: AllocateFile(path, size)
ControlNode -> Cliente: plan de bloques + write_token por bloque
Cliente -> DataNode: WriteBlock(header + chunks)
DataNode -> Cliente: checksum y bytes escritos
Cliente -> ControlNode: CommitFile(bloques completados)
ControlNode -> Cliente: éxito o lista de bloques faltantes
```

El archivo no aparece como completo hasta que `CommitFile` valida que todos los bloques esperados fueron confirmados y que sus tamaños coinciden con el plan.

### 4.2 Reglas de streaming

- El primer mensaje de `WriteBlock` debe ser el encabezado.
- Los mensajes siguientes contienen únicamente fragmentos de datos.
- El DataNode rechaza un bloque cuyo tamaño final no coincida con `expected_size_bytes`.
- El DataNode escribe primero a un archivo temporal y lo renombra atómicamente al finalizar.
- El checksum SHA-256 se calcula mientras llegan los fragmentos y se devuelve en el `BlockWriteResponse`.
- Los tokens de lectura/escritura duran 15 minutos y están vinculados a `file_id`, `block_id`, usuario y operación. El DataNode debe rechazarlos después de su expiración.

## 5. Esquema PostgreSQL

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE node_status AS ENUM ('ALIVE', 'SUSPECT', 'DEAD', 'DECOMMISSIONING');
CREATE TYPE file_status AS ENUM ('PENDING', 'COMMITTED', 'DELETED');
CREATE TYPE block_location_role AS ENUM ('PRIMARY', 'REPLICA');

CREATE TABLE app_user (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE datanode (
    datanode_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    node_name VARCHAR(120) NOT NULL UNIQUE,
    endpoint VARCHAR(255) NOT NULL,
    status node_status NOT NULL DEFAULT 'ALIVE',
    capacity_bytes BIGINT NOT NULL CHECK (capacity_bytes >= 0),
    free_bytes BIGINT NOT NULL CHECK (free_bytes >= 0),
    active_transfers INTEGER NOT NULL DEFAULT 0 CHECK (active_transfers >= 0),
    stored_blocks BIGINT NOT NULL DEFAULT 0 CHECK (stored_blocks >= 0),
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dfs_file (
    file_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_user(user_id),
    path TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    block_size_bytes INTEGER NOT NULL CHECK (block_size_bytes > 0),
    status file_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_id, path)
);

CREATE TABLE dfs_block (
    block_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID NOT NULL REFERENCES dfs_file(file_id),
    block_index INTEGER NOT NULL CHECK (block_index >= 0),
    offset_bytes BIGINT NOT NULL CHECK (offset_bytes >= 0),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    checksum_sha256 CHAR(64),
    UNIQUE (file_id, block_index),
    UNIQUE (file_id, offset_bytes)
);

CREATE TABLE block_location (
    block_id UUID NOT NULL REFERENCES dfs_block(block_id),
    datanode_id UUID NOT NULL REFERENCES datanode(datanode_id),
    role block_location_role NOT NULL DEFAULT 'PRIMARY',
    state VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    written_at TIMESTAMPTZ,
    PRIMARY KEY (block_id, datanode_id),
    UNIQUE (block_id, role)
);

CREATE TABLE file_lock (
    file_id UUID PRIMARY KEY REFERENCES dfs_file(file_id),
    owner_id UUID NOT NULL REFERENCES app_user(user_id),
    lock_token UUID NOT NULL UNIQUE,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_file_owner_path ON dfs_file(owner_id, path);
CREATE INDEX idx_block_file_index ON dfs_block(file_id, block_index);
CREATE INDEX idx_location_node ON block_location(datanode_id);
CREATE INDEX idx_datanode_status_heartbeat ON datanode(status, last_heartbeat_at);
```

### 5.1 Transiciones de estado

```text
File:  PENDING -> COMMITTED -> DELETED
Block: PLANNED -> WRITTEN
Node:  ALIVE -> SUSPECT -> DEAD
                 \-> ALIVE cuando vuelve a publicar heartbeats válidos
```

El borrado es lógico en PostgreSQL. La eliminación física del bloque puede ejecutarse después, pero debe ser idempotente: borrar un bloque que ya no existe debe producir una respuesta exitosa.

## 6. Autenticación y autorización

1. `Login` recibe usuario y contraseña.
2. El ControlNode compara la contraseña con un hash Argon2id o BCrypt; nunca almacena contraseñas en texto plano.
3. Se genera un token firmado con expiración de 30 minutos.
4. Un interceptor gRPC valida el token en cada llamada protegida.
5. Para transferencias, el ControlNode genera un `write_token` o `read_token` vinculado a usuario, archivo, bloque, operación y fecha de expiración.
6. El DataNode valida el token antes de aceptar o entregar bytes.

Los tokens de operación impiden que un Cliente autenticado use arbitrariamente un `block_id` de otro usuario. En esta entrega se permite que el ControlNode sea el emisor; la clave de firma se inyecta como secreto Docker y no se registra en logs.

## 7. Logging y trazabilidad

Se usará SLF4J + Logback con salida JSON. Cada operación debe conservar el mismo `request_id` en Cliente, ControlNode y DataNode.

Ejemplo:

```json
{
  "timestamp": "2026-09-21T15:30:42.125Z",
  "level": "INFO",
  "service": "controlnode",
  "event": "BLOCK_PLAN_CREATED",
  "request_id": "req-7f0e",
  "operation_id": "op-91aa",
  "user_id": "2f6b...",
  "file_id": "9a10...",
  "block_id": "b001...",
  "datanode_id": "dn-01",
  "size_bytes": 8388608
}
```

Eventos mínimos:

| Servicio | Eventos |
|---|---|
| Cliente | `LOGIN`, `ALLOCATE_REQUEST`, `BLOCK_UPLOAD_STARTED`, `BLOCK_UPLOAD_FINISHED`, `COMMIT_REQUEST` |
| ControlNode | `AUTH_SUCCESS`, `BLOCK_PLAN_CREATED`, `FILE_COMMITTED`, `FILE_DELETED`, `NODE_STATUS_CHANGED` |
| DataNode | `BLOCK_WRITE_STARTED`, `BLOCK_WRITE_FINISHED`, `BLOCK_READ`, `BLOCK_DELETE`, `HEARTBEAT_SENT` |

No se deben registrar contraseñas, tokens, contenido de archivos ni secretos. RabbitMQ puede transportar eventos de auditoría, pero los logs locales siguen siendo la evidencia primaria de la demo.

## 8. Despliegue Docker Compose

Servicios mínimos:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: dfsha
      POSTGRES_USER: dfsha
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./db/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dfsha -d dfsha"]
      interval: 5s
      timeout: 3s
      retries: 10

  rabbitmq:
    image: rabbitmq:3-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: dfsha
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}
    ports:
      - "5672:5672"
      - "15672:15672"

  controlnode:
    build: ./controlnode
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/dfsha
      RABBITMQ_HOST: rabbitmq
      DFS_BLOCK_SIZE_BYTES: 8388608
      TOKEN_SECRET: ${TOKEN_SECRET}
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_started
    ports:
      - "50051:50051"

  datanode1:
    build: ./datanode
    environment:
      NODE_NAME: datanode-1
      CONTROLNODE_ENDPOINT: controlnode:50051
      STORAGE_PATH: /data/blocks
    volumes:
      - datanode1_data:/data/blocks
    depends_on:
      - controlnode

  datanode2:
    extends: datanode1
    environment:
      NODE_NAME: datanode-2
    volumes:
      - datanode2_data:/data/blocks

  datanode3:
    extends: datanode1
    environment:
      NODE_NAME: datanode-3
    volumes:
      - datanode3_data:/data/blocks

volumes:
  postgres_data:
  datanode1_data:
  datanode2_data:
  datanode3_data:
```

Para la primera implementación, el Cliente puede ejecutarse desde el host y conectarse a `localhost:50051`. Los DataNodes deben anunciar al ControlNode un endpoint accesible por el Cliente; dentro de Docker no se debe devolver únicamente el nombre interno del contenedor si el Cliente está fuera de la red Docker.

## 9. Criterios de aceptación

- Un archivo de 20 MiB produce tres bloques con el tamaño configurado de 8 MiB.
- `AllocateFile` devuelve bloques ordenados por `block_index`, con offsets sin traslape.
- Dos asignaciones concurrentes no reservan más espacio del disponible en un DataNode.
- Un DataNode rechaza un token de escritura expirado o perteneciente a otro bloque.
- Un `CommitFile` incompleto no cambia el archivo a `COMMITTED`.
- Un `CommitFile` repetido es idempotente.
- El checksum calculado por el DataNode coincide con el checksum del archivo reconstruido.
- Un archivo solo aparece en `ListDirectory` cuando está `COMMITTED`.
- Un heartbeat actualiza `free_bytes`, `active_transfers` y `last_heartbeat_at`.
- Los logs de una subida pueden correlacionarse usando `request_id` y `operation_id`.
- `docker compose up` inicia PostgreSQL, RabbitMQ, ControlNode y tres DataNodes.

## 10. Plan de implementación Java

1. Crear el proyecto Maven/Gradle y generar stubs desde `dfsha.proto`.
2. Crear la migración PostgreSQL y repositorios para usuarios, archivos, bloques y nodos.
3. Implementar `Login` y el interceptor Bearer.
4. Implementar `AllocateFile` con reserva transaccional y la heurística de puntuación.
5. Implementar `WriteBlock` y `ReadBlock` con archivos temporales, checksum y tokens de operación.
6. Implementar `CommitFile` y reconstrucción del archivo en el Cliente.
7. Implementar heartbeats y detección de nodos sospechosos.
8. Agregar logging JSON y propagación de `request_id`.
9. Ejecutar los criterios de aceptación mediante pruebas unitarias, integración con Testcontainers y una prueba completa con Docker Compose.

## 11. Pendientes explícitos para el Hito 3

- Replicación de bloques y factor de replicación.
- Re-replicación después de caída de un DataNode.
- Failover del ControlNode y promoción de PostgreSQL standby.
- Selección de réplicas durante lectura.
- Cifrado de bloques en reposo.
- Rebalanceo y decommission de DataNodes.
