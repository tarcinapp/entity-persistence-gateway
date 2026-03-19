# Distributed Locking: Redis-Based Concurrency Control for Create and Update Flows

## 1. FEATURE IDENTITY & PURPOSE

**Distributed Locking** is the gateway capability that prevents conflicting concurrent writes across multiple gateway instances by coordinating lock ownership in Redis.

It is enforced at request time by dedicated gateway filters:
- `AcquireLockForCreation` for create flows,
- `AcquireLockForUpdate` for update/replace-by-id flows.

The feature applies across both:
- generic controller routes (`entities`, `lists`, `relations`, `entityReactions`, `listReactions`), and
- domain-projected kind-alias routes (`/books`, `/orders`, hierarchy aliases, and through-kind variants).

### The Hard Problem Solved

Without cross-instance locking, two or more concurrent requests can mutate the same logical resource at almost the same time, creating race conditions such as:
- duplicate create processing for semantically identical requests,
- near-simultaneous overwrite on the same record ID,
- inconsistent outcomes when retries arrive during in-flight writes.

In a horizontally scaled gateway, in-memory mutexes are insufficient because each instance only sees local state. The gateway solves this using Redis-backed distributed mutexes so lock ownership is shared globally.

### Key Benefits

- **Data consistency under concurrency:** serializes conflicting writes to the same lock scope.
- **Safer retries:** duplicate create attempts are throttled while the first write is in progress.
- **Cross-instance correctness:** works with multiple gateway pods/instances.
- **Operational predictability:** explicit lock wait/lease settings bound contention behavior.

---

## 2. IMPLEMENTATION MODEL

### 2.1 Create Flow Locking (`AcquireLockForCreation`)

Create routes acquire a lock before forwarding the request downstream.

Two key derivation strategies are used:
1. **Idempotency header fast path** (`X-Idempotency-Key`) 
2. **Payload-hash fallback** (SHA-256 of request body)

This allows lock granularity to represent "same logical create request" even when clients do not provide an explicit idempotency key.

If lock acquisition fails within the configured wait time, the filter returns:
- `429 Too Many Requests`

### 2.2 Update/Replace Flow Locking (`AcquireLockForUpdate`)

Record update routes lock by path `recordId` (`PATCH`/`PUT` by ID).

If the record lock cannot be acquired in time, the filter returns:
- `423 Locked`

This ensures only one write operation can proceed for a specific record ID at a time.

---

## 3. LOCK KEY STRATEGY

### 3.1 Application-Scoped Namespace

All lock keys are prefixed with `app.shortcode` to isolate lock domains between deployments/environments sharing Redis.

### 3.2 Creation Keys

- Header path: `appShortcode:lock:creation:header:<idempotencyKey>`
- Payload path: `appShortcode:lock:creation:hash:<sha256(payload)>`

This design minimizes accidental collisions while still coalescing duplicate create attempts.

### 3.3 Update Keys

- Record write path: `appShortcode:lock-on-record-update:<recordId>`

This creates deterministic, per-record write serialization for by-id mutations.

---

## 4. LOCK LIFECYCLE & REACTIVE OWNERSHIP

Both lock filters use Redisson reactive locks (`RLockReactive`) with:
- `waitTime` (how long to wait to acquire),
- `leaseTime` (auto-expiry safety window).

In reactive execution, thread affinity is not stable. To preserve lock ownership semantics, both filters generate a per-request virtual thread ID and use that ID for both:
- `tryLock(...)`, and
- `unlock(...)`.

Release behavior:
- lock release is attempted in `doFinally`, so unlock runs on success, error, and cancellation paths,
- update flow intentionally avoids `forceUnlock()` to preserve ownership safety.

---

## 5. CONFIGURATION HIERARCHY

### 5.1 Base Configuration Files

Distributed locking is configured through:
- `application-locks.yml` (lock wait/lease values),
- `application-routes.yml` (filter wiring),
- `application.yml` (imports `application-locks.yml`),
- Redis outbound configuration (shared Redis dependency).

### 5.2 Defaults and Record-Type Scopes

`application-locks.yml` provides global defaults and per-record-type operation blocks:

```yaml
app:
  locks:
    default:
      waitTime: 3s
      leaseTime: 30s
    entities:
      create:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      update:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
```

Equivalent sections exist for `lists`, `relations`, and `reactions` (`create`, `update`, and where relevant `createChild`).

### 5.3 Route-Level Binding

Routes bind lock config values explicitly, for example:

```yaml
- name: AcquireLockForCreation
  args:
    waitTime: ${app.locks.entities.create.waitTime}
    leaseTime: ${app.locks.entities.create.leaseTime}

- AcquireLockForUpdate
```

---

## 6. ROUTE INTEGRATION PATTERN

### 6.1 Generic Route Coverage

Locking is wired on create and by-id update/replace routes across core controllers, including:
- `createEntity`, `updateEntityById`, `replaceEntityById`,
- `createList`, `updateListById`, `replaceListById`,
- `createRelation`, `updateRelationById`, `replaceRelationById`,
- reaction create/update/replace routes,
- child/hierarchy creation variants where write contention can occur.

### 6.2 Domain-Projected Route Coverage

Kind-alias variants use the same lock filters, including:
- `createEntityByKindAlias`, `updateEntityByIdByKindAlias`, `replaceEntityByIdByKindAlias`,
- corresponding list/relation/reaction alias routes,
- hierarchy and through-kind create flows.

This keeps concurrency guarantees consistent between generic and domain-projected API surfaces.

### 6.3 Filter Ordering

Typical write-route ordering:
1. `AuthenticateRequest`
2. rate limiting
3. `FetchForbiddenFields`
4. `AuthorizeRequest`
5. lock acquisition filter (`AcquireLockForCreation` or `AcquireLockForUpdate`)
6. downstream mutation filters and proxy forwarding

Placing lock acquisition after authorization avoids consuming lock budget for unauthorized requests.

---

## 7. RESPONSE BEHAVIOR & ERROR SEMANTICS

Lock contention outcomes:
- create contention -> `429 Too Many Requests` with duplicate-processing message,
- update contention -> `423 Locked` for record-level lock conflict.

These responses provide deterministic client behavior for retry/backoff strategies and protect downstream services from conflicting parallel writes.

---

## 8. OPERATIONAL GUIDANCE

### 8.1 Tuning Strategy

Start with conservative defaults, then tune by operation criticality:
1. keep `waitTime` short for high-throughput APIs to fail fast under contention,
2. set `leaseTime` above normal request latency to avoid premature expiry,
3. use per-record-type overrides for hotspots (for example write-heavy reactions).

### 8.2 Idempotency Best Practice

For create endpoints, clients should send `X-Idempotency-Key` when possible. This avoids body hashing overhead and improves duplicate-request collapsing accuracy.

### 8.3 Redis Dependency Consideration

Distributed locking depends on Redis availability and latency. Since lock state is externalized, Redis health directly affects write-path concurrency protection behavior.

---

## 9. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Authentication & Authorization** | Lock filters are executed after identity and policy checks, so only authorized writes contend for locks. |
| **Rate Limiting** | Rate limiting absorbs abuse bursts first; locking then serializes conflicting writes that still pass quotas. |
| **Field Masking** | Replace flows can combine update locks with forbidden/managed field restoration filters to keep both concurrency and field governance safe. |
| **Domain Projection** | Kind-alias and hierarchy routes reuse the same lock model, preserving concurrency semantics across projected APIs. |

---

## 10. FURTHER READING

- **[65-FEATURE-AUTHORIZATION.md](65-FEATURE-AUTHORIZATION.md)** - policy enforcement and route protection sequence
- **[70-FEATURE-FIELD-MASKING.md](70-FEATURE-FIELD-MASKING.md)** - replace-flow payload protection model
- **[75-FEATURE-RATE-LIMITING.md](75-FEATURE-RATE-LIMITING.md)** - traffic shaping and Redis-backed throttling
- **[20-FILTERS.md](20-FILTERS.md)** - low-level filter responsibilities and ordering

---

**Last Updated:** March 2026