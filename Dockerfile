FROM eclipse-temurin:21-jdk-jammy AS build
# Keep the Wrapper on the ZIP distribution whose SHA-256 is pinned.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw --batch-mode package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 taskflow && useradd --uid 10001 --gid taskflow --no-create-home taskflow
WORKDIR /app
COPY --from=build /workspace/target/taskflow-0.1.0.jar app.jar
USER taskflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
