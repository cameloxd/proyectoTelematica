-- DFSha - Esquema PostgreSQL (Hito 2, revisado)
-- Se ejecuta una sola vez al crear el volumen de PostgreSQL
-- (/docker-entrypoint-initdb.d/01-schema.sql).

-- gen_random_uuid() es nativa desde PostgreSQL 13: no se necesita pgcrypto.

CREATE TYPE node_status          AS ENUM ('ALIVE', 'SUSPECT', 'DEAD', 'DECOMMISSIONING');
CREATE TYPE file_status          AS ENUM ('PENDING', 'COMMITTED', 'DELETED');
CREATE TYPE block_location_role  AS ENUM ('PRIMARY', 'REPLICA');
CREATE TYPE block_location_state AS ENUM ('PLANNED', 'WRITTEN', 'DELETING', 'DELETED');

-- ---------------------------------------------------------------------------
-- Usuarios
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(80) NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,             -- Argon2id o BCrypt
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- DataNodes
--   free_bytes     : espacio real en disco, lo sobrescribe cada heartbeat.
--   reserved_bytes : espacio prometido a bloques PLANNED; SOLO lo modifica el
--                    ControlNode, por eso un heartbeat no borra las reservas.
--   available_bytes: lo que realmente se puede asignar.
-- ---------------------------------------------------------------------------
CREATE TABLE datanode (
    datanode_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    node_name         VARCHAR(120) NOT NULL UNIQUE,
    endpoint          VARCHAR(255) NOT NULL,        -- visible para los Clientes
    internal_endpoint VARCHAR(255) NOT NULL,        -- entre nodos
    status            node_status  NOT NULL DEFAULT 'ALIVE',
    capacity_bytes    BIGINT  NOT NULL CHECK (capacity_bytes >= 0),
    free_bytes        BIGINT  NOT NULL CHECK (free_bytes >= 0),
    reserved_bytes    BIGINT  NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
    available_bytes   BIGINT  GENERATED ALWAYS AS (free_bytes - reserved_bytes) STORED,
    active_transfers  INTEGER NOT NULL DEFAULT 0 CHECK (active_transfers >= 0),
    stored_blocks     BIGINT  NOT NULL DEFAULT 0 CHECK (stored_blocks >= 0),
    last_heartbeat_at TIMESTAMPTZ,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Directorios y archivos (namespace jerarquico por usuario)
-- Los paths llegan normalizados (sin '.', '..', ni '/' final).
-- ---------------------------------------------------------------------------
CREATE TABLE dfs_directory (
    directory_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID NOT NULL REFERENCES app_user(user_id),
    path         TEXT NOT NULL
        CHECK (path ~ '^/$|^(/[^/]+)+$' AND path !~ '(^|/)\.\.?(/|$)'),
    parent_path  TEXT GENERATED ALWAYS AS (
        CASE WHEN path = '/'              THEN NULL
             WHEN path ~ '^/[^/]+$'       THEN '/'
             ELSE regexp_replace(path, '/[^/]+$', '')
        END) STORED,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

-- Un path vivo es unico; uno borrado se puede volver a crear.
CREATE UNIQUE INDEX uq_directory_live   ON dfs_directory(owner_id, path)        WHERE deleted_at IS NULL;
CREATE INDEX        idx_directory_child ON dfs_directory(owner_id, parent_path) WHERE deleted_at IS NULL;

CREATE TABLE dfs_file (
    file_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID NOT NULL REFERENCES app_user(user_id),
    path               TEXT NOT NULL
        CHECK (path ~ '^(/[^/]+)+$' AND path !~ '(^|/)\.\.?(/|$)'),
    parent_path        TEXT GENERATED ALWAYS AS (
        CASE WHEN path ~ '^/[^/]+$' THEN '/'
             ELSE regexp_replace(path, '/[^/]+$', '')
        END) STORED,
    size_bytes         BIGINT  NOT NULL CHECK (size_bytes >= 0),
    block_size_bytes   INTEGER NOT NULL CHECK (block_size_bytes > 0),
    status             file_status NOT NULL DEFAULT 'PENDING',
    operation_id       VARCHAR(80) NOT NULL,          -- clave de idempotencia de AllocateFile
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    pending_expires_at TIMESTAMPTZ,                   -- un PENDING vencido lo limpia un job
    committed_at       TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    UNIQUE (owner_id, operation_id),
    CHECK (status <> 'PENDING' OR pending_expires_at IS NOT NULL)
);

-- Solo un archivo vivo por path; tras DELETED se puede volver a subir.
CREATE UNIQUE INDEX uq_file_live_path      ON dfs_file(owner_id, path) WHERE status <> 'DELETED';
CREATE INDEX        idx_file_parent        ON dfs_file(owner_id, parent_path) WHERE status = 'COMMITTED';
CREATE INDEX        idx_file_pending_expiry ON dfs_file(pending_expires_at)   WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Bloques y ubicaciones
-- ---------------------------------------------------------------------------
CREATE TABLE dfs_block (
    block_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id         UUID    NOT NULL REFERENCES dfs_file(file_id),
    block_index     INTEGER NOT NULL CHECK (block_index >= 0),
    offset_bytes    BIGINT  NOT NULL CHECK (offset_bytes >= 0),
    size_bytes      BIGINT  NOT NULL CHECK (size_bytes > 0),
    checksum_sha256 CHAR(64) CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),  -- lo llena ReportBlockStored
    UNIQUE (file_id, block_index),
    UNIQUE (file_id, offset_bytes)
);

-- N:M entre bloques y DataNodes. Admite cualquier factor de replicacion:
-- un solo PRIMARY por bloque y tantas REPLICA como nodos distintos.
CREATE TABLE block_location (
    block_id       UUID NOT NULL REFERENCES dfs_block(block_id),
    datanode_id    UUID NOT NULL REFERENCES datanode(datanode_id),
    role           block_location_role  NOT NULL DEFAULT 'PRIMARY',
    state          block_location_state NOT NULL DEFAULT 'PLANNED',
    reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
    written_at     TIMESTAMPTZ,
    PRIMARY KEY (block_id, datanode_id)
);

CREATE UNIQUE INDEX uq_block_single_primary ON block_location(block_id) WHERE role = 'PRIMARY';
CREATE INDEX idx_block_file                 ON dfs_block(file_id, block_index);
CREATE INDEX idx_location_node_state        ON block_location(datanode_id, state);
CREATE INDEX idx_datanode_status_heartbeat  ON datanode(status, last_heartbeat_at);

-- ---------------------------------------------------------------------------
-- Locks de archivo (RF3: lock())
-- ---------------------------------------------------------------------------
CREATE TABLE file_lock (
    file_id     UUID PRIMARY KEY REFERENCES dfs_file(file_id),
    owner_id    UUID NOT NULL REFERENCES app_user(user_id),
    lock_token  UUID NOT NULL UNIQUE,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------------------
-- Cada usuario nuevo recibe su directorio raiz '/'.
-- ---------------------------------------------------------------------------
CREATE FUNCTION create_root_directory() RETURNS trigger AS $$
BEGIN
    INSERT INTO dfs_directory(owner_id, path) VALUES (NEW.user_id, '/');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_user_root_directory
    AFTER INSERT ON app_user
    FOR EACH ROW EXECUTE FUNCTION create_root_directory();

-- ---------------------------------------------------------------------------
-- Referencia para el ControlNode (no se ejecuta aqui)
--
-- Reserva atomica de espacio, una vez por bloque dentro de la transaccion de
-- AllocateFile. Si devuelve 0 filas otro cliente ocupo el espacio: recalcular.
--
--   UPDATE datanode
--      SET reserved_bytes = reserved_bytes + :block_size
--    WHERE datanode_id = :id
--      AND status = 'ALIVE'
--      AND available_bytes >= :block_size + :reserved_floor;
--
-- Al recibir ReportBlockStored:  reserved_bytes -= block_location.reserved_bytes,
--                                state = 'WRITTEN', written_at = now().
-- Al vencer o abortar un PENDING: liberar reserved_bytes de sus bloques PLANNED.
-- ---------------------------------------------------------------------------
