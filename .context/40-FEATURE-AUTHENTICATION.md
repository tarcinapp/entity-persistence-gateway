# Authentication: JWT Token Validation with Multi-Provider Support

## 1. FEATURE IDENTITY & PURPOSE

**Authentication** is the gateway's mechanism for verifying the identity of every incoming request. The gateway validates JSON Web Tokens (JWTs) presented in the `Authorization` header before any business logic, authorization policy, or data access occurs.

### The Hard Problem Solved

Without authentication at the gateway layer, every microservice in the Tarcinapp Suite would have to implement its own token validation, user identity extraction, and claim parsing — duplicating security-critical logic across services and increasing the attack surface.

The Entity Persistence Gateway centralizes authentication into a single filter (`AuthenticateRequest`) that runs on every route. Once authenticated, the verified identity (subject, roles, groups) is available to every downstream filter through the `GatewaySecurityContext`. No downstream service — not even OPA or the Entity Persistence Service — ever needs to re-validate a token.

### Core Philosophy

1. **Mandatory Validation First:** Authentication runs before authorization, rate limiting, managed field injection, and all other processing. If a token is invalid, the request is rejected immediately with `HTTP 401 Unauthorized`.

2. **Issuer-Based Multi-Provider Routing:** The gateway can be configured with multiple identity providers simultaneously (e.g., an internal Keycloak instance and Google Firebase). The correct validator is selected by reading the `iss` (issuer) claim from the token payload — before the signature is checked.

3. **Optional Authentication Mode:** If no providers are configured, the authentication filter skips validation and passes the request through. All downstream filters that depend on identity (OPA, managed fields, rate limiting) will operate in their unauthenticated fallback mode.

4. **Security Context as Single Source of Truth:** After validation, all extracted identity data (subject, roles, groups, raw JWT) is written into the `GatewaySecurityContext` exchange attribute. All subsequent filters read from this context — they never re-parse the token.

---

## 2. AUTHENTICATION FLOW

### 2.1 Step-by-Step Validation Sequence

```
Incoming Request
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 1. Is authentication configured?                                 │
│    (Are any providers defined in app.auth.providers?)           │
│    NO  → Skip auth, log WARNING, continue filter chain          │
│    YES → Continue                                               │
└─────────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. Is Authorization header present?                             │
│    NO  → Return HTTP 401 Unauthorized                          │
│    YES → Extract "Bearer <token>"                               │
└─────────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. Extract issuer (iss) from token payload                      │
│    (Base64-decode payload WITHOUT verifying signature)          │
│    No issuer claim → HTTP 401                                   │
└─────────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. Look up parser for issuer in TokenParserRegistry             │
│    Unknown issuer → HTTP 401                                    │
│    Known issuer  → Use the matched JwtParser                    │
└─────────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. Validate token using the matched parser                      │
│    - Verify signature (RS256)                                   │
│    - Validate issuer (must match provider.issuer)               │
│    - Validate expiry (with clockSkewSeconds tolerance)          │
│    Any failure → HTTP 401                                       │
└─────────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. Build GatewaySecurityContext from JWT claims                 │
│    - sub   → authSubject                                        │
│    - azp   → authParty                                          │
│    - roles → roles[]                                            │
│    - groups → groups[]                                          │
│    - raw token → encodedJwt                                     │
└─────────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│ 7. Build PolicyData and continue filter chain                   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Key Components

| Component | Class | Responsibility |
|-----------|-------|----------------|
| Auth Filter | `AuthenticateRequest` | Orchestrates the authentication flow |
| JWT Service | `JwtAuthenticationService` | Extracts and validates the token |
| Parser Registry | `TokenParserRegistry` | Routes to the correct validator by issuer |
| Security Context Builder | `SecurityContextBuilder` | Populates `GatewaySecurityContext` from claims |
| Auth Configuration | `AuthConfig` | Binds `app.auth.providers[]` from config |
| Security Context | `GatewaySecurityContext` | Exchange-scoped identity data passed to all filters |

---

## 3. PROVIDER CONFIGURATION

### 3.1 Configuration Structure

**File:** `application-auth.yml`

```yaml
app:
  auth:
    providers:
      - issuer: "https://securetoken.google.com/my-project-id"
        jwk-set-uri: "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        clock-skew-seconds: 60

      - issuer: "tarcinapp-internal"
        public-key: "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        clock-skew-seconds: 10
```

Or in `.properties` format (e.g., `application-dev.properties`):

```properties
app.auth.providers[0].issuer=tarcinapp-idm
app.auth.providers[0].public-key=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
app.auth.providers[0].clock-skew-seconds=60

app.auth.providers[1].issuer=https://securetoken.google.com/my-project-id
app.auth.providers[1].jwk-set-uri=https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com
app.auth.providers[1].clock-skew-seconds=60
```

### 3.2 Provider Properties Reference

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `issuer` | `String` | Yes | — | Must exactly match the `iss` claim in incoming JWTs. Used as the routing key to select the correct parser. |
| `jwk-set-uri` | `String` | One of `jwk-set-uri` or `public-key` | — | URL to the provider's JWKS endpoint. Used for providers that rotate keys (e.g., Google, Keycloak). |
| `public-key` | `String` | One of `jwk-set-uri` or `public-key` | — | Base64-encoded RSA public key (PEM format, with or without headers). Used for internal / self-hosted providers with static keys. |
| `clock-skew-seconds` | `long` | No | `60` | Allowable clock skew in seconds to account for time drift between the token issuer and the gateway. |

> **Mutual Exclusion:**  If both `jwk-set-uri` and `public-key` are provided for the same provider, `jwk-set-uri` takes precedence.

---

## 4. PROVIDER TYPES

### 4.1 JWKS-Based Providers (Dynamic Key Rotation)

Use this mode for identity providers that rotate their signing keys periodically — such as **Google Firebase**, **Auth0**, **Keycloak**, or any OIDC-compliant provider that exposes a JWKS endpoint.

**How it works:**

1. The gateway fetches the JWKS document from `jwk-set-uri` and caches up to 10 keys for 24 hours.
2. For each incoming token, the `kid` (Key ID) field in the JWT header is used to look up the correct public key from the cached JWKS response.
3. The JWKS fetcher is rate-limited to 10 requests per minute to protect against misconfigured retry storms.

```yaml
app:
  auth:
    providers:
      - issuer: "https://securetoken.google.com/my-firebase-project"
        jwk-set-uri: "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        clock-skew-seconds: 60
```

**JWKS Caching Behavior:**

| Parameter | Value | Description |
|-----------|-------|-------------|
| Max cached keys | 10 | Number of JWK entries kept in memory |
| Key TTL | 24 hours | How long keys are cached before re-fetched |
| Rate limit | 10 requests/minute | Max JWKS endpoint requests to prevent flooding |

### 4.2 Static Public Key Providers (Internal / Self-Hosted)

Use this mode for internal identity providers where you control the signing key directly — such as a self-hosted Keycloak instance or a custom token issuer.

**How it works:**

1. At startup, the Base64-encoded public key string is decoded and parsed into a Java `PublicKey` object.
2. All tokens from this issuer are validated against this static key — no network calls are needed during request processing.
3. PEM headers (`-----BEGIN PUBLIC KEY-----`) may optionally be included in the `public-key` value; they are stripped automatically.

```yaml
app:
  auth:
    providers:
      - issuer: "tarcinapp-internal"
        public-key: "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0EspTVIj..."
        clock-skew-seconds: 10
```

---

## 5. SECURITY CONTEXT & EXTRACTED CLAIMS

After successful validation, `SecurityContextBuilder` populates the `GatewaySecurityContext` (stored in the request exchange attributes) from the JWT claims:

### 5.1 GatewaySecurityContext Fields

| Field | Source JWT Claim | Type | Description |
|-------|-----------------|------|-------------|
| `authSubject` | `sub` | `String` | The unique user identifier from the identity provider. Used to populate managed fields (`_createdBy`, `_ownerUsers`) and rate limit keys. |
| `authParty` | `azp` | `String` | The authorized party (client application ID) that requested the token. Used in request ID generation for tracing. |
| `roles` | `roles` | `List<String>` | Array of role strings assigned to the user. Consumed by OPA policies for RBAC decisions and by OAS generation for role-specific API documentation. |
| `groups` | `groups` | `List<String>` | Array of group membership strings. Consumed alongside roles for visibility scoping and OPA decisions. |
| `encodedJwt` | *(raw token)* | `String` | The original Base64-encoded JWT string. Forwarded to OPA as part of the policy inquiry data for full claims inspection. |

### 5.2 Required JWT Payload Structure

The following is the expected JWT payload structure for full feature compatibility:

```json
{
  "sub": "user-uuid-1234",
  "iss": "tarcinapp-internal",
  "azp": "client-app-id",
  "roles": ["tarcinapp.admin", "tarcinapp.editor"],
  "groups": ["group-a", "group-b"],
  "exp": 1735000000,
  "iat": 1734996400
}
```

**Claim Behavior Summary:**

| Claim | Required | Missing Value Behavior |
|-------|----------|----------------------|
| `sub` | Yes | Authentication succeeds but `authSubject` is null; managed fields cannot be populated correctly |
| `iss` | Yes | Authentication fails — issuer is the routing key for provider selection |
| `roles` | No | Defaults to an empty list `[]`; user will not match any role-gated OPA policies |
| `groups` | No | Defaults to an empty list `[]`; user will not be in any group-scoped visibility sets |
| `azp` | No | Defaults to null; request tracing uses `UNKNOWN` as the auth party |
| `exp` | Yes (enforced by parser) | Authentication fails |

### 5.3 Role Naming Convention

Roles follow a namespaced naming convention using the application shortcode (`app.shortcode`) as a prefix:

```
{app.shortcode}.{role-name}
```

**Example:** If `app.shortcode=tarcinapp`, valid role names are:

| Role | Typical Privileges |
|------|-------------------|
| `tarcinapp.admin` | Full access; can set managed fields, bypass restrictions |
| `tarcinapp.editor` | Can create and update records |
| `tarcinapp.member` | Can read public and owned records |
| `tarcinapp.visitor` | Read-only access to public records |

The shortcode prefix allows a single identity provider to issue tokens that govern access to **multiple independent Tarcinapp instances** — e.g., `books.editor` and `music.member` can coexist in the same token.

---

## 6. AUTHENTICATION & OTHER FEATURES

Authentication is the foundational step that enables almost every other gateway feature. The `GatewaySecurityContext` built during authentication is consumed by:

| Feature | How Authentication Data Is Used |
|---------|--------------------------------|
| **Rate Limiting** | When authenticated, rate limit keys are derived from `authSubject`. Without auth, the caller IP is used as the key. |
| **Managed Fields** | `authSubject` is written to `_createdBy`, `_lastUpdatedBy`, and `_ownerUsers` on create/update operations. |
| **Authorization (OPA)** | The full JWT (`encodedJwt`) and extracted claims are included in the OPA policy inquiry data. |
| **Field Masking** | OPA uses roles and token claims to determine which response fields are forbidden for the caller. |
| **Query Scope Reduction** | `authSubject` and `groups` are used to inject visibility filters (`set[audience][userIds]`, `set[audience][groupIds]`) so users only see records they are authorized to see. |
| **OAS Generation** | `roles` and `groups` determine which API endpoints and schemas are shown in the generated OpenAPI documentation for the caller. |
| **Request ID / Tracing** | `authParty` (azp) is embedded into the generated `X-Request-Id` header for end-to-end traceability. |

---

## 7. ERROR HANDLING

### 7.1 HTTP Error Responses

| Scenario | HTTP Status | Description |
|----------|-------------|-------------|
| No `Authorization` header | `401 Unauthorized` | Request reached the gateway without any token |
| Header is not `Bearer` scheme | `401 Unauthorized` | Only Bearer token authentication is supported |
| `iss` claim missing from token | `401 Unauthorized` | Gateway cannot determine which provider parser to use |
| Issuer is not in the configured providers | `401 Unauthorized` | Token is from an unknown/unconfigured identity provider |
| Signature validation failure | `401 Unauthorized` | Token was tampered with or signed by the wrong key |
| Token expired (beyond clock skew) | `401 Unauthorized` | Token has expired |
| JWKS endpoint timed out | `504 Gateway Timeout` | Failed to fetch keys from the identity provider within the timeout |
| JWKS endpoint returned 5xx | `502 Bad Gateway` | Identity provider is unavailable |

### 7.2 Unauthenticated Mode (No Providers Configured)

When `app.auth.providers` is an empty list (the default), the `AuthenticateRequest` filter detects that no parsers were registered and **skips authentication entirely**, logging a `WARN` level message:

```
Authentication is not configured. Requests won't be authenticated!
```

In this mode:
- All requests proceed without an `Authorization` header requirement.
- `GatewaySecurityContext` is still initialized but remains empty (null subject, empty roles/groups).
- OPA authorization, managed fields, and other auth-dependent features operate with null/empty identity.
- Rate limiting falls back to caller IP as the key.

> **Security Note:** Running without authentication configured is only appropriate for local development or internal-only deployments where network-level access control is enforced separately.

---

## 8. CONFIGURATION REFERENCE

### 8.1 Full Configuration Example

```yaml
# application-auth.yml
app:
  auth:
    providers:
      # Provider 1: Google Firebase (JWKS-based)
      - issuer: "https://securetoken.google.com/my-firebase-project"
        jwk-set-uri: "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        clock-skew-seconds: 60

      # Provider 2: Internal Keycloak with static public key
      - issuer: "https://keycloak.internal/realms/tarcinapp"
        public-key: |
          MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0EspTVIjLRu8yCpu
          VhgXgCnwRTpNGSS9lskOTC2GH3sjs/vpTfBHBfxq4WpMMHK63OkqxtzIStGK
          N2XXcUkDrkuJzNgxzd6ySq8XofwfFg0EekWn5ab56PXVUhpkQpZQfssnr3ek
          9v2rdaJMN99ZxhC6WT37zpyRbVVEY+/FH74EkpkXDWxNocv1...
          -----END PUBLIC KEY-----
        clock-skew-seconds: 30
```

### 8.2 Configuration Properties

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `app.auth.providers` | `List<Provider>` | No | `[]` | List of trusted identity providers. Empty list disables authentication. |
| `app.auth.providers[n].issuer` | `String` | Yes | — | Exact string that must match the `iss` claim in the JWT. |
| `app.auth.providers[n].jwk-set-uri` | `String` | Conditional | — | URL of the JWKS endpoint. Required when not using `public-key`. |
| `app.auth.providers[n].public-key` | `String` | Conditional | — | Base64-encoded RSA public key. Required when not using `jwk-set-uri`. |
| `app.auth.providers[n].clock-skew-seconds` | `long` | No | `60` | Tolerance in seconds for token expiry validation to handle clock drift. |

### 8.3 Configuration Files

| File | Purpose |
|------|---------|
| `application-auth.yml` | Primary authentication provider configuration |
| `application-dev.properties` | Environment-specific overrides (e.g., dev provider keys) |

---

## 9. EXAMPLE SCENARIOS

### 9.1 Single Internal Provider (Keycloak or Custom IdM)

```yaml
app:
  auth:
    providers:
      - issuer: "tarcinapp-idm"
        public-key: "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        clock-skew-seconds: 60
```

Expected JWT `iss` claim: `tarcinapp-idm`

---

### 9.2 Multi-Provider: Google + Internal

```yaml
app:
  auth:
    providers:
      - issuer: "https://securetoken.google.com/my-project"
        jwk-set-uri: "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"

      - issuer: "tarcinapp-internal"
        public-key: "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
```

The gateway will route requests to the correct validator based on the `iss` claim. A mobile app using Google Sign-In and a backend service using internal tokens can both be served simultaneously.

---

### 9.3 Disabling Authentication (Development Only)

```yaml
app:
  auth:
    providers: []
```

Or simply omit the `providers` key. The filter chain will skip token validation and log a warning.

---

### 9.4 Sample Valid Token for Internal Provider

```json
// JWT Header
{
  "alg": "RS256",
  "typ": "JWT"
}

// JWT Payload
{
  "sub": "5a7d3c91-e14f-4b2a-9f63-2010bc7a1234",
  "iss": "tarcinapp-internal",
  "azp": "my-frontend-app",
  "roles": ["tarcinapp.editor"],
  "groups": ["team-alpha"],
  "iat": 1734996400,
  "exp": 1735000000
}
```

After successful validation, the `GatewaySecurityContext` will contain:

```
authSubject : "5a7d3c91-e14f-4b2a-9f63-2010bc7a1234"
authParty   : "my-frontend-app"
roles       : ["tarcinapp.editor"]
groups      : ["team-alpha"]
encodedJwt  : "<raw token string>"
```
