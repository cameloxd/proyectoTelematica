-- DFSha - Esquema PostgreSQL (Fase 3)
-- Se ejecuta una sola vez, cuando se crea el volumen de PostgreSQL
-- (/docker-entrypoint-initdb.d/01-schema.sql). Si lo cambias: docker compose down -v
--
-- Idea central: el ControlNode NO guarda nada en memoria. Todo el estado del sistema vive
-- aquí; por eso pueden correr dos ControlNodes a la vez contra la misma base (alta disponibilidad).

CREATE TYPE node_status    AS ENUM ('ALIVE', 'SUSPECT', 'DEAD');
CREATE TYPE file_status    AS ENUM ('PENDING', 'COMMITTED', 'DELETED');
CREATE TYPE location_role  AS ENUM ('PRIMARY', 'REPLICA');
CREATE TYPE location_state AS ENUM ('PLANNED', 'WRITTEN', 'DELETED');

-- ---------------------------------------------------------------------------
-- Usuarios. password_hash = PBKDF2 ("iteraciones:sal:hash"), nunca la contraseña.
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(80) NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- DataNodes. Los crea RegisterDataNode y los actualiza cada heartbeat.
--   status: ALIVE -> SUSPECT (15 s sin heartbeat) -> DEAD (30 s sin heartbeat)
-- ---------------------------------------------------------------------------
CREATE TABLE datanode (
    datanode_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    node_name         VARCHAR(120) NOT NULL UNIQUE,     -- identidad estable (upsert al registrarse)
    endpoint          VARCHAR(255) NOT NULL,            -- lo usan los clientes
    internal_endpoint VARCHAR(255) NOT NULL,            -- lo usan los otros DataNodes
    node_token_hash   CHAR(64)     NOT NULL,            -- SHA-256 del token del nodo
    status            node_status  NOT NULL DEFAULT 'ALIVE',
    capacity_bytes    BIGINT  NOT NULL CHECK (capacity_bytes > 0),
    free_bytes        BIGINT  NOT NULL DEFAULT 0,       -- lo reporta el heartbeat
    active_transfers  INTEGER NOT NULL DEFAULT 0,       -- lo reporta el heartbeat
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Namespace jerárquico por usuario. Los paths llegan normalizados: "/a/b" (sin "/" final).
-- parent_path se calcula solo y sirve para "ls".
-- ---------------------------------------------------------------------------
CREATE TABLE dfs_directory (
    directory_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID NOT NULL REFERENCES app_user(user_id),
    path         TEXT NOT NULL CHECK (path ~ '^/$|^(/[^/]+)+$'),
    parent_path  TEXT GENERATED ALWAYS AS (
        CASE WHEN path = '/'        THEN NULL
             WHEN path ~ '^/[^/]+$' THEN '/'
             ELSE regexp_replace(path, '/[^/]+$', '')
        END) STORED,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ                          -- borrado lógico
);
CREATE UNIQUE INDEX uq_directory_live ON dfs_directory(owner_id, path) WHERE deleted_at IS NULL;

-- Un archivo pasa por: PENDING (subiendo) -> COMMITTED (visible en ls) -> DELETED (rm).
CREATE TABLE dfs_file (
    file_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id         UUID NOT NULL REFERENCES app_user(user_id),
    path             TEXT NOT NULL CHECK (path ~ '^(/[^/]+)+$'),
    parent_path      TEXT GENERATED ALWAYS AS (
        CASE WHEN path ~ '^/[^/]+$' THEN '/'
             ELSE regexp_replace(path, '/[^/]+$', '')
        END) STORED,
    size_bytes       BIGINT  NOT NULL CHECK (size_bytes >= 0),
    block_size_bytes INTEGER NOT NULL CHECK (block_size_bytes > 0),
    status           file_status NOT NULL DEFAULT 'PENDING',
    operation_id     VARCHAR(80) NOT NULL,             -- idempotencia de AllocateFile
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at     TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,
    UNIQUE (owner_id, operation_id)
);
-- Un solo archivo vivo por path; después de rm se puede volver a subir.
CREATE UNIQUE INDEX uq_file_live_path ON dfs_file(owner_id, path) WHERE status <> 'DELETED';

-- ---------------------------------------------------------------------------
-- Bloques y sus copias.
--   block_location es la tabla clave de la replicación: una fila por (bloque, DataNode).
--   PLANNED = asignada, todavía no llega el bloque.  WRITTEN = el DataNode confirmó con
--   ReportBlockStored.  DELETED = se mandó a borrar.
--   El espacio usado de un nodo = suma de sus bloques PLANNED + WRITTEN.
-- ---------------------------------------------------------------------------
CREATE TABLE dfs_block (
    block_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id         UUID    NOT NULL REFERENCES dfs_file(file_id),
    block_index     INTEGER NOT NULL CHECK (block_index >= 0),
    offset_bytes    BIGINT  NOT NULL CHECK (offset_bytes >= 0),
    size_bytes      BIGINT  NOT NULL CHECK (size_bytes > 0),
    checksum_sha256 CHAR(64),                          -- lo llena el primer ReportBlockStored
    UNIQUE (file_id, block_index)
);

CREATE TABLE block_location (
    block_id    UUID NOT NULL REFERENCES dfs_block(block_id),
    datanode_id UUID NOT NULL REFERENCES datanode(datanode_id),
    role        location_role  NOT NULL,
    state       location_state NOT NULL DEFAULT 'PLANNED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    written_at  TIMESTAMPTZ,
    PRIMARY KEY (block_id, datanode_id)                -- nunca dos copias en el mismo nodo
);
CREATE INDEX idx_location_node ON block_location(datanode_id, state);

-- ---------------------------------------------------------------------------
-- lock(): exclusivo, con expiración para que un cliente caído no deje el archivo bloqueado.
-- ---------------------------------------------------------------------------
CREATE TABLE file_lock (
    file_id     UUID PRIMARY KEY REFERENCES dfs_file(file_id),
    owner_id    UUID NOT NULL REFERENCES app_user(user_id),
    lock_token  UUID NOT NULL UNIQUE,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);

-- Cada usuario nuevo recibe su directorio raíz "/".
CREATE FUNCTION create_root_directory() RETURNS trigger AS $$
BEGIN
    INSERT INTO dfs_directory(owner_id, path) VALUES (NEW.user_id, '/');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_user_root_directory
    AFTER INSERT ON app_user
    FOR EACH ROW EXECUTE FUNCTION create_root_directory();
