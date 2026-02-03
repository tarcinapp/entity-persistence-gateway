# Dynamic OAS Orchestrator - Technical Design Document

## 1. Executive Summary

The **Dynamic OAS Orchestrator** is an embedded component within the Entity Persistence Gateway that intercepts requests for API documentation (`/openapi.json`, `/openapi.yaml`) and generates a **Personalized, Virtualized, and Pruned** OpenAPI Specification (OAS) on-the-fly.

### Key Objectives
1. **Path Virtualization**: Transform technical backend paths (`/entities?kind=book`) into domain-specific aliases (`/books`)
2. **Hierarchy Resolution**: Correctly represent nested resource structures (`/books/{id}/chapters`)
3. **Field-Level Masking**: Prune schema properties based on OPA field permissions
4. **Role-Based Caching**: Efficient Redis caching with deterministic role-based keys

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
│                    │  ┌─────────────────┐  │                │                    │
│                    │  │ PathVirtualizer │  │                │                    │
│                    │  │ OperationRewriter│  │                │                    │
│                    │  │ HierarchyResolver│  │                │                    │
│                    │  └─────────────────┘  │                │                    │
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
**Package:** `com.tarcinapp.entitypersistencegateway.oas`

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
**Package:** `com.tarcinapp.entitypersistencegateway.clients.oas`

**Responsibility:** Fetch and parse the backend's raw OAS.

```java
@Component
public class BackendOasClient {
    
    // Cached raw OAS (TTL: 5 minutes default)
    private final AtomicReference<Mono<OpenAPI>> cachedRawOas;
    
    public Mono<OpenAPI> fetchRawOas() {
        // Reactive WebClient call to ${app.outbound.routing-target}/openapi.json
        // Parsed via swagger-parser-v3
    }
}
```

**Caching Strategy:** Raw OAS cached separately from transformed OAS (it's the same for all users).

### 4.3 OasTransformationEngine
**Package:** `com.tarcinapp.entitypersistencegateway.oas.transformation`

**Responsibility:** Core transformation logic that virtualizes paths and operations.

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

**OPA Policy Contract:**

```rego
# Input: JWT claims only (no request payload/params)
package policies.oas.field_visibility

default visible_fields = {}

visible_fields[recordType] = fields {
    # Determine visible fields per record type based on user's roles
    user_roles := input.roles
    fields := compute_visible_fields(recordType, user_roles)
}

# Alternative: forbidden_fields approach (align with existing ForbiddenFieldsLibrary)
forbidden_fields[recordType] = fields {
    # Return fields the user CANNOT see
}
```

**Service Implementation:**

```java
@Component
public class OasFieldPermissionService {
    
    private static final String OAS_FIELD_POLICY = "/policies/oas/field_visibility/policy/result";
    
    public Mono<FieldPermissionContext> fetchFieldPermissions(GatewaySecurityContext securityContext) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(OAS_FIELD_POLICY);
        policyData.setEncodedJwt(securityContext.getEncodedJwt());
        // Note: NO request payload or query params - just identity context
        
        return opaClient.executePolicy(policyData, FieldPermissionContext.class)
            .onErrorResume(e -> {
                log.warn("OPA field permission query failed, returning full visibility: {}", e.getMessage());
                return Mono.just(FieldPermissionContext.fullVisibility());
            });
    }
}
```

**Constraint Enforcement:** Action-level RBAC is NOT evaluated here because OPA requires runtime request data (payloads, query params) that don't exist during spec generation.

### 4.5 OasSchemaPruner
**Package:** `com.tarcinapp.entitypersistencegateway.oas.transformation`

**Responsibility:** Programmatically remove properties from OAS schemas that the user cannot see.

**Pruning Algorithm:**

```java
@Component
public class OasSchemaPruner {
    
    public OpenAPI pruneSchemas(OpenAPI openApi, FieldPermissionContext permissions) {
        if (permissions.isFullVisibility()) {
            return openApi;
        }
        
        // Deep clone to avoid mutating cached raw OAS
        OpenAPI pruned = cloneOpenApi(openApi);
        
        Components components = pruned.getComponents();
        if (components != null && components.getSchemas() != null) {
            components.getSchemas().forEach((schemaName, schema) -> {
                String recordType = inferRecordType(schemaName);
                Set<String> forbiddenFields = permissions.getForbiddenFields(recordType);
                
                pruneSchemaProperties(schema, forbiddenFields);
            });
        }
        
        return pruned;
    }
    
    private void pruneSchemaProperties(Schema<?> schema, Set<String> forbiddenFields) {
        if (schema.getProperties() == null) return;
        
        forbiddenFields.forEach(field -> {
            // Handle nested paths: "address.zipCode"
            if (field.contains(".")) {
                pruneNestedProperty(schema, field);
            } else {
                schema.getProperties().remove(field);
            }
            
            // Also remove from 'required' array if present
            if (schema.getRequired() != null) {
                schema.getRequired().remove(field);
            }
        });
    }
}
```

### 4.6 OasCacheService
**Package:** `com.tarcinapp.entitypersistencegateway.oas.cache`

**Responsibility:** Role-based caching with thundering herd prevention.

**Cache Key Strategy:**

```java
public class OasCacheKeyBuilder {
    
    public String buildCacheKey(GatewaySecurityContext context) {
        // Deterministic hash of permission-affecting attributes
        List<String> keyComponents = new ArrayList<>();
        keyComponents.addAll(context.getRoles());
        keyComponents.addAll(context.getGroups());
        Collections.sort(keyComponents); // Ensure determinism
        
        String roleFingerprint = DigestUtils.sha256Hex(String.join(":", keyComponents));
        return "oas:v1:" + roleFingerprint;
    }
}
```

**Thundering Herd Prevention:**

```java
@Component
public class OasCacheService {
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Map<String, Mono<String>> inflightRequests = new ConcurrentHashMap<>();
    
    public Mono<String> getOrCompute(String cacheKey, Mono<String> computeSpec) {
        return redisTemplate.opsForValue().get(cacheKey)
            .switchIfEmpty(
                Mono.defer(() -> {
                    // Thundering herd: Only one inflight computation per key
                    return inflightRequests.computeIfAbsent(cacheKey, k ->
                        computeSpec
                            .flatMap(spec -> 
                                redisTemplate.opsForValue()
                                    .set(cacheKey, spec, Duration.ofMinutes(15))
                                    .thenReturn(spec)
                            )
                            .doFinally(signal -> inflightRequests.remove(cacheKey))
                            .cache() // Share result with concurrent subscribers
                    );
                })
            );
    }
}
```

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
        field-policy: /policies/oas/field_visibility/policy/result
        timeout: 500ms
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
    │                     │                                              fetchFieldPermissions(jwt)                   │                      │
    │                     │─────────────────────────────────────────────────────────────────────────────────────────►│                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                                              FieldPermissionContext                       │                      │
    │                     │◄─────────────────────────────────────────────────────────────────────────────────────────│                      │
    │                     │                   │                   │                       │                           │                      │
    │                     │                                                              prune(virtualized, permissions)                     │
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
│   │   ├── OasTransformationEngine.java
│   │   ├── PathVirtualizer.java
│   │   ├── OperationRewriter.java
│   │   ├── HierarchyResolver.java
│   │   └── OasSchemaPruner.java
│   ├── security/
│   │   ├── OasFieldPermissionService.java
│   │   └── FieldPermissionContext.java
│   └── cache/
│       ├── OasCacheService.java
│       └── OasCacheKeyBuilder.java
```

---

## 11. Implementation Priority

1. **Phase 1:** Core Transformation (PathVirtualizer, OperationRewriter)
2. **Phase 2:** Hierarchy Resolution
3. **Phase 3:** Field-Level Pruning (OPA Integration)
4. **Phase 4:** Caching Layer
5. **Phase 5:** Edge Cases (Relations, Reactions)

---

## 12. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Backend OAS schema changes | Transformation breaks | Version-aware caching, graceful degradation |
| OPA latency spikes | Slow spec generation | Aggressive caching, OPA failure returns full visibility |
| Memory pressure from large specs | OOM | Caffeine size limits, streaming JSON serialization |
| Cache invalidation complexity | Stale specs | TTL-based expiry, admin endpoint for manual flush |