#!/usr/bin/env bash
# Los 7 escenarios de "listo" de la Fase 3 contra el clúster de Docker (tarea A3.7).
#
# Requisitos: el clúster arriba (docker compose up -d --build) y Java 17 en tu máquina.
# Uso, desde deploy/:   bash escenarios.sh
# Deja la tabla en deploy/resultados-escenarios.md (sirve para el informe).
#
# OJO: el escenario 4 apaga el PostgreSQL primario y promueve el standby. Para volver a empezar:
#   docker compose down -v && docker compose up -d
set -uo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1                      # Git Bash en Windows: no tocar las rutas "/..."
export CONTROLNODE_ENDPOINTS=localhost:50051,localhost:50052
export TLS_CA_FILE=secrets/ca.crt

W=.escenarios                                  # archivos de prueba (no van a Git)
mkdir -p "$W"
JAR=$W/dfsha-client.jar
[ -f "$JAR" ] || docker compose cp controlnode1:/app/dfsha-client.jar "$JAR"   # el cliente sale de la imagen
DB=postgres
USR=demo$RANDOM
NODES="datanode1 datanode2 datanode3 datanode4"
RESULTS=""

# ---------------------------------------------------------------- ayudas
dfs() { { echo "login $USR clave123"; printf '%s\n' "$@"; } | java -jar "$JAR" 2>&1; }   # comandos del cliente
sql() { docker compose exec -T "$DB" psql -U dfsha -d dfsha -tAc "$1"; }
result() { RESULTS+="| $1 | $2 | $3 |"$'\n'; echo ">>> Escenario $1: $3"; echo; }
wait_for() {                                   # wait_for <segundos> <función>: reintenta cada 2 s
  local limit=$1; shift
  for ((t = 0; t < limit; t += 2)); do "$@" && return 0; sleep 2; done
  return 1
}
FILE_SQL="FROM dfs_file f JOIN app_user u ON u.user_id = f.owner_id JOIN dfs_block b ON b.file_id = f.file_id
          JOIN block_location l ON l.block_id = b.block_id JOIN datanode d ON d.datanode_id = l.datanode_id
          WHERE u.username = '$USR' AND f.status = 'COMMITTED'"
copies_ok() {                                  # ¿cada bloque de $1 tiene 3 copias vivas?
  [ "$(sql "SELECT COALESCE(min(n), 0) FROM (SELECT count(*) FILTER (WHERE l.state = 'WRITTEN' AND d.status <> 'DEAD') n
            $FILE_SQL AND f.path = '$1' GROUP BY b.block_id) x")" -ge 3 ]
}
blocks_on_disk() {                             # DataNodes que todavía tienen en disco alguno de $IDS
  for n in $NODES; do
    files=$(docker compose exec -T "$n" ls /data/blocks 2>/dev/null)
    for id in $IDS; do echo "$files" | grep -q "$id" && echo "$n"; done
  done
}

printf 'register %s clave123\n' "$USR" | java -jar "$JAR" > /dev/null 2>&1
head -c 20971520 /dev/urandom > "$W/archivo20.bin"
ORIGINAL=$(sha256sum "$W/archivo20.bin" | cut -d' ' -f1)

# ---------------------------------------------------------------- 1
echo "== 1. Subir 20 MiB con 4 DataNodes: en menos de 30 s cada bloque debe tener 3 copias"
dfs "put $W/archivo20.bin /archivo20.bin" | grep -E "Subido|Error"
if wait_for 30 copies_ok /archivo20.bin; then result 1 "Subir 20 MiB: 3 copias por bloque" PASA
else result 1 "Subir 20 MiB: 3 copias por bloque" FALLA; fi

# ---------------------------------------------------------------- 3 y 2
VICTIM=$(sql "SELECT d.node_name $FILE_SQL AND f.path = '/archivo20.bin' AND b.block_index = 0 AND l.role = 'PRIMARY'")
echo "== 3. Apagar $VICTIM (tiene el bloque 0) y descargar enseguida: el cliente cambia de réplica"
docker compose stop "$VICTIM"
rm -f "$W/bajado.bin"
dfs "get /archivo20.bin $W/bajado.bin" | grep -E "falló|Descargado|Error"
if [ "$(sha256sum "$W/bajado.bin" 2>/dev/null | cut -d' ' -f1)" = "$ORIGINAL" ]; then
  result 3 "Descargar con un DataNode caído (SHA-256 igual)" PASA
else result 3 "Descargar con un DataNode caído (SHA-256 igual)" FALLA; fi

echo "== 2. $VICTIM debe quedar DEAD a los 30 s y sus copias reponerse en otro nodo"
is_dead() { [ "$(sql "SELECT status FROM datanode WHERE node_name = '$VICTIM'")" = DEAD ]; }
if wait_for 50 is_dead && wait_for 40 copies_ok /archivo20.bin; then
  result 2 "DataNode caído: DEAD y factor vuelve a 3" PASA
else result 2 "DataNode caído: DEAD y factor vuelve a 3" FALLA; fi
docker compose start "$VICTIM"

# ---------------------------------------------------------------- 5
echo "== 5. Sin TLS la conexión se rechaza; en disco el bloque está cifrado"
WITHOUT_TLS=$(printf 'login %s clave123\n' "$USR" | TLS_CA_FILE=none java -jar "$JAR" 2>&1)
for i in $(seq 1 3000); do echo "MARCA-SECRETA-DFSHA línea $i"; done > "$W/texto.txt"
dfs "put $W/texto.txt /texto.txt" | grep -E "Subido|Error"
sleep 5
PLAINTEXT=$(for n in $NODES; do docker compose exec -T "$n" sh -c 'grep -l MARCA-SECRETA-DFSHA /data/blocks/*.blk' 2>/dev/null; done)
if echo "$WITHOUT_TLS" | grep -q UNAVAILABLE && [ -z "$PLAINTEXT" ] && dfs "cat /texto.txt 0 19" | grep -q MARCA-SECRETA; then
  result 5 "Sin TLS rechazado y bloques cifrados en disco" PASA
else result 5 "Sin TLS rechazado y bloques cifrados en disco" FALLA; fi

# ---------------------------------------------------------------- 6
echo "== 6. Dos clientes piden lock del mismo archivo: el segundo espera a que venza"
FIRST=$(dfs "lock /texto.txt 15")
SECOND=$(dfs "lock /texto.txt")
echo "$SECOND" | grep -E "Error"
sleep 16
AFTER=$(dfs "lock /texto.txt 5")
if echo "$FIRST" | grep -q "Lock de" && echo "$SECOND" | grep -q bloqueado && echo "$AFTER" | grep -q "Lock de"; then
  result 6 "Lock exclusivo con expiración" PASA
else result 6 "Lock exclusivo con expiración" FALLA; fi

# ---------------------------------------------------------------- 7
echo "== 7. Borrar un archivo: sus bloques desaparecen del disco de TODOS los DataNodes"
IDS=$(sql "SELECT DISTINCT b.block_id $FILE_SQL AND f.path = '/archivo20.bin'")
echo "Antes del rm, bloques en: $(blocks_on_disk | sort | uniq -c | tr '\n' ' ')"
dfs "rm /archivo20.bin" | grep -E "borrado|Error"
gone() { [ -z "$(blocks_on_disk)" ]; }
if [ -n "$IDS" ] && wait_for 30 gone; then result 7 "rm borra el bloque de todas las réplicas" PASA
else result 7 "rm borra el bloque de todas las réplicas" FALLA; fi

# ---------------------------------------------------------------- 4 (al final: apaga el primario)
echo "== 4. Detener el ControlNode activo y el PostgreSQL primario (se promueve el standby)"
docker compose stop controlnode1
lists_texto() { dfs "ls /" | grep -q texto.txt; }
WITHOUT_CONTROLNODE1=$(lists_texto && echo ok)
bash promote-standby.sh
DB=postgres-standby
WITH_STANDBY=$(wait_for 30 lists_texto && echo ok)
rm -f "$W/texto2.txt"
dfs "get /texto.txt $W/texto2.txt" | grep -E "Descargado|Error"
docker compose start controlnode1
if [ "$WITHOUT_CONTROLNODE1" = ok ] && [ "$WITH_STANDBY" = ok ] && cmp -s "$W/texto.txt" "$W/texto2.txt"; then
  result 4 "Failover de ControlNode y de PostgreSQL" PASA
else result 4 "Failover de ControlNode y de PostgreSQL" FALLA; fi

# ---------------------------------------------------------------- tabla
{
  echo "# Resultados de los escenarios de la Fase 3"
  echo
  echo "Fecha: $(date '+%Y-%m-%d %H:%M') · comando: \`bash deploy/escenarios.sh\`"
  echo
  echo "| # | Escenario | Resultado |"
  echo "|---|---|---|"
  printf '%s' "$RESULTS" | sort -t'|' -k2 -n
} > resultados-escenarios.md
cat resultados-escenarios.md
