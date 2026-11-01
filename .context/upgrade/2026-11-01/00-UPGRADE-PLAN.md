# Upgrade Plan — entity-persistence-gateway
**Assessment date:** 2026-11-01  
**Assessor:** Bob (AI)  
**Branch at time of assessment:** `dev` (no uncommitted changes)

---

## Executive Summary

The gateway is a Spring Boot 3.4 / Spring Cloud 2024.0 / Java 21 reactive application. The stack is fundamentally healthy: it already targets Java 21 and Jakarta EE. The majority of debt is in **toolchain artefacts** (Dockerfile uses Java 17, Maven wrapper is ancient) and **minor library version lag**. No dependency requires a disruptive API migration except the potential `redisson` downgrade anomaly and the `wiremock` 3→4 test-scope jump. Code-level debt is localised to one uninjected `ObjectMapper`, three `@Deprecated` internal methods, and two stubbed `TODO` methods.

**Risk profile:** LOW-MEDIUM. All breaking changes are confined to test scope or isolated services.

---

## Upgrade Inventory at a Glance

| Area | Debt Type | Risk | Sub-task |
|------|-----------|------|---------|
| Dockerfile — build stage Java version | Toolchain mismatch (17 vs 21) | 🔴 Breaking (image won't reflect runtime) | [02-TOOLCHAIN.md](02-TOOLCHAIN.md) |
| Dockerfile — runtime base image | Pinned Azul 17-jre-headless | 🔴 Breaking (wrong JRE) | [02-TOOLCHAIN.md](02-TOOLCHAIN.md) |
| Maven wrapper | 3.6.3 → 3.9.x | 🟡 Silent | [02-TOOLCHAIN.md](02-TOOLCHAIN.md) |
| Spring Boot parent | 3.4.13 → 3.5.3 | 🟡 Silent (patch) | [01-DEPENDENCIES.md](01-DEPENDENCIES.md) |
| Spring Cloud BOM | 2024.0.3 → 2025.0.0 | 🟠 Minor breaking | [01-DEPENDENCIES.md](01-DEPENDENCIES.md) |
| Redisson | 3.52.0 → latest compatible | 🟡 Silent | [01-DEPENDENCIES.md](01-DEPENDENCIES.md) |
| JJWT | 0.12.7 → 0.12.7 (current is latest) | ✅ Up to date | — |
| jwks-rsa | 0.23.1 → (0.22.x is latest found) | ✅ Investigate | [01-DEPENDENCIES.md](01-DEPENDENCIES.md) |
| Lombok | 1.18.46 → 1.18.38 (pom is ahead) | ✅ Up to date | — |
| Jolokia | 2.5.1 → 2.2.9 (pom is ahead) | ✅ Up to date | — |
| networknt json-schema-validator | 1.5.6 → 1.5.6 | ✅ Up to date | — |
| swagger-parser-v3 | 2.1.40 → (2.1.27 on central — pom is ahead) | ✅ Up to date | — |
| swagger-models | 2.2.48 → (2.2.31 on central — pom is ahead) | ✅ Up to date | — |
| WireMock (test) | 3.13.2 → 4.0.0-beta | 🟠 Breaking (API changes) | [05-BREAKING-CHANGES.md](05-BREAKING-CHANGES.md) |
| OkHttp mockwebserver (test) | 4.12.0 → 5.x alpha | 🟠 Breaking (alpha, API changes) | [05-BREAKING-CHANGES.md](05-BREAKING-CHANGES.md) |
| embedded-redis (test) | 1.4.3 → 1.4.3 | ✅ Up to date | — |
| `ObjectMapper` not injected in `BackendSchemaService` | Code debt | 🟡 Silent | [03-CODE-DEBT.md](03-CODE-DEBT.md) |
| `@Deprecated` methods in `OasFieldPermissionService` | Code debt | 🟡 Silent cleanup | [03-CODE-DEBT.md](03-CODE-DEBT.md) |
| Stubbed `apply()` in `AbstractPolicyAwareResponsePayloadModifierFilterFactory` | Code debt | 🟡 Verify/remove | [03-CODE-DEBT.md](03-CODE-DEBT.md) |
| Unresolved `TODO` comments in filter classes | Code debt | 🟡 Clarify | [03-CODE-DEBT.md](03-CODE-DEBT.md) |
| devcontainer base image | Debian Bullseye (EOL) | 🟡 Silent | [02-TOOLCHAIN.md](02-TOOLCHAIN.md) |

---

## Execution Order

```
Phase 1 — Toolchain   (no source changes, fastest path to green image)
Phase 2 — Silent deps (bump versions, verify compile + test green)
Phase 3 — Code debt   (ObjectMapper injection, @Deprecated removal)
Phase 4 — Breaking    (Spring Cloud 2025, WireMock 4, OkHttp 5 if applicable)
```

Each phase should be executed on a separate git branch and gated by:
```
./mvnw clean verify
```

---

## Sub-task Documents

| File | Scope |
|------|-------|
| [01-DEPENDENCIES.md](01-DEPENDENCIES.md) | Full dependency matrix with current vs target versions |
| [02-TOOLCHAIN.md](02-TOOLCHAIN.md) | Dockerfile, Maven wrapper, devcontainer fixes |
| [03-CODE-DEBT.md](03-CODE-DEBT.md) | Source-level issues: `ObjectMapper`, `@Deprecated`, `TODO`s |
| [04-SILENT-UPGRADES.md](04-SILENT-UPGRADES.md) | Step-by-step recipe for Phase 1 + Phase 2 (no breaking changes) |
| [05-BREAKING-CHANGES.md](05-BREAKING-CHANGES.md) | Phase 3 + Phase 4: Spring Cloud 2025, WireMock 4, OkHttp 5 recipes |
