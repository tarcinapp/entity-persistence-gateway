# Dynamic OpenAPI Specification Generation

## Overview

The Entity Persistence Gateway includes a sophisticated **Dynamic OpenAPI Specification (OAS) Generator** that produces personalized, runtime-generated API documentation. This feature transforms the technical backend API into clean, domain-specific documentation that respects caller permissions and reflects the gateway's configuration.

### The Promise (What It Does)

When a client requests `/openapi.json` or `/openapi.yaml`, the gateway generates a custom OpenAPI specification that:

1. **Virtualizes Paths**: Transforms generic backend paths (`/entities?filter[where][_kind]=book`) into clean domain-specific URLs (`/books`)
2. **Respects Permissions**: Hides fields, operations, and entire endpoints the caller cannot access
3. **Reflects Configuration**: Shows only enabled routes, configured aliases, and active domain mappings
4. **Resolves Hierarchies**: Properly documents nested resources (`/books/{id}/chapters`)
5. **Caches Intelligently**: Delivers specifications in milliseconds for identical permission profiles

The result is that **each user sees an accurate, executable API specification that matches exactly what they can do**, making client development, testing, and integration dramatically simpler.

---

## Key Capabilities

### 1. Path Virtualization

The gateway exposes domain-specific APIs while the backend remains generic. The OAS generator reflects this abstraction.

**Backend Reality:**
```
GET /entities?filter[where][kind]=book
GET /entities/{id}?filter[where][kind]=book
POST /entities (with {"_kind": "book"} in payload)
```

**Generated OAS Documentation:**
```
GET /books
GET /books/{id}
POST /books
```

**Configuration Source:** `app-oas.yml` and other imported configuration files.

### 2. Field-Level Permission Enforcement

The gateway queries Open Policy Agent (OPA) using the caller's JWT to determine which fields they cannot see, create, or modify. These forbidden fields are then **physically removed** from the generated schema definitions.

**Example:**

For a `book` entity with fields: `title`, `author`, `isbn`, `internalNotes`, `costPrice`

**Admin User** sees all fields:
```json
{
  "Book": {
    "properties": {
      "title": {"type": "string"},
      "author": {"type": "string"},
      "isbn": {"type": "string"},
      "internalNotes": {"type": "string"},
      "costPrice": {"type": "number"}
    }
  }
}
```

**Public User** (forbidden: `internalNotes`, `costPrice`) sees:
```json
{
  "Book": {
    "properties": {
      "title": {"type": "string"},
      "author": {"type": "string"},
      "isbn": {"type": "string"}
    }
  }
}
```

**Operation-Specific Permissions:** The generator can produce different schemas for GET, POST, and PATCH operations based on OPA policies. For example, a user might be allowed to READ `costPrice` but not WRITE it.

### 3. Route Toggle Awareness

When routes are disabled via `application-route-toggles.yml`, they disappear from the generated OAS entirely. This enables:

- **Feature flagging**: Hide experimental endpoints from production documentation
- **Maintenance mode**: Remove specific operations during backend upgrades
- **Domain scoping**: Expose only the subset of routes relevant to a business domain

### 4. Hierarchical Resource Resolution

The gateway understands parent-child relationships configured in kind aliases.

**Configuration Example:**
```yaml
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
```

**Generated Paths:**
```
GET /books
GET /books/{id}
GET /books/{id}/chapters
POST /books/{id}/chapters
GET /books/{id}/chapters/{chapterId}
```

### 5. Multi-Tier Caching

The generator employs a sophisticated caching strategy:

**L1 Cache (Caffeine - In-Memory):**
- 100 most recently requested permission profiles
- Sub-millisecond retrieval
- Survives across requests

**L2 Cache (Redis - Distributed):**
- Shared across all gateway instances
- 15-minute TTL (configurable)
- Enables horizontal scaling

**Cache Key Strategy:**
- Deterministic hash of user's roles, groups, and permission-affecting claims
- Users with identical permissions share the same cached specification
- Cache key example: `oas:v1:sha256(admin|editor|verified-email)`

**Raw Backend OAS Cache:**
- Separate 5-minute cache for the backend's unmodified specification
- Reduces backend load
- Invalidated independently from transformed specs

---

## Configuration

### Enable the Feature

**In `application.yml`:**
```yaml
spring:
  config:
    import:
      - classpath:application-oas-orchestrator.yml
```

### Core Settings

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
        # Uses same policy as FetchForbiddenFields filter
        field-policy: /policies/oas/field_visibility/policy/result
        timeout: 500ms
```

### API Metadata

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

### Kind Alias Configuration

**Note:** By default, the gateway exposes generic routes (`/entities`, `/lists`, etc.). Kind aliases are optional and configured per deployment to specialize the gateway for specific business domains.

**Configuration Location:** `app-oas.yml` or environment-specific configuration files imported via `spring.config.import`.

**Example Configuration (YAML format in `app-oas.yml`):**

```yaml
app:
  oas:
    controllers:
      entities:
        aliases:
          - alias: books
            kind: book
            description: Manage book entities
            # Kind-level schema (applies by default to all routes)
            schema: |
              {
                "type": "object",
                "properties": {
                  "title": {"type": "string"},
                  "author": {"type": "string"}
                },
                "required": ["title", "author"]
              }
            routes:
              createEntity:
                operationId: createBook
                summary: Create a new book
                tags:
                  - Books
                # Route-level schema overrides kind-level schema
                schema: |
                  {
                    "type": "object",
                    "properties": {
                      "title": {"type": "string"},
                      "author": {"type": "string"},
                      "isbn": {"type": "string"}
                    },
                    "required": ["title", "author", "isbn"]
                  }
              findEntities:
                operationId: listBooks
                summary: List all books
              findEntityById:
                operationId: getBook
                summary: Get book by ID
            children:
              - alias: chapters
                kind: chapter
                description: Book chapters
                # Hierarchy-level schema (highest priority for hierarchy requests)
                schema: |
                  {
                    "type": "object",
                    "properties": {
                      "title": {"type": "string"},
                      "pageCount": {"type": "integer"}
                    }
                  }
                routes:
                  findEntityChildren:
                    operationId: listBookChapters
                    summary: List chapters for a book
      lists:
        aliases:
          - alias: bookshelves
            kind: bookshelf
            description: Manage bookshelf collections
```

**Alternative: Properties Format (for environment-specific overrides):**

```properties
# Entity: Books
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].description=Manage book entities
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"title":{"type":"string"}}}
app.oas.controllers.entities.aliases[0].routes.createEntity.operationId=createBook
app.oas.controllers.entities.aliases[0].routes.createEntity.summary=Create a new book
app.oas.controllers.entities.aliases[0].routes.createEntity.tags[0]=Books
app.oas.controllers.entities.aliases[0].routes.createEntity.schema={"type":"object","properties":{"title":{"type":"string"},"isbn":{"type":"string"}},"required":["title","isbn"]}

# Hierarchical relationship: Chapters
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].description=Book chapters
app.oas.controllers.entities.aliases[0].children[0].schema={"type":"object","properties":{"title":{"type":"string"}}}
```

### Schema Resolution (Validation Behavior)

Request validation selects a schema using a strict priority order:

1. **Hierarchy-level schema** (from `children[]` or `parents[]` *inline* `schema`)
2. **Route-level schema** for the target kind (e.g., `routes.createEntity.schema`)
3. **Kind-level schema** for the target kind (e.g., `aliases[].schema`)

If none are configured, validation is skipped for that request. Route-level schema **overrides** kind-level schema, and hierarchy-level schema **overrides both** for hierarchy requests.

> **Important:** While `children[]` / `parents[]` allow a `routes` map in configuration, those route-level entries are **not used** by the request validation logic. Only the inline `schema` on the child/parent alias is considered for hierarchy validation.

---

## Usage Examples

### 1. Anonymous Access (Public API)

If JWT authentication is disabled or the user is anonymous, the generator produces a specification showing only public fields and operations.

**Request:**
```bash
curl http://localhost:8081/openapi.json
```

**Result:** Full specification with all configured aliases but potentially limited field visibility based on anonymous OPA policies.

### 2. Authenticated User (Role-Based Filtering)

**Request:**
```bash
curl -H "Authorization: Bearer eyJhbGc..." http://localhost:8081/openapi.json
```

**Result:** Personalized specification where:
- Forbidden fields are removed from schemas
- Disabled routes are omitted
- Operations reflect the user's write/read permissions

### 3. Cache Performance

**First Request (Cache Miss):**
```
Time: ~450ms
Steps: Backend fetch → Transform → OPA query → Prune → Cache store
```

**Subsequent Requests (Cache Hit):**
```
Time: ~8ms
Steps: Redis lookup → Return
```

**Concurrent Requests with Same Permissions:**
```
Time: ~8ms each (all share the same cached spec)
```

### 4. Swagger UI Integration

The generated OAS can be used directly with Swagger UI or other API documentation tools:

```html
<!DOCTYPE html>
<html>
<head>
    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist/swagger-ui.css" />
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist/swagger-ui-bundle.js"></script>
    <script>
        SwaggerUIBundle({
            url: '/openapi.json',
            dom_id: '#swagger-ui'
        });
    </script>
</body>
</html>
```

---

## Edge Cases & Special Controllers

### Relations Controller

Relations are join records between lists and entities. They don't follow standard entity patterns.

**Backend Path:** `/relations`
**Alias Example:** `/book-assignments`
**Special Handling:** Includes `_listId` and `_entityId` parameters

### Reactions Controllers

Reactions (likes, comments, ratings) are polymorphic and attached to entities or lists.

**Backend Paths:**
- `/entity-reactions`
- `/list-reactions`
- `/entities/{id}/reactions`
- `/lists/{id}/reactions`

**Alias Examples:**
- `/book-reviews`
- `/bookshelf-comments`
- `/books/{id}/reviews`

**Special Handling:** Maintains `reactionType` discriminator

### Through-Controllers

Many-to-many traversal paths that chain resources.

**Backend Path:** `/lists/{id}/entities`
**Configuration:**
- List alias: `bookshelves` (kind: `bookshelf`)
- Entity alias: `books` (kind: `book`)
**Transformed Path:** `/bookshelves/{id}/books`

---

## Performance Characteristics

### Latency Targets

| Scenario | Target | Typical |
|----------|--------|---------|
| Cache Hit (Redis) | < 10ms | 5-8ms |
| Cache Hit (Caffeine L1) | < 1ms | 0.3ms |
| Cache Miss (Full Transform) | < 500ms | 250-450ms |
| Backend OAS Fetch | < 200ms | 100-150ms |
| OPA Field Query | < 100ms | 30-80ms |

### Memory Footprint

- **Caffeine L1 Cache:** ~50MB (100 entries, avg 500KB per spec)
- **Raw Backend OAS:** ~2MB (single cached instance)
- **Transformation Overhead:** ~10MB heap per concurrent request

### Throughput

- **Sustained:** 1000 req/s (cache hits)
- **Cold Start:** 20 req/s (cache misses, OPA queries)
- **Thundering Herd Protection:** Single inflight computation per cache key

---

## Monitoring & Operations

### Key Metrics

```
# Requests
oas.requests.total                  # Total OAS requests
oas.requests.json                   # JSON format requests
oas.requests.yaml                   # YAML format requests

# Cache
oas.cache.hits                      # Cache hit count
oas.cache.misses                    # Cache miss count
oas.cache.hit_rate                  # Hit rate percentage

# Performance
oas.transform.duration              # Transformation time (ms)
oas.backend.fetch.duration          # Backend fetch time (ms)
oas.opa.query.duration              # OPA query time (ms)
oas.prune.duration                  # Schema pruning time (ms)

# Errors
oas.backend.fetch.errors            # Backend fetch failures
oas.opa.query.errors                # OPA query failures
oas.transform.errors                # Transformation errors
```

### Logging

**DEBUG Level:**
```
[OasTransformationEngine] Transforming path /entities -> /books (kind: book)
[OasTransformationEngine] Resolved hierarchy: /books/{id}/chapters
[OasSchemaPruner] Pruning 3 fields from Book schema: [internalNotes, costPrice, _internalId]
```

**INFO Level:**
```
[DynamicOasHandler] OAS request processed (cache hit) in 7ms
[DynamicOasHandler] OAS request processed (cache miss) in 423ms
```

**WARN Level:**
```
[OasFieldPermissionService] OPA field query failed, returning full visibility: Connection timeout
[BackendOasClient] Backend OAS fetch failed, using cached version (age: 3m)
```

### Cache Invalidation

**Manual Flush (Admin Endpoint):**
```bash
# Flush all OAS caches
POST /admin/cache/oas/flush

# Flush specific permission profile
DELETE /admin/cache/oas/{cacheKey}
```

**Automatic Invalidation:**
- TTL expiry (15 minutes default)
- Backend OAS version change (detected via hash)
- OPA policy update (external trigger required)

---

## Resilience & Fallbacks

### Backend OAS Unavailable

**Behavior:** Return last successfully cached raw OAS, transform with current config
**Logging:** WARN level, includes cache age
**Client Impact:** Specification may be slightly stale

### OPA Unavailable

**Behavior:** Return full-visibility specification (no field pruning)
**Logging:** WARN level with OPA endpoint and error
**Client Impact:** User sees more fields than they should, but runtime filters still enforce permissions

### Redis Unavailable

**Behavior:** Fall back to Caffeine L1 cache only
**Logging:** ERROR level
**Client Impact:** Each gateway instance maintains separate cache (increased backend load)

### Transformation Error

**Behavior:** Return HTTP 500 with error details in logs
**Logging:** ERROR level with stack trace
**Client Impact:** OAS request fails, suggest retry or use cached version

---

## Security Considerations

### JWT Validation

OAS requests require valid JWT if authentication is enabled. The same authentication filters that protect API routes also protect OAS endpoints.

### Cache Poisoning Prevention

- Cache keys are deterministic hashes—clients cannot manipulate them
- Raw backend OAS is validated on fetch (schema parsing)
- Transformation errors prevent caching of malformed specs

### Information Disclosure

The OAS generator is designed to prevent information leakage:
- Forbidden fields are physically removed (not just marked as restricted)
- Disabled routes are completely omitted
- Error messages do not expose internal system details

### Rate Limiting

OAS endpoints respect the same rate limits as API routes. Cache hits are cheap, but cache misses trigger expensive operations (backend fetch, OPA query, transformation).

---

## Dependencies

**Maven:**
```xml
<!-- OpenAPI Parser -->
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser-v3</artifactId>
    <version>2.1.22</version>
</dependency>

<!-- OpenAPI Models -->
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-models</artifactId>
    <version>2.2.21</version>
</dependency>

<!-- YAML Support -->
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

---

## Implementation Complexity vs. User Promise

### Why This Is Complex

1. **Path Transformation Logic:** Matching backend paths to configured aliases with proper parameterization
2. **Hierarchy Resolution:** Recursive algorithm to expand parent-child relationships into correct path structures
3. **Schema Pruning:** Deep schema traversal with nested property handling and reference resolution
4. **Operation-Specific Permissions:** Different schemas for GET/POST/PATCH operations on the same entity
5. **Cache Invalidation:** Determining when cached specs are stale (backend changes, config changes, policy changes)
6. **Concurrency Control:** Thundering herd prevention for cache misses
7. **Error Handling:** Graceful degradation for backend failures, OPA failures, Redis failures

### Why The Promise Is Simple

**For API Consumers:**
- Request `/openapi.json`
- Get accurate, executable API documentation
- Use with Swagger UI, Postman, or code generation tools
- Confidence that documented endpoints and fields exactly match runtime behavior

**For Developers:**
- Configure kind aliases in `app-oas.yml` or environment-specific config files
- Define parent-child relationships
- Enable/disable routes via toggles
- OAS documentation automatically reflects all changes
- Without aliases: Gateway exposes generic routes (`/entities`, `/lists`)

---

## Related Documentation

- **[FILTERS.md](FILTERS.md)** - Complete filter documentation including field-level security filters
- **[ROUTES.md](ROUTES.md)** - Route inventory and naming conventions
- **[REPO_CONTEXT.md](REPO_CONTEXT.md)** - Overall gateway architecture and feature overview
- **[doc/DYNAMIC-OAS-ORCHESTRATOR-DESIGN.md](../doc/DYNAMIC-OAS-ORCHESTRATOR-DESIGN.md)** - Technical design document with sequence diagrams

---

## Troubleshooting

### Issue: OAS Returns Empty Paths

**Cause:** No kind aliases configured or all routes disabled
**Solution:** Check `app-oas.yml` or imported configuration files for alias definitions and `application-route-toggles.yml` for route states. Without aliases, gateway exposes generic routes only.

### Issue: Fields Still Visible Despite OPA Policy

**Cause:** OPA policy returns incorrect forbidden fields or OPA query fails (falls back to full visibility)
**Solution:** Check OPA logs, verify policy path matches configuration, test policy with sample JWT

### Issue: Slow OAS Generation (> 1s)

**Cause:** Cache miss on every request (cache disabled or Redis unavailable)
**Solution:** Verify Redis connection, check `oas.cache.enabled=true`, review cache hit rate metrics

### Issue: Stale Specification After Config Change

**Cause:** Cached spec with old configuration still being served
**Solution:** Flush OAS cache via admin endpoint or wait for TTL expiry (15 minutes default)

### Issue: Hierarchy Paths Not Appearing

**Cause:** Children/parents not configured in kind alias definition
**Solution:** Add `.children[]` or `.parents[]` configuration to alias, restart gateway

---

**Last Updated:** February 2026  
**Feature Status:** Production-Ready  
**Minimum Gateway Version:** 1.0.0
