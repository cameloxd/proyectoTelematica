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

## Como contribuir
Lee [CONTRIBUTING.md](CONTRIBUTING.md): ramas, commits y pull requests.
El plan de trabajo esta en `docs/`.
