#!/bin/sh
# Leer el secreto dentro del contenedor, sin interpolacion de $ por Docker Compose.
set -eu
RABBITMQ_DEFAULT_PASS="$(tr -d '\r\n' < /run/secrets/rabbitmq_password)"
export RABBITMQ_DEFAULT_PASS
exec docker-entrypoint.sh rabbitmq-server
