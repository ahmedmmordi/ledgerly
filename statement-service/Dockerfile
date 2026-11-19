# Stage 1: Build the application JAR
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

# Copy Maven wrapper and module POMs for dependency resolution
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common-lib/pom.xml common-lib/
COPY identity-service/pom.xml identity-service/
COPY ledger-service/pom.xml ledger-service/
COPY statement-service/pom.xml statement-service/
COPY api-gateway/pom.xml api-gateway/

# Copy source code of common-lib and statement-service
COPY common-lib/src common-lib/src
COPY statement-service/src statement-service/src

# Build only statement-service and its project dependencies (-am)
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests -pl statement-service -am

# Stage 2: Create lightweight, secure runtime image
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Principle of least privilege: run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy executable fat JAR from builder stage
COPY --from=builder --chown=appuser:appgroup /workspace/statement-service/target/*.jar app.jar

USER appuser

EXPOSE 8083

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
