#!/bin/sh
set -eu
export PGPASSWORD="$(tr -d '\r\n' < /run/secrets/postgres_password)"
export PGSSLMODE=verify-full
export PGSSLROOTCERT=/run/secrets/tls_ca
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  until pg_basebackup -h postgres -U replicator -D "$PGDATA" -R -X stream; do
    echo 'Esperando primario TLS para replicacion'; sleep 3
  done
  chmod 700 "$PGDATA"
fi
cp /run/secrets/tls_cert /tmp/dfsha-postgres.crt
cp /run/secrets/tls_key /tmp/dfsha-postgres.key
chmod 600 /tmp/dfsha-postgres.key
exec postgres -c ssl=on -c ssl_cert_file=/tmp/dfsha-postgres.crt -c ssl_key_file=/tmp/dfsha-postgres.key
