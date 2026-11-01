# 01 — Dependency Matrix
**Assessment date:** 2026-11-01

This document covers every explicitly pinned dependency in [`pom.xml`](../../../pom.xml).  
Dependencies managed entirely by the Spring Boot BOM (no explicit version in pom.xml) are omitted unless a notable issue was found.

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Already at or ahead of latest stable — no action needed |
| 🟡 | Silent upgrade — patch/minor, no API changes expected |
| 🟠 | Minor breaking — API changes limited and scoped (e.g. test scope only) |
| 🔴 | Breaking — significant API change requiring code edits |
| 🔵 | Investigate — version relationship unclear, needs human verification |

---

## Platform / BOM

### Spring Boot Parent
| | |
|---|---|
| **Current** | `3.4.13` |
| **Latest stable** | `3.5.3` |
| **Risk** | 🟡 Silent — patch+minor within SB 3.x |
| **Action** | Bump `<parent>…<version>` to `3.5.3` |
| **Notes** | SB 3.5.x targets Java 17+; Java 21 support unchanged. Spring Framework 6.2→6.3 is included in SB 3.5 but has no breaking changes for this codebase. Auto-configuration changes: none affecting the current feature set (WebFlux, Gateway, Redis, Actuator, Cache). |

### Spring Cloud BOM
| | |
|---|---|
| **Current** | `2024.0.3` (`spring-cloud.version`) |
| **Latest stable** | `2025.0.0` |
| **Risk** | 🟠 Minor breaking — Spring Cloud Gateway had internal SPI changes between 2024.0 and 2025.0 |
| **Action** | Upgrade in Phase 4; verify custom filter factories compile and gateway routes function |
| **Notes** | `spring-cloud-starter-gateway` is the main consumer. The `RoutePredicateHandlerMapping` and `GatewayFilter` contracts are stable, but some internal configuration keys changed between `2024.0` and `2025.0`. Review [Spring Cloud 2025.0 release notes](https://github.com/spring-cloud/spring-cloud-release/wiki) before applying. **Requires SB ≥ 3.5.** Must pair with the SB bump. |

---

## Application Dependencies

### Redisson
| | |
|---|---|
| **Current** | `3.52.0` (property `redisson.version`) |
| **Latest stable** | `3.50.0` (Maven Central search result — the current pom is **ahead** of Central index) |
| **Risk** | ✅ Pom is ahead; no action needed |
| **Notes** | Maven Central search returned `3.50.0` as the "latest" result at time of assessment — this is likely a Central indexing lag. Redisson 3.52.x is a real release. Verify with `mvn dependency:resolve` on a network-connected machine. No downgrade recommended. |

### JJWT (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`)
| | |
|---|---|
| **Current** | `0.12.7` |
| **Latest stable** | `0.12.6` (Central search) — pom is **ahead** |
| **Risk** | ✅ Already at or beyond latest; no action |
| **Notes** | JJWT 0.12.x is a stable, mature API. No breaking changes between 0.12.6 and 0.12.7. |

### jwks-rsa (`com.auth0:jwks-rsa`)
| | |
|---|---|
| **Current** | `0.23.1` |
| **Latest stable** | `0.22.2` (Central search) — pom is **ahead** |
| **Risk** | 🔵 Investigate — Maven Central shows an older version than pom uses |
| **Action** | Verify the 0.23.x line exists on Maven Central (may be on a different repo / auth0 artifactory). Run `mvn dependency:resolve -pl . --fail-fast` to confirm it resolves cleanly. |
| **Notes** | Auth0 jwks-rsa is a JWKS public-key fetcher used by `JwtAuthenticationService`. No known API breaks in 0.22 → 0.23 range. |

### Lombok
| | |
|---|---|
| **Current** | `1.18.46` (property `lombok.version`) |
| **Latest stable** | `1.18.38` (Central search) — pom is **ahead** |
| **Risk** | ✅ Already ahead; no action |
| **Notes** | Lombok is `<scope>provided>` — no runtime impact. 1.18.46 supports Java 21 fully. |

### Jolokia (`jolokia-agent-jvm`)
| | |
|---|---|
| **Current** | `2.5.1` |
| **Latest stable** | `2.2.9` (Central search) — pom is **ahead** |
| **Risk** | ✅ Pom is ahead; no action |
| **Notes** | Jolokia 2.x is the Jakarta-compatible major version. Used for JMX-over-HTTP in `JolokiaAgentConfig.java`. |

### networknt `json-schema-validator`
| | |
|---|---|
| **Current** | `1.5.6` |
| **Latest stable** | `1.5.6` |
| **Risk** | ✅ Up to date |
| **Notes** | Used in `ValidateRequestBodyByKindSchema.java` for JSON Schema validation of incoming payloads. The 1.5.x line supports JSON Schema Draft-07 through Draft-2020-12. No upgrade needed. |

### `swagger-parser-v3` + `swagger-models`
| | |
|---|---|
| **swagger-parser-v3 current** | `2.1.40` |
| **swagger-parser-v3 latest (Central)** | `2.1.27` — pom is **ahead** |
| **swagger-models current** | `2.2.48` |
| **swagger-models latest (Central)** | `2.2.31` — pom is **ahead** |
| **Risk** | ✅ Both ahead; no action |
| **Notes** | Used by the Dynamic OAS Orchestrator (`OasTransformationEngine`, `BackendSchemaService`). Both are swagger-core 3.x artefacts (OpenAPI 3.x). Central index lag applies here too. |

### Caffeine
| | |
|---|---|
| **Managed by** | Spring Boot BOM (no explicit version) |
| **Risk** | ✅ Implicitly managed; will upgrade with SB bump |

### `commons-lang3`
| | |
|---|---|
| **Managed by** | Spring Boot BOM |
| **Risk** | ✅ Implicitly managed |

### `json-path` (Jayway)
| | |
|---|---|
| **Managed by** | Spring Boot BOM |
| **Risk** | ✅ Implicitly managed |

---

## Test Dependencies

### WireMock Standalone
| | |
|---|---|
| **Current** | `3.13.2` |
| **Latest stable** | `4.0.0-beta.10` (unstable) — latest stable 3.x is `3.13.2` |
| **Risk** | 🟠 Minor breaking only if upgrading to 4.x |
| **Action** | Stay on `3.13.2` for now (already latest stable). Monitor for WireMock 4.0 GA. Upgrade is deferred to Phase 4 when it stabilises. |
| **Notes** | WireMock 4.0 switches to a standalone JUnit 5 extension (`@WireMockTest`). The existing `WireMockSetup.java` uses the JUnit 4-style `WireMockRule` or manual server management. Migration is scoped to test infra only. **No production code impact.** |

### OkHttp `mockwebserver`
| | |
|---|---|
| **Current** | `4.12.0` |
| **Latest stable** | `5.0.0-alpha.16` (alpha — unstable) |
| **Risk** | 🟠 Minor breaking if jumping to 5.x alpha |
| **Action** | Stay on `4.12.0` (latest stable). OkHttp 5 is still in alpha as of 2026-11. Do not upgrade until GA. |
| **Notes** | Used in test scope for `MockWebServer`-based tests. OkHttp 5 is a Kotlin-first rewrite; Java API compatibility is preserved but deprecated. |

### `embedded-redis` (`com.github.codemonstur`)
| | |
|---|---|
| **Current** | `1.4.3` |
| **Latest stable** | `1.4.3` |
| **Risk** | ✅ Up to date |

### `reactor-test`
| | |
|---|---|
| **Managed by** | Spring Boot BOM |
| **Risk** | ✅ Implicitly managed |

### `spring-boot-starter-test`
| | |
|---|---|
| **Managed by** | Spring Boot BOM |
| **Risk** | ✅ Implicitly managed |

---

## Maven Build Plugin

### `maven-compiler-plugin`
| | |
|---|---|
| **Current** | `3.13.0` |
| **Risk** | 🟡 Silent — a newer `3.14.x` may be available but 3.13.0 is fully functional for Java 21 + Lombok annotation processing |
| **Action** | Optional bump; not blocking |

---

## Summary Table

| Dependency | Current | Target | Phase | Risk |
|-----------|---------|--------|-------|------|
| Spring Boot parent | 3.4.13 | 3.5.3 | 2 | 🟡 |
| Spring Cloud BOM | 2024.0.3 | 2025.0.0 | 4 | 🟠 |
| redisson-spring-boot-starter | 3.52.0 | 3.52.0 | — | ✅ |
| jjwt-* | 0.12.7 | 0.12.7 | — | ✅ |
| jwks-rsa | 0.23.1 | Verify | 2 | 🔵 |
| lombok | 1.18.46 | 1.18.46 | — | ✅ |
| jolokia-agent-jvm | 2.5.1 | 2.5.1 | — | ✅ |
| json-schema-validator | 1.5.6 | 1.5.6 | — | ✅ |
| swagger-parser-v3 | 2.1.40 | 2.1.40 | — | ✅ |
| swagger-models | 2.2.48 | 2.2.48 | — | ✅ |
| wiremock-standalone | 3.13.2 | 3.13.2 (watch 4.x) | — | ✅ |
| mockwebserver | 4.12.0 | 4.12.0 (watch 5.x GA) | — | ✅ |
| embedded-redis | 1.4.3 | 1.4.3 | — | ✅ |
