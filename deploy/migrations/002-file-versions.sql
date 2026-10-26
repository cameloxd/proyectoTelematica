-- RF3: una version pendiente no oculta ni sustituye la version confirmada hasta el commit.
ALTER TABLE dfs_file ADD COLUMN IF NOT EXISTS replaces_file_id UUID REFERENCES dfs_file(file_id);
ALTER TABLE dfs_file ADD COLUMN IF NOT EXISTS replacement_lock_token UUID;
DROP INDEX IF EXISTS uq_file_live_path;
CREATE UNIQUE INDEX uq_file_live_path ON dfs_file(owner_id, path)
  WHERE status = 'COMMITTED' OR (status = 'PENDING' AND replaces_file_id IS NULL);
CREATE UNIQUE INDEX IF NOT EXISTS uq_file_pending_replacement ON dfs_file(replaces_file_id)
  WHERE status = 'PENDING' AND replaces_file_id IS NOT NULL;
