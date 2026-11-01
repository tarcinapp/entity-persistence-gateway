# 04 — Silent Upgrades (Phase 1 + Phase 2)
**Assessment date:** 2026-11-01

This document is a step-by-step execution recipe for all changes that carry **no breaking API changes** — they can be applied, compiled, and tested without modifying any production Java source files (Phase 1) or with only trivial `pom.xml` version bumps (Phase 2).

Gate after each phase:
```bash
./mvnw clean verify
```
All tests must pass before moving to the next step.

---

## Phase 1 — Toolchain Fixes

These changes fix the most critical defects (wrong JDK in Docker, ancient Maven wrapper) without touching any dependency versions or Java source.

### Step 1.1 — Fix `Dockerfile` (🔴 Blocking)

**File:** [`Dockerfile`](../../../Dockerfile)

Replace the entire file with:

```dockerfile
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
```

**Verify:**
```bash
docker build -t epg-test:local .
docker run --rm epg-test:local java -version   # must print OpenJDK 21
```

> **Alternative runtime base:** If Azul Zulu is preferred for licensing reasons, use `azul/zulu-openjdk-alpine:21-jre` instead of `eclipse-temurin:21-jre-alpine`.

---

### Step 1.2 — Update Maven Wrapper to 3.9.9 (🟡 Silent)

**Option A — manual edit** of [`.mvn/wrapper/maven-wrapper.properties`](../../../.mvn/wrapper/maven-wrapper.properties):

```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
```

**Option B — regenerate** (requires Maven 3.8+ installed locally):
```bash
cd entity-persistence-gateway
mvn -N wrapper:wrapper -Dmaven=3.9.9
```

**Verify:**
```bash
./mvnw --version   # must print Apache Maven 3.9.9
```

---

### Step 1.3 — Update devcontainer base image (🟡 Silent)

**File:** [`.devcontainer/devcontainer.json`](../../../.devcontainer/devcontainer.json)

Change:
```json
"image": "mcr.microsoft.com/devcontainers/base:bullseye"
```
to:
```json
"image": "mcr.microsoft.com/devcontainers/base:bookworm"
```

And update Docker version:
```json
"ghcr.io/devcontainers/features/docker-outside-of-docker:1": {
    "version": "latest",
    ...
}
```

---

### Step 1.4 — Add `.dockerignore` (🟡 Silent)

Create a new file [`../../.dockerignore`](../../../.dockerignore) (repo root):

```
.git
.github
.context
.devcontainer
.vscode
target
*.md
doc
```

---

### Phase 1 Gate

```bash
./mvnw clean verify          # all unit tests green
docker build -t epg:phase1 . # Docker image builds with JDK 21
docker run --rm epg:phase1 java -version  # confirms JRE 21
```

---

## Phase 2 — Dependency Bumps (🟡 Silent)

### Step 2.1 — Spring Boot 3.4.13 → 3.5.3

In [`pom.xml`](../../../pom.xml) change the parent version:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.3</version>   <!-- was 3.4.13 -->
    <relativePath />
</parent>
```

**What to watch:**
- Spring Framework upgraded from 6.2.x to 6.3.x. No breaking changes for the patterns used here.
- Review the [Spring Boot 3.5 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes) for auto-configuration changes.
- The `spring.cloud.gateway.filter.local-response-cache.enabled: false` setting is retained — verify this property still exists under SB 3.5 / Spring Cloud Gateway.

**Verify:**
```bash
./mvnw clean verify
```
Look specifically for any deprecation warnings in actuator endpoint resolution or gateway filter configuration.

---

### Step 2.2 — Verify `jwks-rsa` 0.23.1 Resolves

This is an investigation step, not a version change.

```bash
./mvnw dependency:resolve -Dincludes=com.auth0:jwks-rsa
```

Expected output:
```
com.auth0:jwks-rsa:jar:0.23.1:compile -- module com.auth0.jwks.rsa
```

If resolution fails (artifact not found on Maven Central), downgrade to `0.22.2` which is confirmed on Central, and verify no API changes are needed in [`JwtAuthenticationService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/services/JwtAuthenticationService.java).

---

### Step 2.3 — Verify Redisson 3.52.0 Resolves

```bash
./mvnw dependency:resolve -Dincludes=org.redisson:redisson-spring-boot-starter
```

Confirm the version resolves from Central (the search returned 3.50.0, but 3.52.x is a valid release). No version change expected; this is a health check.

---

### Phase 2 Gate

```bash
./mvnw clean verify
docker build -t epg:phase2 .
```

All existing tests must pass. No new compiler warnings related to deprecated Spring APIs are acceptable without a tracking ticket.

---

## Checklist

| Step | Description | File | Status |
|------|-------------|------|--------|
| 1.1 | Fix Dockerfile JDK 17→21 | `Dockerfile` | ⬜ |
| 1.2 | Update Maven wrapper 3.6.3→3.9.9 | `.mvn/wrapper/maven-wrapper.properties` | ⬜ |
| 1.3 | Update devcontainer base image bullseye→bookworm | `.devcontainer/devcontainer.json` | ⬜ |
| 1.4 | Add `.dockerignore` | `.dockerignore` (new) | ⬜ |
| 2.1 | Bump Spring Boot 3.4.13→3.5.3 | `pom.xml` | ⬜ |
| 2.2 | Verify jwks-rsa 0.23.1 resolves | n/a | ⬜ |
| 2.3 | Verify redisson 3.52.0 resolves | n/a | ⬜ |
