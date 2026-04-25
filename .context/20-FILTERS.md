# Gateway Filters Documentation

This document provides a comprehensive reference for all filters used in the Entity Persistence Gateway. Filters are applied to routes in the order they are defined and execute various middleware operations such as authentication, authorization, validation, transformation, and caching.

## Table of Contents

- [Quick Reference](#quick-reference)
- [Detailed Filter Descriptions](#detailed-filter-descriptions)
- [Filter Usage by Route](#filter-usage-by-route)

---

## Quick Reference

### Built-in Spring Cloud Gateway Filters

| Filter Name | Category | Purpose |
|------------|----------|---------|
| `RewritePath` | Routing | Rewrites request URL paths |
| `RemoveRequestHeader` | Transformation | Removes specific headers |
| `RequestSize` | Validation | Limits request body size |
| `RequestRateLimiter` | Protection | Rate limiting per user/IP |
| `SetStatus` | Response | Sets HTTP status code |

### Custom Application Filters

| Filter Name | Category | Purpose |
|------------|----------|---------|
| `CheckIfRouteEnabled` | Control | Enables/disables routes dynamically |
| `AuthenticateRequest` | Security | Validates authentication tokens |
| `GenerateRequestId` | Tracing | Generates unique request identifiers |
| `FetchForbiddenFields` | Authorization | Retrieves field-level permissions |
| `AuthorizeRequest` | Security | Policy-based authorization |
| `AcquireLockForCreation` | Concurrency | Distributed locking for creates |
| `AcquireLockForUpdate` | Concurrency | Distributed locking for updates |
| `AddManagedFieldsInCreation` | Transformation | Adds system-managed fields |
| `AddManagedFieldsFromOriginalToPayloadInReplace` | Transformation | Preserves managed fields in PUT |
| `AddForbiddenFieldsFromOriginalToPayloadInReplace` | Transformation | Preserves forbidden fields in PUT |
| `ApplyFieldsetConfig` | Transformation | Applies fieldset projections |
| `FieldFilter` | Transformation | Filters response fields |
| `PreventStringifiedJsonFilter` | Validation | Prevents stringified JSON queries |
| `ConvertSimplerQueriesToBackendFormat` | Transformation | Converts query syntax |
| `ConvertDomainIncludeAliasToGenericRelation` | Transformation | Rewrites include relation aliases to generic relations and injects include scope `_kind` |
| `AddSetsToEntityListOrReactionViaRecordQuery` | Transformation | Adds set filters for entities/lists |
| `AddSetsToRelationQuery` | Transformation | Adds set filters for relations |
| `AddSetsToReactionsQuery` | Transformation | Adds set filters for reactions |
| `AddSetsToThroughRecordQuery` | Transformation | Adds set filters for through records |
| `PreventQueryByForbiddenFields` | Validation | Blocks queries on forbidden fields |
| `InjectTypeHintsToQuery` | Transformation | Injects LoopBack 4 type hints (`number`/`boolean`) into where-clause query params and converts implicit exact-match keys to explicit `[eq]` form |
| `DynamicLocalCache` | Performance | Local caching with TTL |
| `PlaceKindNameIntoPayload` | Transformation | Injects kind into request body |
| `ValidateRequestBodyByKindSchema` | Validation | Schema validation by kind |
| `KindResolution` | Transformation | Resolves kind aliases to names |
| `HierarchyKindAliasResolver` | Routing | Resolves hierarchical kind aliases to technical paths |
| `ThroughKindAliasResolver` | Routing | Resolves through-record kind alias routes to technical paths |
| `ConvertKindAliasToKindQuery` | Transformation | Converts kind alias in queries |
| `ProjectDomainIncludeAliasInResponse` | Transformation | Projects generic include fields (e.g. `_entities`) back to requested domain aliases (e.g. `books`) |
| `DynamicTimeout` | Configuration | Sets request timeouts dynamically |
| `DynamicRequestSizeFilter` | Validation | Dynamic request size limits |
| `DynamicRateLimiter` | Protection | Dynamic rate limiting |

---

## Detailed Filter Descriptions

### Security & Authentication Filters

#### `AuthenticateRequest`
**Purpose:** Validates the JWT authentication token in the request and extracts user identity.

**Behavior:**
- Validates JWT tokens from the Authorization header
- Extracts user information and sets it in the request context
- Returns 401 Unauthorized if authentication fails

**Configuration:** None

**Used in:** All routes except `ping` and `explorer`

---

#### `AuthorizeRequest`
**Purpose:** Enforces policy-based authorization using OPA (Open Policy Agent).

**Configuration:**
```yaml
- name: AuthorizeRequest
  args:
    policyName: /policies/auth/routes/{controller}/{operation}/policy
```

**Behavior:**
- Evaluates authorization policies based on user identity and request context
- Returns 403 Forbidden if authorization fails
- Policies are evaluated per route

**Used in:** All authenticated routes

---

#### `FetchForbiddenFields`
**Purpose:** Retrieves field-level access control policies for the current user and resource type.

**Behavior:**
- Queries field-level permissions from the policy engine
- Caches forbidden fields in request context for use by other filters
- Used by validation and transformation filters

**Configuration:** None

**Used in:** Most routes that handle entity data (creates, reads, updates, deletes)

---

### Validation Filters

#### `RequestSize`
**Purpose:** Enforces maximum request body size limits.

**Configuration:**
```yaml
- name: RequestSize
  args:
    maxSize: ${app.request-sizes.entities.create}
```

**Behavior:**
- Validates request body size before processing
- Returns 413 Payload Too Large if limit exceeded
- Configurable per route

**Used in:** POST, PUT, PATCH operations

---

#### `PreventStringifiedJsonFilter`
**Purpose:** Prevents stringified JSON objects in query parameters.

**Behavior:**
- Detects and rejects query parameters containing escaped JSON strings
- Returns 400 Bad Request with error details
- Prevents potential injection attacks

**Configuration:** None

**Used in:** GET operations with query parameters

---

#### `PreventQueryByForbiddenFields`
**Purpose:** Blocks queries that attempt to filter or sort by forbidden fields.

**Behavior:**
- Checks query filters and sort parameters against forbidden fields list
- Returns 400 Bad Request if forbidden fields are used
- Requires `FetchForbiddenFields` to run first

**Configuration:** None

**Used in:** All query/find operations

---

#### `InjectTypeHintsToQuery`
**Purpose:** Automatically injects backend compatible type hints into where-clause query parameters so that clients do not need to include them manually.

**Behavior:**
- Runs after `PreventQueryByForbiddenFields`, before `RemoveRequestHeader=Authorization`
- Skips when no `KindAliasConfigAttr` is present (generic non-alias routes)
- Consults `TypeHintSchemaRegistry` (pre-built at startup from OpenAPI alias schemas) using the same 6-priority key hierarchy as `ValidateRequestBodyByKindSchema`
- Processes all seven where-clause families:
  - `filter[where][…]`, `entityFilter[where][…]`, `listFilter[where][…]`, `filterThrough[where][…]` (filter families)
  - `where[…]`, `entityWhere[…]`, `listWhere[…]` (bulk updateAll / deleteAll / count families)
- For each matched where-clause key whose field resolves to a `number` or `boolean` hint:
  1. **Implicit exact-match** (`filter[where][price]`): renames the key to the explicit `[eq]` form (`filter[where][price][eq]`) and adds the `[type]` sibling at the field level (`filter[where][price][type]=number`)
  2. **Explicit operator** (`filter[where][price][gt]`): keeps the original key unchanged and adds a `[type]` sibling at the field level (`filter[where][price][gt]=200` + `filter[where][price][type]=number`)
  3. **Array-value operators** (`filter[where][price][inq][0]`): keeps all original keys unchanged and adds a `[type]` sibling at the field level (`filter[where][price][type]=number`)
- Supports dot-notation field paths (e.g. `filter[where][info.pageCount]` → registry key `info.pageCount`)
- Skips gateway-managed fields (fields starting with `_`)
- Skips keys containing `[lookup]` — see **Lookup scope limitation** below
- Rebuilds the URI only when at least one hint was injected
- Type hints supported: `number` (maps from JSON Schema `number`, `integer`, `float`, `double`) and `boolean`
- Nullable / union types (`"type": ["number", "null"]`) are handled — first mappable element wins
- Array fields: if the field type is `array`, the hint is derived from `items.type` (e.g. `array` of `integer` → `number`), enabling correct type coercion for `[inq]` queries

**Lookup scope limitation**

Keys of the form `filter[lookup][N][scope][where][field]` are skipped. The filter consults the registry using the *current route's* kind schema. Inside a lookup scope, queries target referenced documents of a potentially different kind whose schema is unknown at request time — the backend resolves the target kind at query execution, not at the gateway level. Applying the parent schema to a lookup scope would produce incorrect type hints.

Clients that need type coercion inside a lookup scope must include the `[type]` hint manually:
```
filter[lookup][0][scope][where][pageCount][lte]=300
&filter[lookup][0][scope][where][pageCount][type]=number
```

**Configuration:** None

**Used in:** All routes that carry where-clause query parameters (find, count, updateAll, deleteAll — generic and all kind-alias, hierarchy, and through variants). Wired into 80 route definitions immediately after `PreventQueryByForbiddenFields`.

**Exact route IDs (cross-validated against `application-routes.yml`):**
- `countEntities`
- `countEntitiesByKindAlias`
- `countEntityReactions`
- `countEntityReactionsByKindAlias`
- `countListReactions`
- `countListReactionsByKindAlias`
- `countLists`
- `countListsByKindAlias`
- `countRelations`
- `countRelationsByKindAlias`
- `deleteEntitiesByListId`
- `deleteEntitiesByListIdByKindAlias`
- `deleteReactionsByEntityId`
- `deleteReactionsByEntityIdByKindAlias`
- `deleteReactionsByListId`
- `deleteReactionsByListIdByKindAlias`
- `findAllEntitiesByKindAlias`
- `findAllEntityReactionsByKindAlias`
- `findAllListReactionsByKindAlias`
- `findAllListsByKindAlias`
- `findAllRelationsByKindAlias`
- `findChildrenEntityReactionsByReactionId`
- `findChildrenEntityReactionsByReactionIdByKindAlias`
- `findChildrenListReactionsByReactionId`
- `findChildrenListReactionsByReactionIdByKindAlias`
- `findEntities`
- `findEntitiesByListId`
- `findEntitiesByListIdByKindAlias`
- `findEntityById`
- `findEntityByIdByKindAlias`
- `findEntityChildren`
- `findEntityChildrenByKindAlias`
- `findEntityHierarchyByKindAlias`
- `findEntityParents`
- `findEntityParentsByKindAlias`
- `findEntityReactionById`
- `findEntityReactionByIdByKindAlias`
- `findEntityReactionHierarchyByKindAlias`
- `findEntityReactions`
- `findListById`
- `findListByIdByKindAlias`
- `findListChildren`
- `findListChildrenByKindAlias`
- `findListHierarchyByKindAlias`
- `findListParents`
- `findListParentsByKindAlias`
- `findListReactionById`
- `findListReactionByIdByKindAlias`
- `findListReactionHierarchyByKindAlias`
- `findListReactions`
- `findLists`
- `findListsByEntityId`
- `findListsByEntityIdByKindAlias`
- `findParentsByEntityReactionId`
- `findParentsByEntityReactionIdByKindAlias`
- `findParentsByListReactionId`
- `findParentsByListReactionIdByKindAlias`
- `findReactionsByEntityId`
- `findReactionsByEntityIdByKindAlias`
- `findReactionsByListId`
- `findReactionsByListIdByKindAlias`
- `findRelationById`
- `findRelationByIdByKindAlias`
- `findRelations`
- `updateAllEntities`
- `updateAllEntitiesByKindAlias`
- `updateAllEntityReactions`
- `updateAllEntityReactionsByKindAlias`
- `updateAllListReactions`
- `updateAllListReactionsByKindAlias`
- `updateAllLists`
- `updateAllListsByKindAlias`
- `updateAllRelations`
- `updateAllRelationsByKindAlias`
- `updateEntitiesByListId`
- `updateEntitiesByListIdByKindAlias`
- `updateReactionsByEntityId`
- `updateReactionsByEntityIdByKindAlias`
- `updateReactionsByListId`
- `updateReactionsByListIdByKindAlias`

---

#### `ValidateRequestBodyByKindSchema`
**Purpose:** Validates request body against JSON schema for the specified kind.

**Behavior:**
- Retrieves schema definition for the entity kind
- Validates payload structure and data types
- Returns 400 Bad Request with validation errors

**Configuration:** None

**Used in:** Kind alias routes with POST/PUT operations

---

### Routing & Transformation Filters

#### `RewritePath`
**Purpose:** Rewrites the request path before forwarding to the backend.

**Configuration:**
```yaml
# Simple rewrite
- RewritePath=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}, 
              ${app.outbound.routing-target.baseUri}entities

# Regex-based rewrite with capture groups
- name: RewritePath
  args:
    regexp: ${app.inbound.baseUri}${app.inbound.controllerBasePaths.relations}/(?<recordId>.*)
    replacement: ${app.outbound.routing-target.baseUri}relations/${recordId}
```

**Behavior:**
- Transforms incoming URLs to backend URLs
- Supports regex patterns with named capture groups
- Essential for path mapping

**Used in:** All routes

---

#### `ConvertSimplerQueriesToBackendFormat`
**Purpose:** Converts simplified query syntax to backend query format.

**Behavior:**
- Transforms user-friendly query parameters to backend query language
- Handles operators like `eq`, `ne`, `gt`, `lt`, etc.
- Converts dot notation to nested objects

**Configuration:** None

**Used in:** All GET operations with filtering

---

#### `AddSetsToEntityListOrReactionViaRecordQuery`
**Purpose:** Adds set membership filters for entity/list queries via record relationships.

**Behavior:**
- Automatically filters records based on user's set memberships
- Applies when querying entities or lists through relations
- Enforces data isolation

**Configuration:** None

**Used in:** Entity and list query operations

---

#### `AddSetsToRelationQuery`
**Purpose:** Adds set membership filters for relation queries.

**Behavior:**
- Filters relations based on user's set permissions
- Ensures users only see authorized relationships

**Configuration:** None

**Used in:** Relation query operations

---

#### `AddSetsToReactionsQuery`
**Purpose:** Adds set membership filters for reaction queries.

**Behavior:**
- Filters reactions based on user's set permissions
- Applies to both entity-reactions and list-reactions

**Configuration:** None

**Used in:** Reaction query operations

---

#### `AddSetsToThroughRecordQuery`
**Purpose:** Adds set membership filters for through-record queries.

**Behavior:**
- Filters records when querying through relationships
- Example: entities through lists, reactions through entities

**Configuration:** None

**Used in:** Through-record query operations

---

### Data Transformation Filters

#### `PlaceKindNameIntoPayload`
**Purpose:** Injects the resolved kind name into the request payload.

**Behavior:**
- Takes kind from URL path and adds it to request body
- Ensures kind consistency between URL and payload
- Used with kind alias routes

**Configuration:** None

**Used in:** Kind alias POST/PUT operations

---

#### `AddManagedFieldsInCreation`
**Purpose:** Adds system-managed fields during resource creation.

**Configuration:**
```yaml
- AddManagedFieldsInCreation

# With exclusions
- name: AddManagedFieldsInCreation
  args:
    excludeFields:
    - OWNER_USERS
```

**Behavior:**
- Adds fields like `createdAt`, `createdBy`, `ownerId`
- Can exclude specific fields per route
- Ensures consistency of system metadata

**Used in:** All POST (create) operations

---

#### `AddManagedFieldsFromOriginalToPayloadInReplace`
**Purpose:** Preserves managed fields when replacing resources (PUT).

**Configuration:**
```yaml
- AddManagedFieldsFromOriginalToPayloadInReplace

# With exclusions
- name: AddManagedFieldsFromOriginalToPayloadInReplace
  args:
    excludeFields:
    - OWNER_USERS
```

**Behavior:**
- Fetches original resource
- Copies managed fields from original to new payload
- Prevents managed fields from being overwritten
- Updates `modifiedAt`, `modifiedBy`

**Used in:** All PUT (replace) operations

---

#### `AddForbiddenFieldsFromOriginalToPayloadInReplace`
**Purpose:** Preserves forbidden fields when replacing resources (PUT).

**Configuration:**
```yaml
- name: AddForbiddenFieldsFromOriginalToPayloadInReplace
  args:
    policyName: /policies/fields/entities/policy
```

**Behavior:**
- Fetches original resource
- Identifies forbidden fields for current user
- Copies forbidden fields from original to new payload
- Prevents unauthorized field modifications

**Used in:** All PUT (replace) operations

---

#### `ApplyFieldsetConfig`
**Purpose:** Applies fieldset projections to limit returned fields.

**Behavior:**
- Reads fieldset configuration from query parameters or headers
- Projects only requested fields in response
- Reduces payload size and network traffic

**Configuration:** None

**Used in:** GET operations returning full documents

---

#### `FieldFilter`
**Purpose:** Filters response fields based on permissions and fieldsets.

**Behavior:**
- Removes forbidden fields from response
- Applies fieldset projections
- Runs after backend response received

**Configuration:** None

**Used in:** All operations returning entity data

---

#### `RemoveRequestHeader`
**Purpose:** Removes specific headers before forwarding request.

**Configuration:**
```yaml
- RemoveRequestHeader=Authorization
```

**Behavior:**
- Strips specified headers from request
- Commonly used to remove Authorization header after authentication
- Prevents header leakage to backend

**Used in:** All routes (removes Authorization header)

---

### Concurrency Control Filters

#### `AcquireLockForCreation`
**Purpose:** Acquires distributed lock for resource creation to prevent duplicates.

**Configuration:**
```yaml
- name: AcquireLockForCreation
  args:
    waitTime: ${app.locks.entities.create.waitTime}
    leaseTime: ${app.locks.entities.create.leaseTime}

# With additional parameters
- name: AcquireLockForCreation
  args:
    recordType: entityReactions
    controllerName: entityReactions
    waitTime: ${app.locks.reactions.create.waitTime}
    leaseTime: ${app.locks.reactions.create.leaseTime}
```

**Behavior:**
- Uses Redis-based distributed locking (Redisson)
- Prevents concurrent creation of duplicate resources
- Returns 409 Conflict if lock cannot be acquired
- Lock is automatically released after operation

**Used in:** POST (create) operations

---

#### `AcquireLockForUpdate`
**Purpose:** Acquires distributed lock for resource updates to prevent race conditions.

**Configuration:**
```yaml
- name: AcquireLockForUpdate
  args:
    waitTime: ${app.locks.entities.update.waitTime}
    leaseTime: ${app.locks.entities.update.leaseTime}
```

**Behavior:**
- Locks resource by ID during update
- Prevents lost updates in concurrent scenarios
- Returns 409 Conflict if lock cannot be acquired
- Supports both PATCH and PUT operations

**Used in:** PATCH and PUT operations

---

### Performance & Caching Filters

#### `DynamicLocalCache`
**Purpose:** Provides local caching with configurable TTL and size.

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntities.timeToLive}
    size: ${app.local-cache.entities.findEntities.size}
```

**Behavior:**
- Caches GET responses in local memory
- Cache key based on URL, sorted query parameters, and authenticated user ID
- Configurable TTL per route (with optional per-kind overrides)
- LRU eviction when size limit reached
- **No active cache invalidation on writes** — entries expire via TTL only
- Sets `Cache-Control`, `ETag`, and `Last-Modified` response headers
- Handles conditional requests (`If-None-Match`, `If-Modified-Since`) returning `304 Not Modified`
- Respects `Cache-Control: no-cache` (forces backend fetch, skips cache read) and `no-store` (bypasses cache entirely) request headers
- Skips caching if backend responds with `Cache-Control: private` or `no-store`

**Used in:** GET operations (reads)

---

### Rate Limiting & Protection Filters

#### `RequestRateLimiter`
**Purpose:** Enforces rate limits per user or IP address.

**Configuration:**
```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter:
      replenishRate: ${app.rate-limits.entities.createEntity.replenishRate}
      burstCapacity: ${app.rate-limits.entities.createEntity.burstCapacity}
```

**Behavior:**
- Uses Redis-based token bucket algorithm
- `replenishRate`: tokens added per second
- `burstCapacity`: maximum tokens in bucket
- Returns 429 Too Many Requests when limit exceeded

**Used in:** All routes except `explorer`

---

#### `DynamicRateLimiter`
**Purpose:** Dynamic rate limiting for kind alias routes.

**Configuration:**
```yaml
- name: DynamicRateLimiter
  args:
    replenishRate: ${app.rate-limits.entities.createEntity.replenishRate}
    burstCapacity: ${app.rate-limits.entities.createEntity.burstCapacity}
```

**Behavior:**
- Similar to `RequestRateLimiter` but with runtime resolution
- Adapts to kind-specific configurations

**Used in:** Kind alias routes

---

### Control & Configuration Filters

#### `CheckIfRouteEnabled`
**Purpose:** Enables or disables routes dynamically without redeployment.

**Configuration:**
```yaml
- name: CheckIfRouteEnabled
  args:
    controllerName: entities
```

**Behavior:**
- Checks route toggle configuration
- Returns 503 Service Unavailable if route disabled
- Useful for maintenance or gradual rollouts

**Used in:** All routes except some utility routes

---

#### `DynamicTimeout`
**Purpose:** Sets connection and response timeouts dynamically.

**Configuration:**
```yaml
- name: DynamicTimeout
  args:
    connectTimeoutMs: ${app.timeouts.entities.createEntity.connectTimeoutMs}
    responseTimeoutMs: ${app.timeouts.entities.createEntity.responseTimeoutMs}
```

**Behavior:**
- Overrides default gateway timeouts
- Per-route timeout configuration
- Prevents slow requests from blocking resources

**Used in:** Kind alias routes

---

#### `DynamicRequestSizeFilter`
**Purpose:** Dynamic request size validation for kind alias routes.

**Configuration:**
```yaml
- name: DynamicRequestSizeFilter
  args:
    maxSize: ${app.request-sizes.entities.create}
```

**Behavior:**
- Similar to `RequestSize` but with runtime resolution
- Adapts to kind-specific size limits

**Used in:** Kind alias routes with request bodies

---

### Utility Filters

#### `GenerateRequestId`
**Purpose:** Generates and injects unique request identifiers for tracing.

**Behavior:**
- Creates UUID for each request
- Adds `X-Request-Id` header
- Enables request tracing across services
- Logged in all log entries

**Configuration:** None

**Used in:** All routes

---

#### `KindResolution`
**Purpose:** Resolves kind aliases to actual kind names.

**Behavior:**
- Reads `kindAlias` from route variables and resolves it in OAS config
- Populates `KindAliasConfigAttr` (kind, alias, controller, recordType, validation flags)
- Example: `/api/v1/entities/users` → kind: `user`
- Returns 404 if alias not configured
- Returns 500 if controller context is missing

**Configuration:** None

**Used in:** Kind alias routes

---

#### `HierarchyKindAliasResolver`
**Purpose:** Resolves hierarchical alias segments in domain-driven URLs to technical children/parents paths.

**Configuration:**
```yaml
- name: HierarchyKindAliasResolver
  args:
    childrenAccessorSegment: ${app.inbound.controllerBasePaths.entitiesChildrenAccessor}
    parentsAccessorSegment: ${app.inbound.controllerBasePaths.entitiesParentsAccessor}
```

**Behavior:**
- Reads the root kind alias from `KindResolution`
- Resolves `{hierarchyAlias}` against configured children/parents in `OpenApiProperties`
- Rewrites the path to the technical children/parents accessor
- Injects `filter[where][_kind]=<targetKind>` for resolved aliases
- Updates `KindAliasConfigAttr` with hierarchy schema keys and validation flags
- Static fallback: if `{hierarchyAlias}` is literally `children` or `parents`, only path rewrite is applied
- Returns 404 if hierarchy alias is not configured

**Used in:** Dynamic hierarchy kind-alias routes (domain-driven URLs)

---

#### `ThroughKindAliasResolver`
**Purpose:** Resolves through-record kind alias routes to technical paths for reactions-through-entity, reactions-through-list, entities-through-list, and lists-through-entity kind alias variants.

**Behavior:**
- Reads the root kind alias from `KindResolution`
- Resolves the through-record target kind from alias configuration
- Rewrites the path to the technical through-record accessor
- Updates `KindAliasConfigAttr` with the resolved kind and validation flags
- Returns 404 if through-record alias is not configured

**Configuration:** None

**Used in:** Through-record kind-alias routes (`createReactionByEntityIdByKindAlias`, `findReactionsByEntityIdByKindAlias`, `updateReactionsByEntityIdByKindAlias`, `deleteReactionsByEntityIdByKindAlias`, `createReactionByListIdByKindAlias`, `findReactionsByListIdByKindAlias`, `updateReactionsByListIdByKindAlias`, `deleteReactionsByListIdByKindAlias`, `createEntityByListIdByKindAlias`, `findEntitiesByListIdByKindAlias`, `updateEntitiesByListIdByKindAlias`, `deleteEntitiesByListIdByKindAlias`, `findListsByEntityIdByKindAlias`)

---

#### `ConvertKindAliasToKindQuery`
**Purpose:** Converts kind alias in query parameters to kind filter.

**Behavior:**
- Transforms kind alias in URL to query filter
- Adds implicit `kind=<resolved-kind>` filter
- Ensures only records of specified kind are returned

**Configuration:** None

**Used in:** Kind alias GET operations

---

#### `ConvertDomainIncludeAliasToGenericRelation`
**Purpose:** Normalizes domain alias values used in `filter[include][...][relation]` into backend generic include relations and injects include-level `_kind` constraints.

**Behavior:**
- Scans top-level and nested include relation keys in query string
- Leaves generic relations (`_entities`, `_reactions`) unchanged
- Resolves configured top-level aliases from domain projection configuration
- Rewrites relation to generic relation bucket and injects `scope[where][_kind]=<kind>`
- If include scope `where` already exists, safely merges using `and` wrapping
- Stores alias projection context in exchange attributes for response-phase remapping

**Configuration:** None

**Used in:** Entity and list read routes (`findAll*`, `find*ById*`, through `find*By*Id`, and hierarchy `find*Children*`/`find*Parents*`), including kind-alias variants where enabled. Not used in relation routes, reaction routes, count routes, create routes, or update-all routes.

**Exact route IDs (cross-validated against `application-routes.yml`):**
- `findEntities`
- `findEntityById`
- `findEntityChildren`
- `findEntityParents`
- `findLists`
- `findListById`
- `findListChildren`
- `findListParents`
- `findEntitiesByListId`
- `findListsByEntityId`
- `findAllEntitiesByKindAlias`
- `findEntityByIdByKindAlias`
- `findEntityChildrenByKindAlias`
- `findEntityParentsByKindAlias`
- `findEntityHierarchyByKindAlias`
- `findAllListsByKindAlias`
- `findListByIdByKindAlias`
- `findListChildrenByKindAlias`
- `findListParentsByKindAlias`
- `findListHierarchyByKindAlias`
- `findEntitiesByListIdByKindAlias`
- `findListsByEntityIdByKindAlias`

---

#### `ProjectDomainIncludeAliasInResponse`
**Purpose:** Reprojects generic include field names in backend payloads to the caller-requested domain alias names.

**Behavior:**
- Implemented using `AbstractResponsePayloadModifierFilterFactory`
- Reads include alias projection context prepared during request phase
- Renames matching include relation keys in JSON payload (for example `_entities` -> `books`)
- Preserves payload shape and skips transformation when no projection context exists

**Configuration:** None

**Used in:** Response chains for entity and list read routes where include alias normalization is enabled (`findAll*`, `find*ById*`, through `find*By*Id`, and hierarchy `find*Children*`/`find*Parents*`, including supported kind-alias variants). Not used in relation routes, reaction routes, count routes, create routes, or update-all routes.

**Exact route IDs (cross-validated against `application-routes.yml`):**
- `findEntities`
- `findEntityById`
- `findEntityChildren`
- `findEntityParents`
- `findLists`
- `findListById`
- `findListChildren`
- `findListParents`
- `findEntitiesByListId`
- `findListsByEntityId`
- `findAllEntitiesByKindAlias`
- `findEntityByIdByKindAlias`
- `findEntityChildrenByKindAlias`
- `findEntityParentsByKindAlias`
- `findEntityHierarchyByKindAlias`
- `findAllListsByKindAlias`
- `findListByIdByKindAlias`
- `findListChildrenByKindAlias`
- `findListParentsByKindAlias`
- `findListHierarchyByKindAlias`
- `findEntitiesByListIdByKindAlias`
- `findListsByEntityIdByKindAlias`

---

#### `SetStatus`
**Purpose:** Sets HTTP status code for response.

**Configuration:**
```yaml
- name: SetStatus
  args:
    status: 404
```

**Behavior:**
- Directly sets HTTP status code
- Used for placeholder routes
- Example: disabled explorer route returns 404

**Used in:** Utility and placeholder routes

---

## Filter Usage by Route

### Entity Controller Routes

#### `createEntity`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateAllEntities`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findEntities`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `countEntities`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
AddSetsToEntityListOrReactionViaRecordQuery → RemoveRequestHeader → DynamicLocalCache
```

#### `findEntityById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
ConvertDomainIncludeAliasToGenericRelation → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → ApplyFieldsetConfig → 
ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `updateEntityById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceEntityById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

#### `deleteEntityById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → AuthorizeRequest → RemoveRequestHeader
```

#### `findEntityChildren`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `createEntityChild`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `findEntityParents`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

---

### List Controller Routes

#### `createList`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateAllLists`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findLists`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `countLists`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → AuthorizeRequest → PreventStringifiedJsonFilter → 
ConvertSimplerQueriesToBackendFormat → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → AddSetsToEntityListOrReactionViaRecordQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findListById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
ConvertDomainIncludeAliasToGenericRelation → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → ApplyFieldsetConfig → 
ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `updateListById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceListById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

#### `deleteListById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → RemoveRequestHeader
```

#### `findListChildren`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `createListChild`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `findListParents`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

---

### Relation Controller Routes

#### `createRelation`
```
RewritePath → RequestSize → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateAllRelations`
```
RewritePath → RequestSize → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToRelationQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findRelations`
```
RewritePath → CheckIfRouteEnabled → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToRelationQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `countRelations`
```
RewritePath → CheckIfRouteEnabled → AuthenticateRequest → GenerateRequestId → 
FetchForbiddenFields → RequestRateLimiter → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → AddSetsToRelationQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findRelationById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ApplyFieldsetConfig → FieldFilter
```

#### `updateRelationById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceRelationById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

#### `deleteRelationById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → AuthorizeRequest → RemoveRequestHeader
```

---

### Entity Reaction Controller Routes

#### `createEntityReaction`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateAllEntityReactions`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findEntityReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `countEntityReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → AddSetsToReactionsQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findEntityReactionById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ApplyFieldsetConfig → FieldFilter
```

#### `updateEntityReactionById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceEntityReactionById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

#### `deleteEntityReactionById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → RemoveRequestHeader
```

#### `findChildrenEntityReactionsByReactionId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `createChildEntityReaction`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `findParentsByEntityReactionId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

---

### Reactions Through Entity Controller Routes

#### `createReactionByEntityId`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateReactionsByEntityId`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findReactionsByEntityId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `deleteReactionsByEntityId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

---

### List Reaction Controller Routes

#### `createListReaction`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateAllListReactions`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findListReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `countListReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → AddSetsToReactionsQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findListReactionById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ApplyFieldsetConfig → FieldFilter
```

#### `updateListReactionById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceListReactionById`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

#### `deleteListReactionById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → AuthorizeRequest → RemoveRequestHeader
```

#### `findChildrenListReactionsByReactionId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `createChildListReaction`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `findParentsByListReactionId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

---

### Reactions Through List Controller Routes

#### `createReactionByListId`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateReactionsByListId`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findReactionsByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `deleteReactionsByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

---

### Entities Through List Controller Routes

#### `createEntityByListId`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

#### `updateEntitiesByListId`
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToThroughRecordQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findEntitiesByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToThroughRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `deleteEntitiesByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
ConvertSimplerQueriesToBackendFormat → AddSetsToThroughRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader
```

---

### Lists Through Entity Controller Routes

#### `findListsByEntityId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToThroughRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

---

### Utility Routes

#### `ping`
```
CheckIfRouteEnabled → RewritePath → RequestRateLimiter → RemoveRequestHeader
```

#### `explorer`
```
CheckIfRouteEnabled → SetStatus
```

---

### Kind Alias Routes

#### `createEntityByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findAllEntitiesByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → RewritePath → 
AuthorizeRequest → PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
ConvertKindAliasToKindQuery → ConvertDomainIncludeAliasToGenericRelation → ApplyFieldsetConfig → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → 
ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `countEntitiesByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
ConvertKindAliasToKindQuery → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
AddSetsToEntityListOrReactionViaRecordQuery → RemoveRequestHeader → DynamicLocalCache
```

#### `updateAllEntitiesByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → PreventStringifiedJsonFilter → 
AuthorizeRequest → ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findEntityByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → ConvertDomainIncludeAliasToGenericRelation → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ApplyFieldsetConfig → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `updateEntityByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `deleteEntityByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → AuthorizeRequest → 
RemoveRequestHeader
```

#### `findEntityChildrenByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
FieldFilter → DynamicLocalCache → ProjectDomainIncludeAliasInResponse
```

#### `createEntityChildByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findEntityParentsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `findEntityHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → CheckIfRouteEnabled → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertDomainIncludeAliasToGenericRelation → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → FieldFilter → DynamicLocalCache → 
ProjectDomainIncludeAliasInResponse
```

#### `createEntityHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `replaceEntityByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader → FieldFilter
```

---

### List Kind Alias Routes

#### `createListByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findAllListsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → RewritePath → 
AuthorizeRequest → PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
ConvertKindAliasToKindQuery → ConvertDomainIncludeAliasToGenericRelation → ApplyFieldsetConfig → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → 
ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `countListsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
ConvertKindAliasToKindQuery → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
AddSetsToEntityListOrReactionViaRecordQuery → RemoveRequestHeader → DynamicLocalCache
```

#### `updateAllListsByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → PreventStringifiedJsonFilter → 
AuthorizeRequest → ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findListByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → ConvertDomainIncludeAliasToGenericRelation → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ApplyFieldsetConfig → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `updateListByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `deleteListByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → AuthorizeRequest → 
RemoveRequestHeader
```

#### `findListChildrenByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
FieldFilter → DynamicLocalCache → ProjectDomainIncludeAliasInResponse
```

#### `createListChildByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findListParentsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `findListHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → CheckIfRouteEnabled → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertDomainIncludeAliasToGenericRelation → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → FieldFilter → DynamicLocalCache → 
ProjectDomainIncludeAliasInResponse
```

#### `createListHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `replaceListByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader → FieldFilter
```

---

### Relation Kind Alias Routes

#### `createRelationByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findAllRelationsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → RewritePath → 
AuthorizeRequest → PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
ConvertKindAliasToKindQuery → ApplyFieldsetConfig → AddSetsToRelationQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `countRelationsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
ConvertKindAliasToKindQuery → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → AddSetsToRelationQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `updateAllRelationsByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → PreventStringifiedJsonFilter → 
AuthorizeRequest → ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToRelationQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findRelationByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → ApplyFieldsetConfig → FieldFilter
```

#### `updateRelationByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceRelationByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader → FieldFilter
```

#### `deleteRelationByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → AuthorizeRequest → 
RemoveRequestHeader
```

---

### Entity Reaction Kind Alias Routes

#### `createEntityReactionByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findAllEntityReactionsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → RewritePath → 
AuthorizeRequest → PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
ConvertKindAliasToKindQuery → ApplyFieldsetConfig → AddSetsToReactionsQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `countEntityReactionsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
ConvertKindAliasToKindQuery → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → AddSetsToReactionsQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `updateAllEntityReactionsByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → PreventStringifiedJsonFilter → 
AuthorizeRequest → ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findEntityReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → ApplyFieldsetConfig → FieldFilter
```

#### `updateEntityReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceEntityReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader → FieldFilter
```

#### `deleteEntityReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → AuthorizeRequest → 
RemoveRequestHeader
```

#### `findChildrenEntityReactionsByReactionIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `createChildEntityReactionByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findParentsByEntityReactionIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `findEntityReactionHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → CheckIfRouteEnabled → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → AddSetsToReactionsQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
FieldFilter → DynamicLocalCache
```

#### `createEntityReactionHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

---

### List Reaction Kind Alias Routes

#### `createListReactionByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findAllListReactionsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → RewritePath → 
AuthorizeRequest → PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
ConvertKindAliasToKindQuery → ApplyFieldsetConfig → AddSetsToReactionsQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `countListReactionsByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
ConvertKindAliasToKindQuery → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → AddSetsToReactionsQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `updateAllListReactionsByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → PreventStringifiedJsonFilter → 
AuthorizeRequest → ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findListReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → ApplyFieldsetConfig → FieldFilter
```

#### `updateListReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → ValidateRequestBodyByKindSchema → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

#### `replaceListReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader → FieldFilter
```

#### `deleteListReactionByIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → AuthorizeRequest → 
RemoveRequestHeader
```

#### `findChildrenListReactionsByReactionIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `createChildListReactionByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `findParentsByListReactionIdByKindAlias`
```
DynamicTimeout → KindResolution → CheckIfRouteEnabled → RewritePath → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → ConvertKindAliasToKindQuery → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `findListReactionHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → CheckIfRouteEnabled → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → AddSetsToReactionsQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
FieldFilter → DynamicLocalCache
```

#### `createListReactionHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

---

### Through-Record Kind Alias Routes

#### `createReactionByEntityIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
ValidateRequestBodyByKindSchema → AuthorizeRequest → AcquireLockForCreation → 
AddManagedFieldsInCreation → ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `updateReactionsByEntityIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findReactionsByEntityIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → PreventStringifiedJsonFilter → 
ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `deleteReactionsByEntityIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → PreventStringifiedJsonFilter → 
ConvertSimplerQueriesToBackendFormat → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `createReactionByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
ValidateRequestBodyByKindSchema → AuthorizeRequest → AcquireLockForCreation → 
AddManagedFieldsInCreation → ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `updateReactionsByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `findReactionsByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → PreventStringifiedJsonFilter → 
ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
InjectTypeHintsToQuery → RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `deleteReactionsByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → PreventStringifiedJsonFilter → 
ConvertSimplerQueriesToBackendFormat → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader
```

#### `createEntityByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → 
GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
ValidateRequestBodyByKindSchema → AuthorizeRequest → AcquireLockForCreation → 
AddManagedFieldsInCreation → ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

#### `updateEntitiesByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ConvertSimplerQueriesToBackendFormat → 
AddSetsToThroughRecordQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findEntitiesByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → PreventStringifiedJsonFilter → 
ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToThroughRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

#### `deleteEntitiesByListIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → ConvertSimplerQueriesToBackendFormat → 
AddSetsToThroughRecordQuery → PreventQueryByForbiddenFields → InjectTypeHintsToQuery → 
RemoveRequestHeader
```

#### `findListsByEntityIdByKindAlias`
```
DynamicTimeout → KindResolution → ThroughKindAliasResolver → CheckIfRouteEnabled → 
RewritePath → AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → 
FetchForbiddenFields → AuthorizeRequest → PreventStringifiedJsonFilter → 
ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
ConvertDomainIncludeAliasToGenericRelation → AddSetsToThroughRecordQuery → 
PreventQueryByForbiddenFields → InjectTypeHintsToQuery → RemoveRequestHeader → 
DynamicLocalCache → ProjectDomainIncludeAliasInResponse → FieldFilter
```

**Note:** Include alias projection is enabled on entity/list kind-alias read routes, including `findAll*`, `find*ById*`, through, and hierarchy reads. It is not enabled on count, update, delete, relation kind-alias, or reaction kind-alias routes.

---

## Filter Chain Patterns

### Common Patterns

**Create Pattern:**
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForCreation → AddManagedFieldsInCreation → ApplyFieldsetConfig → 
RemoveRequestHeader → FieldFilter
```

**Read Pattern (Collection):**
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSets... → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

**Read Pattern (Single):**
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → 
ApplyFieldsetConfig → FieldFilter
```

**Update Pattern (PATCH):**
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → RemoveRequestHeader
```

**Replace Pattern (PUT):**
```
RequestSize → CheckIfRouteEnabled → RewritePath → AuthenticateRequest → 
GenerateRequestId → RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

**Delete Pattern:**
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → AuthorizeRequest → RemoveRequestHeader
```

---

## Configuration Examples

### Request Size Limits
```yaml
app:
  request-sizes:
    entities:
      create: 5MB
      update: 5MB
    lists:
      create: 2MB
      update: 2MB
    relations:
      create: 1MB
      update: 1MB
    reactions:
      create: 1MB
      update: 1MB
```

### Rate Limits
```yaml
app:
  rate-limits:
    entities:
      createEntity:
        replenishRate: 10
        burstCapacity: 20
      findEntities:
        replenishRate: 100
        burstCapacity: 200
```

### Lock Configuration
```yaml
app:
  locks:
    entities:
      create:
        waitTime: 5000
        leaseTime: 10000
      update:
        waitTime: 3000
        leaseTime: 5000
```

### Cache Configuration
```yaml
app:
  local-cache:
    entities:
      findEntities:
        timeToLive: 300
        size: 1000
      findEntityById:
        timeToLive: 600
        size: 500
```

### Timeout Configuration
```yaml
app:
  timeouts:
    entities:
      createEntity:
        connectTimeoutMs: 5000
        responseTimeoutMs: 30000
      findEntities:
        connectTimeoutMs: 3000
        responseTimeoutMs: 10000
```

---

## Filter Dependencies

Some filters depend on data set by previous filters in the chain:

- `AuthorizeRequest` requires `AuthenticateRequest` to set user context
- `PreventQueryByForbiddenFields` requires `FetchForbiddenFields`
- `FieldFilter` requires `FetchForbiddenFields` (for permission-based filtering)
- `AddForbiddenFieldsFromOriginalToPayloadInReplace` requires `FetchForbiddenFields`
- `HierarchyKindAliasResolver` requires `KindResolution` (root alias context)
- `ThroughKindAliasResolver` requires `KindResolution` (root alias context)
- All `AddSets...` filters require `AuthenticateRequest` for user context

---

## Best Practices

1. **Order Matters:** Filters execute in the order defined. Place validation and authentication early in the chain.

2. **Performance:** Place cheap filters (like `CheckIfRouteEnabled`) before expensive ones (like `AuthenticateRequest`).

3. **Caching:** Only use `DynamicLocalCache` on read operations. The filter skips non-GET requests entirely — there is **no active cache invalidation on writes**. Cached entries expire solely via TTL. The filter also sets `Cache-Control`, `ETag`, and `Last-Modified` response headers, and handles conditional requests (`If-None-Match`, `If-Modified-Since`) returning `304 Not Modified` when appropriate. Keep TTLs short enough to tolerate stale reads after writes.

4. **Locking:** Use locking filters only when necessary. They add latency but prevent data consistency issues.

5. **Rate Limiting:** Configure rate limits based on operation cost. Read operations can have higher limits than writes.

6. **Field Filtering:** Always apply `FetchForbiddenFields` → `PreventQueryByForbiddenFields` → `FieldFilter` chain for proper field-level security.

7. **Validation:** Validate early with `RequestSize`, `PreventStringifiedJsonFilter` before expensive operations.

---

## Related Documentation

- [ROUTES.md](ROUTES.md) - Complete route definitions
- [Configuration Files](src/main/resources/) - Filter configuration properties
- [Gateway Filter Implementation](src/main/java/com/tarcinapp/entitypersistencegateway/filters/) - Source code

