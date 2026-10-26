#!/usr/bin/env bash
# Genera todos los secretos del clúster en deploy/secrets/ (esa carpeta NO va a Git).
# Ejecutar desde la carpeta deploy/:   bash gen-secrets.sh
# Si ya existen, no los toca. Para empezar de cero: borra deploy/secrets/.
# En AWS/GCP agrega la IP o el DNS público al certificado:
#   EXTRA_SAN="IP:3.80.1.2,DNS:mi-vm.compute.amazonaws.com" bash gen-secrets.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1      # Git Bash en Windows: que no convierta "/CN=..." en una ruta
mkdir -p secrets
cd secrets

# 1. Contraseñas y claves aleatorias (32 bytes en base64)
random() { [ -f "$1" ] || { openssl rand -base64 32 | tr -d '\r\n' > "$1"; echo "creado $1"; }; }
random postgres_password.txt
random rabbitmq_password.txt
random cluster_registration_key.txt     # para que solo DataNodes del clúster se registren
random storage_key.txt                  # AES-256 del cifrado en reposo (igual en todos los DataNodes)

# 2. Claves Ed25519 de los tokens: la privada solo la tiene el ControlNode
if [ ! -f token_private.pem ]; then
  openssl genpkey -algorithm ed25519 -out token_private.pem
  openssl pkey -in token_private.pem -pubout -out token_public.pem
  echo "creadas las claves Ed25519 de los tokens"
fi

# 3. TLS: una CA propia y un certificado para los nodos, firmado por ella
if [ ! -f ca.crt ]; then
  SAN="DNS:localhost,IP:127.0.0.1,DNS:controlnode1,DNS:controlnode2"
  SAN="$SAN,DNS:datanode1,DNS:datanode2,DNS:datanode3,DNS:datanode4${EXTRA_SAN:+,$EXTRA_SAN}"
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=DFSha CA" -keyout ca.key -out ca.crt
  openssl req -newkey rsa:2048 -nodes -subj "/CN=dfsha-node" -keyout node.key -out node.csr
  printf "subjectAltName=%s\n" "$SAN" > node.ext
  openssl x509 -req -in node.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 365 -extfile node.ext -out node.crt
  rm -f node.csr node.ext ca.srl
  echo "creados ca.crt y node.crt ($SAN)"
fi

# 644 para que el usuario de cada contenedor pueda leerlos. Aceptable en el proyecto académico;
# en producción se usaría un gestor de secretos.
chmod 644 ./*
