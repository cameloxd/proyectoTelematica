#!/bin/sh
# Lo ejecuta el PostgreSQL PRIMARIO una sola vez, al crear su volumen (después de 01-schema.sql).
# Crea el usuario "replicator" y le permite conectarse para hacer streaming replication.
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$(cat /run/secrets/postgres_password)';"
echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"
