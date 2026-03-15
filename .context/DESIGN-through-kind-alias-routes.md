# DESIGN: Domain-Projected Through Routes (Kind Alias Through Routes)

## Current State

### Generic Through Routes (Functional)

These exist in `application-routes.yml` and work at the generic controller level:

| Controller | Routes | URL Pattern | Operations |
|---|---|---|---|
| `reactionsThroughEntity` | 4 | `entities/{recordId}/reactions` | POST, PATCH, GET, DELETE |
| `reactionsThroughList` | 4 | `lists/{recordId}/reactions` | POST, PATCH, GET, DELETE |
| `entitiesThroughList` | 4 | `lists/{recordId}/entities` | POST, PATCH, GET, DELETE |
| `listsThroughEntity` | 1 | `entities/{recordId}/lists` | GET only |

**Total: 13 generic through routes**

### Domain-Projected Routes (Kind Alias — Functional)

These already exist for base controller operations:

| Section | Example URL | Route ID Pattern |
|---|---|---|
| entity kind alias from root | `entities/{kindAlias}` | `createEntityByKindAlias`, `findAllEntitiesByKindAlias` |
| entity kind alias hierarchy | `entities/{kindAlias}/{recordId}/{hierarchyAlias}` | `findEntityHierarchyByKindAlias` |
| list kind alias from root | `lists/{kindAlias}` | `createListByKindAlias` |
| list kind alias hierarchy | `lists/{kindAlias}/{recordId}/{hierarchyAlias}` | `findListHierarchyByKindAlias` |
| relations kind alias | `relations/{kindAlias}` | `createRelationByKindAlias` |
| entity reactions kind alias | `entity-reactions/{kindAlias}` | `createEntityReactionByKindAlias` |
| entity reactions hierarchy | `entity-reactions/{kindAlias}/{reactionId}/{hierarchyAlias}` | `findEntityReactionHierarchyByKindAlias` |
| list reactions kind alias | `list-reactions/{kindAlias}` | `createListReactionByKindAlias` |
| list reactions hierarchy | `list-reactions/{kindAlias}/{reactionId}/{hierarchyAlias}` | `findListReactionHierarchyByKindAlias` |

### What's Missing

**No domain-projected through routes exist.** There are no kind alias versions of through routes in `application-routes.yml`.

---

## Path Structure

### 5-Segment Path Pattern

```
{baseUri}{controller}/{kindAlias}/{recordId}/{throughSegment}/{throughAlias}
```

Where:
- `{controller}` = base controller path (e.g., `entities`, `lists`)
- `{kindAlias}` = parent record's kind alias (e.g., `books`)
- `{recordId}` = parent record UUID
- `{throughSegment}` = fixed through controller base path from config (e.g., `reactions`, `entities`, `lists`)
- `{throughAlias}` = through record's kind alias (e.g., `likes`, `songs`)

**No collision with hierarchy routes** — hierarchy paths are 4 segments (`entities/{kindAlias}/{recordId}/{hierarchyAlias}`), through-kind-alias paths are 5 segments. Spring Cloud Gateway matches by segment count first.

### Concrete URL Examples

| Through Type | URL | Explanation |
|---|---|---|
| reactions through entity | `entities/books/{id}/reactions/likes` | Likes (reaction) for a book (entity) |
| reactions through entity | `entities/vehicles/{id}/reactions/ratings` | Ratings for a vehicle |
| reactions through list | `lists/playlists/{id}/reactions/favorites` | Favorites for a playlist |
| entities through list | `lists/playlists/{id}/entities/songs` | Songs (entities) in a playlist |
| lists through entity | `entities/books/{id}/lists/reading-lists` | Reading lists containing a book |

### Predicate Pattern

```yaml
predicates:
- Path=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/{kindAlias}/{recordId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/${app.inbound.controllerBasePaths.reactionsThroughEntity}/{throughAlias}
- Method=GET
```

The `{throughSegment}` resolves from `app.inbound.controllerBasePaths.*` at config-load time (not a path variable). The `{throughAlias}` is a dynamic path variable captured at runtime.

### Route Placement

These routes should be placed **after** hierarchy routes. No ordering conflict exists since the segment count (5) is different from hierarchy (4).

Suggested placement — at the end of `application-routes.yml`, after all existing kind alias blocks:

```
# (existing) list reactions kind alias - dynamic hierarchy routes

# NEW: reactions through entity kind alias routes
# NEW: reactions through list kind alias routes
# NEW: entities through list kind alias routes
# NEW: lists through entity kind alias routes
```

---

## New Route Blocks

### Route Inventory

**Reactions through Entity Kind Alias** (4 routes):

| Route ID | Method | URL Pattern |
|---|---|---|
| `createReactionByEntityIdByKindAlias` | POST | `entities/{kindAlias}/{recordId}/reactions/{throughAlias}` |
| `findReactionsByEntityIdByKindAlias` | GET | `entities/{kindAlias}/{recordId}/reactions/{throughAlias}` |
| `updateReactionsByEntityIdByKindAlias` | PATCH | `entities/{kindAlias}/{recordId}/reactions/{throughAlias}` |
| `deleteReactionsByEntityIdByKindAlias` | DELETE | `entities/{kindAlias}/{recordId}/reactions/{throughAlias}` |

**Reactions through List Kind Alias** (4 routes):

| Route ID | Method | URL Pattern |
|---|---|---|
| `createReactionByListIdByKindAlias` | POST | `lists/{kindAlias}/{recordId}/reactions/{throughAlias}` |
| `findReactionsByListIdByKindAlias` | GET | `lists/{kindAlias}/{recordId}/reactions/{throughAlias}` |
| `updateReactionsByListIdByKindAlias` | PATCH | `lists/{kindAlias}/{recordId}/reactions/{throughAlias}` |
| `deleteReactionsByListIdByKindAlias` | DELETE | `lists/{kindAlias}/{recordId}/reactions/{throughAlias}` |

**Entities through List Kind Alias** (4 routes):

| Route ID | Method | URL Pattern |
|---|---|---|
| `createEntityByListIdByKindAlias` | POST | `lists/{kindAlias}/{recordId}/entities/{throughAlias}` |
| `findEntitiesByListIdByKindAlias` | GET | `lists/{kindAlias}/{recordId}/entities/{throughAlias}` |
| `updateEntitiesByListIdByKindAlias` | PATCH | `lists/{kindAlias}/{recordId}/entities/{throughAlias}` |
| `deleteEntitiesByListIdByKindAlias` | DELETE | `lists/{kindAlias}/{recordId}/entities/{throughAlias}` |

**Lists through Entity Kind Alias** (1 route):

| Route ID | Method | URL Pattern |
|---|---|---|
| `findListsByEntityIdByKindAlias` | GET | `entities/{kindAlias}/{recordId}/lists/{throughAlias}` |

**Total: 13 new routes**

---

## Cross-Controller Alias Config

Each entity/list alias declares which through-aliases it has:

```properties
# Entity alias "books" has reaction aliases "likes" and "ratings"
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].through.reactions[0].alias=likes
app.oas.controllers.entities.aliases[0].through.reactions[0].kind=like

# List alias "playlists" has entity alias "songs" and reaction alias "favorites"
app.oas.controllers.lists.aliases[0].alias=playlists
app.oas.controllers.lists.aliases[0].through.entities[0].alias=songs
app.oas.controllers.lists.aliases[0].through.entities[0].kind=song
app.oas.controllers.lists.aliases[0].through.reactions[0].alias=favorites
app.oas.controllers.lists.aliases[0].through.reactions[0].kind=favorite
```

### Config Model Change (OpenApiProperties.java)

`AliasConfig` needs a new `through` field:

```java
@Data
public static class AliasConfig {
    private String alias;
    private String singular;
    private String kind;
    // ... existing fields ...
    private List<AliasConfig> children = new ArrayList<>();
    private List<AliasConfig> parents = new ArrayList<>();
    private ThroughConfig through;              // ← NEW
}

@Data
public static class ThroughConfig {
    private List<AliasConfig> reactions = new ArrayList<>();
    private List<AliasConfig> entities = new ArrayList<>();
    private List<AliasConfig> lists = new ArrayList<>();
}
```

---

## Design Decisions

### DECISION 1: Path Structure ✅ DECIDED

**5-segment path**: `{controller}/{kindAlias}/{recordId}/{throughSegment}/{throughAlias}`

- `{throughSegment}` = literal from `app.inbound.controllerBasePaths.*` (e.g., `reactions`)
- `{throughAlias}` = dynamic path variable (e.g., `likes`)
- No collision with 4-segment hierarchy routes

---

### DECISION 2: Cross-Controller Config ✅ DECIDED

Accepted config shape with `through.reactions[]`, `through.entities[]`, `through.lists[]` on `AliasConfig`. See section above.

---

### DECISION 3: Route Ordering ✅ RESOLVED

No conflict — 5 segments vs 4 segments. Routes placed at end of file after all existing kind alias blocks.

---

### DECISION 4: KindResolution — Dual Alias Resolution ✅ CONFIRMED

**Reference:** See [REFERENCE-hierarchy-dual-alias-resolution.md](REFERENCE-hierarchy-dual-alias-resolution.md) for how the existing hierarchy routes solve the same two-alias-in-one-URL problem.

With the new path structure, there are **two aliases** in the URL:
1. `{kindAlias}` — parent record's alias (e.g., `books` → kind `book`)
2. `{throughAlias}` — through record's alias (e.g., `likes` → kind `like`)

This is structurally identical to hierarchy routes (`{kindAlias}` + `{hierarchyAlias}`), with one key difference: hierarchy aliases belong to the **same controller** as the parent, while through aliases cross into a **different controller's domain**.

**Hierarchy approach recap:**
1. `KindResolution` resolves `{kindAlias}` → populates `KindAliasConfigAttr` with root kind
2. `HierarchyKindAliasResolver` resolves `{hierarchyAlias}` → **overwrites** `KindAliasConfigAttr.kindName` with the hierarchy kind
3. After both filters run, `kindName` = hierarchy kind (e.g., `chapter`), not root kind (`book`)
4. All downstream filters (`PlaceKindNameIntoPayload`, `ConvertKindAliasToKindQuery`, `ValidateRequestBodyByKindSchema`, `DynamicTimeout`) automatically use the hierarchy kind
5. Alias lookup: walks `rootAliasConfig.getChildren()`/`.getParents()` — NOT a global registry lookup

**Proposed approach for through routes (following the hierarchy pattern):**

1. `KindResolution` resolves `{kindAlias}` → populates `KindAliasConfigAttr` with parent kind (unchanged)
2. New `ThroughKindAliasResolver` filter resolves `{throughAlias}` → **overwrites** `KindAliasConfigAttr.kindName` with through kind
3. After both filters run, `kindName` = through kind (e.g., `like`)
4. Downstream filters automatically use the through kind
5. Alias lookup: walks `rootAliasConfig.getThrough().getReactions()`/`.getEntities()`/`.getLists()`

**The `ThroughKindAliasResolver` would:**
1. Read `KindAliasConfigAttr` from exchange (set by `KindResolution`)
2. Extract `throughAlias` from URI template variables
3. Get root alias config from `openApiProperties`
4. Determine which through type by checking route metadata — the `recordType` tells us: `entityReactions` → search `through.reactions`, `entities` → search `through.entities`, `lists` → search `through.lists`
5. Search the matching through list for the alias
6. If not found → return 404
7. On match, overwrite `KindAliasConfigAttr`:
   - `kindAlias` → through alias (e.g., `likes`)
   - `kindName` → through kind (e.g., `like`)
   - Mark as through request (new fields)
   - Inject `filter[where][_kind]=like` into query (for GET routes)

**Path rewrite stays at route level** — unlike `HierarchyKindAliasResolver` which must rewrite internally (because the backend segment depends on whether the alias is a child or parent), through routes have a static rewrite target. A route-level `RewritePath` filter handles it.

**New fields on `KindAliasConfigAttr`:**

```java
// Through alias resolution (NEW — mirrors isHierarchyRequest pattern)
boolean isThroughRequest;           // true if resolved via ThroughKindAliasResolver
String throughSchemaKey;            // "through:entities:book:likes"
String throughRouteSchemaKey;       // "through-route:entities:book:likes:findReactionsByEntityId"
```

**Question for you:** Confirm this approach? It follows the hierarchy pattern:
- Two-filter pipeline (KindResolution → ThroughKindAliasResolver)
- Overwrite `kindName` with the "deeper" kind
- Walk parent's config lists (not global registry)
- Kind query injection by the resolver filter, path rewrite by route-level `RewritePath`

**Difference from hierarchy:** `HierarchyKindAliasResolver` handles path rewrite internally because the backend segment is dynamic (`children` vs `parents` — depends on resolution). `ThroughKindAliasResolver` does NOT need to rewrite — the backend path is static and handled by route-level `RewritePath`.

---

### DECISION 5: Authorization Policy Names ✅ CONFIRMED

Reuse existing through policies:

```yaml
policyName: /policies/auth/routes/reactionsThroughEntity/createReactionByEntityId/policy
```

Consistent with how kind alias routes reuse base controller policies.

---

### DECISION 6: Timeout Configuration ✅ CONFIRMED

**Default timeout falls back to generic through timeout:**
```yaml
- name: DynamicTimeout
  args:
    connectTimeoutMs: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.connectTimeoutMs}
    responseTimeoutMs: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.responseTimeoutMs}
```

**Per-kind override key pattern:** `app.timeouts.<recordType>.kinds.<throughKind>.<routeId>`

Example:
```
app.timeouts.entityReactions.kinds.like.createReactionByEntityIdByKindAlias.connectTimeoutMs
```

Reads naturally: "timeout for creating a `like` reaction through an entity kind alias route."

---

### DECISION 7: Validation (`ValidateRequestBodyByKindSchema`) ✅ CONFIRMED

INCLUDE `ValidateRequestBodyByKindSchema` in through-kind-alias POST routes.

With `kindName` overwritten to the through kind (`like`), the filter validates the request body against the `like` schema. This is correct — the POST body is a reaction of kind `like`.

---

### DECISION 8: `PlaceKindNameIntoPayload` ✅ CONFIRMED

INCLUDE `PlaceKindNameIntoPayload` in through-kind-alias POST/PUT routes.

With `kindName` overwritten to the through kind (`like`), the filter places `_kind=like` into the reaction payload. This is correct — creating a reaction of kind `like`.

---

### DECISION 9: `ConvertKindAliasToKindQuery` ✅ CONFIRMED

INCLUDE `ConvertKindAliasToKindQuery` in through-kind-alias GET routes.

`GET entities/books/{id}/reactions/likes` returns only reactions with `_kind=like`, not all reactions for that entity.

**Note:** If Decision 4 is confirmed (ThroughKindAliasResolver injects `filter[where][_kind]` directly — like HierarchyKindAliasResolver does), then the explicit `ConvertKindAliasToKindQuery` filter may be redundant. The through resolver would handle both path rewrite and kind query injection. To be decided during implementation.

---

### DECISION 10: `AddSetsTo*` Filter Selection ✅ CONFIRMED

There are **4 AddSetsTo* filter variants**. The correct one depends on the through route type.

**Actual filter usage in existing generic through routes:**

| Through Route | Filter Used | Why |
|---|---|---|
| `reactionsThroughEntity` (all 4 ops) | `AddSetsToEntityListOrReactionViaRecordQuery` | Reactions accessed *via* an entity record — single `set[audience]` check |
| `reactionsThroughList` (all 4 ops) | `AddSetsToEntityListOrReactionViaRecordQuery` | Reactions accessed *via* a list record — single `set[audience]` check |
| `entitiesThroughList` (all 4 ops) | `AddSetsToThroughRecordQuery` | Entities accessed *through* a list — dual `set` + `setThrough` check |
| `listsThroughEntity` (GET only) | `AddSetsToThroughRecordQuery` | Lists accessed *through* an entity — dual `set` + `setThrough` check |

**The 4 filters and their purposes:**

| Filter | Adds to query | Used by |
|---|---|---|
| `AddSetsToEntityListOrReactionViaRecordQuery` | `set[audience][userIds/groupIds]` | Base entities/lists, reactions through entity/list, hierarchy routes |
| `AddSetsToThroughRecordQuery` | `set[audience]` + `setThrough[audience]` | Entities through list, lists through entity |
| `AddSetsToReactionsQuery` | `entitySet[audience]` or `listSet[audience]` + `set[audience]` | Direct entity-reactions, list-reactions (from root, not through) |
| `AddSetsToRelationQuery` | `set[or][actives/pendings]` + `listSet[audience]` + `entitySet[audience]` | Relations |

**For through-kind-alias routes, use the SAME filter as the generic through route:**

| Through Kind Alias Route | Filter |
|---|---|
| reactions through entity kind alias | `AddSetsToEntityListOrReactionViaRecordQuery` |
| reactions through list kind alias | `AddSetsToEntityListOrReactionViaRecordQuery` |
| entities through list kind alias | `AddSetsToThroughRecordQuery` |
| lists through entity kind alias | `AddSetsToThroughRecordQuery` |

---

### DECISION 11: `CheckIfRouteEnabled` Controller Name ✅ CONFIRMED

**Option C — verbose and explicit.** Both aliases are named in the controller name:

| Through Type | Controller Name |
|---|---|
| reactions through entity | `reactionKindAliasThroughEntityKindAlias` |
| reactions through list | `reactionKindAliasThroughListKindAlias` |
| entities through list | `entityKindAliasThroughListKindAlias` |
| lists through entity | `listKindAliasThroughEntityKindAlias` |

Verbose but unambiguous — no character savings needed for environment variable config keys.

---

### DECISION 12: `RewritePath` Rules ✅ DECIDED

The inbound URL contains 5 segments. The backend URL has 3 segments. The rewrite strips `{kindAlias}` and `{throughAlias}`:

```
INBOUND:  /api/v1/entities/books/{recordId}/reactions/likes
BACKEND:  /entities/{recordId}/reactions
```

Concretely:
```yaml
RewritePath=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/(?<kindAlias>[^/]+)/(?<recordId>[^/]+)/${app.inbound.controllerBasePaths.reactionsThroughEntity}/(?<throughAlias>[^/]+), ${app.outbound.routing-target.baseUri}entities/${recordId}/reactions
```

The route-level `RewritePath` is correct here. Unlike `HierarchyKindAliasResolver` (which rewrites internally because the backend segment depends on resolution), through routes have a static backend path — so route-level `RewritePath` works.

---

## Summary of Decisions

| # | Decision | Status | Notes |
|---|---|---|---|
| 1 | Path structure: 5-segment with `{throughAlias}` | ✅ Decided | No hierarchy collision |
| 2 | Config: `through.reactions[]`, `through.entities[]`, `through.lists[]` | ✅ Decided | On `AliasConfig` |
| 3 | Route placement: end of file | ✅ Resolved | No ordering conflict |
| 4 | KindResolution: two-filter pipeline (KindResolution → ThroughKindAliasResolver) | ✅ Confirmed | Follows hierarchy pattern, route-level RewritePath |
| 5 | Auth policies: reuse generic through | ✅ Confirmed | |
| 6 | Timeout key: `<recordType>.kinds.<throughKind>.<routeId>` | ✅ Confirmed | |
| 7 | Validation: include for POST | ✅ Confirmed | Through kind used for schema |
| 8 | PlaceKindNameIntoPayload: include for POST/PUT | ✅ Confirmed | Through kind placed |
| 9 | ConvertKindAliasToKindQuery: include for GET | ✅ Confirmed | May be handled by resolver filter (like hierarchy) |
| 10 | AddSetsTo*: match generic through route's filter | ✅ Confirmed | Parent scoping via policies; query scoping matches generic through |
| 11 | Controller names: naming convention | ✅ Confirmed | Option C: `reactionKindAliasThroughEntityKindAlias` (verbose, explicit) |
| 12 | RewritePath: strip kindAlias + throughAlias | ✅ Decided | Route-level filter (static target) |

---

## Proposed Filter Chain (Example: `findReactionsByEntityIdByKindAlias`)

```yaml
- id: findReactionsByEntityIdByKindAlias
  uri: ${app.outbound.routing-target.protocol}://${app.outbound.routing-target.host}:${app.outbound.routing-target.port}
  predicates:
  - Path=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/{kindAlias}/{recordId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/${app.inbound.controllerBasePaths.reactionsThroughEntity}/{throughAlias}
  - Method=GET
  filters:
  - name: DynamicTimeout
    args:
      connectTimeoutMs: ${app.timeouts.reactionsThroughEntity.findReactionsByEntityId.connectTimeoutMs}
      responseTimeoutMs: ${app.timeouts.reactionsThroughEntity.findReactionsByEntityId.responseTimeoutMs}
  - KindResolution                                    # resolves {kindAlias} → parent kind
  - name: ThroughKindAliasResolver                    # resolves {throughAlias} → through kind, injects _kind query
    args:
      throughSegment: reactions
  - name: CheckIfRouteEnabled
    args:
      controllerName: reactionKindAliasThroughEntityKindAlias
  - RewritePath=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/(?<kindAlias>[^/]+)/(?<recordId>[^/]+)/${app.inbound.controllerBasePaths.reactionsThroughEntity}/(?<throughAlias>[^/]+), ${app.outbound.routing-target.baseUri}entities/${recordId}/reactions
  - AuthenticateRequest
  - GenerateRequestId
  - name: DynamicRateLimiter
    args:
      replenishRate: ${app.rate-limits.reactions.findEntityReactions.replenishRate}
      burstCapacity: ${app.rate-limits.reactions.findEntityReactions.burstCapacity}
  - FetchForbiddenFields
  - name: AuthorizeRequest
    args:
      policyName: /policies/auth/routes/reactionsThroughEntity/findReactionsByEntityId/policy
  - PreventStringifiedJsonFilter
  - ApplyFieldsetConfig
  - ConvertSimplerQueriesToBackendFormat
  - AddSetsToEntityListOrReactionViaRecordQuery       # same as generic reactionsThroughEntity
  - PreventQueryByForbiddenFields
  - RemoveRequestHeader=Authorization
  - name: DynamicLocalCache
    args:
      timeToLive: ${app.local-cache.entityReactions.findEntityReactions.timeToLive}
      size: ${app.local-cache.entityReactions.findEntityReactions.size}
  - FieldFilter
  metadata:
    recordType: entityReactions
    controllerName: reactionKindAliasThroughEntityKindAlias
    baseControllerName: entities
    tags:
    - get
    - find
    - read-only
    - entityReactions
    - reactionKindAliasThroughEntityKindAlias
    - kind-alias
    - through
    - collection
    - reaction
```

## Proposed Filter Chain (Example: `createReactionByEntityIdByKindAlias`)

```yaml
- id: createReactionByEntityIdByKindAlias
  uri: ${app.outbound.routing-target.protocol}://${app.outbound.routing-target.host}:${app.outbound.routing-target.port}
  predicates:
  - Path=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/{kindAlias}/{recordId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/${app.inbound.controllerBasePaths.reactionsThroughEntity}/{throughAlias}
  - Method=POST
  filters:
  - name: DynamicTimeout
    args:
      connectTimeoutMs: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.connectTimeoutMs}
      responseTimeoutMs: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.responseTimeoutMs}
  - KindResolution
  - name: ThroughKindAliasResolver
    args:
      throughSegment: reactions
  - name: DynamicRequestSizeFilter
    args:
      maxSize: ${app.request-sizes.reactions.create}
  - name: CheckIfRouteEnabled
    args:
      controllerName: reactionKindAliasThroughEntityKindAlias
  - RewritePath=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/(?<kindAlias>[^/]+)/(?<recordId>[^/]+)/${app.inbound.controllerBasePaths.reactionsThroughEntity}/(?<throughAlias>[^/]+), ${app.outbound.routing-target.baseUri}entities/${recordId}/reactions
  - PlaceKindNameIntoPayload                          # places _kind=like into reaction body
  - AuthenticateRequest
  - GenerateRequestId
  - name: DynamicRateLimiter
    args:
      replenishRate: ${app.rate-limits.reactions.createEntityReaction.replenishRate}
      burstCapacity: ${app.rate-limits.reactions.createEntityReaction.burstCapacity}
  - FetchForbiddenFields
  - ValidateRequestBodyByKindSchema                   # validates against 'like' schema
  - name: AuthorizeRequest
    args:
      policyName: /policies/auth/routes/reactionsThroughEntity/createReactionByEntityId/policy
  - name: AcquireLockForCreation
    args:
      waitTime: ${app.locks.reactions.create.waitTime}
      leaseTime: ${app.locks.reactions.create.leaseTime}
  - AddManagedFieldsInCreation
  - ApplyFieldsetConfig
  - RemoveRequestHeader=Authorization
  - FieldFilter
  metadata:
    recordType: entityReactions
    controllerName: reactionKindAliasThroughEntityKindAlias
    baseControllerName: entities
    tags:
    - post
    - create
    - write
    - manage
    - entityReactions
    - reactionKindAliasThroughEntityKindAlias
    - kind-alias
    - through
    - reaction
```

---

### Filter Chain Comparison

| Filter | Generic Through (reactions) | Generic Through (entities/lists) | Kind Alias Base | Through Kind Alias |
|---|---|---|---|---|
| DynamicTimeout | ❌ (metadata) | ❌ (metadata) | ✅ | ✅ |
| KindResolution | ❌ | ❌ | ✅ (resolves kind) | ✅ (resolves parent kind) |
| ThroughKindAliasResolver | ❌ | ❌ | ❌ | ✅ NEW (resolves through kind, injects _kind query) |
| CheckIfRouteEnabled | ✅ throughCtrl | ✅ throughCtrl | ✅ kindAliasCtrl | ✅ e.g. `reactionKindAliasThroughEntityKindAlias` |
| DynamicRequestSizeFilter | ❌ (static) | ❌ (static) | ✅ | ✅ (POST only) |
| DynamicRateLimiter | ❌ (static) | ❌ (static) | ✅ | ✅ |
| PlaceKindNameIntoPayload | ❌ | ❌ | ✅ (POST) | ✅ (POST, uses through kind) |
| ValidateRequestBodyByKindSchema | ❌ | ❌ | ✅ (POST) | ✅ (POST, validates through kind schema) |
| AddSetsTo* | ViaRecord | ThroughRecord | ViaRecord | Same as generic through equivalent |
| AuthorizeRequest | through policy | through policy | base policy | through policy (reuse) |
| DynamicLocalCache | ❌ (static) | ❌ (static) | ✅ | ✅ |
