# DFSha Hito 2

Esta carpeta contiene el contrato gRPC, el esquema PostgreSQL y un bootstrap Java ejecutable para validar los flujos principales de Hito 2.

## Requisitos

- Docker Desktop con Compose v2.
- Bash y OpenSSL para generar secretos.
- Java 17 y Maven 3.9 si se desea compilar fuera de Docker.

## Arranque con Docker

Desde esta carpeta:

```bash
cp env.example .env
bash gen-secrets.sh
docker compose config
docker compose up --build
```

Puertos publicados:

- ControlNode: `localhost:50051`
- DataNode 1: `localhost:50061`
- DataNode 2: `localhost:50062`
- DataNode 3: `localhost:50063`
- Consola RabbitMQ: `http://localhost:15672`

La implementación Java actual es un bootstrap en memoria para validar registro de DataNodes, asignación de bloques, escritura, reporte de bloques y commit. PostgreSQL y RabbitMQ ya están definidos en Compose; el siguiente paso de implementación es reemplazar los mapas en memoria por repositorios JDBC y consumidores RabbitMQ.

## Compilación local

```bash
mvn -DskipTests package
```

El contrato está en `src/main/proto/dfsha.proto`. Los stubs Java se generan automáticamente durante `mvn package`.

## Advertencias

- El bootstrap usa `usePlaintext()` entre servicios; TLS/mTLS sigue siendo obligatorio para cerrar RNF6.
- Los tokens del bootstrap son identificadores temporales; la firma Ed25519 debe incorporarse en el adaptador de seguridad antes de una demo fuera de localhost.
- No se deben subir `.env` ni `secrets/` al repositorio.
