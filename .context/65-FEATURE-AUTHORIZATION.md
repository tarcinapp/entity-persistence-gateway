# Authorization: Policy-Based Access Control with OPA

## 1. FEATURE IDENTITY & PURPOSE

**Authorization** in the gateway is a policy-driven decision layer that evaluates every protected request using Open Policy Agent (OPA), with support for field-level restrictions and response-safe shaping.

It works together with authentication, but solves a different problem:
- **Authentication** answers: "Who is the caller?"
- **Authorization** answers: "What is this caller allowed to do here, with this payload/query/path?"

### The Problem

A generic persistence API needs fine-grained controls beyond simple role checks:
- Route-level permission checks (`createEntity`, `findListsByEntityId`, etc.)
- Payload-aware decisions for write operations
- Query-aware decisions for read/bulk update operations
- Field-level protection in both request and response paths
- Consistent deny behavior across all controllers and aliases

Hardcoding this in gateway code would be brittle and slow to evolve.

### The Solution

The gateway acts as a **Policy Enforcement Point (PEP)** and delegates decisions to OPA:
- Builds a structured `PolicyData` context from JWT + request + payload/original record
- Executes route-specific authorization policies (`AuthorizeRequest`)
- Fetches forbidden field rules (`FetchForbiddenFields`) via a central policy
- Applies those rules in downstream filters (query blocking, payload restoration, response filtering)
- Uses Redis caching for safe, read-only policy checks

This architecture keeps policy logic external, auditable, and changeable without redeploying gateway code.

---

## 2. AUTHORIZATION MODEL & CORE CONCEPTS

### 2.1 Providers and JWT Validation

Authorization depends on authenticated identity. JWT validation is configured under `app.auth.providers`:

```yaml
app:
  auth:
    providers:
      - issuer: tarcinapp-idm
        public-key: "<rsa-public-key>"
        clockSkewSeconds: 60
```

Supported provider styles:
- **JWKS-backed provider** (`jwk-set-uri`) for rotating keys
- **Static RSA public key provider** (`public-key`) for internal issuers

At runtime:
1. JWT issuer (`iss`) is extracted from token payload
2. Matching parser is selected per issuer
3. Signature + claims are validated with configured skew
4. Security context is populated (`subject`, `roles`, `groups`, `azp`)

If no providers are configured, auth/authorization filters currently run in a permissive bypass mode (with warnings in logs).

### 2.2 PolicyData (Authorization Input Contract)

Every policy call receives `PolicyData` as OPA input. Key fields include:
- `policyName`
- `appShortcode`
- `operation` (`find`/`update`)
- `httpMethod`, `requestPath`, `queryParams`
- `encodedJwt`
- `requestPayload` (for payload-bearing routes)
- `originalRecord` (for routes that require pre-update state)

`operation` is normalized by HTTP method in policy-data builders:
- `GET`/`DELETE` -> `find`
- `POST` -> `find` (used for response field visibility after creation)
- `PATCH`/`PUT` -> `update`

### 2.3 Builder Selection by Route

`PolicyDataBuilderRegistry` maps route IDs to specific builders (for example):
- `createEntity` -> payload builder
- `updateEntityById` -> payload + original record builder
- `findEntities` -> query-only builder
- through/hierarchy routes -> specialized builders with parent context

This keeps OPA input consistent while matching each route's semantics.

### 2.4 Route-Level Policy Decisions

Each route config includes explicit policy path wiring:

```yaml
- name: AuthorizeRequest
  args:
    policyName: /policies/auth/routes/entities/createEntity/policy
```

This means access is decided per route operation, not only per controller.

### 2.5 Forbidden Fields as Centralized Policy

A second authorization call fetches field restrictions via a shared policy:

```text
/policies/gateway/forbidden_fields/policy/result
```

Returned rules are stored in gateway context and reused by filters such as:
- `PreventQueryByForbiddenFields`
- `AddForbiddenFieldsFromOriginalToPayloadInReplace`
- `FieldFilter`

This gives consistent field-level enforcement across query, payload, and response stages.

### 2.6 Safe Authorization Caching

`RedisCacheAuthorizationClient` caches policy responses only for **read-only methods**:
- Cacheable: `GET`, `HEAD`, `OPTIONS`
- Not cacheable: `POST`, `PUT`, `PATCH`, `DELETE`

Cache key shape:
- `auth:policy:{policyName}:{sha256(jwt)}`

TTL is derived from JWT `exp`, so cached decisions never outlive token validity.

---

## 3. CONFIGURATION

### 3.1 Enable Auth Configuration Import

`application.yml` imports auth config by default:

```yaml
spring:
  config:
    import:
      - classpath:application-auth.yml
```

### 3.2 Configure Providers

Default template (`application-auth.yml`):

```yaml
app:
  auth:
    providers: []
```

Typical dev override (`application-dev.properties`):

```properties
app.auth.providers[0].issuer=tarcinapp-idm
app.auth.providers[0].public-key=<rsa-public-key>
```

### 3.3 Configure OPA Endpoint

OPA connection is configured in `app-outbound.yml`:

```yaml
app:
  outbound:
    opa:
      protocol: https
      host: entity-persistence-gateway-policies
      port: 443
      connectTimeoutMs: 500
      readTimeoutMs: 300
      writeTimeoutMs: 300
```

### 3.4 Route Wiring Pattern

Protected routes follow this filter order pattern:

```yaml
filters:
  - AuthenticateRequest
  - FetchForbiddenFields
  - name: AuthorizeRequest
    args:
      policyName: /policies/auth/routes/<controller>/<routeId>/policy
  - RemoveRequestHeader=Authorization
  - FieldFilter
```

Actual routes include operation-specific policy names for all controllers and kind-alias variants.

---

## 4. RUNTIME FLOW

### 4.1 End-to-End Request Sequence

1. `AuthenticateRequest`
2. JWT validation via issuer-specific parser
3. Security context initialization (`subject`, roles, groups, token)
4. PolicyData preparation via route-mapped builder
5. `FetchForbiddenFields` executes central forbidden-fields policy
6. `AuthorizeRequest` executes route-specific allow/deny policy
7. Downstream filters enforce field/query restrictions
8. Authorization header is removed before backend forwarding

### 4.2 OPA Execution Behavior

`OpaClient` sends policy requests to `/v1/data/{policyName}` and applies:
- response timeout
- retry with backoff for retryable timeout cases
- typed response mapping (`PolicyResult` or specialized result type)

### 4.3 Why Forbidden-Fields Fetch Happens Early

By retrieving forbidden fields before major mutation/query filters, the gateway can:
- block forbidden query predicates early
- prevent forbidden-field overwrite in replace flows
- guarantee response field redaction consistency

---

## 5. ERROR HANDLING & SECURITY POSTURE

### 5.1 Authentication Errors

`AuthenticateRequest` maps failures to clear statuses:
- Invalid/missing token: `401 Unauthorized`
- Policy-source timeout: `504 Gateway Timeout`
- Upstream lookup `404` (where applicable): `404 Not Found`
- Upstream 5xx in auth-context fetch path: `502 Bad Gateway`

### 5.2 Authorization Decision Outcomes

`AuthorizeRequest` behavior:
- Policy `allow=true` -> continue
- Policy `allow=false` -> `403 Forbidden`
- Policy execution error (OPA/client failure) -> `500 Internal Server Error`

### 5.3 Forbidden Fields Fetch Failures

`FetchForbiddenFields` is fail-closed for policy execution errors:
- Failure to retrieve rules -> request is blocked (`500`)

This prevents accidental data exposure when the policy system is unavailable.

### 5.4 Secure-by-Design Defaults

- Authorization is externalized to OPA policies (single source of truth)
- Write-method policy calls are never cached (prevents payload-based cache poisoning)
- Cached decisions expire with JWT expiration
- Authorization header is stripped before request forwarding

---

## 6. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Route Toggles** | Disabled routes return 404 before authorization checks complete |
| **Domain Projection** | OPA receives resolved route/path context after projection decisions |
| **Field Filtering** | Forbidden fields library drives response redaction and query restrictions |
| **Managed Fields** | Replace/update filters preserve protected fields based on policy outputs |
| **Dynamic OAS** | Uses the same forbidden-fields policy path to generate permission-aware schemas |
| **Rate Limiting / Locks** | Authorization runs in the same route pipeline as throttling and concurrency controls |

---

## 7. FURTHER READING

- **[20-FILTERS.md](20-FILTERS.md)** - Detailed filter-level reference (`AuthenticateRequest`, `AuthorizeRequest`, forbidden-fields filters)
- **[55-FEATURE-DOMAIN-PROJECTION.md](55-FEATURE-DOMAIN-PROJECTION.md)** - Domain aliasing and hierarchy behavior that influence policy context
- **[50-FEATURE-ROUTE-TOGGLES.md](50-FEATURE-ROUTE-TOGGLES.md)** - Route availability controls and evaluation order
- **[60-FEATURE-DYNAMIC-OAS-GENERATION.md](60-FEATURE-DYNAMIC-OAS-GENERATION.md)** - Permission-aware OpenAPI generation using forbidden-fields policy
- **[05-REPO-CONTEXT.md](05-REPO-CONTEXT.md)** - High-level architecture and feature map

---

**Last Updated:** March 2026
