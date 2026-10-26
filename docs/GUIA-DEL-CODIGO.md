# DFSha: guía del código

Esta guía sirve para entender, explicar y modificar todo el proyecto. Se lee en este orden:
el mapa (sección 1), los flujos (2), los archivos (3), cómo cambiar cosas (4), las decisiones de
diseño (5) y las preguntas típicas de la sustentación (6).

Cada capacidad tiene un punto de implementación. El reparto de abajo indica responsables de revisión
y explicación, no autoría exclusiva ni permiso para crear versiones paralelas por fase. Los comentarios
dicen el *porqué*; esta guía dice *dónde*. El inventario de tareas y evidencia vive únicamente en
[ESTADO-DEL-PROYECTO.md](ESTADO-DEL-PROYECTO.md); los comandos de prueba, en [PRUEBAS.md](PRUEBAS.md).

---

## 1. El mapa en una página

```text
                        ┌──────────────────────────────┐
   Cliente (CLI)  ──────┤  ControlNode 1   ControlNode 2│   gRPC + TLS, token de sesión
   ClientMain           │  (iguales, sin estado propio) │   (si uno se cae, el cliente usa el otro)
   DfsClient            └───────┬──────────────┬────────┘
       │                        │ SQL          │ RabbitMQ (dfsha.events)
       │                ┌───────▼──────┐  ┌────▼─────┐
       │                │ PostgreSQL   │  │ RabbitMQ │  heartbeats, réplicas, borrados
       │                │ primario ──► │  └────┬─────┘
       │                │ standby      │       │
       │                └──────────────┘       │
       │  WriteBlock / ReadBlock (gRPC + TLS,  │
       │  token firmado por bloque)            │
   ┌───▼───────┬───────────┬───────────┬───────▼───┐
   │ DataNode1 │ DataNode2 │ DataNode3 │ DataNode4 │   bloques cifrados con AES-256-GCM
   └───────────┴─────┬─────┴───────────┴───────────┘
                     └── réplicas: un DataNode copia de otro con ReadBlock
```

Hay tres ideas clave:

1. **El ControlNode solo maneja metadatos** (quién es quién, dónde está cada bloque). Los datos
   van directo entre el cliente y los DataNodes.
2. **El ControlNode no guarda nada en memoria.** Todo vive en PostgreSQL, por eso pueden correr
   dos a la vez y si uno se cae el otro sigue igual.
3. **Lo asíncrono va por RabbitMQ**: heartbeats, "copia este bloque" y "borra este bloque".
   Lo que el usuario espera va por gRPC.

### Quién explica qué

| Integrante | Archivos |
|---|---|
| **Martín** (comunicaciones) | `dfsha-proto/.../dfsha.proto`, todo `dfsha-common` (menos `Tokens`), `datanode/ControlLink`, las secciones 1 y 2 de `control/ClusterMonitor` (heartbeats y estados), todo `deploy/` |
| **Camilo** (ControlNode) | `deploy/schema.sql`, `common/Tokens`, todo `dfsha-controlnode` (en `ClusterMonitor`, la sección 3: réplicas) |
| **Mendel** (cliente y DataNode) | `client/ClientMain`, `client/DfsClient`, `datanode/BlockStore`, `datanode/DataNodeServiceImpl`, `datanode/Replicator`, `datanode/DataNodeMain` |

### Archivos de la implementación

```text
dfsha-proto/src/main/proto/dfsha.proto      el contrato: mensajes y servicios gRPC
dfsha-common/                               lo que usan todos
  Env.java          variables de entorno y secretos (_FILE)
  Rpc.java          servidores/canales gRPC con TLS, token Bearer, failover, errores
  Tokens.java       tokens firmados con Ed25519 (sesión y bloques)
  Rabbit.java       conexión, colas y publicar/consumir en RabbitMQ
  DfsPaths.java     rutas tipo Linux: resolve("/a", "../b") = "/b"
  Hash.java         SHA-256
dfsha-controlnode/                          servidor de metadatos
  ControlNodeMain.java     arma todo y arranca
  ControlServiceImpl.java  lo que pide el cliente: login, ls, mkdir, rm, put, get, lock
  NodeRegistryImpl.java    lo que dicen los DataNodes: registro y "ya guardé el bloque"
  ClusterMonitor.java      tareas de fondo: heartbeats, ALIVE/SUSPECT/DEAD, réplicas
  Placement.java           en qué DataNode va cada bloque (heurística 60/25/15)
  CapacityReservations.java candado común para reservas primarias y de réplicas
  Db.java                  pool de PostgreSQL y ayudas para SQL
  Passwords.java           hash de contraseñas con PBKDF2
dfsha-datanode/                             servidor de bloques
  DataNodeMain.java        arma todo y arranca
  DataNodeServiceImpl.java WriteBlock / ReadBlock / DeleteBlock con validación de tokens
  BlockStore.java          disco: guardar y leer bloques cifrados
  ControlLink.java         registro, heartbeat, ReportBlockStored, colas de RabbitMQ
  Replicator.java          copiar una réplica desde otro DataNode
dfsha-client/                               consola del usuario
  ClientMain.java          lee comandos y llama a DfsClient
  DfsClient.java           la API: put/get en paralelo, failover, read parcial, lock
deploy/                                     Docker, esquema, secretos y pruebas
```

---

## 2. Los flujos, paso a paso

Cada paso dice `archivo → método`. Si el profe pregunta "¿dónde pasa X?", la respuesta está aquí.

### 2.1 Arranque

1. **ControlNode** (`ControlNodeMain.main`): abre el pool de PostgreSQL (`Db.fromEnv`), carga las
   claves (`Tokens.fromEnv`), se conecta a RabbitMQ (`Rabbit.connect`) y arranca el servidor gRPC
   con el interceptor de autenticación (`Rpc.requireToken`). Luego lanza las tareas de fondo
   (`ClusterMonitor.start`).
2. **DataNode** (`DataNodeMain.main`):
   1. Primero arranca su servidor gRPC.
   2. Luego se registra con la clave del clúster (`ControlLink.register` → `NodeRegistryImpl.registerDataNode`),
      que le devuelve un `datanode_id` y un `node_token`.
   3. Al final declara sus colas y empieza los heartbeats (`ControlLink.start`).

### 2.2 Usuario: `register` y `login`

1. `ClientMain` (caso `"login"`) llama a `DfsClient.login`, que hace el rpc `Login`.
2. `ControlServiceImpl.login` busca el usuario y compara la contraseña con `Passwords.matches`.
   Si coincide, emite un token `SESSION` firmado con `Tokens.issue`.
3. Desde ahí, `Rpc.bearerToken` agrega `authorization: Bearer <token>` a cada llamada, y en el
   servidor `Rpc.requireToken` lo verifica y deja el `user_id` en `Rpc.USER_ID`.
   `ControlServiceImpl.caller()` lo lee: así cada usuario solo ve sus archivos.

### 2.3 Subir un archivo: `put` (RF2)

```text
Cliente                         ControlNode                       DataNode
  │ AllocateFile(path, size, op_id) ──►│ crea dfs_file PENDING,
  │                                    │ parte en bloques, elige nodos
  │◄── plan: bloque → DataNode + token WRITE
  │ WriteBlock (en paralelo, un stream por bloque) ─────────────────►│ valida token, SHA-256,
  │                                    │◄── ReportBlockStored ─────────│ guarda cifrado
  │◄──────────────────────────────── respuesta con el SHA-256 ────────│
  │ CommitFile ───────────────────────►│ ¿todos los bloques WRITTEN? → COMMITTED
```

1. `DfsClient.put` genera un `operation_id` y llama `AllocateFile`.
2. `ControlServiceImpl.allocateFile`, todo en una transacción:
   1. Si ese `operation_id` ya existe, devuelve el mismo plan (idempotencia).
   2. Valida que el padre exista y que el nombre esté libre.
   3. Descarta una subida anterior que quedó a medias en ese mismo path.
   4. Toma el candado `pg_advisory_xact_lock(1)` para que dos subidas no cuenten el mismo espacio.
   5. Crea el archivo, parte en bloques de `BLOCK_SIZE_BYTES` (20 MiB = 8 + 8 + 4) y por cada
      bloque elige nodo con `Placement.choosePrimary`. Crea `dfs_block` y una `block_location`
      PRIMARY/PLANNED.
   6. `uploadPlan` arma la respuesta con un token `WRITE` por bloque.
3. `DfsClient.writeBlock`, un hilo por bloque (`runInParallel`):
   1. Lee el pedazo del archivo y calcula su SHA-256.
   2. Lo manda con `sendBlock`: un header y luego trozos de 1 MiB.
   3. Compara el SHA-256 que devuelve el DataNode y reintenta hasta 3 veces.
4. `DataNodeServiceImpl.writeBlock`:
   1. Valida que el token sea `WRITE` y de **ese** bloque.
   2. Acumula los bytes y verifica el tamaño.
   3. Calcula el SHA-256 y guarda con `BlockStore.save`, que cifra y renombra atómicamente.
   4. **Antes de responder**, avisa al ControlNode (`ControlLink.reportStored`).
5. `NodeRegistryImpl.reportBlockStored` verifica el `node_token`, el tamaño y el checksum, y marca
   la ubicación como `WRITTEN`.
6. `DfsClient.put` llama `CommitFile`. En `ControlServiceImpl.commitFile`, si a algún bloque le
   falta una copia `WRITTEN`, responde `missing_block_ids` y el cliente reenvía solo esos.
   Si no falta ninguno, el archivo pasa a `COMMITTED` y ya aparece en `ls`.

### 2.4 Réplicas (después del commit, sin que el usuario espere)

1. Cada 5 s corre `ClusterMonitor.replicateMissingCopies`, en un solo ControlNode a la vez
   (`pg_try_advisory_xact_lock(2)`). `planReplication` adquiere además `CapacityReservations`
   antes de consultar capacidad: es el mismo candado que usa AllocateFile, no otra política de reservas.
   1. Borra las réplicas PLANNED de hace más de 60 s, para reintentarlas.
   2. Busca los bloques de archivos COMMITTED con menos de `REPLICATION_FACTOR` copias. Cuentan
      las PLANNED y las WRITTEN en nodos que no estén DEAD.
   3. Por cada copia que falta: elige un origen (una copia WRITTEN en un nodo ALIVE) y un destino
      con `Placement.chooseReplicas`, que nunca repite nodo. Crea la `block_location` REPLICA/PLANNED
      y publica una `ReplicationTask` en la cola `replication.<destino>`.
2. El DataNode destino la recibe (`ControlLink.start` → `Replicator.copy`):
   1. Pide el bloque al origen con `ReadBlock`, usando el token de la tarea.
   2. Verifica el SHA-256, lo guarda y hace `ReportBlockStored`. La copia queda `WRITTEN`.
   3. Si algo falla, lanza una excepción: el mensaje va a `dfsha.replication.failed` y el escaneo
      lo vuelve a pedir.

> El **mismo escaneo** crea las réplicas de un archivo nuevo **y** repone las de un nodo caído.
> Es un solo mecanismo y no dos.

### 2.5 Un DataNode se cae

1. `ControlLink.sendHeartbeat` publica cada 5 s en `heartbeat.<id>`, y
   `ClusterMonitor.onHeartbeat` lo recibe y deja el nodo `ALIVE`.
2. Si el nodo deja de latir, `ClusterMonitor.updateNodeStatus` (cada 5 s) lo pasa a `SUSPECT`
   a los 15 s y a `DEAD` a los 30 s.
3. Sus copias dejan de contar como vivas, así que el escaneo de réplicas (2.4) crea copias nuevas
   en otros nodos.
4. Mientras tanto, el cliente lee de otra réplica (2.6).
5. Si el nodo vuelve, se registra de nuevo con el mismo nombre y conserva su id y sus bloques.
   Algunos bloques quedan con 4 copias, lo cual no hace daño.

### 2.6 Bajar un archivo: `get`, con failover

1. `DfsClient.get` pide `GetFileManifest`.
2. `ControlServiceImpl.getFileManifest` devuelve, por bloque, **todas** las copias WRITTEN en
   nodos que no estén DEAD (primero las ALIVE y menos cargadas), con un token `READ`.
3. `DfsClient.readWithFailover`, en paralelo por bloque:
   1. Prueba la primera copia; si no responde, da error o el SHA-256 no coincide, pasa a la
      siguiente y deja en el log qué réplica sirvió.
   2. Cada bloque se escribe en su offset con `FileChannel.write(buffer, posición)`. Al final se
      renombra `.part` al archivo final.

### 2.7 `cat` (lectura parcial, RF3), `rm`, `rmdir`, `lock`

- **`cat ruta offset bytes`**: `DfsClient.read` pide solo los pedazos de bloque que tocan el
  rango. Usa `ReadBlock` con `offset_bytes` y `length_bytes`, y `DataNodeServiceImpl.readBlock`
  manda solo ese rango.
- **`rm`**: `ControlServiceImpl.deletePath` hace tres cosas.
  1. Rechaza si hay un lock vigente.
  2. Marca el archivo `DELETED` y sus ubicaciones `DELETED`.
  3. Publica un `BlockDeleteTask` por copia en `delete.<nodo>`.

  Cada DataNode borra lo suyo (`ControlLink.start`). Si un nodo estaba caído, su cola es durable y
  lo borra al volver.
- **`rmdir`**: mismo rpc. Si la ruta es un directorio vacío, `deleteEmptyDirectory` lo marca
  borrado.
- **`lock` / `unlock`**: `ControlServiceImpl.acquireLock` hace un solo `INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at < now()`.
  Si hay un lock vigente no vuelve ninguna fila y se rechaza. El lock vence solo (60 s por
  defecto). `releaseLock` lo borra con el `lock_id`.

### 2.8 Se cae un ControlNode o el PostgreSQL primario

- **ControlNode**: `Rpc.Failover.call` recibe `UNAVAILABLE` y repite la llamada en el siguiente
  endpoint de `CONTROLNODE_ENDPOINTS`. Lo usan el cliente y los DataNodes.
- **PostgreSQL**: `postgres-standby` recibe todos los cambios por streaming replication
  (`deploy/postgres/standby.sh`). Si el primario muere, `bash deploy/promote-standby.sh` lo
  promueve. Los ControlNodes no se reinician: `DB_URL` tiene los dos hosts con
  `targetServerType=primary` y el driver encuentra solo al nuevo primario.

---

## 3. Archivo por archivo

### `dfsha.proto` (Martín)

Es el contrato. Tiene 3 servicios:

- `ControlService`: cliente → ControlNode.
- `DataNodeService`: cliente o DataNode → DataNode.
- `NodeRegistryService`: DataNode → ControlNode.

`Heartbeat`, `ReplicationTask` y `BlockDeleteTask` no son rpc: se serializan con
`toByteArray()` y viajan por RabbitMQ. La versión 1.1 solo agrega cosas, sin renumerar nada.
Maven genera las clases Java en `co.eafit.dfsha.grpc.v1` (`ControlServiceGrpc`, `BlockPlan`…).

### `dfsha-common` (Martín; `Tokens`: Camilo)

- **`Env`**: `get`, `getInt`, `getLong` y `getList` leen variables de entorno. `secret(NAME)` lee
  primero el archivo `NAME_FILE` (así llegan los Docker secrets) y si no, la variable `NAME`.
- **`Rpc`**:
  - `server`: TLS si hay `TLS_CERT_FILE` y `TLS_KEY_FILE`.
  - `channel`: TLS si hay `TLS_CA_FILE`, o `deploy/secrets/ca.crt` si existe; `none` lo apaga.
  - `requireToken`: interceptor del servidor.
  - `bearerToken`: interceptor del cliente.
  - `Failover`: lista de endpoints que se prueban en orden.
  - `respond`: ejecuta un rpc y convierte cualquier excepción en un error gRPC. `23505` (UNIQUE de
    PostgreSQL) pasa a ser `ALREADY_EXISTS`.
  - `error` y `ok`: atajos.
- **`Tokens`**: formato `base64url("TIPO|sujeto|expira").base64url(firma Ed25519)`.
  - `issue` firma, y solo el ControlNode tiene la clave privada.
  - `verify` devuelve el sujeto si la firma es válida, el tipo coincide y no venció. Si no,
    devuelve `null`.
  - Tipos: `SESSION` (sujeto = user_id), y `WRITE`, `READ` y `DELETE` (sujeto = block_id).
- **`Rabbit`**:
  - El constructor declara el exchange `dfsha.events` (topic), el de fallidos y la cola de
    heartbeats con TTL de 30 s.
  - `declareNodeQueues(id)` crea las dos colas de un DataNode con dead-letter.
  - `publish(key, bytes, persistente)` publica y espera la confirmación de RabbitMQ.
  - `consume(cola, handler)` hace ack si el handler termina bien y nack si falla.
- **`DfsPaths`**: `resolve(cwd, entrada)` normaliza `.`, `..` y `//`. También `parent` y `name`.
- **`Hash`**: SHA-256 en hexadecimal de bytes o de un archivo.

### `dfsha-controlnode` (Camilo; heartbeats y estados: Martín)

- **`ControlNodeMain`**: crea todo, aplica el interceptor solo a `ControlService` (menos `Register`
  y `Login`) y arranca. `NodeRegistryService` no lleva token de usuario: los DataNodes usan su
  `node_token`.
- **`Db`**: `tx(c -> ...)` hace commit si todo sale bien y rollback si algo lanza una excepción.
  `query`, `one` y `update` llenan los `?` en orden. Un multi-host en `DB_URL` da el failover de
  PostgreSQL.
- **`ControlServiceImpl`**: un método por rpc, cada uno con su SQL en una transacción. Están
  agrupados en secciones: usuarios, namespace, subida, descarga, lock y ayudas.
- **`NodeRegistryImpl`**:
  - `registerDataNode` compara la clave del clúster en tiempo constante, hace upsert por
    `node_name` y genera un `node_token` nuevo (en la base solo queda su SHA-256).
  - `reportBlockStored` marca WRITTEN. La primera copia fija el checksum y las réplicas deben
    coincidir con él.
- **`ClusterMonitor`**: un consumidor y dos tareas periódicas.
  - Sección 1, `onHeartbeat` (consumidor de RabbitMQ).
  - Sección 2, `updateNodeStatus`: `UPDATE ... RETURNING node_name` para loguear los cambios de
    estado.
  - Sección 3: `planReplication` calcula y reserva en PostgreSQL; `replicateMissingCopies`
    publica las tareas resultantes. No hay un segundo planificador en el DataNode.
- **`CapacityReservations`**: único acceso al candado de capacidad, compartido por AllocateFile
  y el planificador de réplicas. El candado 2 solo elige al escáner; no sustituye este candado.
- **`Placement`**:
  - `aliveNodes` calcula el espacio disponible como capacidad menos los bloques PLANNED y WRITTEN
    ya asignados.
  - `choosePrimary` usa rondas y puntaje.
  - `chooseReplicas` toma los de mayor puntaje sin repetir nodo.
  - `score` = 0.60 espacio + 0.25 carga + 0.15 bloques.
- **`Passwords`**: PBKDF2-HMAC-SHA256 con 100 000 iteraciones y sal aleatoria; se guarda como
  `iteraciones:sal:hash`.

### `dfsha-datanode` (Mendel; `ControlLink`: Martín)

- **`DataNodeMain`**: el orden importa. Primero el servidor, luego el registro y al final las colas
  y los heartbeats.
- **`DataNodeServiceImpl`**:
  - `writeBlock` es un `StreamObserver` con tres eventos: `onNext` recibe el header y los datos,
    `onCompleted` guarda y reporta, y `onError` descarta.
  - `readBlock` devuelve el rango pedido en trozos de 1 MiB.
  - `deleteBlock` exige un token DELETE.
  - Los tres validan el token con `tokens.verify(token, TIPO)` y comparan contra el `block_id`.
- **`BlockStore`**: AES-256-GCM. El archivo guarda el IV (12 bytes) seguido del cifrado y la
  etiqueta. El `block_id` va como dato asociado y se escribe `.tmp` con `SYNC` antes de renombrar
  (`ATOMIC_MOVE`). `save` valida el UUID con `file()` antes de cifrar o crear el temporal, igual
  que las otras operaciones de almacenamiento.
- **`ControlLink`**: `register` reintenta cada 3 s. `start` consume las colas, `sendHeartbeat`
  publica la capacidad, lo libre, las transferencias en curso y los bloques, y `reportStored`
  avisa cada bloque guardado.
- **`Replicator`**: `copy(task)` hace ReadBlock contra el origen, verifica, guarda y reporta.

### `dfsha-client` (Mendel)

- **`ClientMain`**: un `switch` con un caso por comando. Los errores gRPC se muestran como
  `Error: CÓDIGO - descripción`, y `myLocks` guarda los `lock_id` de la sesión.
- **`DfsClient`**: `control(...)` llama al ControlNode vivo con un plazo de 30 s. Ver los flujos
  2.3, 2.6 y 2.7.

### `deploy/` (Martín; `schema.sql`: Camilo)

| Archivo | Para qué |
|---|---|
| `schema.sql` | Tablas. La clave es `block_location`: una fila por (bloque, nodo) con su rol y estado. |
| `docker-compose.yml` | PostgreSQL primario y standby, RabbitMQ, 2 ControlNodes y 4 DataNodes. TLS configurado en gRPC, no en AMQP/SQL. |
| `Dockerfile` | Compila con Maven y deja una imagen con los 3 jars. |
| `gen-secrets.sh` | Contraseñas, clave AES, claves Ed25519, CA y certificado TLS. |
| `postgres/init-replication.sh` | Crea el usuario `replicator` en el primario. |
| `postgres/standby.sh` | Clona el primario con `pg_basebackup -R` y queda replicando. |
| `promote-standby.sh` | Failover de PostgreSQL. |
| `escenarios.sh` | Los 7 escenarios de la Fase 3; deja la tabla en `resultados-escenarios.md`. |

---

## 4. Cómo cambiar cosas

| Quiero… | Dónde | Qué hago |
|---|---|---|
| Cambiar el tamaño de bloque | `deploy/.env` | `BLOCK_SIZE_BYTES=16777216` y `docker compose up -d`. Solo afecta archivos nuevos. |
| Cambiar el factor de replicación | `deploy/.env` | `REPLICATION_FACTOR=2`. El escaneo ajusta solo los bloques que tengan menos copias. |
| Cambiar los tiempos de SUSPECT y DEAD | `docker-compose.yml`, en `x-controlnode` | `SUSPECT_AFTER_SECONDS` y `DEAD_AFTER_SECONDS`. El intervalo del heartbeat es `HEARTBEAT_INTERVAL_SECONDS` en los DataNodes. |
| Agregar un 5.º DataNode | `docker-compose.yml` | Copiar el bloque de `datanode4` con nombre, puerto y volumen nuevos. Agregar `DNS:datanode5` al SAN en `gen-secrets.sh` y regenerar los certificados (borrar `secrets/ca.crt`). |
| Cambiar la heurística | `Placement.score` | Cambiar los pesos 0.60/0.25/0.15. `PlacementTest` dice si se rompió algo. |
| Agregar un comando al cliente | `ClientMain` | Un `case "nombre" -> { ... }` que llame a un método nuevo de `DfsClient`. Agregarlo a `HELP`. |
| Agregar un rpc nuevo | 4 lugares | (1) Mensaje y `rpc` en `dfsha.proto`. (2) `@Override` en `ControlServiceImpl` con `Rpc.respond(out, () -> { ... })`. (3) Método en `DfsClient` con `control(s -> s.miRpc(...))`. (4) Comando en `ClientMain`. |
| Agregar una columna | `schema.sql` | Editar y recrear: `docker compose down -v && docker compose up -d` (borra los datos). |
| Desplegar en AWS o GCP | `.env` y `gen-secrets.sh` | `ADVERTISE_HOST=<IP pública>`. Generar certificados con `EXTRA_SAN="IP:<IP pública>" bash gen-secrets.sh` y abrir los puertos 50051-50052 y 50061-50064 en el security group. |
| Ver qué pasa adentro | terminal | `docker compose logs -f controlnode1 datanode2`. Consola de RabbitMQ en http://localhost:15672. SQL con `docker compose exec postgres psql -U dfsha -d dfsha`. |
| Probar sin TLS | cliente | `TLS_CA_FILE=none`. Sin certificados en el servidor (`TLS_CERT_FILE` vacío), el servidor queda en texto plano. |

Consultas útiles en `psql`:

```sql
-- estado de los nodos
SELECT node_name, status, now() - last_heartbeat_at AS sin_latir FROM datanode ORDER BY 1;
-- copias de cada bloque
SELECT f.path, b.block_index, d.node_name, l.role, l.state
FROM block_location l JOIN dfs_block b USING (block_id) JOIN dfs_file f USING (file_id)
JOIN datanode d USING (datanode_id) WHERE f.status = 'COMMITTED' ORDER BY 1, 2, 3;
```

---

## 5. Decisiones de diseño (insumo del informe, secciones 7.2 y 7.3)

| Tema | Decisión | Por qué |
|---|---|---|
| Arquitectura | Maestro–trabajador estilo HDFS: ControlNode (metadatos) y DataNodes (bloques). | Separa control de datos: el ControlNode no es cuello de botella para los bytes. |
| Comunicación | gRPC para lo síncrono (streaming de bloques) y RabbitMQ para lo asíncrono. | El usuario no espera las réplicas ni el borrado físico. Las colas durables sobreviven a nodos caídos. |
| Particionamiento | Bloques de tamaño fijo (8 MiB), uno por nodo por ronda y luego puntaje 60/25/15. | Un archivo queda repartido en varios nodos (RNF4) y se balancean espacio, carga y cantidad. |
| Escritura | La copia primaria es síncrona: con su `ReportBlockStored` el commit ya puede responder. | La latencia de subida no depende del factor de replicación. |
| Réplicas | Asíncronas, por evento. Factor configurable (3). Nunca dos copias en el mismo nodo (llave primaria `(block_id, datanode_id)`). | Es lo que hace HDFS. Con 4 nodos y factor 3 se puede reponer una copia si cae un nodo. |
| Copia de una réplica | El destino jala el bloque del origen con `ReadBlock`. | Reutiliza el rpc del cliente; no hay rpc nuevo. |
| Reposición | El mismo escaneo periódico (5 s) sirve para archivos nuevos y para nodos caídos. | Un solo mecanismo es más simple y más fácil de probar. |
| Consistencia | Replicación eventual: entre el commit y la última réplica hay una ventana. Solo se leen copias WRITTEN. | La API del cliente es WORM; falta proteger y probar la inmutabilidad del almacenamiento ante reenvíos distintos. |
| Metadatos | Transacciones de PostgreSQL y `CapacityReservations` común a primarias y réplicas. | Las reservas se serializan; el lock de elección del escáner tiene una función distinta. |
| HA del ControlNode | Dos ControlNodes activos y sin estado. Failover en el cliente y en los DataNodes. | No hace falta elegir líder: el estado está en PostgreSQL. |
| HA de PostgreSQL | Primario y standby con streaming replication asíncrona y promoción manual asistida. | Puede haber cambios recientes aún no replicados: no se garantiza pérdida cero. La promoción automática no está implementada. |
| Nodo que vuelve | Conserva su id (upsert por nombre) y sus bloques. Si sobra una copia, no se borra. | No necesita block report. Una copia de más no daña nada. |
| Seguridad en tránsito | TLS en todos los gRPC con una CA propia. | RNF6. Sin TLS, el cliente no conecta. |
| Seguridad en reposo | AES-256-GCM por bloque con IV aleatorio. La clave es la misma en todos los DataNodes (secret). | Cualquier nodo puede servir una réplica. GCM además detecta bloques alterados. |
| Autenticación | Usuario y contraseña (PBKDF2), token de sesión Ed25519 y tokens por bloque. Los DataNodes solo tienen la clave pública. | Un DataNode no puede fabricar permisos. Ningún nodo guarda tokens. |
| Entre nodos | Clave de registro del clúster y `node_token` en cada reporte y heartbeat. | Un proceso ajeno no puede registrarse ni reportar bloques. |
| Lock | Exclusivo, con expiración, en la tabla `file_lock`. | Un cliente caído no deja el archivo bloqueado para siempre. |

**Limitaciones conocidas (trabajo futuro):**

- RabbitMQ es un solo nodo y no usa TLS.
- Un archivo PENDING abandonado en otro path ocupa espacio hasta que se suba otro en ese path
  (falta un job de limpieza).
- No hay mTLS, ACL por grupos, 2FA ni API keys.
- No hay rebalanceo ni decommission.
- `write()` parcial no existe: los archivos son WORM y se reemplazan con `rm` + `put`.

Los riesgos y criterios pendientes, incluidos los borrados ante fallos del broker y los reenvíos de
bloques distintos, se mantienen en [ESTADO-DEL-PROYECTO.md](ESTADO-DEL-PROYECTO.md). No interpretar
esta descripción del diseño como evidencia de ejecución de los escenarios.

---

## 6. Preguntas típicas de la sustentación

- **¿Qué pasa si se cae un DataNode en medio de una subida?** El `WriteBlock` falla, y el cliente
  reintenta 3 veces y luego aborta. El archivo queda PENDING, invisible en `ls`. Un nuevo `put`
  al mismo path lo descarta (`allocateFile`, paso 2).
- **¿Y si se cae durante una descarga?** `readWithFailover` pasa a la siguiente copia del manifiesto.
- **¿Cómo sabe el ControlNode que un nodo murió?** Porque no recibe heartbeats por RabbitMQ.
  `updateNodeStatus` lo pasa a SUSPECT a los 15 s y a DEAD a los 30 s.
- **¿Por qué el cliente no le dice al ControlNode "ya subí el bloque"?** Porque el cliente podría
  mentir. Lo reporta el DataNode con su `node_token`, y `CommitFile` solo confía en esos reportes.
- **¿Qué impide que un usuario lea bloques de otro?** El DataNode solo entrega un bloque con un
  token `READ` de ese `block_id`, y ese token solo lo emite el ControlNode al dueño del archivo
  (`getFileManifest` filtra por `owner_id`).
- **¿Qué pasa si dos clientes suben al mismo tiempo?** Cada uno sube sus bloques en paralelo. En
  el ControlNode, `pg_advisory_xact_lock(1)` hace que las asignaciones se hagan una tras otra,
  para no prometer el mismo espacio dos veces.
- **¿Qué pasa si se repite un AllocateFile por un timeout?** Con el mismo `operation_id` devuelve
  el mismo plan, sin crear otro archivo.
- **¿Dónde está la transparencia (RNF7)?** El usuario usa rutas tipo Linux y nunca ve dónde está
  cada bloque. Tampoco nota el failover entre réplicas ni entre ControlNodes. La localización es
  dinámica: el manifiesto se pide en cada `get`.
- **¿Cómo se demuestra que está cifrado?** Con el escenario 5: un `grep` del texto original sobre
  los `.blk` no encuentra nada. `BlockStoreTest.enDiscoNoApareceElTextoOriginal` lo prueba
  también.
- **¿Por qué gRPC y no REST?** Por el streaming bidireccional eficiente para bloques de 8 MiB, el
  contrato tipado (`.proto`) y HTTP/2 con TLS.

---

## 7. Cómo correr y probar

```bash
# Pruebas unitarias y jars; integración PostgreSQL según PRUEBAS.md
mvn -q package                       # genera dfsha-*/target/dfsha-*.jar

# Clúster completo
cd deploy
cp env.example .env
bash gen-secrets.sh
docker compose up -d --build

# Cliente (desde la raíz del repo; usa deploy/secrets/ca.crt para TLS)
java -jar dfsha-client/target/dfsha-client.jar
#   register ana 1234
#   login ana 1234
#   mkdir docs
#   put foto.jpg docs/foto.jpg
#   ls docs
#   get docs/foto.jpg copia.jpg
#   cat docs/notas.txt 0 100
#   lock docs/foto.jpg
#   unlock docs/foto.jpg
#   rm docs/foto.jpg

# Los 7 escenarios de la Fase 3 (deja deploy/resultados-escenarios.md)
cd deploy && bash escenarios.sh
```

Si no tienes Maven en tu máquina, el cliente se puede sacar de la imagen:
`docker compose cp controlnode1:/app/dfsha-client.jar .`
