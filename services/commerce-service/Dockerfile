FROM maven:3.10.0-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src src
ARG MAVEN_LEGACY_MYSQL=false
RUN --mount=type=cache,target=/root/.m2 mvn -Dcommerceflow.legacyMysql="$MAVEN_LEGACY_MYSQL" package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
