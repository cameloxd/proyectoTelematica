# Una sola imagen con los tres programas (ControlNode, DataNode y cliente).
# Cada servicio de docker-compose.yml elige cuál correr con "command".
# Se construye desde la raíz del repo (docker compose lo hace solo: build.context = ..).

# Etapa 1: compilar con Maven. La caché guarda las dependencias entre builds.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# Etapa 2: solo el JRE y los jars ejecutables (imagen pequeña, sin Maven ni código fuente).
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/dfsha-controlnode/target/dfsha-controlnode.jar \
                  /src/dfsha-datanode/target/dfsha-datanode.jar \
                  /src/dfsha-client/target/dfsha-client.jar ./
