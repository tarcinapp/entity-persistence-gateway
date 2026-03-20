# Rate Limiting: Redis-Backed Traffic Shaping for Generic and Domain-Projected Routes

## 1. FEATURE IDENTITY & PURPOSE

**Rate Limiting** is the gateway capability that protects downstream services from overload and enforces fair request distribution across callers.

It is implemented with Spring Cloud Gateway's Redis token-bucket algorithm and runs on virtually every API route, including both:
- generic controller routes (for example, `/entities`, `/lists`, `/relations`), and
- domain-projected kind-alias routes (for example, `/books`, `/orders`, hierarchical alias paths).

### The Hard Problem Solved

Without gateway-level throttling, bursty traffic can quickly saturate backend services (Entity Persistence Service, OPA, Redis-dependent filters), causing elevated latency and cascading failures.

In this system, the challenge is stronger because two routing modes coexist:
- **Static generic routes** with known route IDs and static limits.
- **Dynamic domain-projected routes** where effective limits may depend on resolved kind aliases at runtime.

The gateway solves this by combining two complementary filters:
- `RequestRateLimiter` for static/generic routes.
- `DynamicRateLimiter` for kind-alias/domain-projected routes.

---

## 2. IMPLEMENTATION MODEL

### 2.1 Static Route Limiting (`RequestRateLimiter`)

Generic routes in `application-routes.yml` use the built-in filter:

```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter:
      replenishRate: ${app.rate-limits.entities.findEntities.replenishRate}
      burstCapacity: ${app.rate-limits.entities.findEntities.burstCapacity}
```

This model is route-centric: each route ID resolves to fixed configuration values from `application-rate-limits.yml`.

### 2.2 Dynamic Kind-Aware Limiting (`DynamicRateLimiter`)

Kind-alias routes (domain projection) use:

```yaml
- KindResolution
- name: DynamicRateLimiter
  args:
    replenishRate: ${app.rate-limits.entities.findEntities.replenishRate}
    burstCapacity: ${app.rate-limits.entities.findEntities.burstCapacity}
```

At runtime, `DynamicRateLimiter`:
1. Reads resolved kind metadata from `KindAliasConfigAttr` (set by `KindResolution`).
2. Uses route ID as operation name.
3. Tries kind+operation limits first.
4. Falls back to kind default.
5. Falls back to route args.
6. Falls back to hard defaults (`10/20`) if nothing is defined.

It then calls `RedisRateLimiter.isAllowed(...)` with an effective, dynamically constructed route key.

---

## 3. TOKEN BUCKET SEMANTICS

Both limiter paths use Redis token-bucket semantics:

- `replenishRate`: tokens added per second.
- `burstCapacity`: maximum bucket size.

Practical behavior:
- A client may burst up to `burstCapacity` immediately.
- Sustained throughput is bounded by `replenishRate`.
- Once depleted, requests receive `429 Too Many Requests` until tokens refill.

---

## 4. KEY RESOLUTION STRATEGY

Rate limiting fairness depends on how caller keys are derived.

### 4.1 Generic Routes (`UserKeyResolver`)

`UserKeyResolver` key format:
- authenticated: `authSubject:operation`
- unauthenticated fallback: `operation:ip`

Where:
- `authSubject` comes from `GatewaySecurityContext` (set by `AuthenticateRequest`),
- `operation` is the matched route ID,
- `ip` is request remote address.

### 4.2 Domain-Projected Routes (`DynamicRateLimiter#resolveKey`)

`DynamicRateLimiter` adds kind-awareness to isolate buckets across aliases:
- authenticated: `authSubject:kind:operation`
- unauthenticated fallback: `kind:operation:ip`

This prevents high traffic on one domain alias from consuming quota intended for another alias.

---

## 5. CONFIGURATION HIERARCHY

### 5.1 Base Configuration Files

Rate limiting is split across:
- `application-rate-limits.yml` (limits definition),
- `application-routes.yml` (filter wiring per route),
- `application.yml` (imports),
- Redis connectivity from outbound/app config.

### 5.2 Global, Controller, Route Defaults

`application-rate-limits.yml` defines a hierarchy like:

```yaml
app:
  rate-limits:
    default:
      replenishRate: 10
      burstCapacity: 20
    entities:
      default:
        replenishRate: ${app.rate-limits.default.replenishRate}
        burstCapacity: ${app.rate-limits.default.burstCapacity}
      findEntities:
        replenishRate: ${app.rate-limits.entities.default.replenishRate}
        burstCapacity: ${app.rate-limits.entities.default.burstCapacity}
```

### 5.3 Kind-Specific Overrides (Domain Projection)

For projected routes, kind overrides follow this path:

- `app.rate-limits.<recordType>.kinds.<kindName>.<operation>.replenishRate`
- `app.rate-limits.<recordType>.kinds.<kindName>.<operation>.burstCapacity`

Kind default fallback:

- `app.rate-limits.<recordType>.kinds.<kindName>.default.replenishRate`
- `app.rate-limits.<recordType>.kinds.<kindName>.default.burstCapacity`

Example:

```yaml
app:
  rate-limits:
    entities:
      default:
        replenishRate: 10
        burstCapacity: 20
      kinds:
        book:
          default:
            replenishRate: 20
            burstCapacity: 40
          findAllEntitiesByKindAlias:
            replenishRate: 60
            burstCapacity: 120
          createEntityByKindAlias:
            replenishRate: 15
            burstCapacity: 30
```

---

## 6. DOMAIN PROJECTION INTEGRATION

Rate limiting is tightly integrated with the Domain Projection pipeline.

### 6.1 Filter Ordering on Kind-Alias Routes

Typical order:
1. `KindResolution` resolves alias to concrete kind.
2. `AuthenticateRequest` builds `GatewaySecurityContext`.
3. `GenerateRequestId` stamps tracing ID.
4. `DynamicRateLimiter` enforces kind-aware throttle.
5. Authorization/validation/transformation filters continue.

This ordering ensures the limiter can use both:
- resolved kind (`kindName`), and
- caller identity (`authSubject`) when available.

### 6.2 Runtime Effect

For two routes sharing the same base controller but different aliases:
- `GET /books` and `GET /orders`

the limiter can apply different quotas because keys and config resolution are kind-aware.

### 6.3 Hierarchical Kind-Alias Routes

Hierarchy routes (for example, `findEntityHierarchyByKindAlias`, `createListHierarchyByKindAlias`) also use `DynamicRateLimiter`, so nested domain APIs inherit the same kind-aware throttling model.

---

## 7. REDIS DEPENDENCY

Rate limiting relies on Redis as shared state for token buckets.

Typical binding:

```yaml
spring:
  data:
    redis:
      host: ${app.outbound.redis.host}
      port: ${app.outbound.redis.port}
      database: ${app.outbound.redis.database}
      password: ${app.outbound.redis.password}
```

If Redis is unavailable, rate limiting behavior degrades according to underlying Spring/Redis limiter error handling and may affect route availability.

---

## 8. RESPONSE BEHAVIOR & ERROR SEMANTICS

When a request exceeds quota:
- HTTP status: `429 Too Many Requests`
- Body: none (empty body for dynamic limiter path)

The dynamic OAS generation logic documents 429 accordingly as a no-body response.

Additional edge behavior:
- If a kind-alias route reaches `DynamicRateLimiter` without resolved kind configuration, it fails with `404 Not Found` (missing kind alias config context).

---

## 9. OPERATIONAL GUIDANCE

### 9.1 Tuning Strategy

Start with broad defaults, then tighten where needed:
1. Set conservative global defaults.
2. Set controller defaults by workload profile.
3. Override hot routes.
4. Add kind-level overrides for projected domains with distinct traffic patterns.

### 9.2 Common Production Patterns

- Higher read limits for public catalog-like aliases.
- Lower write limits on mutation-heavy routes.
- Tight limits on destructive or expensive aggregation endpoints.
- Separate limits for parent-child hierarchy operations when they are burst-prone.

### 9.3 Security/Abuse Posture

Because keys include user identity (or IP fallback), abuse by one principal does not automatically consume bucket capacity for other principals in the same route/kind partition.

---

## 10. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Authentication** | Supplies `authSubject` used in rate-limit key derivation; fallback is caller IP when unauthenticated. |
| **Domain Projection** | `KindResolution` feeds kind context to `DynamicRateLimiter`, enabling per-kind and per-kind-operation limits. |
| **Route Toggles** | Route availability is checked in the same pipeline; disabled routes return 404 before normal processing completes. |
| **Distributed Locks** | Both operate in the request pipeline: rate limiting controls request volume, locks control write concurrency. |
| **Dynamic OAS Generation** | Generated OAS includes `429` as a standard response for projected endpoints. |

---

## 11. FURTHER READING

- **[70-FEATURE-DOMAIN-PROJECTION.md](70-FEATURE-DOMAIN-PROJECTION.md)** - alias resolution, hierarchy behavior, and projected route semantics
- **[40-FEATURE-AUTHENTICATION.md](40-FEATURE-AUTHENTICATION.md)** - identity extraction used in limiter key generation
- **[75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md)** - route enablement/disablement interactions
- **[20-FILTERS.md](20-FILTERS.md)** - filter reference, including `RequestRateLimiter` and `DynamicRateLimiter`
- **[10-ROUTES.md](10-ROUTES.md)** - route IDs used as operation names in dynamic rate-limit lookup