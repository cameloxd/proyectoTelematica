# DFSha

Sistema de archivos distribuido por bloques (estilo HDFS) con alta disponibilidad, replicación y
seguridad, en Java. Proyecto 1 de ST0263 Tópicos Especiales en Telemática / SI3007 Sistemas
Distribuidos, EAFIT 2026-2. Equipo: Mendel, Camilo y Martín.

**Para entender el código y cómo modificarlo: [docs/GUIA-DEL-CODIGO.md](docs/GUIA-DEL-CODIGO.md).**

## Qué hace

- **Cliente (CLI)**:
  - `register` y `login`
  - `ls`, `cd`, `pwd`, `mkdir`, `rmdir` y `rm` (RF1)
  - `put` y `get`, que suben y bajan bloques en paralelo (RF2)
  - `cat` (lectura parcial), `lock` y `unlock` (RF3)
- **ControlNode** (2 instancias activas): metadatos en PostgreSQL, partición de archivos en bloques
  de 8 MiB, réplicas con factor 3, y detección de nodos caídos con reposición de copias.
- **DataNodes** (4): guardan los bloques cifrados con AES-256-GCM y copian réplicas entre ellos.
- **Comunicación**:
  - gRPC con TLS en todos los canales.
  - RabbitMQ para heartbeats, réplicas y borrados.
  - PostgreSQL primario + standby con streaming replication.

```text
Cliente ──gRPC/TLS──► ControlNode 1 | ControlNode 2 ──► PostgreSQL (primario → standby)
   │                         │
   │                         └──RabbitMQ──► heartbeats · réplicas · borrados
   └──gRPC/TLS (bloques)──► DataNode 1..4 ◄──réplicas──► DataNode 1..4
```

## Requisitos

Java 17, Maven 3.9+ y Docker con Compose v2. En Windows, los `.sh` se corren con Git Bash.

## Correr el clúster

```bash
mvn -q package                 # compila, corre las pruebas y genera los jars
cd deploy
cp env.example .env
bash gen-secrets.sh            # contraseñas, claves y certificados TLS (no van a Git)
docker compose up -d --build   # PostgreSQL x2, RabbitMQ, 2 ControlNodes, 4 DataNodes
docker compose ps
```

## Usar el cliente

Desde la raíz del repo (toma la CA de `deploy/secrets/ca.crt` para TLS):

```bash
java -jar dfsha-client/target/dfsha-client.jar
dfsha:/> register ana 1234
dfsha:/> login ana 1234
dfsha:/> put archivo.bin
dfsha:/> ls
dfsha:/> get archivo.bin copia.bin
```

Variables del cliente:

| Variable | Valor por defecto |
|---|---|
| `CONTROLNODE_ENDPOINTS` | `localhost:50051,localhost:50052` |
| `TLS_CA_FILE` | `deploy/secrets/ca.crt`; `none` = sin TLS |

## Probar los escenarios de la Fase 3

```bash
cd deploy && bash escenarios.sh     # 7 escenarios; deja la tabla en deploy/resultados-escenarios.md
```

Los escenarios son:

1. Réplicas (factor 3).
2. Caída de un DataNode y reposición de sus copias.
3. Descarga con un nodo caído.
4. Failover del ControlNode y de PostgreSQL.
5. TLS y cifrado en disco.
6. Lock.
7. Borrado en todas las réplicas.

## Estructura

```text
dfsha-proto/        contrato gRPC (dfsha.proto)
dfsha-common/       gRPC + TLS, tokens Ed25519, RabbitMQ (Env, Rpc, Tokens, Rabbit)
dfsha-controlnode/  metadatos, asignación de bloques, réplicas, estados de nodos
dfsha-datanode/     bloques cifrados, validación de tokens, copia de réplicas
dfsha-client/       consola y API del cliente
deploy/             docker-compose, Dockerfile, schema.sql, secretos, failover, escenarios
docs/               guía del código, documentos de los hitos
```

Las reglas de ramas y commits están en [CONTRIBUTING.md](CONTRIBUTING.md).
