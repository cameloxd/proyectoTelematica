#!/usr/bin/env bash
# Genera los secretos que usa docker-compose.yml. Ejecutar desde la raiz del proyecto.
set -euo pipefail
mkdir -p secrets

gen() { [ -f "$1" ] || { openssl rand -base64 32 | tr -d '\n' > "$1"; echo "creado $1"; }; }
gen secrets/postgres_password.txt
gen secrets/rabbitmq_password.txt
gen secrets/cluster_registration_key.txt

if [ ! -f secrets/token_private.pem ]; then
  openssl genpkey -algorithm ed25519 -out secrets/token_private.pem
  openssl pkey -in secrets/token_private.pem -pubout -out secrets/token_public.pem
  echo "creadas claves Ed25519 de tokens"
fi

# 644 para que el usuario no-root de cada contenedor pueda leer los archivos montados.
# Es aceptable para el proyecto academico; en produccion usar Docker Swarm secrets o un vault.
chmod 644 secrets/*
