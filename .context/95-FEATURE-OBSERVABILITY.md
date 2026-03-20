# Observability: JMX, Metrics, and Health Endpoint Exposure

## 1. FEATURE IDENTITY & PURPOSE

**Observability** is the gateway's built-in capability for externaling its internal runtime state—health, live metrics, configuration, and JMX bean data—to operations tooling, monitoring systems, and infrastructure orchestrators.

It is delivered through three complementary layers:
- **Spring Boot Actuator** — HTTP endpoints for health, metrics, gateway inspection, config, and info.
- **Micrometer Metrics** — Structured in-process metric collection with export to Prometheus and JMX.
- **Jolokia JMX Agent** — An HTTP bridge to JMX, enabling GUI tools like Hawtio and JConsole to connect over the network without native RMI.

### The Hard Problem Solved

An API gateway that cannot surface its own state is a black box. Without observability:
- Kubernetes cannot make reliable pod scheduling decisions (no liveness/readiness signals).
- SRE teams cannot detect latency regressions, connection pool saturation, or error spikes without poking at logs.
- Capacity planning is guesswork: there is no P95/P99 latency data.
- Debugging gateway behavior (route wiring, active filters, configuration property resolution) requires redeployment or log analysis.

This gateway externalizes all of that through standardized interfaces—so the operational posture is consistent whether running locally, in Docker, or in a Kubernetes cluster.

### Core Philosophy

1. **Separation of Exposure Planes:** HTTP management endpoints are controlled independently from JMX. HTTP exposure is intentionally restrictive (configured per environment via `app.inbound.exposedEndpoints`). JMX exposure is fully open—because it runs on a dedicated port accessible only via local access or SSH tunnel.

2. **Kubernetes-Native Probes:** Liveness and readiness endpoints are first-class citizens, enabling precise pod lifecycle management in K8s clusters.

3. **Prometheus-First Metrics:** Micrometer metrics are structured with percentile histograms from the start, enabling P95/P99 latency visualization in Grafana without post-hoc configuration changes.

4. **JMX for Deep Debugging:** JMX with Jolokia provides a low-overhead path to inspect MBeans—gateway routes, thread pools, cache state, and custom actuator data—without any application restart.

---

## 2. IMPLEMENTATION MODEL

### 2.1 The Three Observability Layers

```
                    ┌───────────────────────────────────────────────┐
                    │          Entity Persistence Gateway           │
                    │                                               │
  HTTP Clients      │  ┌─────────────────────────────────────────┐ │
  (Prometheus,      │  │         Spring Boot Actuator             │ │
   SREs, K8s)  ───▶ │  │  /actuator/health   → liveness/readiness│ │
                    │  │  /actuator/metrics   → Micrometer data   │ │
                    │  │  /actuator/prometheus→ Prometheus scrape │ │
                    │  │  /actuator/gateway   → route inspection  │ │
                    │  │  /actuator/env       → config resolution │ │
                    │  │  /actuator/info      → build/git info    │ │
                    │  └──────────────────────────────────────────┘ │
                    │                                               │
  Hawtio /          │  ┌──────────────────────────────────────────┐ │
  JConsole      ───▶│  │   Jolokia Agent (Port 8778)              │ │
                    │  │   HTTP → JMX bridge                      │ │
                    │  │   Exposes all JMX MBeans over HTTP/JSON  │ │
                    │  └──────────────────────────────────────────┘ │
                    │                                               │
                    │  ┌──────────────────────────────────────────┐ │
                    │  │   Micrometer (In-Process)                 │ │
                    │  │   → Prometheus export (HTTP /actuator)   │ │
                    │  │   → JMX export (MBeans, visible in GUI)  │ │
                    │  └──────────────────────────────────────────┘ │
                    └───────────────────────────────────────────────┘
```

### 2.2 Spring Boot Actuator Endpoints

Actuator is the primary HTTP observability interface. Each endpoint serves a distinct purpose:

| Endpoint | Path | Purpose |
|----------|------|---------|
| `health` | `/actuator/health` | Overall health status with component detail |
| `health/liveness` | `/actuator/health/liveness` | Kubernetes liveness probe |
| `health/readiness` | `/actuator/health/readiness` | Kubernetes readiness probe |
| `metrics` | `/actuator/metrics` | Micrometer metric names and values |
| `prometheus` | `/actuator/prometheus` | Prometheus scrape endpoint |
| `gateway` | `/actuator/gateway` | Inspect routes, filters, and topology |
| `env` | `/actuator/env` | Resolved configuration properties |
| `info` | `/actuator/info` | Build, git, OS, and Java environment info |

The set of endpoints exposed via HTTP is controlled by `app.inbound.exposedEndpoints`.

### 2.3 Micrometer Metrics Collection

Micrometer acts as the in-process metric registry. It collects:
- JVM metrics (heap, GC, threads): from auto-configured binders.
- HTTP server request metrics (`http.server.requests`): timing, status codes.
- Gateway-specific metrics (`gateway.requests`): per-route request/response data.
- Reactive/Netty metrics: event loop and connection pool state.

All metrics are tagged with `application` (from `app.name`) and `environment` (from active Spring profile), providing consistent cardinality across scrapes.

Percentile histograms are enabled for `http.server.requests` and `gateway.requests`, allowing Prometheus and Grafana to compute P95/P99 latency over any time window without client-side approximation.

### 2.4 Jolokia JMX Agent

Jolokia is a JMX-HTTP bridge implemented by `JolokiaAgentConfig`. When enabled, it starts an embedded HTTP server (separate from the main gateway server) on a configurable port (`app.jmx.jolokia.port`, default `8778`).

It exposes all JMX MBeans as a JSON REST API, enabling:
- **Hawtio:** web-based JMX GUI optimized for Spring/Camel/gateway debugging.
- **JConsole / VisualVM:** via the Jolokia connector (no native RMI required).
- **Remote actuator inspection:** via SSH tunnel to the Jolokia port (safer than opening native RMI ports).

Discovery is enabled by default so Hawtio can auto-detect the agent without manual endpoint configuration.

The Java configuration class `JolokiaAgentConfig` is conditionally activated:

```java
@ConditionalOnProperty(name = "app.jolokia.enabled", havingValue = "true", matchIfMissing = false)
public class JolokiaAgentConfig {
    // Starts a JolokiaServer on the configured host/port with discoveryEnabled
}
```

This means Jolokia is **opt-in**: the server only starts if `app.jolokia.enabled=true` (configured as `app.jmx.jolokia.enabled` in `application-jmx.yml`, which maps to `app.jolokia.enabled` read by the Java config).

### 2.5 JMX Domain Organization

Spring JMX is configured to separate concerns across two JMX domains:

| Domain | Content | Purpose |
|--------|---------|---------|
| `${app.shortcode}.jmx` | Core Spring/app MBeans | Application runtime state |
| `management.gateway` | Actuator MBeans | Health, routes, gateway management |
| `gateway.metrics` | Micrometer JMX export | Metric values as MBeans |

This separation keeps different operator concerns in distinct folders within JConsole or Hawtio, and prevents naming collisions.

---

## 3. CONFIGURATION REFERENCE

### 3.1 JMX Configuration (`application-jmx.yml`)

```yaml
app:
  jmx:
    enabled: true                        # Enable/disable Spring JMX entirely
    default-domain: ${app.shortcode}.jmx # MBean domain prefix for core beans
    unique-names: false                  # Whether MBean names must be unique

    jolokia:
      enabled: true           # Start the embedded Jolokia HTTP server
      host: "0.0.0.0"         # Interface for Jolokia to bind (0.0.0.0 = all interfaces)
      port: 8778              # Dedicated Jolokia agent port (separate from main server)
      discoveryEnabled: true  # Enable Hawtio auto-discovery of this agent
```

`application-jmx.yml` values are linked into Spring's native JMX settings in `application.yml`:

```yaml
spring:
  jmx:
    enabled: ${app.jmx.enabled}
    default-domain: ${app.jmx.default-domain}
    unique-names: ${app.jmx.unique-names}
```

### 3.2 Management & Actuator Configuration (`application-management.yml`)

#### HTTP Endpoint Exposure

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "${app.inbound.exposedEndpoints}"  # Controlled per-environment
      base-path: /actuator
    jmx:
      exposure:
        include: "*"             # All endpoints exposed via JMX (secure by port)
      domain: management.gateway
      unique-names: true
```

#### Individual Endpoint Settings

```yaml
management:
  endpoint:
    health:
      show-details: always        # Show component details in health responses
      show-components: always
      probes:
        enabled: true             # Enable /health/liveness and /health/readiness
    gateway:
      enabled: true               # Enable /actuator/gateway for route inspection
    metrics:
      enabled: true
    prometheus:
      enabled: true
    shutdown:
      enabled: false              # Remote HTTP shutdown is always disabled (security)
    env:
      enabled: true
    configprops:
      enabled: false              # Off by default (produces very large output)
```

#### Micrometer Metrics Configuration

```yaml
management:
  metrics:
    tags:
      application: ${app.name}                     # Tag every metric with app name
      environment: ${spring.profiles.active:default} # Tag with active profile
    distribution:
      percentiles-histogram:
        http.server.requests: true   # Enable histogram for HTTP server metrics
        gateway.requests: true       # Enable histogram for gateway route metrics
    export:
      prometheus:
        enabled: true
        step: 1m           # Prometheus scrape step
      jmx:
        enabled: true
        domain: gateway.metrics
        step: 1m           # JMX metric refresh interval
```

#### Info Endpoint Content

```yaml
management:
  info:
    env:     { enabled: true }    # Exposes info.* properties from config
    java:    { enabled: true }    # JVM version and vendor
    os:      { enabled: true }    # OS name and version
    build:   { enabled: true }    # Maven build info (version, artifact, time)
    git:     { enabled: true, mode: full }  # Full git commit metadata
```

### 3.3 HTTP Endpoint Exposure Control (`app-inbound.yml`)

Which actuator endpoints are reachable via HTTP is controlled by:

```yaml
app:
  inbound:
    metricsEnabled: true
    exposedEndpoints: "gateway,health,metrics,prometheus,jolokia,env,info"
```

`metricsEnabled` controls Spring Cloud Gateway's own metrics filter. `exposedEndpoints` maps directly to `management.endpoints.web.exposure.include`. To fully disable HTTP observability access:

```yaml
app:
  inbound:
    metricsEnabled: false
    exposedEndpoints: "none"
```

---

## 4. OPERATIONAL GUIDANCE

### 4.1 Connecting Hawtio for JMX Inspection

Hawtio is the recommended GUI for JMX-based debugging. Connect it to the Jolokia agent:

1. Start the gateway with `app.jmx.jolokia.enabled: true`.
2. Open Hawtio and connect to `http://<gateway-host>:8778/jolokia`.
3. If Hawtio discovery is enabled, the gateway agent will appear automatically.

In production, expose port `8778` only via SSH tunnel, never directly:

```bash
ssh -L 8778:localhost:8778 user@gateway-host
# Then point Hawtio to http://localhost:8778/jolokia
```

**Key areas to inspect in Hawtio:**
- `management.gateway` domain: route health, active filter chains.
- `gateway.metrics` domain: live metric values (request count, latency buckets).
- `${app.shortcode}.jmx` domain: core Spring and Redis connection pool beans.

### 4.2 Prometheus Scrape Setup

Add the gateway as a scrape target in your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: entity-persistence-gateway
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['gateway-host:8081']
```

Because percentile histograms are enabled, Grafana can immediately compute P95/P99 latency using `histogram_quantile`:

```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))
```

### 4.3 Kubernetes Health Probes

With `management.endpoint.health.probes.enabled: true`, Kubernetes probes bind to:

- **Liveness:** `GET /actuator/health/liveness` — responds `200 UP` or `503 OUT_OF_SERVICE`
- **Readiness:** `GET /actuator/health/readiness` — responds `200 ACCEPTING_TRAFFIC` or `503 REFUSING_TRAFFIC`

Suggested K8s probe configuration:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 15
  periodSeconds: 5
  failureThreshold: 3
```

### 4.4 Inspecting Active Routes via Actuator

The gateway actuator endpoint exposes live route definitions without requiring a deployment or log search:

```bash
curl http://localhost:8081/actuator/gateway/routes | jq '.[].id'
```

This is useful for verifying that route toggles and kind-alias projections are correctly wired after a configuration change.

### 4.5 Security Posture for Exposed Endpoints

| Endpoint | Risk Level | Recommendation |
|----------|-----------|----------------|
| `health` | Low | Safe to expose publicly if needed for K8s probes |
| `prometheus` | Medium | Restrict to scrape network; avoid public exposure |
| `gateway` | Medium | Internal networks only; reveals route topology |
| `env` | High | Reveals resolved config values; restrict strictly |
| `info` | Low–Medium | Reveals git/build info; acceptable for internal networks |
| `jolokia` (port 8778) | High | Never expose publicly; use SSH tunnel only |

In production, tighten `app.inbound.exposedEndpoints` to only what your environment actually scrapes:

```yaml
app:
  inbound:
    exposedEndpoints: "health,prometheus"
```

### 4.6 Disabling Jolokia in Production

If JMX GUI access is not needed in a deployment environment:

```yaml
app:
  jmx:
    jolokia:
      enabled: false
```

This prevents `JolokiaAgentConfig` from initializing and no port `8778` listener starts.

---

## 5. FURTHER READING

- **[application-jmx.yml](../src/main/resources/application-jmx.yml)** — Full JMX and Jolokia configuration
- **[application-management.yml](../src/main/resources/application-management.yml)** — Actuator, metrics, and info endpoint settings
- **[app-inbound.yml](../src/main/resources/app-inbound.yml)** — HTTP endpoint exposure control via `exposedEndpoints`
- **[75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md)** — Route enablement toggles visible via `/actuator/gateway`
- **[50-FEATURE-RATE-LIMITING.md](50-FEATURE-RATE-LIMITING.md)** — Redis-backed throttling whose state contributes to gateway metrics
- **[60-FEATURE-DISTRIBUTED-LOCKING.md](60-FEATURE-DISTRIBUTED-LOCKING.md)** — Redis-based locking dependent on Redis health visible in actuator

---

**Last Updated:** March 2026
