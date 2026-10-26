-- Aplicar después de schema.sql, en el primario. Reejecutable, sin borrar datos.
-- published_at solo confirma entrega al broker: NO confirma el borrado físico.
CREATE TABLE IF NOT EXISTS block_delete_outbox (
    block_id        UUID NOT NULL REFERENCES dfs_block(block_id),
    datanode_id     UUID NOT NULL REFERENCES datanode(datanode_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts        BIGINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(120),
    PRIMARY KEY (block_id, datanode_id)
);
CREATE INDEX IF NOT EXISTS idx_delete_outbox_pending
    ON block_delete_outbox(next_attempt_at, created_at) WHERE published_at IS NULL;

-- Repara la ventana de publicación de versiones anteriores. Puede reenviar tareas:
-- borrar un block_id ya ausente es idempotente (BlockStore.delete usa Files.deleteIfExists).
INSERT INTO block_delete_outbox(block_id, datanode_id)
SELECT l.block_id, l.datanode_id
FROM block_location l
JOIN dfs_block b ON b.block_id = l.block_id
JOIN dfs_file f ON f.file_id = b.file_id
WHERE f.status = 'DELETED' AND l.state = 'DELETED'
ON CONFLICT (block_id, datanode_id) DO NOTHING;
