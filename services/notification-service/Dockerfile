FROM maven:3.10.0-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S notification && adduser -S notification -G notification
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER notification
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
