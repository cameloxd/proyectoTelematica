#!/usr/bin/env bash
# Failover de PostgreSQL (manual asistido): el standby pasa a ser el primario.
# Úsalo cuando el primario se cae. Desde deploy/:   bash promote-standby.sh
#
# Los ControlNodes no se reinician: su DB_URL tiene los dos hosts con targetServerType=primary,
# así que las conexiones nuevas encuentran solas al nuevo primario.
set -euo pipefail
cd "$(dirname "$0")"
docker compose stop postgres || true          # si seguía vivo, se apaga: nunca dos primarios
docker compose exec -T postgres-standby psql -U dfsha -d dfsha -c "SELECT pg_promote();"
echo "Listo: postgres-standby es ahora el primario."
echo "No vuelvas a arrancar el 'postgres' viejo (quedaría con datos atrasados)."
echo "Para volver al estado inicial: docker compose down -v && docker compose up -d"
