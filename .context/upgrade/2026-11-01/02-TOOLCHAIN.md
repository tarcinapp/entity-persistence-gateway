# 02 — Toolchain Debt
**Assessment date:** 2026-11-01

This document covers the build toolchain, container images, and development environment configuration.

---

## 1. Dockerfile — Critical Java Version Mismatch

**File:** [`Dockerfile`](../../../Dockerfile)

```dockerfile
# CURRENT (BROKEN FOR JAVA 21 CODE)
FROM maven:3.8.4-openjdk-17 AS builder   # ← builds with JDK 17
...
FROM azul/zulu-openjdk:17-jre-headless-latest  # ← runs on JRE 17
```

### Problem
The `pom.xml` declares:
```xml
<java.version>21</java.version>
<maven.compiler.release>21</maven.compiler.release>
```

The Dockerfile build stage uses `openjdk-17`, meaning:
- `javac --release 21` will **fail** inside the Docker builder because JDK 17 does not support `--release 21`.
- Even if it somehow compiled (it won't), the runtime image also runs JRE 17 which cannot execute Java 21 class files.

**This is a build-breaking defect in the container image.** The app presumably works in local dev (where a JDK 21 is installed) but the Docker image cannot be built as-is.

### Fix — Phase 1 (Toolchain)

```dockerfile
# FIXED
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
COPY . /workspace/
WORKDIR /workspace
RUN mvn package -Dmaven.test.skip=true

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

**Changes explained:**
- `maven:3.9.9-eclipse-temurin-21-alpine` — Maven 3.9.x on JDK 21 (Alpine is smaller).
- `eclipse-temurin:21-jre-alpine` — Official Eclipse Adoptium JRE 21 (widely used, supported, small).
- Added `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` — best practice for containerized JVMs.
- Removed `azul/zulu-openjdk` — not wrong, but Eclipse Temurin is the community standard. Zulu can be kept if organisational preference mandates it: swap to `azul/zulu-openjdk:21-jre-headless-latest`.

**Risk:** 🔴 Blocking — **must fix before any Docker-based deployment**.

---

## 2. Maven Wrapper — Ancient Version

**File:** [`.mvn/wrapper/maven-wrapper.properties`](../../../.mvn/wrapper/maven-wrapper.properties)

```properties
# CURRENT
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.6.3/apache-maven-3.6.3-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/io/takari/maven-wrapper/0.5.6/maven-wrapper-0.5.6.jar
```

### Problem
- Maven **3.6.3** was released in 2019. Current LTS is **3.9.9** (2025).
- Maven 3.6.x lacks `maven-wrapper` plugin support (`mvnw` is driven by the old Takari wrapper), meaning `./mvnw` may not pick up the `wrapperUrl` correctly in newer environments.
- Maven 3.8+ introduced important fixes for dependency resolution, reproducible builds, and plugin compatibility.
- Several plugins in the project (e.g. `spring-boot-maven-plugin`) work best with Maven 3.8+.

### Fix — Phase 1 (Toolchain)

Update `.mvn/wrapper/maven-wrapper.properties` to use the modern Maven wrapper format:

```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
```

Alternatively, regenerate the wrapper properly:
```bash
mvn -N wrapper:wrapper -Dmaven=3.9.9
```
This also regenerates `mvnw` and `mvnw.cmd` with the proper shebang/script for the modern wrapper.

**Risk:** 🟡 Silent — no source changes required, just wrapper property file update.

---

## 3. devcontainer — EOL Base Image

**File:** [`.devcontainer/devcontainer.json`](../../../.devcontainer/devcontainer.json)

```json
"image": "mcr.microsoft.com/devcontainers/base:bullseye"
```

### Problem
- Debian **Bullseye** (11) reached End-of-Life on **June 2024**.
- The `devcontainers/base:bullseye` image is no longer receiving security patches.
- The Docker-outside-of-Docker feature requests Docker `20.10` which is also EOL (Docker CE 27.x is current).

### Fix — Phase 1 (Toolchain)

Update `.devcontainer/devcontainer.json`:

```json
{
  "name": "entity-persistence-gateway devcontainer",
  "image": "mcr.microsoft.com/devcontainers/base:bookworm",
  "features": {
    "ghcr.io/devcontainers/features/docker-outside-of-docker:1": {
      "version": "latest",
      "enableNonRootDocker": "true",
      "moby": "true"
    },
    "ghcr.io/devcontainers/features/git:1": {},
    "ghcr.io/devcontainers-contrib/features/springboot-sdkman:2": {}
  }
}
```

**Key changes:**
- `bullseye` → `bookworm` (Debian 12, supported until 2028).
- Docker version `20.10` → `latest`.
- Removed `apt-get-packages` feature with `telnet,iputils-ping,dnsutils,dnsmasq` — these are debug tools that should not be hardcoded in the devcontainer base. Add as-needed or move to a `postCreateCommand`.

**Risk:** 🟡 Silent — development environment only, no production impact.

---

## 4. Missing `.dockerignore`

### Problem
There is no `.dockerignore` file. The `COPY . /workspace/` instruction in the Dockerfile copies:
- `.git/` directory (~MB depending on history)
- `.context/` documentation
- `target/` if it exists locally
- IDE files (`.vscode/`, `.devcontainer/`)

This bloats the build context and can expose sensitive files.

### Fix — Phase 1 (Toolchain)

Create `.dockerignore`:
```
.git
.github
.context
.devcontainer
.vscode
target
*.md
```

**Risk:** 🟡 Silent — build time and image hygiene improvement only.

---

## Summary Table

| Issue | File | Risk | Phase |
|-------|------|------|-------|
| JDK 17 in Dockerfile (should be 21) | `Dockerfile` | 🔴 Blocking | 1 |
| Runtime JRE 17 in Dockerfile | `Dockerfile` | 🔴 Blocking | 1 |
| Maven wrapper 3.6.3 → 3.9.9 | `.mvn/wrapper/maven-wrapper.properties` | 🟡 Silent | 1 |
| devcontainer Bullseye → Bookworm | `.devcontainer/devcontainer.json` | 🟡 Silent | 1 |
| No `.dockerignore` | (create) | 🟡 Silent | 1 |
