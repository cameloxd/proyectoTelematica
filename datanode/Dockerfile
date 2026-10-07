FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
COPY controlnode/src controlnode/src
COPY datanode/src datanode/src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/dfsha-hito2-0.1.0-SNAPSHOT.jar /app/dfsha.jar
EXPOSE 50061 50062 50063
ENTRYPOINT ["java", "-cp", "/app/dfsha.jar", "co.eafit.dfsha.data.DataNodeMain"]
