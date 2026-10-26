#!/bin/sh
set -eu
export PGPASSWORD="$(tr -d '\r\n' < /run/secrets/postgres_password)"
if PGSSLMODE=disable psql -h postgres -U dfsha -d dfsha -c 'SELECT 1' >/dev/null 2>&1; then
  echo 'FALLO: PostgreSQL acepto SQL sin TLS' >&2; exit 1
fi
export PGSSLMODE=verify-full
export PGSSLROOTCERT=/run/secrets/tls_ca
task_ssl="$(psql -h postgres -U dfsha -d dfsha -v ON_ERROR_STOP=1 -tAc 'SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()')"
test "$task_ssl" = t
echo 'TLS_SQL_VERIFICADO: certificado/hostname validados y conexion sin TLS rechazada'
