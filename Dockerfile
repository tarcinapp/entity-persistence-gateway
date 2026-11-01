# ──────────────────────────────────────────────────────────
# Stage 1: Build
# ──────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
COPY . /workspace/
WORKDIR /workspace
RUN mvn package -Dmaven.test.skip=true

# ──────────────────────────────────────────────────────────
# Stage 2: Runtime
# ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
COPY --from=builder /workspace/target/*.jar /opt/app.jar
WORKDIR /opt
EXPOSE 8080
CMD ["java", \
     "-Dlog4j2.isThreadContextMapInheritable=true", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-jar", "app.jar"]
