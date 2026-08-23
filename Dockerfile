FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/data /app/logs
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q -O - --timeout=3 http://localhost:8080/mcp | head -c 1 | grep -q .
ENTRYPOINT ["java", "-jar", "app.jar"]
