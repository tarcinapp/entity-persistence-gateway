# Cross-Origin Resource Sharing (CORS): Browser Access Control for Gateway APIs

## 1. FEATURE IDENTITY & PURPOSE

**CORS** is the gateway capability that controls which browser-based frontends are allowed to call the API from different origins.

In this repository, CORS is configured globally at the Spring Cloud Gateway layer and applies consistently across all routes under the gateway path space.

### The Hard Problem Solved

Modern browsers enforce same-origin policy by default. Without explicit CORS policy, legitimate frontend applications hosted on different domains (or ports in development) cannot call gateway APIs even when tokens and route permissions are valid.

The gateway solves this by declaring a centralized, environment-configurable CORS policy that:
- allows approved origins to call APIs,
- permits required methods and headers,
- exposes key response headers to browser JavaScript,
- supports credentialed requests when needed,
- caches preflight decisions to reduce latency and browser overhead.

---

## 2. IMPLEMENTATION MODEL

### 2.1 Global Gateway CORS Wiring

CORS is handled by Spring Cloud Gateway's built-in `globalcors` configuration block in `application.yml`. It is registered on `RoutePredicateHandlerMapping` (all gateway routes) and, via `add-to-simple-url-handler-mapping: true`, also on `SimpleUrlHandlerMapping` (actuator and other non-route paths).

A dedicated **`corsPreflightHandler`** route (see §2.3) is declared at `order: -1` with a `Method=OPTIONS` predicate. This ensures every OPTIONS preflight is matched by the gateway before it can reach any auth-protected route.

When Spring dispatches a matched preflight, `AbstractHandlerMapping.getHandler()` detects it is a CORS preflight request and, because globalcors is configured, replaces the `FilteringWebHandler` with Spring's internal `REQUEST_HANDLED_HANDLER`. The CORS processor validates the request origin/method/headers against the configured policy and writes the `200 OK` preflight response with `Access-Control-*` headers. **The gateway filter chain (including any `GatewayFilter`s on the OPTIONS route) does not execute for preflights.** The backend is never contacted.

For actual cross-origin requests (GET, POST, etc.) that pass through auth filters and are forwarded to the backend, globalcors adds the appropriate `Access-Control-Allow-Origin` header to the response.

### 2.2 Backend CORS Header Stripping

The backend entity-persistence-service emits its own `Access-Control-Allow-Origin: *` header. This must not reach the browser alongside the gateway's header because:
- Two `Access-Control-Allow-Origin` values in one response cause browsers to reject credentialed requests.
- A wildcard `*` is forbidden when `Access-Control-Allow-Credentials: true`.

`DedupeResponseHeader RETAIN_FIRST` is used for `Access-Control-Allow-Origin` and `Access-Control-Allow-Credentials`: globalcors writes these first (at handler-mapping time), the backend's duplicate arrives later during proxying, and `RETAIN_FIRST` discards it. `RETAIN_UNIQUE` is used for `Access-Control-Expose-Headers` to collapse duplicates while preserving the full list of values. `RemoveResponseHeader` is used for the preflight-only headers (`Allow-Methods`, `Allow-Headers`, `Max-Age`) since globalcors does not write those on actual requests.

### 2.3 CORS Preflight Route (`corsPreflightHandler`)

Declared as the first route in `application-routes.yml`:

```yaml
- id: corsPreflightHandler
  uri: ${app.outbound.routing-target...}
  order: -1
  predicates:
    - Method=OPTIONS
  filters:
    - name: RequestRateLimiter
      args:
        redis-rate-limiter:
          replenishRate: ${app.rate-limits.cors.preflight.replenishRate}
          burstCapacity: ${app.rate-limits.cors.preflight.burstCapacity}
```

**Why the `RequestRateLimiter` is declared but does not execute:** As described in §2.1, the gateway filter chain is bypassed for preflights. The rate-limit config is retained to document intent and to enable future enforcement via a `WebFilter`-level rate limiter (which runs before handler-mapping dispatch). Browser-side preflight caching (`maxAge: 3600`) naturally limits preflight volume in practice.

### 2.4 Inbound Policy Source

Default policy values are defined in `app-inbound.yml` as **comma-separated scalar strings** (not YAML sequences). This format is required so that `${app.inbound.cors.*}` placeholders in `application.yml` resolve to a single property key that globalcors can reference. Spring Boot's relaxed binding splits comma-separated scalars into `List<String>` at binding time.

```yaml
app:
  inbound:
    cors:
      allowedOrigins: "http://localhost:8080"
      allowedMethods: "GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD"
      allowedHeaders: "Accept,Authorization,Content-Type,...,${app.requestId}"
      exposedHeaders: "Location,ETag,X-Total-Count,...,${app.requestId}"
      allowCredentials: true
      maxAge: 3600
```

---

## 3. CONFIGURATION HIERARCHY

### 3.1 Base Configuration Files

| File | Role |
|------|------|
| `app-inbound.yml` | Default CORS policy values (comma-separated scalars) |
| `application.yml` | globalcors wiring + backend CORS header stripping |
| `application-routes.yml` | `corsPreflightHandler` route definition |
| `application-rate-limits.yml` | `cors.preflight.replenishRate/burstCapacity` values |

### 3.2 Property Reference

| Property | Type | Purpose |
|----------|------|---------|
| `app.inbound.cors.allowedOrigins` | `String` (CSV) | Origins allowed to access API from browser context |
| `app.inbound.cors.allowedMethods` | `String` (CSV) | HTTP methods accepted for cross-origin requests |
| `app.inbound.cors.allowedHeaders` | `String` (CSV) | Request headers browser is allowed to send |
| `app.inbound.cors.exposedHeaders` | `String` (CSV) | Response headers browser JavaScript can read |
| `app.inbound.cors.allowCredentials` | `Boolean` | Whether credentials (cookies/auth) are allowed |
| `app.inbound.cors.maxAge` | `Long` | Browser preflight cache duration (seconds) |
| `app.rate-limits.cors.preflight.replenishRate` | `Int` | Token bucket refill rate for OPTIONS route |
| `app.rate-limits.cors.preflight.burstCapacity` | `Int` | Max burst for OPTIONS route |

### 3.3 Environment Override Strategy

`app.inbound.cors.allowedOrigins` can be overridden directly in profile-specific property files. Profile-specific files have higher precedence than all non-profile config data, including files imported via `spring.config.import`. Since `allowedOrigins` is stored as a CSV scalar (not a YAML list), it resolves to a single property key that a flat property file can override cleanly.

```properties
# application-dev.properties
app.inbound.cors.allowedOrigins=http://localhost:8080,http://localhost:5173
```

The `globalcors` binding in `application.yml` references `${app.inbound.cors.allowedOrigins}`, so it automatically picks up the overridden value.

---

## 4. RUNTIME BEHAVIOR & FLOW

### 4.1 Preflight Requests (`OPTIONS`)

1. Browser sends `OPTIONS /api/v1/entities` with `Origin` and `Access-Control-Request-Method` headers.
2. `corsPreflightHandler` route (order: -1) matches via `Method=OPTIONS`.
3. `AbstractHandlerMapping.getHandler()` detects a CORS preflight + globalcors config → returns `REQUEST_HANDLED_HANDLER`.
4. Spring's `DefaultCorsProcessor` validates origin/method/headers and writes `200 OK` with `Access-Control-*` headers.
5. Filter chain and backend are never invoked.

### 4.2 Actual Cross-Origin Requests

When origin/method/header constraints are satisfied, globalcors adds `Access-Control-Allow-Origin` (and related headers) to the backend response. Backend-emitted CORS headers are stripped by `default-filters` before reaching the browser.

### 4.3 Credentials & Header Exposure

- `allowCredentials: true` enables credentialed cross-origin browser requests.
- `exposedHeaders` makes operational headers (pagination, ETag, retry hints, request ID) visible to frontend code.

---

## 5. BENEFITS

### 5.1 Security Boundary at the Edge

CORS policy is enforced before frontend JavaScript can consume gateway responses, reducing accidental data exposure to unapproved web origins.

### 5.2 Centralized Control

A single configuration surface governs all routes, avoiding duplicated CORS logic in downstream services or custom per-controller code.

### 5.3 No Custom Java Required

The globalcors approach is purely configuration-driven. No `@Bean`-registered `CorsWebFilter` or `@ConfigurationProperties` binders are needed.

### 5.4 Lower Latency for Browser Workloads

Preflight caching (`maxAge`) reduces repeated `OPTIONS` traffic and improves perceived responsiveness.

### 5.5 Safer Multi-Environment Operation

Environment-specific origin overrides target the globalcors binding directly, allowing permissive local development and strict production posture without code changes.

---

## 6. OPERATIONAL GUIDANCE

### 6.1 Production Hardening Checklist

1. Allow only exact, trusted frontend origins.
2. Keep `allowedHeaders` minimal and intentional.
3. Expose only headers that clients actually need.
4. Re-verify CORS policy after introducing new frontend domains.
5. Review CORS settings whenever auth/session model changes.

### 6.2 Common Misconfiguration Patterns

- Missing `Authorization` in `allowedHeaders` causing authenticated browser calls to fail.
- Missing `OPTIONS` in `allowedMethods` causing preflight rejection.
- Omitting required operational headers from `exposedHeaders`, leaving client code unable to read pagination or ETag metadata.
- Overly broad origin policy in production.

---

## 7. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Authentication** | CORS controls browser access first; token validation applies on accepted non-preflight requests. Preflights bypass auth entirely (no filter chain runs). |
| **Authorization (OPA)** | CORS approval does not bypass authorization. OPA policy checks still decide allow/deny for route operations. |
| **Rate Limiting** | `corsPreflightHandler` declares a rate-limit config for intent. Effective enforcement for preflights requires a WebFilter-level limiter. Actual request rate limiting applies normally. |
| **Request Size Limiting** | CORS handles browser eligibility; size limits still enforce payload guardrails for accepted requests. |
| **Dynamic OAS Generation** | Browser clients requesting OpenAPI endpoints are subject to the same CORS policy surface. |

---

## 8. RELATED DOCUMENTATION

- **[40-FEATURE-AUTHENTICATION.md](40-FEATURE-AUTHENTICATION.md)** - JWT validation and identity context model
- **[45-FEATURE-AUTHORIZATION.md](45-FEATURE-AUTHORIZATION.md)** - policy-driven route and field permissions
- **[50-FEATURE-RATE-LIMITING.md](50-FEATURE-RATE-LIMITING.md)** - traffic shaping and abuse protection
- **[55-FEATURE-REQUEST-SIZE-LIMITING.md](55-FEATURE-REQUEST-SIZE-LIMITING.md)** - payload size guardrails
- **[90-FEATURE-DYNAMIC-OAS-GENERATION.md](90-FEATURE-DYNAMIC-OAS-GENERATION.md)** - runtime OpenAPI generation behavior

---

**Last Updated:** May 2026
