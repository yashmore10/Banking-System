# syntax=docker/dockerfile:1.7
# Build one service by supplying --build-arg SERVICE=<module-name>.
FROM maven:3.9.11-eclipse-temurin-21 AS build

ARG SERVICE
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f "${SERVICE}/pom.xml" -DskipTests package

FROM eclipse-temurin:21-jre

ARG SERVICE
WORKDIR /app
COPY --from=build /workspace/${SERVICE}/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
