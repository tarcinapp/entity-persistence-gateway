# 03 — Code Debt
**Assessment date:** 2026-11-01

This document covers source-level issues found during static analysis of `src/main/java`. All items are scoped to production code. Test-scope issues are noted separately at the end.

---

## 1. Uninjected `ObjectMapper` in `BackendSchemaService`

**File:** [`src/main/java/.../oas/service/BackendSchemaService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/service/BackendSchemaService.java) — line 58

### Problem

```java
// BackendSchemaService.java — constructor accepts ObjectMapper but ignores it
public BackendSchemaService(BackendOasClient backendOasClient,
        OasOrchestratorProperties properties,
        ObjectMapper objectMapper) {       // ← received but NOT stored
    this.backendOasClient = backendOasClient;
    this.properties = properties;
    // objectMapper is silently dropped
}

// Then later, a new instance is created manually:
private ObjectMapper createSchemaObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();                          // ← line 58
    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    return mapper;
}
```

The constructor signature accepts `ObjectMapper` (which Spring injects the shared, pre-configured bean into) but immediately discards it. A private factory method then instantiates a `new ObjectMapper()` directly, bypassing Spring's global configuration (Jackson date handling, `JavaTimeModule`, etc.).

### Risk
- The handmade mapper is missing all global configuration (e.g., `write-dates-as-timestamps: false` from `application.yml`).
- A separate mapper instance means Jackson modules registered globally do not apply, potentially causing inconsistent serialization when schema nodes contain date-like properties.
- Minor performance overhead (object allocation on every call to `createSchemaObjectMapper()`).

### Fix (Phase 3 — Code Debt)

Store the injected `ObjectMapper`, copy it with `NON_NULL` inclusion (to avoid mutating the shared bean), and delete `createSchemaObjectMapper()`:

```java
private final ObjectMapper schemaObjectMapper;

public BackendSchemaService(BackendOasClient backendOasClient,
        OasOrchestratorProperties properties,
        ObjectMapper objectMapper) {
    this.backendOasClient = backendOasClient;
    this.properties = properties;
    // Derive a schema-specific mapper from the shared bean; do NOT mutate the original
    this.schemaObjectMapper = objectMapper.copy()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
}
```

Replace all usages of `createSchemaObjectMapper()` with `this.schemaObjectMapper`.

**Breaking:** No. Pure internal improvement.

---

## 2. Stubbed `apply(C, PR)` Method in `AbstractPolicyAwareResponsePayloadModifierFilterFactory`

**File:** [`src/main/java/.../filters/base/AbstractPolicyAwareResponsePayloadModifierFilterFactory.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/base/AbstractPolicyAwareResponsePayloadModifierFilterFactory.java) — lines 194–198

### Problem

```java
@Override
// TODO: why I have this code here?
public GatewayFilter apply(C config, PR policyResult) {
    // TODO Auto-generated method stub
    return null;  // ← always returns null
}
```

This is an IDE-generated stub that was never implemented. Returning `null` from a method annotated with `@Override` in a filter factory is dangerous: if any code path ever calls this overload instead of the other `apply(C)`, the gateway filter chain will receive `null` and throw a `NullPointerException`.

### Investigation needed

Determine whether this overload is:
1. **Dead code** — no caller ever invokes `apply(C config, PR policyResult)` on this class. In that case: **remove the method**.
2. **Required by the abstract parent** — if `AbstractPolicyAwareFilterFactory` declares this abstract, then a proper implementation is needed.

Grep result confirms the parent class declares it abstract:
```
src/main/java/.../filters/base/AbstractPolicyAwareFilterFactory.java
```
The concrete subclasses of `AbstractPolicyAwareResponsePayloadModifierFilterFactory` (e.g., `FieldFilterGatewayFilterFactory`, `ProjectDomainIncludeAliasInResponse`) should be checked to see if they override it meaningfully.

### Fix (Phase 3 — Code Debt)

- If the two-argument `apply` is never called at runtime (verify with a log or test), **remove the override** from the abstract class and add a `throw new UnsupportedOperationException()` instead of returning `null`.
- If it is called, implement it properly by delegating to the single-argument form or to the actual filter logic.

**Breaking:** No (internal implementation detail).

---

## 3. Three `@Deprecated` Methods in `OasFieldPermissionService`

**File:** [`src/main/java/.../oas/security/OasFieldPermissionService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/security/OasFieldPermissionService.java) — lines 232, 264, 285

### Problem

Three methods are marked `@Deprecated` with the comment:
> "Use `fetchMultiOperationPermissions` instead for proper per-operation permissions."

These are:
- `fetchFieldPermissions(GatewaySecurityContext, String)` — public, may have callers
- `fetchAnonymousPermissions(String)` — private
- `buildPolicyData(GatewaySecurityContext, String)` — private

If the new multi-operation API is the recommended path, these methods are dead code waiting to be removed.

### Action (Phase 3 — Code Debt)

1. **Grep for callers** of `fetchFieldPermissions` outside `OasFieldPermissionService` itself:
   ```bash
   grep -r "fetchFieldPermissions" src/
   ```
2. If no external callers exist → **delete all three methods**.
3. If external callers exist → **migrate them** to `fetchMultiOperationPermissions` before deleting.

**Breaking:** No (internal service; no public API surface).

---

## 4. Unresolved `TODO` Comments

Several production files contain `TODO` comments that represent unanswered design questions:

| File | Line | TODO Text |
|------|------|-----------|
| [`AddSetsToEntityListOrReactionViaRecordQuery.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/common/request/AddSetsToEntityListOrReactionViaRecordQuery.java) | 98 | `// TODO: Any risk here?!` |
| [`AddSetsToEntityListOrReactionViaRecordQuery.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/common/request/AddSetsToEntityListOrReactionViaRecordQuery.java) | 124 | `// TODO: Should I ask this to PEP?` |
| [`AddSetsToRelationQuery.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/common/request/AddSetsToRelationQuery.java) | 137 | `// TODO: Should I ask this to PEP?` |
| [`AddSetsToReactionsQuery.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/common/request/AddSetsToReactionsQuery.java) | 75 | `// TODO: Any risk here?!` |
| [`BackendSchemaService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/service/BackendSchemaService.java) | 317 | `return null; // TODO implement if needed` |
| [`AbstractPolicyAwareResponsePayloadModifierFilterFactory.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/filters/base/AbstractPolicyAwareResponsePayloadModifierFilterFactory.java) | 194 | `// TODO: why I have this code here?` |

### "Any risk here?!" TODOs (lines 98, 75)

These are in query-set injection filters. The question likely refers to whether a set can safely override a user-supplied filter parameter. These are design questions that should be answered, documented, and the TODO replaced with a comment explaining the decision.

### "Should I ask this to PEP?" TODOs (lines 124, 137)

"PEP" = Policy Enforcement Point (OPA). The question is whether a query-set filter should call OPA before applying its transformation. This is an architectural decision. Until answered, the current behaviour (not calling OPA) should be explicitly documented as intentional.

### `BackendSchemaService` line 317

```java
return null; // TODO implement if needed
```

A `null` return from a service method is a bug waiting to happen. Either:
- Implement the method properly, or
- Throw `UnsupportedOperationException` and document why it is unimplemented.

### Fix (Phase 3 — Code Debt)

For each TODO:
1. Investigate the context.
2. Either implement or replace with a clear `// DECISION: <rationale>` comment.
3. The `null` return in `BackendSchemaService:317` must be resolved before Phase 4.

---

## 5. `@PostConstruct .block()` in `BackendSchemaService`

**File:** [`src/main/java/.../oas/service/BackendSchemaService.java`](../../../src/main/java/com/tarcinapp/entitypersistencegateway/oas/service/BackendSchemaService.java) — lines 63–68

```java
@PostConstruct
public void initializeSchemas() {
    log.info("Initializing Backend Schema Service...");
    fetchAndCompileSchemas()
            .block(Duration.ofSeconds(60));   // ← blocking call on Netty event loop
}
```

Calling `.block()` during application startup inside a `@PostConstruct` method on a Reactor Netty thread is an antipattern. In a WebFlux application, Netty I/O threads must never block. While `@PostConstruct` typically runs on a Spring initialisation thread (not a Netty thread), this is fragile and may cause `BlockingOperationError` in reactive strict mode.

### Fix (Phase 3 — Code Debt)

Use a `SmartLifecycle` bean or a `ApplicationRunner` instead:

```java
@Component
public class BackendSchemaInitializer implements SmartLifecycle {
    private final BackendSchemaService service;
    private volatile boolean running = false;

    @Override
    public void start() {
        service.fetchAndCompileSchemas()
               .block(Duration.ofSeconds(60));
        running = true;
    }

    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; } // after all beans
}
```

Or, simplest safe fix: move to `ApplicationRunner` which runs on the main thread post-context-refresh:

```java
@Bean
ApplicationRunner schemaInitRunner(BackendSchemaService svc) {
    return args -> svc.fetchAndCompileSchemas().block(Duration.ofSeconds(60));
}
```

**Risk:** 🟡 Silent — the current code works but is fragile.

---

## 6. Test-Scope Code Issues

These are lower priority (test code only) but worth noting:

| Issue | Files |
|-------|-------|
| `new ObjectMapper()` in tests | `OasTransformationEngineTest`, `NullExampleRemovalTest`, `AuthorizeRequestTest` — acceptable in tests since no Spring context is available |
| Unused constructor parameter `objectMapper` in `BackendSchemaService` | Causes a minor confusion about DI contract (fixed by issue #1 above) |

---

## Summary Table

| # | Issue | File | Risk | Phase |
|---|-------|------|------|-------|
| 1 | Injected `ObjectMapper` silently dropped; `new ObjectMapper()` used instead | `BackendSchemaService.java:58` | 🟡 Silent | 3 |
| 2 | Stubbed `apply(C,PR)` returns `null` | `AbstractPolicyAwareResponsePayloadModifierFilterFactory.java:195` | 🟡 Silent / potential NPE | 3 |
| 3 | Three `@Deprecated` methods not yet removed | `OasFieldPermissionService.java:232,264,285` | 🟡 Silent cleanup | 3 |
| 4 | Unresolved `TODO` comments (6 locations) | Various | 🟡 Clarify | 3 |
| 5 | `.block()` in `@PostConstruct` | `BackendSchemaService.java:67` | 🟡 Silent / fragile | 3 |
