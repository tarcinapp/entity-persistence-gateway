# Dynamic OAS Orchestrator - Technical Design Document

## 1. Executive Summary

The **Dynamic OAS Orchestrator** is an embedded component within the Entity Persistence Gateway that intercepts requests for API documentation (`/openapi.json`, `/openapi.yaml`) and generates a **Personalized, Virtualized, and Pruned** OpenAPI Specification (OAS) on-the-fly.

### Key Objectives
1. **Path Virtualization**: Transform technical backend paths (`/entities?kind=book`) into domain-specific aliases (`/books`)
2. **Hierarchy Resolution**: Correctly represent nested resource structures (`/books/{id}/chapters`)
3. **Field-Level Masking**: Prune schema properties based on OPA field permissions
4. **Unused Schema Cleanup**: Remove unreferenced component schemas after pruning
5. **Role-Based Caching**: Efficient Redis caching with deterministic role-based keys

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Dynamic OAS Request Flow                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────┐    ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │   Consumer   │───►│ DynamicOasHandler   │───►│   OasCacheService           │ │
│  │ (GET /openapi)│   │  (Entry Point)      │    │   (Redis + Caffeine L2)     │ │
│  └──────────────┘    └─────────┬───────────┘    └──────────┬──────────────────┘ │
│                                │ Cache Miss                 │ Cache Hit          │
│                                ▼                            │                    │
│                    ┌───────────────────────┐                │                    │
│                    │  BackendOasClient     │                │                    │
│                    │  (Fetch Raw OAS)      │                │                    │
│                    └───────────┬───────────┘                │                    │
│                                │                            │                    │
│                                ▼                            │                    │
│                    ┌───────────────────────┐                │                    │
│                    │ OasTransformationEngine│               │                    │
│                    │  (Path Virtualization, Operation       │                    │
│                    │   Rewriting, Hierarchy Resolution)     │                    │
│                    └───────────┬───────────┘                │                    │
│                                │                            │                    │
│                                ▼                            │                    │
│              ┌─────────────────────────────────┐            │                    │
│              │ OasFieldPermissionService       │            │                    │
│              │  (OPA Field-Level Query)        │            │                    │
│              └───────────────┬─────────────────┘            │                    │
│                              │                              │                    │
│                              ▼                              │                    │
│              ┌─────────────────────────────────┐            │                    │
│              │ OasSchemaPruner                 │            │                    │
│              │  (Remove Forbidden Properties)  │            │                    │
│              └───────────────┬─────────────────┘            │                    │
│                              │                              │                    │
│                              ▼                              ▼                    │
│                    ┌───────────────────────────────────────────┐                 │
│                    │        Personalized OAS Response          │                 │
│                    └───────────────────────────────────────────┘                 │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Sources of Truth

### 3.1 OpenApiProperties.java
The configuration backbone providing:
- **Controller Mappings**: Maps technical `controllerName` to business domain
- **Alias Resolution**: `aliasLookupByControllerAndAlias` provides O(1) lookup for alias→kind mapping
- **Hierarchy Definitions**: `AliasConfig.children` and `AliasConfig.parents` define nested relationships
- **Route Configurations**: Per-route `operationId`, `summary`, `description`, `tags` overrides

**Critical Method:**
```java
public AliasContext getAliasContext(String controllerName, String alias) {
    // Fast lookup for controller:alias → AliasContext(controllerName, AliasConfig)
    return aliasLookupByControllerAndAlias.get(buildAliasKey(controllerName, alias));
}
```

### 3.2 application-routes.yml
Contains route metadata with:
- `id`: Route identifier (e.g., `createEntity`, `findEntities`)
- `metadata.controllerName`: Logical controller grouping
- `metadata.recordType`: Technical record type (entities, lists, relations, reactions)
- `metadata.tags`: Route categorization for filtering

### 3.3 app-oas.yml
Global API metadata:
- `title`, `version`, `description`, `contact`
- `servers`: Environment-specific base URLs
- `controllers`: Placeholder for alias configurations (loaded from properties)

### 3.4 Backend OAS (entity-persistence-service)
The raw technical OpenAPI spec with:
- Technical paths: `/entities`, `/entities/{id}`, `/entities/{id}/children`
- Technical operationIds: `findEntities`, `createEntity`, `findChildrenByEntityId`
- Complete schema definitions with all internal fields

---

## 4. Component Specifications

### 4.1 DynamicOasHandler
**Package:** `com.tarcinapp.entitypersistencegateway.oas.handler`

**Responsibility:** Entry point for OAS requests. Orchestrates the transformation pipeline.

```java
@Component
public class DynamicOasHandler {
    
    public Mono<ServerResponse> handleOasRequest(ServerRequest request) {
        // 1. Extract JWT and determine cache key
        // 2. Check cache for existing personalized spec
        // 3. On cache miss: orchestrate transformation pipeline
        // 4. Return JSON/YAML based on Accept header
    }
}
```

**Isolation Guarantee:** Uses dedicated RouterFunction, completely separate from gateway route processing.

### 4.2 BackendOasClient
**Package:** `com.tarcinapp.entitypersistencegateway.oas.client`

**Responsibility:** Fetch and parse the backend's raw OAS.

```java
@Component
public class BackendOasClient {
    
    // Cached raw OAS (TTL: 5 minutes default)
    private final AtomicReference<CachedOas> cachedRawOas = new AtomicReference<>();
    
    public Mono<OpenAPI> fetchRawOas() {
        // Reactive WebClient call to ${app.outbound.routing-target}/openapi.json
        // Parsed via swagger-parser-v3 (OpenAPIV3Parser)
    }
}
```

**Caching Strategy:** Raw OAS cached separately from transformed OAS (it's the same for all users).

### 4.3 OasTransformationEngine
**Package:** `com.tarcinapp.entitypersistencegateway.oas.transformation`

**Responsibility:** Core transformation logic that virtualizes paths and operations. All sub-concerns (path virtualization, operation rewriting, hierarchy resolution) are implemented as private methods within this class — there are no separate `PathVirtualizer`, `OperationRewriter`, or `HierarchyResolver` classes.

**Additional Services Used:**
- `BackendSchemaService`: Pre-fetched backend operation schemas used when building domain-specific request body schemas.
- `RouteMetadataService`: Extracts route tags and controller metadata from `GatewayProperties` for toggle-based filtering.

**Response Schema Policy:** Backend response schemas are preserved per operation and transformed into alias-specific components (x-record-type injected). POST/PUT/PATCH/DELETE keep their native shapes; only GET responses are rebound to domain response schemas. All responses are still pruned with FIND permissions.

**Transformation Rules:**

| Backend Pattern | Alias Config | Transformed Path |
|-----------------|--------------|------------------|
| `/entities` | `alias: books, kind: book` | `/books` |
| `/entities/{id}` | `alias: books, kind: book` | `/books/{id}` |
| `/entities/{id}/children` | `children: [{alias: chapters, kind: chapter}]` | `/books/{id}/chapters` |
| `/entities/{id}/parents` | `parents: [{alias: authors, kind: author}]` | `/books/{id}/authors` |

**Operation Rewriting:**

```java
private void rewriteOperation(Operation operation, AliasConfig alias, String routeId) {
    RouteConfig routeConfig = alias.getRoutes().get(routeId);
    
    if (routeConfig != null) {
        // Direct mapping from config
        operation.setOperationId(routeConfig.getOperationId());
        operation.setSummary(routeConfig.getSummary());
        operation.setDescription(routeConfig.getDescription());
        operation.setTags(routeConfig.getTags());
    } else {
        // Fallback: Auto-generate based on alias
        // findEntities → listBooks, createEntity → createBook
        operation.setOperationId(generateOperationId(routeId, alias));
    }
}
```

**Hierarchy Resolution Algorithm:**

```java
public List<TransformedPath> resolveHierarchies(AliasConfig rootAlias, String controllerName) {
    List<TransformedPath> paths = new ArrayList<>();
    
    // 1. Process children hierarchies
    for (AliasConfig childAlias : rootAlias.getChildren()) {
        // /books/{id}/chapters → kind=chapter
        paths.add(new TransformedPath(
            "/" + rootAlias.getAlias() + "/{id}/" + childAlias.getAlias(),
            "children",
            childAlias.getKind(),
            childAlias
        ));
        
        // Recursive: /books/{id}/chapters/{childId}/sections
        paths.addAll(resolveHierarchies(childAlias, controllerName));
    }
    
    // 2. Process parent hierarchies
    for (AliasConfig parentAlias : rootAlias.getParents()) {
        paths.add(new TransformedPath(
            "/" + rootAlias.getAlias() + "/{id}/" + parentAlias.getAlias(),
            "parents",
            parentAlias.getKind(),
            parentAlias
        ));
    }
    
    return paths;
}
```

### 4.4 OasFieldPermissionService
**Package:** `com.tarcinapp.entitypersistencegateway.oas.security`

**Responsibility:** Query OPA for field-level permissions (NOT action-level RBAC).

**Multi-Operation Query Strategy:** OPA returns different forbidden fields based on HTTP method and operation type. The service makes **3 parallel OPA queries** — one each for find (GET), create (POST), and update (PATCH) — and combines results into a `MultiOperationFieldPermissions` object. This enables the pruner to apply operation-specific field visibility (e.g., a field visible on GET but forbidden on POST).

```java
// Query 1 (find):   { httpMethod: "GET",   requestPath: "/entities", operation: "find" }
// Query 2 (create): { httpMethod: "POST",  requestPath: "/entities", operation: "create" }
// Query 3 (update): { httpMethod: "PATCH", requestPath: "/entities", operation: "update" }
```

**OPA Policy Used:** The same policy as `FetchForbiddenFieldsGatewayFilterFactory`, reusing the existing `ForbiddenFieldsLibrary` response structure. The policy path is configurable; the default is the gateway's shared forbidden-fields policy.

**Service Implementation:**

```java
@Service
public class OasFieldPermissionService {
    
    // Default: reuses the gateway's existing forbidden-fields policy
    // Override via app.oas.orchestrator.opa.field-policy
    
    public Mono<MultiOperationFieldPermissions> fetchMultiOperationPermissions(
            GatewaySecurityContext securityContext, String basePath) {
        
        // Query OPA in parallel for all 3 operations
        Mono<FieldPermissionContext> findPerms   = fetchPermissionsForOperation(securityContext, basePath, Operation.FIND);
        Mono<FieldPermissionContext> createPerms = fetchPermissionsForOperation(securityContext, basePath, Operation.CREATE);
        Mono<FieldPermissionContext> updatePerms = fetchPermissionsForOperation(securityContext, basePath, Operation.UPDATE);
        
        return Mono.zip(findPerms, createPerms, updatePerms)
            .map(tuple -> {
                MultiOperationFieldPermissions result = new MultiOperationFieldPermissions();
                result.setPermissionsForOperation(Operation.FIND,   tuple.getT1());
                result.setPermissionsForOperation(Operation.CREATE, tuple.getT2());
                result.setPermissionsForOperation(Operation.UPDATE, tuple.getT3());
                return result;
            });
    }
}
```

**Constraint Enforcement:** Action-level RBAC is NOT evaluated here because OPA requires runtime request data (payloads, query params) that don't exist during spec generation. OPA failures fall back to full visibility (fail-open) unless `opa.fail-closed=true`.

### 4.5 OasSchemaPruner
**Package:** `com.tarcinapp.entitypersistencegateway.oas.transformation`

**Responsibility:** Programmatically remove properties from OAS schemas that the user cannot see.

**Schema Identification:** Record type is determined by reading the `x-record-type` vendor extension injected by `OasTransformationEngine` during schema generation. If a schema is missing this extension, a CRITICAL error is logged and the schema is skipped (fail-safe). Record type is never inferred from the schema name.

**Final Cleanup:** After field pruning, `pruneUnusedSchemas()` removes any component schemas no longer referenced by any path or component.

**Two Pruning Variants:**

```java
@Component
public class OasSchemaPruner {
    
    /**
     * Single-permission variant (legacy / fallback).
     * Applies the same forbidden field set to all operations.
     */
    public OpenAPI prune(OpenAPI openApi, FieldPermissionContext permissions) { ... }
    
    /**
     * Multi-operation variant (primary path).
     * Applies per-operation forbidden fields:
     *   GET response schemas  → FIND permissions
     *   POST request schemas  → CREATE permissions
     *   PATCH/PUT schemas     → UPDATE permissions
     */
    public OpenAPI pruneWithMultiOperationPermissions(
            OpenAPI openApi, MultiOperationFieldPermissions permissions) { ... }
}
```

`DynamicOasHandler` calls `pruneWithMultiOperationPermissions()`. Both variants deep-clone the OpenAPI object before modifying it to avoid mutating the cached raw OAS.

### 4.6 OasCacheService
**Package:** `com.tarcinapp.entitypersistencegateway.oas.cache`

**Responsibility:** Role-based caching with two-level hierarchy and thundering herd prevention.

**Cache Key Strategy:** Roles are prefixed with `r:` and groups with `g:`, then all components are sorted for determinism and joined with `|` before hashing:

```java
@Component
public class OasCacheKeyBuilder {
    
    private static final String KEY_PREFIX = "oas:v1:";
    private static final String ANONYMOUS_KEY = KEY_PREFIX + "anonymous";
    
    public String buildCacheKey(GatewaySecurityContext context) {
        if (context == null || context.getEncodedJwt() == null) {
            return ANONYMOUS_KEY; // "oas:v1:anonymous"
        }
        
        List<String> components = new ArrayList<>();
        // Roles prefixed r:, groups prefixed g:
        roles.forEach(r -> components.add("r:" + r));
        groups.forEach(g -> components.add("g:" + g));
        Collections.sort(components); // Ensure determinism
        
        String fingerprint = sha256Hex(String.join("|", components));
        return KEY_PREFIX + fingerprint; // "oas:v1:{sha256}"
    }
}
```

**Two-Level Cache + Thundering Herd Prevention:**

```java
@Service
public class OasCacheService {
    
    private final ReactiveStringRedisTemplate redisTemplate;
    private final Cache<String, String> localCache; // L1: Caffeine
    private final ConcurrentHashMap<String, Mono<String>> inflightComputations;
    
    public Mono<String> getOrCompute(String cacheKey, Mono<String> computeSpec) {
        // 1. L1: Check Caffeine (sub-millisecond)
        String local = localCache.getIfPresent(cacheKey);
        if (local != null) return Mono.just(local);
        
        // 2. L2: Check Redis (cross-instance sharing)
        return redisTemplate.opsForValue().get(cacheKey)
            .switchIfEmpty(Mono.defer(() ->
                // 3. Compute with thundering herd protection
                inflightComputations.computeIfAbsent(cacheKey, k ->
                    computeSpec
                        .flatMap(spec -> redisTemplate.opsForValue()
                            .set(cacheKey, spec, ttl).thenReturn(spec))
                        .doFinally(s -> inflightComputations.remove(cacheKey))
                        .cache() // Share with concurrent subscribers
                )
            ))
            .doOnNext(spec -> localCache.put(cacheKey, spec)); // Populate L1
    }
}
```

### 4.7 BackendSchemaService
**Package:** `com.tarcinapp.entitypersistencegateway.oas.service`

**Responsibility:** Pre-fetches and indexes the backend's request/response schemas by controller and HTTP method at startup. The `OasTransformationEngine` uses these cached `JsonNode` schemas when constructing domain-specific `New{Alias}` and `Patch{Alias}` schemas without needing to re-fetch the backend OAS on each transformation.

**Key format:** `"{controllerName}:{HTTP_METHOD}"` (e.g., `"entities:POST"`, `"entities:PATCH"`).

### 4.8 RouteMetadataService
**Package:** `com.tarcinapp.entitypersistencegateway.oas.service`

**Responsibility:** Extracts route metadata (tags, controller name, record type) from Spring Cloud Gateway's `GatewayProperties` at startup. The `OasTransformationEngine` uses this to filter operations based on route toggles without re-reading the YAML configuration.

---

## 5. Edge Cases

### 5.1 Relations Controller (`/relations`)
Relations don't follow standard entity patterns - they're join records between lists and entities.

**Handling:**
- Map to alias if configured: `/relations` → `/book-assignments`
- Include `_listId` and `_entityId` parameters in transformed spec
- Schema references both source and target types

### 5.2 Reactions Controllers (`/entity-reactions`, `/list-reactions`)
Reactions are polymorphic (likes, comments, ratings).

**Handling:**
- Preserve `_entityId`/`_listId` relationship
- If alias configured: `/entity-reactions` → `/book-reviews`
- Maintain `reactionType` discriminator in schema

### 5.3 Through-Controllers (`/entities/{id}/reactions`, `/lists/{id}/entities`)
These are compound paths that chain resources.

**Handling:**
- Resolve both the root resource and the target resource aliases
- `/entities/{id}/reactions` with root=book → `/books/{id}/reviews`
- `/lists/{id}/entities` with root=bookshelf, target=book → `/bookshelves/{id}/books`

### 5.4 Anonymous Users (No JWT)
**Handling:**
- Use special cache key: `oas:v1:anonymous`
- Query OPA with empty claims → returns public-only field visibility
- Potentially different schema pruning than authenticated users

### 5.5 Schema Name Collisions
Backend uses verbose schema names like `GenericEntityExcluding__idempotencyKey-...`.

**Handling:**
- Generate cleaner names: `Book`, `BookInput`, `BookPartial`
- Maintain internal mapping for reference resolution

---

## 6. Configuration

### 6.1 New Configuration Properties

```yaml
app:
  oas:
    orchestrator:
      enabled: true
      endpoints:
        json: /openapi.json
        yaml: /openapi.yaml
      cache:
        enabled: true
        ttl: PT15M  # 15 minutes
        raw-oas-ttl: PT5M  # Raw backend OAS cache
      backend:
        spec-path: /explorer/openapi.json
        connect-timeout: 3000
        read-timeout: 5000
      opa:
        # Default: reuses the gateway's existing forbidden-fields policy
        # Override to point to a dedicated OAS field-visibility policy if needed
        field-policy: /policies/gateway/forbidden_fields/policy/result
        timeout: PT500MS
        fail-closed: false  # When false, OPA failure returns full-visibility spec
```

---

## 7. Dependencies

Add to `pom.xml`:

```xml
<!-- OpenAPI Parser -->
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser-v3</artifactId>
    <version>2.1.22</version>
</dependency>

<!-- OpenAPI Models (transitive, but explicit for clarity) -->
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-models</artifactId>
    <version>2.2.21</version>
</dependency>

<!-- YAML serialization for OAS output -->
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

---

## 8. Sequence Diagram: OAS Request Flow

```
Consumer           DynamicOasHandler    OasCacheService    BackendOasClient    OasTransformationEngine    OasFieldPermissionService    OasSchemaPruner
    │                     │                   │                   │                       │                           │                      │
    │ GET /openapi.json   │                   │                   │                       │                           │                      │
    │────────────────────►│                   │                   │                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │ buildCacheKey()   │                   │                       │                           │                      │
    │                     │──────────────────►│                   │                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │   get(cacheKey)   │                   │                       │                           │                      │
    │                     │──────────────────►│                   │                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │   Cache Miss      │                   │                       │                           │                      │
    │                     │◄──────────────────│                   │                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │              fetchRawOas()            │                       │                           │                      │
    │                     │──────────────────────────────────────►│                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │              OpenAPI (raw)            │                       │                           │                      │
    │                     │◄──────────────────────────────────────│                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                        transform(raw, openApiProperties)      │                           │                      │
    │                     │──────────────────────────────────────────────────────────────►│                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                       OpenAPI (virtualized)                   │                           │                      │
    │                     │◄──────────────────────────────────────────────────────────────│                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                                              fetchMultiOperationPermissions(jwt, basePath)│                      │
    │                     │─────────────────────────────────────────────────────────────────────────────────────────►│                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                                              MultiOperationFieldPermissions               │                      │
    │                     │◄─────────────────────────────────────────────────────────────────────────────────────────│                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                                                              pruneWithMultiOperationPermissions(virtualized, permissions)│
    │                     │─────────────────────────────────────────────────────────────────────────────────────────────────────────────────►│
    │                     │                   │                   │                       │                           │                      │
    │                     │                                                              OpenAPI (pruned)                                    │
    │                     │◄─────────────────────────────────────────────────────────────────────────────────────────────────────────────────│
    │                     │                   │                   │                       │                           │                      │
    │                     │ set(key, pruned)  │                   │                       │                           │                      │
    │                     │──────────────────►│                   │                       │                           │                      │
    │                     │                   │                   │                       │                           │                      │
    │ 200 OK (OpenAPI)    │                   │                   │                       │                           │                      │
    │◄────────────────────│                   │                   │                       │                           │                      │
```

---

## 9. Non-Functional Requirements

### 9.1 Performance
- **Target Latency (Cache Hit):** < 10ms
- **Target Latency (Cache Miss):** < 500ms
- **Memory:** L2 Caffeine cache with max 100 entries

### 9.2 Resilience
- Backend OAS fetch failure: Return cached version or 503
- OPA failure: Return full-visibility spec (log warning, don't block)
- Redis failure: Fall back to Caffeine L2 cache

### 9.3 Observability
- Metrics: `oas.requests.total`, `oas.cache.hits`, `oas.transform.duration`
- Logging: DEBUG for transformation decisions, WARN for fallbacks

---

## 10. File Structure

```
src/main/java/com/tarcinapp/entitypersistencegateway/
├── oas/
│   ├── config/
│   │   ├── OasOrchestratorProperties.java
│   │   └── OasRouterConfiguration.java
│   ├── handler/
│   │   └── DynamicOasHandler.java
│   ├── client/
│   │   └── BackendOasClient.java
│   ├── transformation/
│   │   ├── OasTransformationEngine.java   ← contains path virtualization, operation
│   │   │                                    rewriting, and hierarchy resolution logic
│   │   └── OasSchemaPruner.java
│   ├── security/
│   │   ├── OasFieldPermissionService.java
│   │   ├── FieldPermissionContext.java
│   │   └── MultiOperationFieldPermissions.java
│   ├── service/
│   │   ├── BackendSchemaService.java
│   │   └── RouteMetadataService.java
│   └── cache/
│       ├── OasCacheService.java
│       └── OasCacheKeyBuilder.java
```

---

## 11. Implementation Priority

1. **Phase 1:** Core Transformation (path virtualization & operation rewriting in `OasTransformationEngine`)
2. **Phase 2:** Hierarchy Resolution
3. **Phase 3:** Backend Schema Indexing (`BackendSchemaService`)
4. **Phase 4:** Field-Level Pruning (OPA Integration — `OasFieldPermissionService`, multi-operation `OasSchemaPruner`)
5. **Phase 5:** Caching Layer
6. **Phase 6:** Edge Cases (Relations, Reactions, Through-Controllers)

---

## 12. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Backend OAS schema changes | Transformation breaks | Version-aware caching, graceful degradation |
| OPA latency spikes | Slow spec generation | Aggressive caching, OPA failure returns full visibility |
| Memory pressure from large specs | OOM | Caffeine size limits, streaming JSON serialization |
| Cache invalidation complexity | Stale specs | TTL-based expiry, admin endpoint for manual flush |