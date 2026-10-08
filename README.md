# DFSha

Sistema de archivos distribuido basado en bloques (estilo HDFS) en Java.
ST0263 Topicos Especiales en Telematica / SI3007 Sistemas Distribuidos - EAFIT 2026-2.

Componentes: **Cliente** (CLI) - **ControlNode** (metadatos, PostgreSQL) - **DataNodes** (bloques).
Comunicacion: gRPC en el camino de datos y control; RabbitMQ para eventos asincronos.

## Estructura

```text
dfsha-proto/        contrato gRPC (dfsha.proto) -> genera los stubs Java
dfsha-common/       codigo compartido: configuracion, gRPC, RabbitMQ, logging
dfsha-client/       CLI del cliente
dfsha-controlnode/  servidor de metadatos
dfsha-datanode/     servidor de bloques
deploy/             docker-compose, schema.sql, secretos (gen-secrets.sh)
docs/               hitos, roadmap y referencias
```

## Requisitos
Java 17, Maven 3.9+, Docker con Compose v2.

## Compilar
```bash
mvn -q -DskipTests package
```

## Infraestructura local (PostgreSQL + RabbitMQ)
```bash
cd deploy
cp env.example .env
bash gen-secrets.sh
docker compose up postgres rabbitmq
```
Consola de RabbitMQ: http://localhost:15672

## Conectarse desde tu IDE

Con `docker compose up -d postgres rabbitmq` corriendo (desde `deploy/`):

| Servicio | Dirección | Usuario | Contraseña |
|---|---|---|---|
| PostgreSQL | `jdbc:postgresql://localhost:5432/dfsha` (usa el puerto de `POSTGRES_PORT` si lo cambiaste en `.env`) | `dfsha` | `deploy/secrets/postgres_password.txt` |
| RabbitMQ (AMQP) | `localhost:5672` | `dfsha` | `deploy/secrets/rabbitmq_password.txt` |
| RabbitMQ (consola) | http://localhost:15672 | `dfsha` | la misma de RabbitMQ |

Las contraseñas son aleatorias en cada máquina: las genera `bash gen-secrets.sh`.
Si `docker compose up` falla con "forbidden by its access permissions" en Windows,
el puerto está reservado: define `POSTGRES_PORT=5433` en `deploy/.env`.
Si cambias `schema.sql`, recrea el volumen con `docker compose down -v`.


