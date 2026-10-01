FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace/api
COPY api/.mvn .mvn
COPY api/mvnw api/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY proto ../proto
COPY api/src src
RUN ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api/target/cacanode-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
