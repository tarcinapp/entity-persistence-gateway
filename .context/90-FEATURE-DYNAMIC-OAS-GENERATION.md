# Dynamic OpenAPI Specification Generation

> **Prerequisite Reading:** This document assumes familiarity with **Domain Projection**—the gateway's core capability for transforming generic endpoints into business-specific APIs. See [70-FEATURE-DOMAIN-PROJECTION.md](70-FEATURE-DOMAIN-PROJECTION.md) for foundational concepts including kind aliases, hierarchical relationships, route toggles, and schema configuration.

## 1. FEATURE IDENTITY & PURPOSE

The **Dynamic OAS Generator** produces personalized OpenAPI documentation that accurately reflects each caller's view of the projected domain. It transforms the backend's technical specification into a **domain-aware, permission-filtered API contract**.

### The Problem

When the gateway projects domain-specific APIs through configuration:
- Endpoints like `/products` and `/books/{id}/chapters` don't exist in the backend spec
- Field visibility varies by user role and OPA policies
- Routes can be toggled on/off dynamically
- Request schemas vary by operation type and hierarchy context

Static Swagger files cannot represent this reality. Developers need documentation that matches exactly what they can access at runtime.

### The Solution

Request `/openapi.json` and receive an OpenAPI specification where:
- **Paths reflect your domain:** `/products`, `/orders`, `/books/{id}/chapters`
- **Schemas match your permissions:** Forbidden fields are removed, not just hidden
- **Operations match enabled routes:** Disabled routes don't appear
- **Request bodies use correct schemas:** Priority-based schema resolution applied
- **Unused schemas are removed:** Components only contain schemas actually referenced
- **Response schemas stay operation-accurate:** POST/PUT/PATCH/DELETE responses use backend shapes per operation (transformed + pruned with FIND permissions)

### Core Principles

1. **Truth in Documentation:** The spec is never more permissive than runtime. If you can't access a field, it doesn't exist in your documentation.

2. **Permission-Aware Generation:** OPA policies determine field visibility per user, per operation.

3. **Cache-Optimized Delivery:** Users with identical permissions share cached specifications.

---

## 2. OAS-SPECIFIC CONCEPTS

> **Note:** For foundational concepts like path virtualization, schema override by specificity, and hierarchical relationships, see [70-FEATURE-DOMAIN-PROJECTION.md](70-FEATURE-DOMAIN-PROJECTION.md).

### 2.1 Field-Level Permission Enforcement

The gateway queries Open Policy Agent (OPA) to determine which fields each caller cannot see, create, or modify. These forbidden fields are **physically removed** from generated schema definitions—not just marked as restricted.

**Example:**

For a `book` entity with fields: `title`, `author`, `isbn`, `internalNotes`, `costPrice`

| User Role | Visible Fields in OAS |
|-----------|----------------------|
| Admin | `title`, `author`, `isbn`, `internalNotes`, `costPrice` |
| Editor | `title`, `author`, `isbn`, `internalNotes` |
| Public | `title`, `author`, `isbn` |

**Operation-Specific Permissions:** The generator produces different schemas for GET, POST, and PATCH operations. A user might READ `costPrice` but not WRITE it—their GET response schema includes the field, but POST/PATCH request schemas do not.

**Required Field Handling by Schema Variant:**

| Schema Variant | Required Fields Behavior |
|---|---|
| `{Kind}` (GET/PUT) | Merges base + user-config required fields — keeps all |
| `New{Kind}` (POST) | Drops base-schema required fields (gateway provides defaults), keeps user-config required fields (e.g., `isbn`) |
| `Patch{Kind}` (PATCH) | All required fields removed — partial updates make every field optional |

User-config required fields are extracted from the alias/route schema JSON using `extractUserRequiredFields()`. This ensures that domain-specific constraints (like requiring `isbn` on book creation) are preserved in the generated OAS, while infrastructure fields managed by the gateway remain optional.

### 2.2 Route Toggle Integration

When routes are disabled via `application-route-toggles.yml`, they disappear from the generated OAS entirely. The documentation always reflects what's actually accessible.

### 2.3 Schema Name Generation

Generated schema names follow predictable patterns based on the context:

| Context | Pattern | Example |
|---------|---------|---------|
| Kind-level | `New{Alias}` | `NewBooks` |
| Route-level | `New{Alias}{RouteId}` | `NewBooksCreateEntity` |
| Hierarchy-level | `New{ParentAlias}Child{ChildAlias}` | `NewBooksChildChapter` |
| Hierarchy-route | `New{ParentAlias}Child{ChildAlias}{RouteId}` | `NewBooksChildChapterCreateEntityChild` |

### 2.4 x-original-route-id Extension

Each operation includes an `x-original-route-id` extension, enabling schema resolution to identify the applicable route-level schema:

```json
{
  "paths": {
    "/books/{id}/chapters": {
      "post": {
        "x-original-route-id": "createEntityChild",
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/NewBooksChildChapterCreateEntityChild"
              }
            }
          }
        }
      }
    }
  }
}
```

---

## 3. CONFIGURATION

> **Note:** For kind alias configuration (aliases, schemas, hierarchies, route toggles), see [DOMAIN-PROJECTION.md](DOMAIN-PROJECTION.md). This section covers OAS-specific settings only.

### 3.1 Enable the Feature

**In `application.yml`:**
```yaml
spring:
  config:
    import:
      - classpath:application-oas-orchestrator.yml
```

### 3.2 Orchestrator Settings

**File:** `application-oas-orchestrator.yml`

```yaml
app:
  oas:
    orchestrator:
      enabled: true
      
      endpoints:
        json: /openapi.json
        yaml: /openapi.yaml
      
      backend:
        spec-path: /explorer/openapi.json
        connect-timeout-ms: 10000
        read-timeout-ms: 10000
        retry:
          max-attempts: 5
          initial-interval-ms: 2000
      
      cache:
        enabled: true
        ttl: 15m
        raw-oas-ttl: 5m
        max-local-cache-size: 100
      
      opa:
        field-policy: /policies/oas/field_visibility/policy/result
        timeout: 500ms
```

### 3.3 API Metadata

**File:** `app-oas.yml`

```yaml
app:
  oas:
    title: "Entity Persistence Platform API"
    version: "1.0.0"
    description: |
      Dynamic entity management with security and validation.
    
    contact:
      name: "Platform Team"
      email: "support@example.com"
    
    servers:
      - url: "https://api.example.com"
        description: "Production"
      - url: "http://localhost:8081"
        description: "Development"
```

### 3.4 OAS-Specific Route Customizations

While schemas are covered in Domain Projection, the OAS generator supports additional per-route metadata:

```yaml
routes:
  createEntity:
    operationId: createBook      # Custom operation ID
    summary: Create a new book   # Operation summary
    description: |               # Extended description
      Creates a new book record in the catalog.
    tags:
      - Books                    # Swagger UI grouping
      - Catalog
```

### 3.5 Singularization & Explicit Singular Override

The OAS generator automatically singularizes alias names to produce natural operationIds (e.g., `books` → `createBook`). The heuristic handles most English plurals correctly, including `-ies` → `-y`, `-sses`/`-shes`/`-ches`/`-xes` → strip `es`, and general `-s` → strip `s`.

For irregular plurals where the heuristic produces incorrect results (e.g., `heroes` → `heroe`), use the `singular` config property:

```properties
app.oas.controllers.entities.aliases[0].alias=heroes
app.oas.controllers.entities.aliases[0].singular=hero
```

When `singular` is set, it takes precedence over the heuristic for operationId and summary generation.

---

## 4. CACHING ARCHITECTURE

### 4.1 Multi-Tier Strategy

```
┌────────────────────────────────────────────────┐
│  L1: Caffeine (In-Memory)                      │
│  • 100 entries max                             │
│  • Sub-millisecond retrieval                   │
│  • Per-instance cache                          │
├────────────────────────────────────────────────┤
│  L2: Redis (Distributed)                       │
│  • Shared across all gateway instances         │
│  • 15-minute TTL (configurable)                │
│  • Enables horizontal scaling                  │
├────────────────────────────────────────────────┤
│  Raw Backend OAS Cache                         │
│  • 5-minute TTL                                │
│  • Reduces backend load                        │
│  • Invalidated independently                   │
└────────────────────────────────────────────────┘
```

### 4.2 Cache Key Strategy

Cache keys are deterministic hashes of permission-affecting claims:
- User's roles
- Group memberships
- Custom claims affecting field visibility

**Example:** `oas:v1:sha256(admin|editor|verified-email)`

Users with identical permissions share the same cached specification.

### 4.3 Thundering Herd Protection

When a cache miss occurs, only one concurrent request triggers the expensive transformation. Other requests wait for the result, preventing backend overload.

---

## 5. SPECIAL CONTROLLERS

### 5.1 Relations Controller

Relations are join records between lists and entities (flat topology—no hierarchy support).

| Aspect | Detail |
|--------|--------|
| Backend Path | `/relations` |
| Alias Example | `/book-assignments` |
| Special Fields | `_listId`, `_entityId` |
| Supported Operations | CRUD only (no children/parents) |

### 5.2 Reactions Controllers

Reactions (likes, comments, ratings) attach to entities or lists.

| Backend Path | Alias Example |
|-------------|---------------|
| `/entity-reactions` | `/book-reviews` |
| `/list-reactions` | `/bookshelf-comments` |
| `/entities/{id}/reactions` | `/books/{id}/reviews` |

### 5.3 Through-Controllers

Many-to-many traversal paths that chain resources.

**Example:**
- Backend: `/lists/{id}/entities`
- With aliases: `bookshelves` (list) + `books` (entity)
- Result: `/bookshelves/{id}/books`

---

## 6. PERFORMANCE

### 6.1 Latency Targets

| Scenario | Target | Typical |
|----------|--------|---------|
| Cache Hit (Caffeine L1) | < 1ms | 0.3ms |
| Cache Hit (Redis L2) | < 10ms | 5-8ms |
| Cache Miss (Full Transform) | < 500ms | 250-450ms |
| Backend OAS Fetch | < 200ms | 100-150ms |
| OPA Field Query | < 100ms | 30-80ms |

### 6.2 Resource Usage

| Component | Allocation |
|-----------|------------|
| Caffeine L1 Cache | ~50MB (100 entries × ~500KB) |
| Raw Backend OAS | ~2MB |
| Transformation Overhead | ~10MB heap per concurrent request |

### 6.3 Throughput

| Mode | Requests/Second |
|------|----------------|
| Cache Hits | 1000+ |
| Cache Misses | ~20 |

---

## 7. RESILIENCE & FALLBACKS

| Failure Mode | Behavior | Impact |
|--------------|----------|--------|
| Backend OAS Unavailable | Use last cached raw OAS | Spec may be stale |
| OPA Unavailable | Return full-visibility spec | User sees more fields (runtime still enforces) |
| Redis Unavailable | Fall back to Caffeine L1 only | Separate cache per instance |
| Transformation Error | HTTP 500 with logged details | Request fails |

---

## 8. SECURITY

### 8.1 Principles

1. **JWT Validation:** OAS endpoints require valid JWT if authentication is enabled
2. **Cache Poisoning Prevention:** Cache keys are deterministic hashes—clients cannot manipulate them
3. **Information Disclosure Prevention:** Forbidden fields are physically removed, not just marked restricted
4. **Rate Limiting:** OAS endpoints respect the same rate limits as API routes

### 8.2 Important Guarantee

The generated specification is **never more permissive** than the runtime system. Even if OAS generation fails to prune a field, the runtime field filters still enforce access control.

---

## 9. TROUBLESHOOTING

| Issue | Cause | Solution |
|-------|-------|----------|
| Empty paths in OAS | No aliases configured or all routes disabled | Check `app-oas.yml` for alias definitions |
| Fields visible despite OPA policy | OPA query failed (falls back to full visibility) | Check OPA logs and connectivity |
| Slow generation (> 1s) | Cache disabled or Redis unavailable | Verify `cache.enabled=true` and Redis connection |
| Stale spec after config change | Cached spec still being served | Flush cache or wait for TTL (15 min) |
| Hierarchy paths missing | Children/parents not configured | Add `.children[]` or `.parents[]` to alias |
| Wrong schema for hierarchy route | Hierarchy-route schema not set | Add `children[n].routes.{routeId}.schema` |

---

## 10. RELATED DOCUMENTATION

- **[70-FEATURE-DOMAIN-PROJECTION.md](70-FEATURE-DOMAIN-PROJECTION.md)** — **Start here.** Core concepts for transforming generic APIs into domain-specific endpoints
- **[75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md)** — Route toggle configuration and how it affects OAS generation
- **[20-FILTERS.md](20-FILTERS.md)** — Gateway filter documentation including validation filters
- **[10-ROUTES.md](10-ROUTES.md)** — Route inventory and naming conventions
- **[05-REPO-CONTEXT.md](05-REPO-CONTEXT.md)** — Gateway architecture overview

---

**Last Updated:** February 2026  