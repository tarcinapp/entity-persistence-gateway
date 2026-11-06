# Application Shortcode Propagation: Downstream Header Injection

## 1. FEATURE IDENTITY & PURPOSE

**Application Shortcode Propagation** is the gateway capability that injects the configured `app.shortcode` value into every upstream request as the `X-App-Shortcode` HTTP header.

It is implemented using the built-in Spring Cloud Gateway `AddRequestHeader` filter, registered once in `spring.cloud.gateway.default-filters` inside `application.yml` — requiring zero per-route configuration and no custom filter class.

The feature ensures that every service downstream of the gateway — the Universal Orchestrator, Entity Persistence Cache, and Entity Persistence Service — can identify the **application deployment context** without coupling to external configuration sources.

### The Hard Problem Solved

The gateway is a multi-application platform: the same gateway binary can be deployed as `books`, `music`, or `erp` simply by changing `app.shortcode`. Downstream services are generic and stateless — they do not know which application they are serving unless the gateway tells them.

Without this header:

- The **Universal Orchestrator** cannot build application-namespaced Redis cache keys or lock keys in custom pre/post-processor logic, forcing developers to hard-code a shortcode or inject it through a separate configuration channel.
- The **Entity Persistence Cache** cannot reliably namespace its lock keys and findById cache keys unless it reads `app.shortcode` independently — which would require EPC to have its own application-identity configuration, creating a synchronisation risk.
- Custom processors in the orchestration layer cannot implement per-application routing logic, metrics tagging, or audit enrichment without knowing which application they are serving.

The gateway is the single authoritative source for `app.shortcode`. By propagating it on every request, all downstream services receive consistent application identity without any secondary configuration.

### Key Benefits

- **Single source of truth:** `app.shortcode` is set once in the gateway and propagated to all layers.
- **Cache key namespace consistency:** EPC and orchestrator-level caches can reliably prefix keys with the application shortcode.
- **Per-application custom logic:** Orchestrator processors can branch on `X-App-Shortcode` to implement application-specific enrichment or validation.
- **Zero downstream configuration:** Downstream services do not need to replicate `app.shortcode` in their own config files.
- **Zero custom code:** Implemented with one line in `application.yml` using a built-in Spring Cloud Gateway filter.

---

## 2. IMPLEMENTATION

### 2.1 Configuration in `application.yml`

The header is injected via the `AddRequestHeader` built-in Spring Cloud Gateway filter, added to the global `default-filters` block so it applies to every proxied route automatically:

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        # ... existing CORS response header filters ...
        # Propagate application identity to all upstream services.
        # Downstream services (Orchestrator, EPC, EPS) use this to namespace Redis keys
        # and implement per-application logic without their own shortcode configuration.
        - AddRequestHeader=X-App-Shortcode, ${app.shortcode}
```

`${app.shortcode}` is resolved at runtime from the `app.shortcode` property defined in `application.yml`. No custom Java class is needed.

### 2.2 Header Name: `X-App-Shortcode`

The header name `X-App-Shortcode` was chosen to:

- Follow the `X-` custom header convention already established in this gateway (`X-Request-Id`, `X-User-Id`, `X-User-Roles`, `X-User-Groups`).
- Be unambiguous and self-describing: it signals application identity, not user identity.
- Avoid collision with any standard HTTP or LoopBack header.

The header carries the raw value of `app.shortcode` (e.g., `books`, `tarcinapp`, `myapp`). No transformation is applied.

### 2.3 Route Coverage

Because `AddRequestHeader` is in `default-filters`, it is applied to **every proxied route** — both Base Generic routes and Kind Alias routes. It does not apply to gateway-local utility routes (`/ping`, `/openapi.json`, `/openapi.yaml`, actuator endpoints) because those routes are not matched by the `default-filters` proxy path.

---

## 3. DOWNSTREAM CONSUMPTION PATTERNS

### 3.1 Universal Orchestrator — Custom Processor Logic

Camel processors in the orchestrator receive the header via the Camel exchange:

```java
@ApplicationScoped
public class CreateEntityPreProcessor implements Processor {
    @Override
    public void process(Exchange exchange) throws Exception {
        String shortcode = exchange.getIn().getHeader("X-App-Shortcode", String.class);

        // Use for application-specific branching
        if ("books".equals(shortcode)) {
            // books-specific enrichment
        }

        // Use for Redis key namespacing
        String lockKey = shortcode + ":custom:lock:" + someId;
    }
}
```

The orchestrator automatically forwards all received headers — including `X-App-Shortcode` — to EPS/EPC downstream (via `bridgeEndpoint=true` and the Camel HTTP component's default header propagation behavior). No special forwarding logic is needed.

### 3.2 Entity Persistence Cache — Cache and Lock Key Namespacing

EPC reads `X-App-Shortcode` (received from the orchestrator, which received it from the gateway) to construct its Redis keys:

```
{X-App-Shortcode}:epc:cache:entity:{id}
{X-App-Shortcode}:epc:lock:uniqueness:entity:{kind}:{slug}:{userId}
{X-App-Shortcode}:epc:lock:limit:entity:{scopeHash}:{userId}
```

This ensures that two EPC instances serving different applications (`books` and `music`) can share a single Redis cluster without key collisions.

> **Fallback:** EPC should also accept an `EPC_APP_SHORTCODE` env var as a startup-time fallback for scenarios where EPC is called directly without a gateway — such as migration scripts or administrative tools. The gateway header is the **preferred, runtime-authoritative source**.

### 3.3 Per-Application Single Orchestration Logic

`X-App-Shortcode` is the discriminator that allows one orchestrator deployment to serve multiple applications:

```java
String shortcode = exchange.getIn().getHeader("X-App-Shortcode", String.class);
switch (shortcode) {
    case "books"   -> booksPreProcessor.process(exchange);
    case "music"   -> musicPreProcessor.process(exchange);
    default        -> defaultPreProcessor.process(exchange);
}
```

---

## 4. RELATIONSHIP TO `app.shortcode` USAGE IN THE GATEWAY

`app.shortcode` serves multiple namespacing purposes within the gateway itself. This feature extends that namespacing downstream:

| Usage | Location | Key/Value Pattern |
|-------|----------|-------------------|
| Request ID prefix | `RequestIdService` | `{SHORTCODE}-{AZP}-{TIMESTAMP}-{RANDOM}` |
| JMX domain | `application-jmx.yml` | `{shortcode}.jmx` |
| Role prefixes | `AddSetsTo*` filters | `{shortcode}.admin`, `{shortcode}.member`, etc. |
| Redis lock keys (creation) | `AcquireLockForCreation` | `{shortcode}:lock:creation:{strategy}:{key}` |
| Redis lock keys (update) | `AcquireLockForUpdate` | `{shortcode}:lock-on-record-update:{recordId}` |
| Local cache keys | `DynamicLocalCacheGatewayFilterFactory` | `{shortcode}:cache:METHOD:PATH:?query:userId` |
| OPA policy data | `AuthenticateRequest`, `OasFieldPermissionService` | `policyData.appShortcode` field |
| **Downstream header** | **`default-filters: AddRequestHeader`** *(this feature)* | **`X-App-Shortcode: {shortcode}`** |

The consistent application of `app.shortcode` across all these surfaces means that a deployment can be fully rebranded simply by changing a single configuration value.

---

## 5. CONFIGURATION

No additional configuration properties are introduced by this feature. It reads the existing `app.shortcode` property.

| Property | Default | Description |
|----------|---------|-------------|
| `app.shortcode` | `tarcinapp` | The application shortcode injected as `X-App-Shortcode` header value. Already defined in `application.yml`. |

### 5.1 CORS

`X-App-Shortcode` is an **outbound request header** (gateway → upstream) — it is not a browser-visible response header, so it does not need to be added to `app.inbound.cors.exposedHeaders`.

---

## 6. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Request Tracing** | `X-App-Shortcode` complements `X-Request-Id`: the request ID identifies a single request; the shortcode identifies the application context. Together they enable per-application log filtering. |
| **Distributed Locking** | Lock keys in the gateway already use `app.shortcode`. Downstream lock keys in EPC use the same value from `X-App-Shortcode` for namespace consistency. |
| **Local Response Caching** | The cache key format `{appShortcode}:cache:METHOD:PATH:?query:userId` (see `58-FEATURE-LOCAL-CACHING.md §3`) follows the same prefix convention. |
| **Orchestration Layer** | All orchestrator processors receive `X-App-Shortcode` as a standard gateway-injected header alongside `X-User-Id`, `X-Request-Id`, `X-User-Roles`, and `X-User-Groups`. See `24_CONCEPT_orchestration-layer.md §Headers Available in Processors`. |

---

## 7. FURTHER READING

- **[58-FEATURE-LOCAL-CACHING.md](58-FEATURE-LOCAL-CACHING.md)** — local cache key strategy with `{appShortcode}:cache:` prefix
- **[60-FEATURE-DISTRIBUTED-LOCKING.md](60-FEATURE-DISTRIBUTED-LOCKING.md)** — existing lock key namespacing with `app.shortcode`
- **[../../../tarcinapp-suite/.context/24_CONCEPT_orchestration-layer.md](24_CONCEPT_orchestration-layer.md)** — headers available in orchestrator processors
- **[../../../tarcinapp-suite/.context/15_COMPONENT_entity-persistence-cache.md](15_COMPONENT_entity-persistence-cache.md)** — EPC lock key and cache key design using `{APP_SHORTCODE}` prefix
- **[30-GATEWAY-CONFIGURATION-REFERENCE.md](30-GATEWAY-CONFIGURATION-REFERENCE.md)** — `app.shortcode` configuration key reference

---

**Last Updated:** November 2026
