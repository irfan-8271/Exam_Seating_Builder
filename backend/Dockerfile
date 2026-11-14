# ===================================================================
#  Backend — Exam Seating Engine (Spring Boot 4 / Java 21)
# ===================================================================
#  Multi-stage build:
#    stage 1 (builder) — compiles with Maven, caches dependencies
#    stage 2 (runtime) — small JRE-only image with the built jar
# ===================================================================

# ---------- Stage 1: build ----------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy POM first to leverage Docker layer caching for dependencies
COPY pom.xml ./
RUN mvn -B dependency:go-offline -DexcludeGroupIds=org.projectlombok || true

# Copy source and build executable JAR
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Install curl for reliable healthchecks & network checks
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl bash \
    && rm -rf /var/lib/apt/lists/*

# Run as non-root user for security best practices
RUN groupadd --system app && useradd --system --gid app --create-home app

WORKDIR /app

# Copy the JAR artifact from builder
COPY --from=builder /build/target/exam-seating-engine-*.jar /app/app.jar

RUN chown -R app:app /app

USER app

# Port configuration (matches server.port ${PORT:8080})
ARG APP_PORT=8080
ENV PORT=${APP_PORT}
EXPOSE ${APP_PORT}

# Memory optimization for cloud containers (e.g., Render 512MB RAM free tier)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Xmx384m"

# Healthcheck probing the public status endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -f http://127.0.0.1:${PORT:-8080}/api/v1/admin/status || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

