# Build Stage
FROM maven:3.8.8-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
# Warm up maven cache for dependencies
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Run Stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install curl for Docker health checks
RUN apk add --no-cache curl

COPY --from=build /app/target/flash-sale-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080

# Run with production optimized JVM flags
ENTRYPOINT ["java", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=50", \
            "-Xms512m", \
            "-Xmx1g", \
            "-jar", \
            "app.jar"]
