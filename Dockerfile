# ==============================================================================
# Stage 1: Build
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build files first for layer caching
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon 2>/dev/null || true

# Copy source and build
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

# ==============================================================================
# Stage 2: Runtime
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -g 1001 cce && adduser -u 1001 -G cce -s /bin/sh -D cce

WORKDIR /app

# Copy built artifact
COPY --from=builder /app/build/libs/*.jar app.jar

# Set ownership
RUN chown -R cce:cce /app

USER cce

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
