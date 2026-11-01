# 05 — Breaking Changes (Phase 3 + Phase 4)
**Assessment date:** 2026-11-01

This document covers the changes that require source code modifications or carry a non-trivial API migration risk. They are deferred to later phases to be done after the project is green on Phase 1 and Phase 2.

Gate after each phase:
```bash
./mvnw clean verify
docker build -t epg:phaseN .
```

---

## Phase 3 — Code Debt Resolution

These changes are isolated Java edits. No external API contracts change. They can be executed independently in any order within the phase.

### Step 3.1 — Fix `ObjectMapper` injection in `BackendSchemaService`

**File:** [`src/.../oas/service/BackendSchemaService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/service/BackendSchemaService.java)

**Context:** Detailed in [03-CODE-DEBT.md §1](03-CODE-DEBT.md#1-uninjected-objectmapper-in-backendschemaservice).

**Change:**

```java
// BEFORE
private final BackendOasClient backendOasClient;
private final OasOrchestratorProperties properties;

public BackendSchemaService(BackendOasClient backendOasClient,
        OasOrchestratorProperties properties,
        ObjectMapper objectMapper) {
    this.backendOasClient = backendOasClient;
    this.properties = properties;
}

private ObjectMapper createSchemaObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    return mapper;
}
```

```java
// AFTER
private final BackendOasClient backendOasClient;
private final OasOrchestratorProperties properties;
private final ObjectMapper schemaObjectMapper;   // ← add field

public BackendSchemaService(BackendOasClient backendOasClient,
        OasOrchestratorProperties properties,
        ObjectMapper objectMapper) {
    this.backendOasClient = backendOasClient;
    this.properties = properties;
    this.schemaObjectMapper = objectMapper.copy()  // ← copy; don't mutate shared bean
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
}

// DELETE createSchemaObjectMapper() entirely
// Replace all callers: createSchemaObjectMapper() → this.schemaObjectMapper
```

**Test:** Run `./mvnw test -pl . -Dtest=OasTransformationEngineTest` and all OAS-related unit tests.

---

### Step 3.2 — Resolve stubbed `apply(C, PR)` in `AbstractPolicyAwareResponsePayloadModifierFilterFactory`

**File:** [`src/.../filters/base/AbstractPolicyAwareResponsePayloadModifierFilterFactory.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/base/AbstractPolicyAwareResponsePayloadModifierFilterFactory.java)

**Context:** Detailed in [03-CODE-DEBT.md §2](03-CODE-DEBT.md#2-stubbed-applyc-pr-method).

**Pre-step — verify dead code:**
```bash
grep -rn "\.apply(" src/main/java/ | grep -v "chain.filter\|GatewayFilter"
```

If no external callers → remove the override:

```java
// DELETE these lines entirely:
@Override
// TODO: why I have this code here?
public GatewayFilter apply(C config, PR policyResult) {
    // TODO Auto-generated method stub
    return null;
}
```

If the parent abstract class forces the override, replace with:
```java
@Override
public GatewayFilter apply(C config, PR policyResult) {
    throw new UnsupportedOperationException(
        "Use apply(C config) instead; two-argument form is not supported.");
}
```

---

### Step 3.3 — Remove `@Deprecated` methods from `OasFieldPermissionService`

**File:** [`src/.../oas/security/OasFieldPermissionService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/security/OasFieldPermissionService.java)

**Context:** Detailed in [03-CODE-DEBT.md §3](03-CODE-DEBT.md#3-three-deprecated-methods-in-oasfieldpermissionservice).

**Pre-step — find callers:**
```bash
grep -rn "fetchFieldPermissions\b" src/
```

If no callers outside the class itself → delete all three deprecated methods (`fetchFieldPermissions`, `fetchAnonymousPermissions`, `buildPolicyData`).

If callers exist → migrate them to `fetchMultiOperationPermissions` first, then delete.

---

### Step 3.4 — Resolve `TODO` comments

**Context:** Detailed in [03-CODE-DEBT.md §4](03-CODE-DEBT.md#4-unresolved-todo-comments).

For each TODO:

1. **`BackendSchemaService.java:317` — `return null;`**
   - Read the method signature and determine what a null return means to callers.
   - If callers can handle null: document with `// INTENTIONAL: returns null when X, callers must null-check`.
   - If callers cannot handle null: throw `UnsupportedOperationException("Not yet implemented: <method name>")`.

2. **"Any risk here?!" TODOs** (filter classes):
   - Inspect the surrounding code to understand what "risk" the author was worried about.
   - Replace with: `// VERIFIED SAFE: <explanation>` or raise a GitHub issue if the risk is real.

3. **"Should I ask this to PEP?" TODOs** (query-set filters):
   - Current behaviour does NOT call OPA. Document this as intentional:
   - `// DECISION: OPA is NOT consulted here because <reason>. Revisit if <condition>.`

---

### Step 3.5 — Address `@PostConstruct .block()` in `BackendSchemaService`

**File:** [`src/.../oas/service/BackendSchemaService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/service/BackendSchemaService.java)

**Context:** Detailed in [03-CODE-DEBT.md §5](03-CODE-DEBT.md#5-postconstruct-block-in-backendschemaservice).

**Change:** Move schema initialisation from `@PostConstruct` to an `ApplicationRunner` bean in a `@Configuration` class:

```java
// In a @Configuration class (e.g., OasOrchestratorConfig or a new OasInitConfig)
@Bean
ApplicationRunner backendSchemaInitRunner(BackendSchemaService backendSchemaService) {
    return args -> backendSchemaService.fetchAndCompileSchemas()
                                       .block(Duration.ofSeconds(60));
}
```

Delete the `@PostConstruct initializeSchemas()` method from `BackendSchemaService`.

**Test:** Start the application in local dev mode and confirm the OAS schemas are loaded (check the log line `"Initializing Backend Schema Service..."`).

---

### Phase 3 Gate

```bash
./mvnw clean verify
# Zero test failures, zero new compiler warnings
```

---

## Phase 4 — Spring Cloud 2025.0 Upgrade

This is the highest-effort and highest-risk change. It must be done on its own branch (`upgrade/spring-cloud-2025`) and fully validated before merging.

**Prerequisite:** Phase 1 + Phase 2 + Phase 3 must be complete (app runs on SB 3.5.3).

### Step 4.1 — Bump Spring Cloud BOM

In [`pom.xml`](../../../pom.xml):

```xml
<spring-cloud.version>2025.0.0</spring-cloud.version>
```

Spring Cloud 2025.0.0 requires **Spring Boot ≥ 3.5.0** — confirmed satisfied after Phase 2.

---

### Step 4.2 — Review Gateway Configuration Key Changes

Spring Cloud Gateway between `2024.0` and `2025.0` introduced the following configuration changes:

| Old key | New key | Note |
|---------|---------|------|
| `spring.cloud.gateway.httpclient.pool.max-life-time` | unchanged | Verify property still accepted |
| `spring.cloud.gateway.filter.local-response-cache.enabled` | unchanged | Already set to `false` |
| `spring.cloud.gateway.metrics.enabled` | unchanged | |
| `spring.cloud.gateway.default-filters` | unchanged | |

**Action:** After bumping, run the app and check for `UnknownPropertyException` or `BindException` in the startup log against [`application.yml`](../../../src/main/resources/application.yml) and the imported config files.

---

### Step 4.3 — Verify Custom Filter Factory Compilation

Spring Cloud 2025.0 changed some internal classes. Check the following for any compilation errors:

- All classes extending `AbstractGatewayFilterFactory` — constructor signatures are unchanged in 2025.0.
- `GatewayFilter` interface — unchanged.
- `ServerWebExchange` / `GatewayFilterChain` — unchanged.
- `RoutePredicateHandlerMapping` — internal, should not affect this codebase.

**Specific classes to review:**

| Class | Risk |
|-------|------|
| [`AbstractPolicyAwareFilterFactory`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/base/AbstractPolicyAwareFilterFactory.java) | Low — uses standard SCG APIs |
| [`DynamicRateLimiter`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/common/request/DynamicRateLimiter.java) | Medium — rate limiter internals changed in SCG 4.x |
| [`DynamicLocalCacheGatewayFilterFactory`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/common/request/DynamicLocalCacheGatewayFilterFactory.java) | Medium — local cache filter removed from SCG 4.x default |

**DynamicRateLimiter — known SCG 4.x change:**
Spring Cloud Gateway 4.x (part of 2025.0 BOM) dropped the `redis-rate-limiter.*` YAML shorthand in favour of the `RedisRateLimiter` bean configuration. Verify that `DynamicRateLimiter.java` does not rely on the old `ServerWebExchangeUtils.getExchangeAttribute(GATEWAY_RATE_LIMITER_RESPONSE_ATTR)` pattern which was removed.

---

### Step 4.4 — Validate All Routes

After compilation, start the application with the dev profile and verify:

```bash
curl http://localhost:8081/actuator/gateway/routes
```

All routes defined in [`application-routes.yml`](../../../src/main/resources/application-routes.yml) must appear. Any route that fails to initialize due to an unknown filter or predicate name will be logged as a `WARNING` or cause a startup failure.

---

### Step 4.5 — WireMock 4.x (Optional, when stable)

**Current:** `wiremock-standalone:3.13.2` — **latest stable 3.x; no action needed**.

This step is for when WireMock 4.0 reaches GA.

**WireMock 4.0 breaking changes (preview):**
- `WireMockRule` (JUnit 4 style) is removed → replace with `@WireMockTest` or `WireMockExtension`.
- `WireMock.configureFor(...)` moved to `WireMockServer.options()`.
- Admin API client consolidated.

**Files to check when the time comes:**
- [`WireMockSetup.java`](../../../src/test/java/com/tarcinapp/entitypersistencegateway/util/WireMockSetup.java) — contains the WireMock server lifecycle management.

**Do not upgrade until 4.0 GA.** Track: https://github.com/wiremock/wiremock/releases

---

### Step 4.6 — OkHttp `mockwebserver` 5.x (Optional, when GA)

**Current:** `mockwebserver:4.12.0` — **latest stable; no action needed**.

OkHttp 5.x is Kotlin-first. The `MockWebServer` class API is preserved but many constructors changed to use `MockWebServer.Builder`. No migration until 5.0.0 GA.

---

### Phase 4 Gate

```bash
./mvnw clean verify
# All tests green, zero deprecated API usage warnings
# Integration smoke test against a running instance
```

---

## Risk Register

| Change | Risk | Mitigation |
|--------|------|-----------|
| Spring Cloud 2025.0.0 | Gateway internals changed | Dedicated branch, full route validation |
| `DynamicRateLimiter` with new SCG rate limiter API | Compilation or runtime error | Review SCG 4.x migration guide before applying |
| `DynamicLocalCacheGatewayFilterFactory` | Filter may have been restructured | Smoke-test cache behaviour with integration test |
| WireMock 4.x (future) | Test infrastructure rewrite | Defer until 4.0 GA; scope to test files only |
| OkHttp 5.x (future) | Kotlin interop changes | Defer until 5.0 GA; minimal test impact |
