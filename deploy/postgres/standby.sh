#!/bin/sh
# PostgreSQL en reserva (standby): una copia viva del primario por streaming replication.
# La primera vez clona el primario con pg_basebackup; -R lo deja configurado como standby.
# Las siguientes veces solo arranca y sigue recibiendo los cambios del primario.
set -e
export PGPASSWORD="$(cat /run/secrets/postgres_password)"
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  until pg_basebackup -h postgres -U replicator -D "$PGDATA" -R -X stream; do
    echo "El primario todavía no está listo; reintento en 3 s"
    sleep 3
  done
  chmod 700 "$PGDATA"
fi
exec postgres
