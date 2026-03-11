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

For example, if you have entity alias `books` and entity-reaction alias `likes`:
- `GET /entities/books` works (kind alias entity) ✅
- `GET /entities/{id}/reactions` works (generic through) ✅
- `GET /entities/books/{id}/likes` does NOT exist ❌

---

## New Route Blocks Needed

### New sections to add to `application-routes.yml`:

```
# (existing) entity reactions kind alias - dynamic hierarchy routes
# (existing) list reactions kind alias from root mapping
# (existing) list reactions kind alias - dynamic hierarchy routes

# NEW: reactions through entity kind alias routes
# NEW: reactions through list kind alias routes  
# NEW: entities through list kind alias routes
# NEW: lists through entity kind alias routes
```

### Route Inventory

**Reactions through Entity Kind Alias** (4 routes):

| Route ID | Method | URL Pattern |
|---|---|---|
| `createReactionByEntityIdByKindAlias` | POST | `entities/{kindAlias}/{recordId}/reactions` |
| `findReactionsByEntityIdByKindAlias` | GET | `entities/{kindAlias}/{recordId}/reactions` |
| `updateReactionsByEntityIdByKindAlias` | PATCH | `entities/{kindAlias}/{recordId}/reactions` |
| `deleteReactionsByEntityIdByKindAlias` | DELETE | `entities/{kindAlias}/{recordId}/reactions` |

**Reactions through List Kind Alias** (4 routes):

| Route ID | Method | URL Pattern |
|---|---|---|
| `createReactionByListIdByKindAlias` | POST | `lists/{kindAlias}/{recordId}/reactions` |
| `findReactionsByListIdByKindAlias` | GET | `lists/{kindAlias}/{recordId}/reactions` |
| `updateReactionsByListIdByKindAlias` | PATCH | `lists/{kindAlias}/{recordId}/reactions` |
| `deleteReactionsByListIdByKindAlias` | DELETE | `lists/{kindAlias}/{recordId}/reactions` |

**Entities through List Kind Alias** (4 routes):

| Route ID | Method | URL Pattern |
|---|---|---|
| `createEntityByListIdByKindAlias` | POST | `lists/{kindAlias}/{recordId}/entities` |
| `findEntitiesByListIdByKindAlias` | GET | `lists/{kindAlias}/{recordId}/entities` |
| `updateEntitiesByListIdByKindAlias` | PATCH | `lists/{kindAlias}/{recordId}/entities` |
| `deleteEntitiesByListIdByKindAlias` | DELETE | `lists/{kindAlias}/{recordId}/entities` |

**Lists through Entity Kind Alias** (1 route):

| Route ID | Method | URL Pattern |
|---|---|---|
| `findListsByEntityIdByKindAlias` | GET | `entities/{kindAlias}/{recordId}/lists` |

**Total: 13 new routes**

---

## Design Decisions Required

### DECISION 1: Path Structure — Static `reactions` segment or dynamic through alias?

**Option A — Static through segment** (recommended for Phase 1):
```
entities/{kindAlias}/{recordId}/reactions     ← "reactions" is fixed
lists/{kindAlias}/{recordId}/entities         ← "entities" is fixed
entities/{kindAlias}/{recordId}/lists          ← "lists" is fixed
```
The through segment uses the configured controller base path (`reactionsThroughEntity`, `entitiesThroughList`, `listsThroughEntity`).

**Option B — Dynamic through alias segment** (future):
```
entities/books/{recordId}/likes               ← "likes" is a reaction alias
lists/playlists/{recordId}/songs              ← "songs" is an entity alias
entities/books/{recordId}/reading-lists       ← "reading-lists" is a list alias
```
This requires cross-controller alias linking (see Decision 2).

**Question for you:** Should Phase 1 use static through segments (e.g., `books/{id}/reactions`), or do you want to jump straight to dynamic through aliases (e.g., `books/{id}/likes`)?

---

### DECISION 2: Cross-Controller Alias Config (only needed for Option B above)

If we go with dynamic through aliases, we need a way to link entity aliases to reaction/list aliases. Current `AliasConfig` has `children`/`parents` (same-controller hierarchy) but no cross-controller linking.

**Proposed config extension:**
```properties
# Entity alias "books" has reaction through alias "likes"
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].through.reactions[0].alias=likes
app.oas.controllers.entities.aliases[0].through.reactions[0].kind=like

# Entity alias "books" can be accessed through list alias "reading-lists"  
app.oas.controllers.lists.aliases[0].alias=reading-lists
app.oas.controllers.lists.aliases[0].through.entities[0].alias=books
app.oas.controllers.lists.aliases[0].through.entities[0].kind=book
```

**Question for you:** Is this config shape acceptable? Or do you prefer a different approach?

---

### DECISION 3: Predicate Design

The predicates need to capture `kindAlias` and `recordId`:

```yaml
predicates:
- Path=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/{kindAlias}/{recordId:[0-9a-fA-F-]{36}}/${app.inbound.controllerBasePaths.reactionsThroughEntity}
- Method=POST
```

**Key concern: Path collision with hierarchy routes.**

The hierarchy route uses:
```
entities/{kindAlias}/{recordId:[UUID]}/{hierarchyAlias}
```

The through-kind-alias route would use:
```
entities/{kindAlias}/{recordId:[UUID]}/reactions
```

Since `reactions` is a fixed string and `hierarchyAlias` is dynamic, Spring Cloud Gateway will match the route with the **most specific predicate first**. The through route with a literal `reactions` segment is more specific than the hierarchy route with a wildcard `{hierarchyAlias}`.

**Critical: Route ordering matters.** Through-kind-alias routes must be placed **before** hierarchy routes in `application-routes.yml` to ensure correct matching. If hierarchy routes come first, a request to `books/{id}/reactions` could incorrectly match `findEntityHierarchyByKindAlias` with `hierarchyAlias=reactions`.

**UPDATE: Actually, both use path variable syntax.** The through route uses `${app.inbound.controllerBasePaths.reactionsThroughEntity}` which resolves to `reactions` — a literal string in the path pattern. The hierarchy route uses `{hierarchyAlias}` which is a path variable. Spring Cloud Gateway's `PathRoutePredicateFactory` sorts by specificity, so the literal `reactions` would match first. However, since the through path resolves from a variable at config time, this should work correctly as long as the through routes are defined before hierarchy routes.

**Question for you:** Should through-kind-alias route blocks be placed immediately after the base kind alias routes and before hierarchy routes? Or at the very end of the file (after all existing kind alias sections)?

---

### DECISION 4: KindResolution Filter — Can it work as-is?

**Current behavior:** `KindResolutionGatewayFilterFactory` extracts `{kindAlias}` from URL, looks up `openApiProperties.getAliasContext(controllerName, kindAlias)`, and populates `KindAliasConfigAttr` with `kindName`, `controllerName`, `recordType`, etc.

**For through-kind-alias routes:**
- Route: `entities/{kindAlias}/{recordId}/reactions`
- `{kindAlias}` = `books` (the entity alias, not the reaction alias)
- `controllerName` in metadata = something like `reactionsThroughEntityKindAlias`
- `baseControllerName` in metadata = `entities` (the controller that the kindAlias belongs to)

**The filter looks up aliases using `baseControllerName`:**
```java
String lookupControllerName = (baseControllerName == null || baseControllerName.isBlank())
    ? controllerName
    : baseControllerName;
OpenApiProperties.AliasContext aliasContext = openApiProperties.getAliasContext(lookupControllerName, kindAlias);
```

This means: if `baseControllerName=entities`, it resolves `kindAlias=books` against the `entities` controller aliases → finds kind `book` ✅

**But what about the "through" record type?** The through routes create reactions/entities, not the parent entity type. The `recordType` in metadata should be `entityReactions` (for reactions through entity), not `entities`. This is critical for:
- `ConvertKindAliasToKindQuery` — adds `filter[where][_kind]=book` to query. But for a through route, the _kind filter should apply to the **parent entity**, not the reaction record itself. Actually, reactions inherit the parent's _kind context via the backend's through relation mechanism.

**No changes needed to KindResolution filter.** It correctly resolves the parent entity's kind alias. The `recordType` from metadata will correctly indicate the through record type (`entityReactions`).

**One thing to verify:** Does the `ConvertKindAliasToKindQuery` filter make sense for through routes? In generic through routes, there's no `_kind` query. The backend filters reactions/entities by the parent record's ID relationship, not by `_kind`. However, if the user accesses `entities/books/{id}/reactions`, we may want to ensure the parent entity is of kind `book` — but that's handled by the backend's through mechanism already. 

**Question for you:** Should through-kind-alias routes use `ConvertKindAliasToKindQuery`? This would add `filter[where][_kind]=book` to the query — but these routes query reactions/entities *through* a parent record, not the parent record itself. The `_kind` filter might not apply to the through records. What is the desired behavior?

---

### DECISION 5: Authorization Policy Names

**Generic through routes use their own policies:**
```
/policies/auth/routes/reactionsThroughEntity/createReactionByEntityId/policy
```

**Kind alias routes reuse base controller policies:**
```
/policies/auth/routes/entities/createEntity/policy
```

**For through-kind-alias routes, which pattern?**

**Option A:** Reuse existing through policies (consistent with how kind alias routes reuse base policies):
```yaml
policyName: /policies/auth/routes/reactionsThroughEntity/createReactionByEntityId/policy
```

**Option B:** New through-kind-alias-specific policies:
```yaml
policyName: /policies/auth/routes/reactionsThroughEntity/createReactionByEntityIdByKindAlias/policy
```

**Recommendation:** Option A — reuse existing through policies. This is consistent with how entity kind alias routes reuse `entities/createEntity/policy`.

**Question for you:** Should through-kind-alias routes reuse the generic through route authorization policies?

---

### DECISION 6: Timeout Configuration

**Generic through routes use static metadata timeouts:**
```yaml
metadata:
  connect-timeout: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.connectTimeoutMs}
  response-timeout: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.responseTimeoutMs}
```

**Kind alias routes use `DynamicTimeout` filter:**
```yaml
filters:
- name: DynamicTimeout
  args:
    connectTimeoutMs: ${app.timeouts.entities.createEntity.connectTimeoutMs}
    responseTimeoutMs: ${app.timeouts.entities.createEntity.responseTimeoutMs}
```

The `DynamicTimeout` filter allows per-kind override via:
```
app.timeouts.<recordType>.kinds.<kindName>.<operation>.connectTimeoutMs
```

**For through-kind-alias routes:**

The `DynamicTimeout` filter needs:
- A `recordType` to build the config key
- A `kindName` from `KindAliasConfigAttr` (which KindResolution sets)
- An `operation` from the route ID

**Default timeout should fall back to the generic through timeout:**
```yaml
- name: DynamicTimeout
  args:
    connectTimeoutMs: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.connectTimeoutMs}
    responseTimeoutMs: ${app.timeouts.reactionsThroughEntity.createReactionByEntityId.responseTimeoutMs}
```

**Kind-specific override would look like:**
```properties
app.timeouts.entityReactions.kinds.like.createReactionByEntityIdByKindAlias.connectTimeoutMs=5000
```

**Or should it fallback through the parent entity's kind name?**
```properties
app.timeouts.reactionsThroughEntity.kinds.book.createReactionByEntityIdByKindAlias.connectTimeoutMs=5000
```

**Question for you:** For DynamicTimeout on through-kind-alias routes, what should the config key structure be? The `recordType` in route metadata will be something like `entityReactions` — but the kind resolved by KindResolution is the *parent's* kind (`book`), not a reaction kind. So the timeout key would be `app.timeouts.entityReactions.kinds.book.createReactionByEntityIdByKindAlias.connectTimeoutMs`. Is this the intended behavior?

---

### DECISION 7: Validation (`ValidateRequestBodyByKindSchema`)

**Generic through routes do NOT use `ValidateRequestBodyByKindSchema`.** They have no kind resolution.

**Kind alias create routes DO use it:**
```yaml
- ValidateRequestBodyByKindSchema
```

**For through-kind-alias create routes (POST):**
The kind resolved is the *parent's* kind (e.g., `book`), but the request body being posted is a *reaction* or *entity*. The validation schema should be for the reaction/entity type, not the parent entity type.

**This means `ValidateRequestBodyByKindSchema` would NOT apply** for through-kind-alias routes, because:
1. The resolved kind is the parent's kind, not the created record's kind
2. The schema for the created record (reaction/entity) depends on the through record type, not the parent alias

**Recommendation:** Do NOT include `ValidateRequestBodyByKindSchema` in through-kind-alias routes (same as generic through routes).

**Question for you:** Confirm that request body validation is not needed for through-kind-alias routes?

---

### DECISION 8: `PlaceKindNameIntoPayload`

**Generic through POST routes do NOT use `PlaceKindNameIntoPayload`.** The created record (reaction/entity) gets its kind through the backend's through mechanism, not from the URL.

**Kind alias POST routes DO use it:**
```yaml
- PlaceKindNameIntoPayload
```

**For through-kind-alias POST routes:**
The resolved kind is the *parent's* kind (e.g., `book`). But the record being created is a reaction, not an entity. Placing `_kind=book` into a reaction payload would be incorrect.

**Recommendation:** Do NOT include `PlaceKindNameIntoPayload` in through-kind-alias routes.

**Question for you:** Confirm?

---

### DECISION 9: `AddSetsToThroughRecordQuery` vs `AddSetsToEntityListOrReactionViaRecordQuery`

**Generic through routes use `AddSetsToThroughRecordQuery`** — applies dual visibility (set + setThrough).

**Kind alias routes use `AddSetsToEntityListOrReactionViaRecordQuery`** — applies single set-based visibility.

**For through-kind-alias routes:** These are still through routes conceptually. The query goes through a parent-child relation. Dual visibility (set + setThrough) is needed.

**Recommendation:** Use `AddSetsToThroughRecordQuery` (same as generic through routes).

---

### DECISION 10: `CheckIfRouteEnabled` Controller Name

**Generic through routes use:**
```yaml
controllerName: reactionsThroughEntity
```

**Kind alias routes use:**
```yaml
controllerName: entitiesKindAlias
```

**For through-kind-alias routes, options:**
- `reactionsThroughEntityKindAlias` — new controller name
- `reactionsThroughEntity` — reuse generic

**Recommendation:** Use a new controller name like `reactionsThroughEntityKindAlias` for independent toggle control. This allows disabling domain-projected through routes without disabling generic through routes.

**Question for you:** Should through-kind-alias routes have their own `controllerName` for toggle control?

---

### DECISION 11: `RewritePath` Rules

**Generic through route:**
```yaml
RewritePath=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/(?<recordId>.*)/${app.inbound.controllerBasePaths.reactionsThroughEntity}, ${app.outbound.routing-target.baseUri}entities/${recordId}/reactions
```

**Kind alias through route needs to strip the kindAlias:**
```yaml
RewritePath=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/(?<kindAlias>[^/]+)/(?<recordId>[^/]+)/${app.inbound.controllerBasePaths.reactionsThroughEntity}, ${app.outbound.routing-target.baseUri}entities/${recordId}/reactions
```

The `kindAlias` segment is captured but not used in the rewrite target — it's consumed by `KindResolution` filter.

---

## Summary of Questions for You

| # | Question | Recommendation |
|---|---|---|
| 1 | Static through segments (`books/{id}/reactions`) or dynamic aliases (`books/{id}/likes`)? | Static for Phase 1 |
| 2 | Cross-controller alias config shape (if dynamic aliases)? | Defer to Phase 2 |
| 3 | Route placement: before hierarchy routes or at end of file? | After each controller's kind alias section, before hierarchy |
| 4 | Should through-kind-alias routes use `ConvertKindAliasToKindQuery`? | Probably not — through queries are scoped by parent ID, not _kind |
| 5 | Reuse generic through auth policies? | Yes |
| 6 | DynamicTimeout key structure for through-kind-alias? | `app.timeouts.<recordType>.kinds.<parentKind>.<operation>` |
| 7 | Skip `ValidateRequestBodyByKindSchema` for through-kind-alias? | Yes — resolved kind is parent's, not created record's |
| 8 | Skip `PlaceKindNameIntoPayload` for through-kind-alias? | Yes — same reason |
| 9 | Use `AddSetsToThroughRecordQuery`? | Yes — through routes need dual visibility |
| 10 | New controllerName for toggle control? | Yes — `reactionsThroughEntityKindAlias` etc. |
| 11 | RewritePath strips kindAlias? | Yes — drop kindAlias, keep recordId |

---

## Proposed Filter Chain (Example: `findReactionsByEntityIdByKindAlias`)

```yaml
- id: findReactionsByEntityIdByKindAlias
  uri: ${backend}
  predicates:
  - Path=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}/{kindAlias}/{recordId:[UUID]}/${app.inbound.controllerBasePaths.reactionsThroughEntity}
  - Method=GET
  filters:
  - name: DynamicTimeout
    args:
      connectTimeoutMs: ${app.timeouts.reactionsThroughEntity.findReactionsByEntityId.connectTimeoutMs}
      responseTimeoutMs: ${app.timeouts.reactionsThroughEntity.findReactionsByEntityId.responseTimeoutMs}
  - KindResolution
  - name: CheckIfRouteEnabled
    args:
      controllerName: reactionsThroughEntityKindAlias
  - RewritePath=...entities/(?<kindAlias>[^/]+)/(?<recordId>[^/]+)/reactions, ...entities/${recordId}/reactions
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
  - AddSetsToThroughRecordQuery          # ← Through dual visibility
  - PreventQueryByForbiddenFields
  - RemoveRequestHeader=Authorization
  - name: DynamicLocalCache
    args:
      timeToLive: ${app.local-cache.entityReactions.findEntityReactions.timeToLive}
      size: ${app.local-cache.entityReactions.findEntityReactions.size}
  - FieldFilter
  metadata:
    recordType: entityReactions
    controllerName: reactionsThroughEntityKindAlias
    baseControllerName: entities
    tags:
    - get
    - find
    - read-only
    - entityReactions
    - reactionsThroughEntityKindAlias
    - kind-alias
    - through
    - collection
    - reaction
```

### Filter Chain Comparison

| Filter | Generic Through | Kind Alias Base | Through Kind Alias |
|---|---|---|---|
| DynamicTimeout | ❌ (metadata) | ✅ | ✅ |
| KindResolution | ❌ | ✅ | ✅ |
| CheckIfRouteEnabled | ✅ throughCtrl | ✅ kindAliasCtrl | ✅ throughKindAliasCtrl |
| DynamicRequestSizeFilter | ❌ (static) | ✅ | ✅ (POST only) |
| DynamicRateLimiter | ❌ (static) | ✅ | ✅ |
| PlaceKindNameIntoPayload | ❌ | ✅ (POST) | ❌ |
| ValidateRequestBodyByKindSchema | ❌ | ✅ (POST) | ❌ |
| ConvertKindAliasToKindQuery | ❌ | ✅ (GET/count) | ❌ (TBD) |
| AddSetsTo*Query | ThroughRecord | ViaRecord | ThroughRecord |
| AuthorizeRequest | through policy | base policy | through policy (reuse) |
| DynamicLocalCache | ❌ (static) | ✅ | ✅ |
