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
| `AddSetsToEntityListOrReactionViaRecordQuery` | Transformation | Adds set filters for entities/lists |
| `AddSetsToRelationQuery` | Transformation | Adds set filters for relations |
| `AddSetsToReactionsQuery` | Transformation | Adds set filters for reactions |
| `AddSetsToThroughRecordQuery` | Transformation | Adds set filters for through records |
| `PreventQueryByForbiddenFields` | Validation | Blocks queries on forbidden fields |
| `DynamicLocalCache` | Performance | Local caching with TTL |
| `PlaceKindNameIntoPayload` | Transformation | Injects kind into request body |
| `ValidateRequestBodyByKindSchema` | Validation | Schema validation by kind |
| `KindResolution` | Transformation | Resolves kind aliases to names |
| `HierarchyKindAliasResolver` | Routing | Resolves hierarchical kind aliases to technical paths |
| `ConvertKindAliasToKindQuery` | Transformation | Converts kind alias in queries |
| `DynamicTimeout` | Configuration | Sets request timeouts dynamically |
| `DynamicRequestSizeFilter` | Validation | Dynamic request size limits |
| `DynamicRateLimiter` | Protection | Dynamic rate limiting |

---

## Detailed Filter Descriptions

### Security & Authentication Filters

#### `AuthenticateRequest`
**Purpose:** Validates the authentication token in the request and extracts user identity.

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
- Cache key based on URL and query parameters
- Configurable TTL per route
- LRU eviction when size limit reached
- Cache invalidation on writes

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
- Maps URL kind aliases to database kind names
- Example: `/api/v1/entities/users` → kind: `user`
- Validates kind existence
- Returns 404 if kind not found

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
- Rewrites the path to technical children/parents accessors
- Injects `filter[where][_kind]=<targetKind>` for the resolved hierarchy kind
- Updates kind alias attributes for downstream validation and authorization

**Used in:** Dynamic hierarchy kind-alias routes (domain-driven URLs)

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
RemoveRequestHeader
```

#### `findEntities`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `countEntities`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → AddSetsToEntityListOrReactionViaRecordQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findEntityById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → 
ApplyFieldsetConfig → FieldFilter
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
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
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
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
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
RemoveRequestHeader
```

#### `findLists`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `countLists`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → AuthorizeRequest → PreventStringifiedJsonFilter → 
PreventQueryByForbiddenFields → AddSetsToEntityListOrReactionViaRecordQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findListById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → 
ApplyFieldsetConfig → FieldFilter
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
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
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
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
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
AddSetsToRelationQuery → PreventQueryByForbiddenFields → RemoveRequestHeader
```

#### `findRelations`
```
RewritePath → CheckIfRouteEnabled → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToRelationQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `countRelations`
```
RewritePath → CheckIfRouteEnabled → AuthenticateRequest → GenerateRequestId → 
FetchForbiddenFields → RequestRateLimiter → AuthorizeRequest → 
PreventStringifiedJsonFilter → PreventQueryByForbiddenFields → AddSetsToRelationQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findRelationById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → 
ApplyFieldsetConfig → FieldFilter
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
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader
```

#### `findEntityReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `countEntityReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → PreventQueryByForbiddenFields → AddSetsToReactionsQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findEntityReactionById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → 
ApplyFieldsetConfig → FieldFilter
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
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
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
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
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
RemoveRequestHeader
```

#### `findReactionsByEntityId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `deleteReactionsByEntityId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader
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
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader
```

#### `findListReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `countListReactions`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → PreventQueryByForbiddenFields → AddSetsToReactionsQuery → 
RemoveRequestHeader → DynamicLocalCache
```

#### `findListReactionById`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → 
ApplyFieldsetConfig → FieldFilter
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
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
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
AddSetsToReactionsQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
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
RemoveRequestHeader
```

#### `findReactionsByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToEntityListOrReactionViaRecordQuery → PreventQueryByForbiddenFields → 
RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `deleteReactionsByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → RemoveRequestHeader
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
AddSetsToThroughRecordQuery → PreventQueryByForbiddenFields → RemoveRequestHeader
```

#### `findEntitiesByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToThroughRecordQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
```

#### `deleteEntitiesByListId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
ConvertSimplerQueriesToBackendFormat → AddSetsToThroughRecordQuery → 
PreventQueryByForbiddenFields → RemoveRequestHeader
```

---

### Lists Through Entity Controller Routes

#### `findListsByEntityId`
```
CheckIfRouteEnabled → RewritePath → AuthenticateRequest → GenerateRequestId → 
RequestRateLimiter → FetchForbiddenFields → AuthorizeRequest → 
PreventStringifiedJsonFilter → ApplyFieldsetConfig → ConvertSimplerQueriesToBackendFormat → 
AddSetsToThroughRecordQuery → PreventQueryByForbiddenFields → RemoveRequestHeader → 
DynamicLocalCache → FieldFilter
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
ConvertKindAliasToKindQuery → ApplyFieldsetConfig → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → RemoveRequestHeader → DynamicLocalCache → FieldFilter
```

#### `replaceEntityByIdByKindAlias`
```
DynamicTimeout → KindResolution → DynamicRequestSizeFilter → CheckIfRouteEnabled → 
RewritePath → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForUpdate → AddForbiddenFieldsFromOriginalToPayloadInReplace → 
AddManagedFieldsFromOriginalToPayloadInReplace → RemoveRequestHeader
```

#### `findEntityHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → CheckIfRouteEnabled → 
AuthenticateRequest → GenerateRequestId → DynamicRateLimiter → FetchForbiddenFields → 
AuthorizeRequest → PreventStringifiedJsonFilter → ApplyFieldsetConfig → 
ConvertSimplerQueriesToBackendFormat → AddSetsToEntityListOrReactionViaRecordQuery → 
PreventQueryByForbiddenFields → RemoveRequestHeader → FieldFilter → DynamicLocalCache
```

#### `createEntityHierarchyByKindAlias`
```
DynamicTimeout → KindResolution → HierarchyKindAliasResolver → DynamicRequestSizeFilter → 
CheckIfRouteEnabled → PlaceKindNameIntoPayload → AuthenticateRequest → GenerateRequestId → 
DynamicRateLimiter → FetchForbiddenFields → ValidateRequestBodyByKindSchema → 
AuthorizeRequest → AcquireLockForCreation → AddManagedFieldsInCreation → 
ApplyFieldsetConfig → RemoveRequestHeader → FieldFilter
```

**Note:** Kind alias routes follow similar patterns for other operations (count, update, find by ID, delete, children, parents, etc.)

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
- All `AddSets...` filters require `AuthenticateRequest` for user context

---

## Best Practices

1. **Order Matters:** Filters execute in the order defined. Place validation and authentication early in the chain.

2. **Performance:** Place cheap filters (like `CheckIfRouteEnabled`) before expensive ones (like `AuthenticateRequest`).

3. **Caching:** Only use `DynamicLocalCache` on read operations. Cache invalidation happens automatically on writes.

4. **Locking:** Use locking filters only when necessary. They add latency but prevent data consistency issues.

5. **Rate Limiting:** Configure rate limits based on operation cost. Read operations can have higher limits than writes.

6. **Field Filtering:** Always apply `FetchForbiddenFields` → `PreventQueryByForbiddenFields` → `FieldFilter` chain for proper field-level security.

7. **Validation:** Validate early with `RequestSize`, `PreventStringifiedJsonFilter` before expensive operations.

---

## Related Documentation

- [ROUTES.md](ROUTES.md) - Complete route definitions
- [Configuration Files](src/main/resources/) - Filter configuration properties
- [Gateway Filter Implementation](src/main/java/com/tarcinapp/entitypersistencegateway/filters/) - Source code

