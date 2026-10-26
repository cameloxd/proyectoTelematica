#!/bin/sh
set -eu
install -m 0644 /run/secrets/tls_cert /tmp/dfsha-postgres.crt
install -m 0600 -o postgres -g postgres /run/secrets/tls_key /tmp/dfsha-postgres.key
exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/dfsha-postgres.crt -c ssl_key_file=/tmp/dfsha-postgres.key
