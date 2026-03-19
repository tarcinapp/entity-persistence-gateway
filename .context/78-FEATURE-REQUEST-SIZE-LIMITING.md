# Request Size Limiting: Early Payload Guardrails for Generic and Kind-Alias Routes

## 1. FEATURE IDENTITY & PURPOSE

**Request Size Limiting** is the gateway capability that enforces maximum payload sizes before expensive downstream processing begins.

It protects both:
- generic controller routes (for example, `/entities`, `/lists`, `/relations`), and
- domain-projected kind-alias routes (for example, `/books`, `/orders`, hierarchy alias paths).

### The Hard Problem Solved

Without strict payload limits at the gateway edge, oversized request bodies can increase memory pressure, consume reactive worker resources, and amplify abuse impact before authorization, validation, or persistence logic executes.

In this gateway, the challenge is twofold:
- **Static generic routes** can use fixed, route-level limits.
- **Dynamic kind-alias routes** may require kind-specific size policies resolved at runtime.

The gateway solves this with two complementary filters:
- `RequestSize` for static/generic routes.
- `DynamicRequestSizeFilter` for kind-alias/domain-projected routes.

---

## 2. IMPLEMENTATION MODEL

### 2.1 Static Route Limiting (`RequestSize`)

Generic write routes wire Spring Cloud Gateway's built-in filter:

```yaml
- name: RequestSize
  args:
    maxSize: ${app.request-sizes.entities.create}
```

This model is route-centric: each route references a concrete property (for example, `entities.create` or `entities.update`).

### 2.2 Dynamic Kind-Aware Limiting (`DynamicRequestSizeFilter`)

Kind-alias routes wire a custom filter:

```yaml
- KindResolution
- name: DynamicRequestSizeFilter
  args:
    maxSize: ${app.request-sizes.entities.create}
```

At runtime, `DynamicRequestSizeFilter`:
1. Resolves `recordType` from route config/metadata.
2. Reads resolved kind context from `KindAliasConfigAttr` (set by `KindResolution`).
3. Maps HTTP method to operation (`create` for `POST`, `update` for `PUT`/`PATCH`).
4. Resolves effective max size with fallback hierarchy:
   - `app.request-sizes.<recordType>.kinds.<kindName>.<operation>`
   - `app.request-sizes.<recordType>.<operation>`
   - `app.request-sizes.default.<operation>`
   - filter arg `maxSize` (config fallback)
5. Compares request size against resolved limit and rejects if exceeded.

---

## 3. CONFIGURATION HIERARCHY

### 3.1 Base Configuration Files

Request size policy is split across:
- `application-request-sizes.yml` (limits definition),
- `application-routes.yml` (filter wiring per route),
- `application.yml` (imports and composition),
- optional environment overrides in `application-dev.properties`.

### 3.2 Default and Record-Type Limits

`application-request-sizes.yml` defines a layered structure:

```yaml
app:
  request-sizes:
    default:
      create: 2KB
      update: 2KB
    entities:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}
    lists:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}
    relations:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}
    reactions:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}
```

### 3.3 Kind-Specific Overrides

For domain-projected routes, kind-level overrides follow:
- `app.request-sizes.<recordType>.kinds.<kindName>.create`
- `app.request-sizes.<recordType>.kinds.<kindName>.update`

Example:

```yaml
app:
  request-sizes:
    entities:
      kinds:
        book:
          create: 10KB
          update: 8KB
```

### 3.4 Environment-Specific Overrides

Development can override defaults (for example in `application-dev.properties`):

```properties
app.request-sizes.default.create=5KB
app.request-sizes.default.update=5KB
```

---

## 4. OPERATION COVERAGE

Request size limiting applies only to HTTP methods that typically carry request bodies:
- `POST` -> `create`
- `PUT` -> `update`
- `PATCH` -> `update`

`GET` and `DELETE` are not size-limited by this feature path.

For kind-alias routes, operation classification is done in `DynamicRequestSizeFilter#determineOperation`.

---

## 5. FILTER ORDERING & EXECUTION PHASE

### 5.1 Generic Routes

On generic write routes, `RequestSize` is intentionally placed at the beginning of the filter chain so oversized payloads fail fast before security, locking, schema validation, or backend forwarding.

### 5.2 Kind-Alias Routes

Typical order on alias write routes:
1. `DynamicTimeout`
2. `KindResolution`
3. `DynamicRequestSizeFilter`
4. `CheckIfRouteEnabled`
5. remaining auth/authorization/validation/transformation filters

This ordering ensures size limits can be resolved with concrete kind metadata and still fail early in the lifecycle.

---

## 6. RUNTIME BEHAVIOR & RESPONSE SEMANTICS

### 6.1 Over-Limit Requests

When payload size exceeds the effective limit:
- HTTP status: `413 Payload Too Large`
- Response body: empty
- Request processing stops immediately

### 6.2 Missing Kind Context on Alias Routes

`DynamicRequestSizeFilter` depends on kind resolution context. If kind alias metadata is missing or unresolved at that point:
- HTTP status: `404 Not Found`
- Reason: kind configuration not found for alias

### 6.3 DataSize Parsing

Limits are parsed using Spring `DataSize` (for example `2KB`, `5MB`). Invalid configured values are logged and fallback resolution continues to lower-priority levels.

---

## 7. KEY DIFFERENCE: STATIC VS DYNAMIC ENFORCEMENT

| Path | Filter | Limit Source | Scope |
|------|--------|--------------|-------|
| Generic routes | `RequestSize` | Route `maxSize` property reference | Per route / per record type |
| Kind-alias routes | `DynamicRequestSizeFilter` | Runtime hierarchy (`kind -> recordType -> default -> config`) | Per route, per record type, optionally per kind |

Practical impact:
- Generic and alias routes can share sane defaults.
- High-volume or rich-payload kinds can receive tailored limits without forking route definitions.

---

## 8. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Domain Projection** | `KindResolution` provides `kindName` used by `DynamicRequestSizeFilter` to resolve kind-specific size policy. |
| **Dynamic Timeout** | Runs immediately before dynamic size checks on alias routes, keeping timeout and payload policy in the same early guardrail zone. |
| **Route Toggles** | Size checks run before most deeper processing; toggles are still enforced after early validation in current route order. |
| **Schema Validation** | Request size rejection occurs before expensive JSON schema validation, reducing unnecessary compute on oversized bodies. |
| **Rate Limiting** | Complements request-rate controls by constraining request volume (`rate`) and request weight (`size`). |

---

## 9. OPERATIONAL GUIDANCE

### 9.1 Tuning Strategy

Use a layered approach:
1. Define conservative global defaults (`default.create`, `default.update`).
2. Override by record type where payload characteristics differ.
3. Add kind-level overrides only where necessary.

### 9.2 Recommended Patterns

- Keep `update` limits at or below `create` unless full-replace payloads require larger sizes.
- Increase limits only for kinds with explicit schema need (for example large nested objects).
- Pair larger size limits with stricter rate limits to avoid abuse amplification.

### 9.3 Observability

`DynamicRequestSizeFilter` logs selected size source and over-limit rejections, which helps verify fallback behavior during rollout and incident analysis.

---

## 10. LIMITATIONS & EDGE CONSIDERATIONS

- `DynamicRequestSizeFilter` compares against `Content-Length`; clients that omit the header may not be constrained by this specific check path.
- Size limits are operation-based (`create`/`update`), not route-ID-specific; route-level differentiation should be expressed through route-configured `maxSize` defaults or recordType/kind hierarchy.
- Alias-route enforcement depends on correct `KindResolution` ordering.

---

## 11. FURTHER READING

- **[20-FILTERS.md](20-FILTERS.md)** - filter catalog, ordering, and behavior details
- **[55-FEATURE-DOMAIN-PROJECTION.md](55-FEATURE-DOMAIN-PROJECTION.md)** - kind alias resolution and route projection
- **[50-FEATURE-ROUTE-TOGGLES.md](50-FEATURE-ROUTE-TOGGLES.md)** - route enable/disable semantics
- **[75-FEATURE-RATE-LIMITING.md](75-FEATURE-RATE-LIMITING.md)** - request-rate protection patterns

---

**Last Updated:** March 2026
