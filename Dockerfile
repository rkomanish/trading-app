# ─── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first (only re-downloads when pom.xml changes)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Build the application jar (skip tests — they require a live DB)
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user
RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser

# Platforms inject $PORT; default to 8080 locally.
ENV PORT=8080
EXPOSE 8080

# Respect container memory limits; honour the injected port.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75.0 -Dserver.port=${PORT:-8080} -jar app.jar"]
