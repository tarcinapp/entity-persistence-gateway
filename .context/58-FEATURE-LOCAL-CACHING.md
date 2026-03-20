# Local Caching: In-Memory GET Response Caching for Lower Latency and Backend Load

## 1. FEATURE IDENTITY & PURPOSE

**Local Caching** is the gateway capability that stores eligible `GET` responses in local process memory and serves repeated requests directly from memory until TTL expiry.

It is implemented by:
- `DynamicLocalCacheGatewayFilterFactory` (route filter), and
- `DynamicLocalCacheService` (Caffeine-backed in-memory store).

The feature is wired broadly on read routes across generic and domain-projected surfaces (entities, lists, relations, reactions, hierarchy and through routes).

### The Hard Problem Solved

Without a fast local cache, repeated read traffic (for example list refreshes, polling, and repeated detail reads) causes avoidable backend round-trips and higher latency.

The gateway solves this by:
- caching successful `GET` responses per user and canonical query,
- applying per-route TTL and size controls,
- honoring client and backend cache directives,
- supporting conditional request semantics (`ETag` / `If-Modified-Since`) for efficient `304 Not Modified` flows.

### Key Benefits

- **Lower latency:** cache hits avoid backend call overhead.
- **Lower backend pressure:** repeated reads are absorbed at gateway edge.
- **Per-route tuning:** TTL and size are configurable per operation.
- **User isolation:** authenticated users do not share the same cached entries.
- **HTTP-friendly behavior:** emits `ETag`, `Cache-Control`, and `X-Cache-Status`.

---

## 2. IMPLEMENTATION MODEL

### 2.1 Eligibility Rules

`DynamicLocalCache` only caches when all of the following are true:
- request method is `GET`,
- route cache `size` is configured and greater than `0`,
- client request does not force bypass with `Cache-Control: no-store`,
- backend response is `200 OK`,
- backend response is not marked `Cache-Control: no-store` or `private`.

If any condition fails, request is forwarded without cache write/read.

### 2.2 Read Path

1. Build cache key from method + path + sorted query string + user identity.
2. On hit:
   - evaluate preconditions (`If-Unmodified-Since`) and validators (`If-None-Match`, `If-Modified-Since`),
   - return `304` where applicable,
   - otherwise return cached payload with `X-Cache-Status: HIT`.
3. On miss: continue to backend.

### 2.3 Write Path

On eligible backend `200` response:
1. Capture response body and selected headers.
2. Compute effective TTL as `min(routeTTL, backend max-age)` when backend provides `Cache-Control: max-age`.
3. Compute `ETag` (MD5 of response body).
4. Store in local cache.
5. Return response with `X-Cache-Status: MISS`, `ETag`, and normalized `Cache-Control`.

---

## 3. CACHE KEY STRATEGY

Cache key format:

`METHOD:PATH:?sortedQuery:userId`

Details:
- Query parameters are canonicalized (sorted keys and values), so logically equivalent query orders map to the same key.
- `userId` is `GatewaySecurityContext.authSubject` when authenticated; otherwise `public`.

Practical effect:
- two users hitting the same URL do not share the same authenticated cache entry,
- unauthenticated/public traffic shares the `public` segment.

---

## 4. TTL, SIZE, AND MEMORY MODEL

### 4.1 Route TTL and Backend TTL

Each route passes `timeToLive` and `size` arguments to `DynamicLocalCache`.

Final TTL decision:
- if backend sends `Cache-Control: max-age=<seconds>`, gateway uses the smaller of backend max-age and configured route TTL,
- otherwise configured route TTL is used.

This keeps gateway cache lifetime bounded by explicit backend freshness signals.

### 4.2 Per-Entry Expiration

`DynamicLocalCacheService` uses Caffeine with custom per-entry expiry. Each cached response carries its own TTL duration.

### 4.3 Memory Sizing

Global memory limit for all local entries is controlled by:
- `app.local-cache.global-total-size-mb` (fixed MB limit, optional), or
- `app.local-cache.heap-usage-percentage` (dynamic fraction of JVM max heap).

If fixed size is absent, cache weight limit is calculated from heap percentage.

### 4.4 Entry Weighting and Safety

Entry weight estimation uses:
- response body byte length, plus
- fixed metadata overhead.

Sensitive/volatile headers (`Authorization`, `Cookie`, `Set-Cookie`, proxy forwarding headers) are excluded from stored headers.

---

## 5. CONFIGURATION HIERARCHY

### 5.1 Base Configuration Files

Local caching configuration spans:
- `application-local-caching.yml` (cache hierarchy and defaults),
- `application-routes.yml` (`DynamicLocalCache` wiring per route),
- `application.yml` (imports `application-local-caching.yml`).

### 5.2 Hierarchical Configuration Shape

`application-local-caching.yml` follows this shape:

```yaml
app:
  local-cache:
    global-total-size-mb: null
    heap-usage-percentage: 0.20
    stats-enabled: false
    default:
      size: 0MB
      timeToLive: 30s
    entities:
      default:
        size: ${app.local-cache.default.size}
        timeToLive: ${app.local-cache.default.timeToLive}
      findEntities:
        size: ${app.local-cache.entities.default.size}
        timeToLive: ${app.local-cache.entities.default.timeToLive}
```

Equivalent trees exist for `lists`, `relations`, `entityReactions`, and `listReactions`.

### 5.3 Kind-Specific Dynamic Overrides

For kind-alias routes, the filter can resolve optional overrides from environment/config at runtime:

- `app.local-cache.<recordType>.kinds.<kindName>.<operation>.size`
- `app.local-cache.<recordType>.kinds.<kindName>.<operation>.timeToLive`

Fallback chain:
1. kind + operation,
2. kind default,
3. route args (`size`, `timeToLive`).

This enables selective caching policy per domain alias and operation.

---

## 6. CLIENT USAGE

### 6.1 Standard Read

Client issues normal `GET` request; gateway may return cached or fresh response.

Operational header:
- `X-Cache-Status: HIT` or `MISS`.

### 6.2 Force Bypass (Client-Controlled)

To skip cache read and force backend fetch, client sends:

```http
Cache-Control: no-cache
```

To bypass caching behavior fully for that call:

```http
Cache-Control: no-store
```

### 6.3 Conditional Requests

Clients can use conditional headers for bandwidth efficiency:

- `If-None-Match` with gateway-provided `ETag`,
- `If-Modified-Since` / `If-Unmodified-Since`.

Possible outcomes on cache hit:
- `304 Not Modified` when validators match,
- `412 Precondition Failed` for failed `If-Unmodified-Since` precondition,
- `200 OK` with cached body otherwise.

### 6.4 Example Flow

1. First request:
```http
GET /api/v1/entities?limit=20&offset=0
```
Response includes:
- `X-Cache-Status: MISS`
- `ETag: "<hash>"`

2. Repeated request:
```http
GET /api/v1/entities?offset=0&limit=20
```
(same logical query order) can return:
- `X-Cache-Status: HIT`

3. Conditional request:
```http
GET /api/v1/entities?limit=20&offset=0
If-None-Match: "<hash>"
```
can return:
- `304 Not Modified`

---

## 7. ROUTE COVERAGE & ORDERING

### 7.1 Coverage

`DynamicLocalCache` is applied on many read routes in `application-routes.yml`, including generic controllers and domain-projected/through/hierarchy variants.

Common cached read operations include:
- find collections,
- count operations,
- find by id,
- parent/child read traversals.

### 7.2 Filter Ordering Consideration

Caching is placed after request-shaping and authorization-related filters on read routes, so cache keys and cached payloads represent post-validation, authorized request context.

Because key includes user identity, cached payload reuse remains principal-scoped.

---

## 8. OPERATIONAL GUIDANCE

### 8.1 Tuning Strategy

1. Start with small TTL for high-change resources.
2. Increase size/TTL gradually for hot, read-heavy operations.
3. Keep mutation-heavy or highly volatile endpoints at `size: 0MB` (disabled).
4. Use kind-specific overrides for high-traffic aliases.

### 8.2 Observability

Useful signals:
- response header `X-Cache-Status` for per-request hit/miss,
- optional Caffeine stats via `app.local-cache.stats-enabled=true`.

### 8.3 Consistency Trade-Off

This feature is TTL-based in-process caching. It improves performance but introduces bounded staleness until entry expiry.

For data requiring near-zero staleness, keep TTL short or disable local cache for those operations.

---

## 9. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Authentication** | Cache key includes authenticated subject, preventing cross-user cache sharing for authenticated traffic. |
| **Authorization** | Caching runs after policy evaluation in route chains, so only authorized responses are cached for that user context. |
| **Fieldsets / Field Filtering** | On routes where payload-shaping filters execute after caching, cached payload can include post-backend but pre-late-filter state; response-phase filters still apply before final client output. |
| **Rate Limiting** | Both features reduce backend pressure: rate limiter controls request admission, local cache absorbs repeated reads that are admitted. |

---

## 10. FURTHER READING

- **[20-FILTERS.md](20-FILTERS.md)** - detailed filter catalog and ordering context
- **[50-FEATURE-RATE-LIMITING.md](50-FEATURE-RATE-LIMITING.md)** - request throttling complement
- **[80-FEATURE-QUERY-SIMPLIFICATION.md](80-FEATURE-QUERY-SIMPLIFICATION.md)** - query canonicalization inputs before cache participation
- **[85-FEATURE-FIELDSETS.md](85-FEATURE-FIELDSETS.md)** - response shaping behavior on read routes

---

**Last Updated:** March 2026