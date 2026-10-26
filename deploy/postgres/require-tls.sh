#!/bin/sh
# Solo inicializacion del perfil seguro: red SQL/replicacion exclusivamente TLS.
set -eu
task_hba="$PGDATA/pg_hba.conf"
task_new="$PGDATA/pg_hba.dfsha-tls"
{
  printf 'hostnossl all all 0.0.0.0/0 reject\nhostnossl all all ::/0 reject\n'
  printf 'hostnossl replication all 0.0.0.0/0 reject\nhostnossl replication all ::/0 reject\n'
  cat "$task_hba"
} > "$task_new"
chmod 600 "$task_new"
mv "$task_new" "$task_hba"
