#!/bin/sh
# Solo para el cluster de demo identificado: no imprime ni cambia archivos de secretos.
# Mantiene la credencial de replicator y la configuracion del standby existente.
set -eu
case "${1:-}" in
  rabbitmq)
    task_password="$(tr -d '\r\n' < /run/secrets/rabbitmq_password)"
    test -n "$task_password"
    rabbitmqctl change_password dfsha "$task_password"
    rabbitmqctl authenticate_user dfsha "$task_password"
    ;;
  postgres)
    task_password="$(tr -d '\r\n' < /run/secrets/postgres_password)"
    test -n "$task_password"
    psql -U dfsha -d dfsha -v ON_ERROR_STOP=1 --set=demo_password="$task_password" <<'SQL'
ALTER ROLE dfsha PASSWORD :'demo_password';
SQL
    ;;
  *) echo 'Modo requerido: rabbitmq o postgres' >&2; exit 2 ;;
esac
