# Gateway Configuration Reference: Hierarchy, Resolution, and Operations

## 1. FEATURE IDENTITY & PURPOSE

This document is the dedicated configuration reference for the Entity Persistence Gateway.

It solves the hard problem of configuration discoverability across modular YAML files by providing:

1. A single map of all gateway configuration domains.
2. Multiple perspectives for reading configuration (layer-based, request-flow-based, override-scope-based).
3. Quick lookup tables with key, default value, sample value, and source file.

The Single Source of Truth remains `src/main/resources/*`. Files under `target/classes/*` are generated output and must not be edited.

---

## 2. CONFIGURATION PHILOSOPHY & HIERARCHY

### 2.1 Three-Tier Organization Model

The gateway uses a modular configuration architecture:

1. Tier 1 - Root Orchestrator: `application.yml`
2. Tier 2 - Domain Layers: `app-inbound.yml`, `app-outbound.yml`, `application-auth.yml`
3. Tier 3 - Feature Files: `application-rate-limits.yml`, `application-timeouts.yml`, `application-locks.yml`, `application-fieldsets.yml`, and others

### 2.2 Configuration Surface Segregation (app. vs spring.)

The gateway follows a deliberate two-surface configuration model:

1. Primary Surface: `app.*`
2. Runtime Surface: `spring.*` and related framework-native blocks (`server.*`, `management.*`)

Sample:

```yaml
app:
  inbound:
    port: 8081
spring:
  cloud:
    gateway:
      metrics:
        enabled: ${app.inbound.metricsEnabled}
server:
  port: ${app.inbound.port}
```

In normal operations, users should configure behavior through `app.*` keys. These keys are domain-oriented, grouped by gateway concerns, and reflected into runtime framework properties through mapping and interpolation.

Direct edits under `spring.*` should be treated as an expert path. If a user knows exactly what they are tuning and understands framework side effects, direct `spring.*` overrides are possible; for most cases, `app.*` is sufficient and preferred.

### 2.3 Inheritance & Resolution Strategy

Most operational features follow hierarchical fallback:

1. Global defaults
2. Controller defaults
3. Kind-specific defaults (optional)
4. Route-specific override

Property interpolation uses Spring placeholders to avoid duplication, for example:

```yaml
app:
  rate-limits:
    entities:
      default:
        replenishRate: ${app.rate-limits.default.replenishRate}
```

### 2.4 Perspectives Used in This Document

This reference is intentionally grouped by multiple perspectives:

1. Layer Perspective: inbound, outbound, security, runtime protection, observability.
2. Request Lifecycle Perspective: what config applies in each filter-chain step.
3. Override Scope Perspective: where defaults are inherited versus overridden.

---

## 3. PERSPECTIVE A: LAYER-BASED CONFIGURATION MAP

### 3.1 Root Orchestrator

Source: `application.yml`

Responsibilities:

1. Defines core app identity (`app.name`, `app.shortcode`, request id header).
  Sample: `app.name: entity-persistence-gateway`, `app.shortcode: tarcinapp`, `app.requestId: X-Request-Id`
  This identity block gives the gateway a stable naming surface for logs, metrics, request tracing, and namespace-sensitive runtime concerns.

2. Maps server and gateway runtime to app-level keys.
  Sample: `server.port: ${app.inbound.port}`, `spring.cloud.gateway.metrics.enabled: ${app.inbound.metricsEnabled}`
  This mapping keeps operational tuning centralized under `app.*` while still driving Spring-native runtime properties.

3. Imports all modular configuration files via `spring.config.import`.
  Sample: `- classpath:application-routes.yml`, `- classpath:application-rate-limits.yml`
  The import chain is the composition mechanism that stitches feature-specific files into one effective runtime configuration.

### 3.2 Inbound Layer

Source: `app-inbound.yml`

Responsibilities:

1. Public server bind and base URI.
  Sample: `address: 0.0.0.0`, `port: 8081`, `baseUri: /api/v1/`
  These keys define where the gateway listens and what external URI prefix is exposed to client applications.

2. Controller base path grammar for routing topology.
  Sample: `controllerBasePaths.entities: entities`, `controllerBasePaths.relations: relations`
  This grammar layer controls canonical URL segments and keeps route definitions predictable across generic and alias endpoints.

3. CORS allowlists and credential behavior.
  Sample: `allowedOrigins: ["http://localhost:8080"]`, `allowCredentials: true`, `maxAge: 3600`
  These values define browser trust boundaries for cross-origin clients and directly affect preflight behavior and header visibility.

4. Endpoint exposure control for actuator via shared key.
  Sample: `exposedEndpoints: "gateway,health,metrics,prometheus,jolokia,env,info"`
  This shared key is consumed by management configuration to determine which actuator endpoints are visible over HTTP.

### 3.3 Outbound Layer

Source: `app-outbound.yml`

Responsibilities:

1. Routing target backend connectivity and pool tuning.
  Sample: `routing-target.host: entity-persistence-service`, `pool.maxConnections: 500`
  These settings drive the default business traffic path and determine outbound throughput capacity under load.

2. Policy-source connectivity with fail-fast posture.
  Sample: `policy-source.connectTimeoutMs: 1000`, `policy-source.responseTimeout: PT1S`
  The policy data channel is intentionally strict to avoid blocking authorization flow when policy source dependencies are degraded.

3. OPA connectivity parameters.
  Sample: `opa.host: entity-persistence-gateway-policies`, `opa.port: 443`
  OPA endpoint parameters define where authorization and field-policy decisions are fetched at runtime.

4. Redis connectivity for shared control planes (rate limiting, locks, cache metadata).
  Sample: `redis.host: gateway-redis-master`, `redis.database: 0`
  Redis acts as distributed coordination storage for throttling state, lock state, and shared cache orchestration metadata.

### 3.4 Security Layer

Sources: `application-auth.yml`, `application-management.yml`

Responsibilities:

1. JWT provider definitions for multi-issuer token validation.
  Sample: `providers[*].issuer`, `providers[*].jwk-set-uri`, `providers[*].clockSkewSeconds`
  This model allows multiple identity providers to coexist and resolves token validation strategy by issuer.

2. Operational endpoint exposure posture (health, gateway, metrics, env).
  Sample: `management.endpoint.health.probes.enabled: true`, `management.endpoints.web.exposure.include: ${app.inbound.exposedEndpoints}`
  Operational endpoint settings determine observability depth while balancing security posture in public or internal environments.

### 3.5 Runtime Protection Layer

Sources: `application-rate-limits.yml`, `application-request-sizes.yml`, `application-locks.yml`, `application-timeouts.yml`, `application-route-toggles.yml`

Responsibilities:

1. Throughput control.
  Sample: `app.rate-limits.default.replenishRate: 10`, `app.rate-limits.default.burstCapacity: 20`
  Throughput control protects downstream services and enforces fair usage using token-bucket semantics.

2. Payload size guardrails.
  Sample: `app.request-sizes.default.create: 2KB`, `app.request-sizes.default.update: 2KB`
  Request size limits prevent oversized payloads from consuming excessive memory and processing resources.

3. Distributed concurrency control.
  Sample: `app.locks.default.waitTime: 3s`, `app.locks.default.leaseTime: 30s`
  Lock settings serialize conflicting operations and reduce race conditions across distributed gateway instances.

4. Latency budget enforcement.
  Sample: `app.timeouts.default.connectTimeoutMs: 3000`, `app.timeouts.default.responseTimeoutMs: 30000`
  Timeout budgets constrain slow downstream behavior and keep reactive worker resources from being pinned indefinitely.

5. Dynamic availability control of routes/controllers/tags.
  Sample: `app.toggles.routes.off`, `app.toggles.controllers.off`, `app.toggles.tags.off`
  Toggle-based availability lets operators disable problematic endpoints quickly without redeploying the gateway.

### 3.6 API Projection Layer

Sources: `application-fieldsets.yml`, `application-queries.yml`, `application-routes.yml`, `application-oas-orchestrator.yml`

Responsibilities:

1. Field projection defaults and named fieldsets.
  Sample: `app.fieldsets.global.hide-managed-except-id`, `app.fieldsets.entities.defaultFieldset`
  Fieldset configuration controls payload shaping so clients can receive policy-aligned and use-case-specific field views.

2. Saved query macro aliases.
  Sample: `app.queries.my: "'sets[owners][userIds]='+#userId"`
  Query macros provide short developer-friendly query entry points that expand into backend-compatible filter expressions.

3. Route-level filter chains and metadata.
  Sample: `application-routes.yml` entries with `filters`, `args`, `metadata.connect-timeout`
  Route metadata binds abstract configuration families to concrete operation routes in the gateway filter chain.

4. Dynamic OpenAPI orchestration behavior.
  Sample: `app.oas.orchestrator.enabled: true`, `app.oas.orchestrator.endpoints.json: /openapi.json`
  OAS orchestration settings control how runtime API documentation is generated, cached, and policy-pruned.

### 3.7 Observability Layer

Sources: `application-logging.yml`, `application-jmx.yml`, `application-management.yml`

Responsibilities:

1. Package-level log levels.
2. JMX and Jolokia runtime visibility.
3. Actuator, metrics tags, histogram and export settings.

---

## 4. PERSPECTIVE B: REQUEST LIFECYCLE TO CONFIG MAPPING

| Request Lifecycle Stage | Main Filters/Components | Primary Configuration Families |
| --- | --- | --- |
| Inbound acceptance | Netty + Gateway ingress | `app.inbound.*`, `server.*` |
| Route enablement gate | `CheckIfRouteEnabled` | `app.toggles.*` |
| Rate protection | `RequestRateLimiter`, `DynamicRateLimiter` | `app.rate-limits.*` |
| Body size protection | `RequestSize`, `DynamicRequestSizeFilter` | `app.request-sizes.*` |
| Authentication | `AuthenticateRequest` | `app.auth.providers[*]` |
| Authorization | `AuthorizeRequest` + OPA calls | `app.outbound.opa.*` + route `policyName` |
| Concurrency control | `AcquireLockForCreation`, `AcquireLockForUpdate` | `app.locks.*` |
| Query/fieldset projection | query and fieldset filters | `app.queries.*`, `app.fieldsets.*` |
| Backend call | routed HTTP client | `app.outbound.routing-target.*`, `app.timeouts.*` |
| Caching | `DynamicLocalCache` | `app.local-cache.*` |
| Observability | tracing/logging/metrics | `app.requestId`, `app.logging.*`, `management.*` |

---

## 5. PERSPECTIVE C: OVERRIDE-SCOPE MODEL

### 5.1 Scope Ladder

The common scope ladder is:

1. `default`
2. controller (`entities`, `lists`, `relations`, `reactions`, etc.)
3. optional `kinds.<kindName>`
4. route-level operation id

### 5.2 Features Using Hierarchical Override

| Feature | Global | Controller | Kind | Route |
| --- | --- | --- | --- | --- |
| Timeouts | Yes | Yes | Optional | Yes |
| Rate Limits | Yes | Yes | Optional | Yes |
| Request Sizes | Yes | Yes | Optional | Yes |
| Local Cache | Yes | Yes | Optional | Yes |
| Locks | Yes | Yes | No (in current source) | Operation-level |

---

## 6. QUICK REFERENCE: CORE KEYS, DEFAULTS, AND SAMPLES

This section prioritizes high-signal operational keys. Detailed route-by-route keys are covered by pattern because route catalogs are large and directly tied to `application-routes.yml` route ids.

### 6.1 Root App & Server

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.name` | `entity-persistence-gateway` | `entity-persistence-gateway` | Application identity | Used for Spring app name and metric tags. | `application.yml` |
| `app.shortcode` | `tarcinapp` | `acmegw` | Prefix namespace | Used in request id prefixes, role prefixes, role/jmx naming conventions, and lock/cache namespaces. | `application.yml` |
| `app.requestId` | `X-Request-Id` | `X-Request-Id` | Trace header name | Header key used by request id generation and log correlation. | `application.yml` |
| `app.debug` | `false` | `true` | Spring debug switch | Mapped to root `debug` property in runtime. | `application.yml` |
| `app.error.include-message` | `never` | `always` | Error detail policy | Controls error response message visibility. Keep strict in production. | `application.yml` |
| `app.error.include-stacktrace` | `never` | `on_param` | Stacktrace exposure policy | Controls stacktrace exposure in error responses. | `application.yml` |
| `app.inbound.address` | `0.0.0.0` | `0.0.0.0` | Bind address | Network interface binding for inbound server. | `app-inbound.yml` |
| `app.inbound.port` | `8081` | `8081` | Bind port | Main HTTP port for gateway ingress. | `app-inbound.yml` |
| `app.inbound.baseUri` | `/api/v1/` | `/api/v1/` | API base URI | Prefix used in route predicates and rewriting strategy. | `app-inbound.yml` |

### 6.2 Inbound CORS & Endpoint Exposure

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.inbound.metricsEnabled` | `true` | `true` | Gateway metrics toggle | Controls Spring Cloud Gateway metrics generation. | `app-inbound.yml` |
| `app.inbound.exposedEndpoints` | `gateway,health,metrics,prometheus,jolokia,env,info` | `health,prometheus` | Actuator exposure list | Mapped into `management.endpoints.web.exposure.include`; narrow this in production. | `app-inbound.yml`, `application-management.yml` |
| `app.inbound.cors.allowedOrigins` | `http://localhost:8080` | `https://app.company.com` | CORS origin allowlist | Controls which origins can call browser-based APIs. | `app-inbound.yml` |
| `app.inbound.cors.allowedMethods` | `GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD` | `GET,POST,PATCH` | CORS method allowlist | Controls allowed browser preflight methods. | `app-inbound.yml` |
| `app.inbound.cors.allowedHeaders` | includes common headers + `${app.requestId}` | add custom tenant header | CORS request header allowlist | Must include all custom headers expected by clients. | `app-inbound.yml` |
| `app.inbound.cors.exposedHeaders` | includes `X-Total-Count`, `X-Page-Count`, `${app.requestId}` | add `Retry-After` | CORS response header allowlist | Allows frontend code to read selected response headers. | `app-inbound.yml` |
| `app.inbound.cors.allowCredentials` | `true` | `false` | Credentials policy | Allows cookie/authorization credentials for CORS requests. | `app-inbound.yml` |
| `app.inbound.cors.maxAge` | `3600` | `600` | Preflight cache TTL | Browser preflight cache duration in seconds. | `app-inbound.yml` |

### 6.3 Outbound Routing, Policy Source, OPA, Redis

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.outbound.routing-target.protocol` | `http` | `http` | Backend protocol | Protocol for primary routed backend calls. | `app-outbound.yml` |
| `app.outbound.routing-target.host` | `entity-persistence-service` | `entity-persistence-service` | Backend host | Main service host for route forwarding. | `app-outbound.yml` |
| `app.outbound.routing-target.port` | `80` | `8080` | Backend port | Main service port for route forwarding. | `app-outbound.yml` |
| `app.outbound.routing-target.connectTimeoutMs` | `${app.timeouts.default.connectTimeoutMs}` | `3000` | Connection timeout | Shared HTTP connect timeout for outbound calls. | `app-outbound.yml` |
| `app.outbound.routing-target.responseTimeout` | `${app.timeouts.default.responseTimeoutMs}` | `30000` | Response timeout | End-to-end backend response wait budget. | `app-outbound.yml` |
| `app.outbound.routing-target.pool.maxConnections` | `500` | `700` | Pool capacity | Max concurrent outbound connections to backend. | `app-outbound.yml` |
| `app.outbound.policy-source.connectTimeoutMs` | `1000` | `500` | Fast-fail connect timeout | Security data fetch path is intentionally fail-fast. | `app-outbound.yml` |
| `app.outbound.policy-source.responseTimeout` | `PT1S` | `PT750MS` | Fast-fail response timeout | Prevents authorization pipeline from hanging. | `app-outbound.yml` |
| `app.outbound.opa.host` | `entity-persistence-gateway-policies` | `opa-service` | OPA host | Endpoint for policy decisions and field restrictions. | `app-outbound.yml` |
| `app.outbound.opa.port` | `443` | `8181` | OPA port | OPA service port. | `app-outbound.yml` |
| `app.outbound.redis.host` | `gateway-redis-master` | `redis-gateway` | Redis host | Shared state backend for rate limiting, locks, and cache coordination. | `app-outbound.yml` |
| `app.outbound.redis.database` | `0` | `1` | Redis DB index | Logical db segregation within Redis. | `app-outbound.yml` |

### 6.4 Authentication

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.auth.providers` | `[]` | list of issuers | JWT provider registry | Empty list means no provider is configured; each provider defines issuer and key source. | `application-auth.yml` |
| `app.auth.providers[*].issuer` | no default | `https://securetoken.google.com/project-id` | Issuer match value | Must match JWT `iss` claim for provider resolution. | `application-auth.yml` |
| `app.auth.providers[*].jwk-set-uri` | no default | `https://www.googleapis.com/.../jwk/...` | JWKS endpoint | Rotating key source for public-key retrieval. | `application-auth.yml` |
| `app.auth.providers[*].public-key` | no default | RSA public key value | Static key source | Static PEM/public key mode for internal issuers. | `application-auth.yml` |
| `app.auth.providers[*].clockSkewSeconds` | no default | `60` | JWT skew tolerance | Allowed clock drift when validating exp/nbf claims. | `application-auth.yml` |

### 6.5 Rate Limits

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.rate-limits.default.replenishRate` | `10` | `20` | Token refill rate | Tokens replenished per second for token bucket. | `application-rate-limits.yml` |
| `app.rate-limits.default.burstCapacity` | `20` | `40` | Token burst ceiling | Max bucket capacity for temporary bursts. | `application-rate-limits.yml` |
| `app.rate-limits.<controller>.default.*` | inherits global | `entities.default` | Controller baseline | Baseline limits for all operations in one controller. | `application-rate-limits.yml` |
| `app.rate-limits.<controller>.kinds.<kind>.*` | commented example | `entities.kinds.book.findAllEntitiesByKindAlias` | Kind-specific override | Optional override for kind-alias route behavior. | `application-rate-limits.yml` |
| `app.rate-limits.<controller>.<routeId>.*` | inherits controller | `entities.createEntity.replenishRate` | Route-specific limit | Final operation-level value used by route rate limiter args. | `application-rate-limits.yml` |

### 6.6 Request Size Limits

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.request-sizes.default.create` | `2KB` | `5KB` | Create size baseline | Max body size for create operations. | `application-request-sizes.yml` |
| `app.request-sizes.default.update` | `2KB` | `8KB` | Update size baseline | Max body size for update/replace operations. | `application-request-sizes.yml` |
| `app.request-sizes.<controller>.create` | inherits global create | `entities.create` | Controller create limit | Controller-level create limit override. | `application-request-sizes.yml` |
| `app.request-sizes.<controller>.update` | inherits global update | `relations.update` | Controller update limit | Controller-level update limit override. | `application-request-sizes.yml` |
| `app.request-sizes.<controller>.kinds.<kind>.*` | commented example | `entities.kinds.book.create=10KB` | Kind-specific limit | Optional kind-level body-size tuning for alias routes. | `application-request-sizes.yml` |

### 6.7 Distributed Locks

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.locks.default.waitTime` | `3s` | `5s` | Acquisition wait budget | Max wait duration for lock acquisition before fail response. | `application-locks.yml` |
| `app.locks.default.leaseTime` | `30s` | `45s` | Lock auto-expiry | Safety auto-release duration to avoid stale lock deadlocks. | `application-locks.yml` |
| `app.locks.<controller>.create.*` | inherits default | `entities.create.waitTime` | Create lock policy | Applied by creation lock filters. | `application-locks.yml` |
| `app.locks.<controller>.update.*` | inherits default | `lists.update.leaseTime` | Update lock policy | Applied by update lock filters. | `application-locks.yml` |
| `app.locks.<controller>.createChild.*` | inherits default | `reactions.createChild.waitTime` | Hierarchy create lock policy | Applied by child-creation lock path. | `application-locks.yml` |

### 6.8 Timeouts

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.timeouts.default.connectTimeoutMs` | `3000` | `2000` | Global connect timeout | TCP connect budget across route metadata and httpclient mapping. | `application-timeouts.yml` |
| `app.timeouts.default.responseTimeoutMs` | `30000` | `45000` | Global response timeout | Main response budget for backend operations. | `application-timeouts.yml` |
| `app.timeouts.<controller>.default.*` | inherits global | `entities.default` | Controller timeout baseline | Baseline for controller operations unless route override exists. | `application-timeouts.yml` |
| `app.timeouts.<controller>.kinds.<kind>.*` | commented example | `entities.kinds.book.default` | Kind timeout override | Optional alias-kind latency profile. | `application-timeouts.yml` |
| `app.timeouts.<controller>.<routeId>.*` | inherits controller | `entities.findEntityById.connectTimeoutMs` | Route timeout override | Final route budget values used in route metadata. | `application-timeouts.yml` |

### 6.9 Fieldsets and Query Macros

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.fieldsets.global.show-all.mode` | `hide` | `hide` | Built-in fieldset mode | Hide mode with empty fields means expose all fields. | `application-fieldsets.yml` |
| `app.fieldsets.global.show-all.fields` | `[]` | `[]` | Built-in field list | Empty list under hide mode keeps full payload. | `application-fieldsets.yml` |
| `app.fieldsets.global.hide-managed-except-id.mode` | `hide` | `hide` | Managed-field projection preset | Hides all managed fields except record id. | `application-fieldsets.yml` |
| `app.fieldsets.<recordType>.defaultFieldset` | `null` | `hide-managed-except-id` | Default fieldset binding | Optional implicit fieldset per record type when request does not include explicit fieldset parameter. | `application-fieldsets.yml` |
| `app.queries.my` | `'sets[owners][userIds]='+#userId` | same as default | Saved query macro | Expands `q=my` into owner-scoped backend query. | `application-queries.yml` |
| `app.queries.actives` | `'sets[actives]'` | same as default | Saved query macro | Expands `q=actives` into active-set filter expression. | `application-queries.yml` |

### 6.10 Local Cache

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.local-cache.global-total-size-mb` | `null` | `256` | Global cache cap | Optional total cap for all local caches. | `application-local-caching.yml` |
| `app.local-cache.heap-usage-percentage` | `0.20` | `0.15` | Heap budget ratio | Max heap ratio available for local cache allocations. | `application-local-caching.yml` |
| `app.local-cache.stats-enabled` | `false` | `true` | Caffeine stats toggle | Enables cache hit/miss stats collection overhead. | `application-local-caching.yml` |
| `app.local-cache.default.size` | `0MB` | `10MB` | Default cache size | Effectively disables cache by default unless overridden. | `application-local-caching.yml` |
| `app.local-cache.default.timeToLive` | `30s` | `60s` | Default cache TTL | Short burst-protection TTL for repetitive reads. | `application-local-caching.yml` |
| `app.local-cache.<controller>.<routeId>.size` | inherits controller default | `entities.findEntities.size` | Route cache size | Per-route cache entry budget. | `application-local-caching.yml` |
| `app.local-cache.<controller>.<routeId>.timeToLive` | inherits controller default | `lists.findLists.timeToLive` | Route cache TTL | Per-route cache duration override. | `application-local-caching.yml` |

### 6.11 Observability & Management

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.logging.tarcinapp` | `INFO` | `DEBUG` | Core package log level | Controls logging verbosity for project packages. | `application-logging.yml` |
| `app.logging.gateway` | `INFO` | `DEBUG` | Gateway framework log level | Useful for route/filter debugging in lower env. | `application-logging.yml` |
| `app.jmx.enabled` | `true` | `true` | JMX enablement | Maps to `spring.jmx.enabled`. | `application-jmx.yml` |
| `app.jmx.default-domain` | `${app.shortcode}.jmx` | `acmegw.jmx` | JMX domain | Namespaces gateway MBeans in tooling. | `application-jmx.yml` |
| `app.jmx.jolokia.enabled` | `true` | `false` | Jolokia toggle | Enables HTTP bridge for JMX operations. | `application-jmx.yml` |
| `management.endpoints.web.base-path` | `/actuator` | `/actuator` | Actuator base path | HTTP base path for management endpoints. | `application-management.yml` |
| `management.endpoint.health.probes.enabled` | `true` | `true` | K8s probe endpoints | Enables readiness/liveness endpoint groups. | `application-management.yml` |
| `management.metrics.tags.application` | `${app.name}` | `entity-persistence-gateway` | Metric tag | Common metric tag for dashboard segmentation. | `application-management.yml` |

### 6.12 Dynamic OAS Orchestrator

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.oas.orchestrator.enabled` | `true` | `true` | OAS orchestrator master switch | Enables runtime-generated OpenAPI projection endpoints. | `application-oas-orchestrator.yml` |
| `app.oas.orchestrator.endpoints.json` | `/openapi.json` | `/openapi.json` | JSON spec endpoint | Public endpoint for generated JSON OpenAPI spec. | `application-oas-orchestrator.yml` |
| `app.oas.orchestrator.endpoints.yaml` | `/openapi.yaml` | `/openapi.yaml` | YAML spec endpoint | Public endpoint for generated YAML OpenAPI spec. | `application-oas-orchestrator.yml` |
| `app.oas.orchestrator.backend.spec-path` | `/explorer/openapi.json` | `/explorer/openapi.json` | Backend spec source path | Source endpoint path used to fetch raw backend OAS. | `application-oas-orchestrator.yml` |
| `app.oas.orchestrator.cache.ttl` | `15m` | `10m` | Orchestrated cache TTL | TTL for transformed/personalized OAS docs. | `application-oas-orchestrator.yml` |
| `app.oas.orchestrator.cache.raw-oas-ttl` | `5m` | `2m` | Raw spec cache TTL | TTL for backend raw OAS before transformation. | `application-oas-orchestrator.yml` |
| `app.oas.orchestrator.opa.fail-closed` | `true` | `true` | Fail-closed policy | On OPA failures, return service unavailable instead of leaking broader schema. | `application-oas-orchestrator.yml` |

### 6.13 Route Toggles

| Key | Default Value | Sample Value | Short Explanation | Detailed Explanation | Source |
| --- | --- | --- | --- | --- | --- |
| `app.toggles.routes.off` | not set | `[findEntities, countEntities]` | Disable route ids | Explicitly disables listed routes. | `application-route-toggles.yml` |
| `app.toggles.routes.on` | not set | `[findEntities]` | Re-enable route ids | Explicitly re-enables listed routes if previously disabled by broader scope. | `application-route-toggles.yml` |
| `app.toggles.controllers.off` | not set | `[relations]` | Disable controller groups | Disables all routes under matching controllers. | `application-route-toggles.yml` |
| `app.toggles.tags.off` | not set | `[write]` | Disable route tag groups | Disables routes by semantic tags for emergency controls. | `application-route-toggles.yml` |

---

## 7. DETAILED CONFIGURATION NOTES BY DOMAIN

### 7.1 Route Catalog Binding Rules

Route definitions in `application-routes.yml` are the execution bridge between logical config and runtime behavior. Most route entries reference:

1. Request size keys (`app.request-sizes.*`).
2. Rate limit keys (`app.rate-limits.*`).
3. Lock keys (`app.locks.*`).
4. Timeout keys (`app.timeouts.*`) through metadata.
5. Policy path args for authorization.

### 7.2 Why Pattern-Based Documentation Is Used for Route-Level Keys

Per-route key sets are intentionally repetitive and map 1:1 to route ids. This keeps runtime configuration predictable and allows bulk overrides through defaults. The practical documentation strategy is:

1. Keep complete route inventory in route docs.
2. Keep key patterns and inheritance model here.
3. Use route id as the final segment in each feature configuration family.

---

## 8. CONFIGURATION RESOLUTION & PRECEDENCE

### 8.1 Spring Property Precedence (Practical)

Effective value is resolved in this practical order:

1. Command-line arguments.
2. Environment variables.
3. Profile-specific files/properties.
4. Imported YAML files in root config.
5. Defaults in source YAML.

### 8.2 Effective Value Reasoning Example

Scenario: resolve timeout for `findEntityById`.

1. Check `app.timeouts.entities.findEntityById.*`.
2. If absent, fallback to `app.timeouts.entities.default.*`.
3. If absent, fallback to `app.timeouts.default.*`.

---

## 9. QUICK OPERATIONAL CHECKLIST

### 9.1 Safe Production Posture

1. Keep actuator exposure narrow (`health,prometheus` minimum public profile).
2. Keep policy-source and OPA timeouts fail-fast.
3. Keep route toggles ready for emergency operations.
4. Keep local cache conservative unless workload justifies larger footprint.

### 9.2 Common Symptoms to Configuration Families

| Symptom | First Configuration Families to Inspect |
| --- | --- |
| 429 Too Many Requests | `app.rate-limits.*` |
| 413 Payload Too Large | `app.request-sizes.*` |
| 423 Locked | `app.locks.*` |
| 504 / timeout behavior | `app.timeouts.*`, `app.outbound.routing-target.*` |
| CORS preflight failures | `app.inbound.cors.*` |
| Missing route at runtime | `application-routes.yml`, `app.toggles.*` |

---

## 10. NEXT EXPANSION TRACK

The next pass can add a fully expanded Appendix with every route-specific key under these families:

1. `app.rate-limits.*`
2. `app.timeouts.*`
3. `app.local-cache.*`
4. `app.request-sizes.*`

This is straightforward because each family already follows deterministic route-id naming.
