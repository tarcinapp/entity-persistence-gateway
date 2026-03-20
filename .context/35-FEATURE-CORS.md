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

CORS is wired through Spring Cloud Gateway's `globalcors` configuration in `application.yml`:

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOrigins: ${app.inbound.cors.allowedOrigins}
            allowedMethods: ${app.inbound.cors.allowedMethods}
            allowedHeaders: ${app.inbound.cors.allowedHeaders}
            exposedHeaders: ${app.inbound.cors.exposedHeaders}
            allowCredentials: ${app.inbound.cors.allowCredentials}
            maxAge: ${app.inbound.cors.maxAge}
```

Key behavior:
1. Policy scope is global (`[/**]`).
2. Effective values are sourced from `app.inbound.cors.*` properties.
3. No custom filter is required for baseline CORS enforcement.

### 2.2 Inbound Policy Source

Default policy values are defined in `app-inbound.yml`:

```yaml
app:
  inbound:
    cors:
      allowedOrigins:
        - "http://localhost:8080"
      allowedMethods:
        - "GET"
        - "POST"
        - "PUT"
        - "PATCH"
        - "DELETE"
        - "OPTIONS"
        - "HEAD"
      allowedHeaders:
        - "Accept"
        - "Authorization"
        - "Content-Type"
        - ${app.requestId}
      exposedHeaders:
        - "Location"
        - "ETag"
        - "X-Total-Count"
        - "Retry-After"
        - ${app.requestId}
      allowCredentials: true
      maxAge: 3600
```

The actual file contains a wider, production-oriented header list. The snippet above highlights the structure and intent.

---

## 3. CONFIGURATION HIERARCHY

### 3.1 Base Configuration Files

CORS behavior is controlled by:
- `application.yml` (global gateway CORS wiring),
- `app-inbound.yml` (default policy values),
- optional environment-specific overrides (for example profile-specific properties or deployment env vars).

### 3.2 Property Reference

| Property | Type | Purpose |
|----------|------|---------|
| `app.inbound.cors.allowedOrigins` | `List<String>` | Origins allowed to access API from browser context |
| `app.inbound.cors.allowedMethods` | `List<String>` | HTTP methods accepted for cross-origin requests |
| `app.inbound.cors.allowedHeaders` | `List<String>` | Request headers browser is allowed to send |
| `app.inbound.cors.exposedHeaders` | `List<String>` | Response headers browser JavaScript can read |
| `app.inbound.cors.allowCredentials` | `Boolean` | Whether credentials (cookies/auth) are allowed |
| `app.inbound.cors.maxAge` | `Long` | Browser preflight cache duration (seconds) |

### 3.3 Environment Override Strategy

Recommended pattern:
1. Keep safe defaults in `app-inbound.yml`.
2. Override `app.inbound.cors.allowedOrigins` per environment.
3. Keep method/header lists aligned with actual client usage.
4. Use restrictive origin lists in production (avoid broad wildcard posture when credentials are enabled).

---

## 4. RUNTIME BEHAVIOR & FLOW

### 4.1 Preflight Requests (`OPTIONS`)

For non-simple cross-origin calls, browsers send preflight checks first. The gateway evaluates requested method/headers against configured allow-lists and returns CORS approval headers when allowed.

`maxAge` controls how long browsers can cache this approval before sending another preflight.

### 4.2 Actual Cross-Origin Requests

When origin/method/header constraints are satisfied, the gateway includes appropriate `Access-Control-*` headers so browser requests proceed normally through the route filter chain.

If policy does not allow the call, browser clients fail at CORS layer even if backend route or token would otherwise be valid.

### 4.3 Credentials & Header Exposure

- `allowCredentials: true` enables credentialed cross-origin browser requests.
- `exposedHeaders` makes operational headers (pagination, ETag, retry hints, request ID) visible to frontend code.

This is essential for robust client behavior such as:
- pagination controls from `X-Total-Count`,
- optimistic/concurrency flows via `ETag`,
- tracing correlation using request ID headers.

---

## 5. BENEFITS

### 5.1 Security Boundary at the Edge

CORS policy is enforced before frontend JavaScript can consume gateway responses, reducing accidental data exposure to unapproved web origins.

### 5.2 Centralized Control

A single configuration surface governs all routes, avoiding duplicated CORS logic in downstream services or custom per-controller code.

### 5.3 Better Frontend Developer Experience

Explicitly allowed methods/headers and exposed response metadata eliminate common browser integration failures and simplify SPA/mobile-web development.

### 5.4 Lower Latency for Browser Workloads

Preflight caching (`maxAge`) reduces repeated `OPTIONS` traffic and improves perceived responsiveness.

### 5.5 Safer Multi-Environment Operation

Environment-specific origin overrides allow permissive local development and strict production posture without code changes.

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
| **Authentication** | CORS controls browser access first; token validation still applies on accepted requests. |
| **Authorization (OPA)** | CORS approval does not bypass authorization. OPA policy checks still decide allow/deny for route operations. |
| **Rate Limiting** | CORS and rate limiting are complementary: origin-based browser control plus caller/route throttling. |
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

**Last Updated:** March 2026
