# OAS Test Results — Member Role

> **Date**: 2026-02-28  
> **Role**: `tarcinapp.member`  
> **Gateway**: localhost:8081 (Spring Boot, dev profile)  
> **Backend**: localhost:3000 (LoopBack)  
> **OPA**: localhost:8181  
> **Redis**: localhost:6379

---

## Executive Summary

Tested the dynamically generated OpenAPI Specification across 9 test phases (Phase 0, 1a–1e, 2, 3, 4). All 5 controller types were tested in isolation and combined. **7 open bugs** remain out of 16 originally reported.

> **Fixed**: BUG-001, BUG-002 (Singularization/Summary Truncation), BUG-003 (Create Response Schemas), BUG-005 (Children GET Response Type)  
> **Not a bug**: BUG-004 (x-record-type), BUG-006 (204 No Content), BUG-008 (empty tag descriptions), BUG-011 (hyphenated keys), BUG-012 (empty PatchRelation)

### Metrics by Phase

| Phase | Config | Paths | Ops | Schemas | New Bugs Found |
|-------|--------|-------|-----|---------|----------------|
| 0 — Baseline | No aliases | 23 | 52 | 32 | BUG-003,004,005,006 |
| 1a — Entities | vehicles+garages | 29 | 67 | 52 | BUG-007,008,009 |
| 1b — Lists | playlists+albums | 28 | 64 | 47 | (confirms 1a bugs) |
| 1c — Relations | memberships+partnerships | 23 | 52 | 40 | BUG-010,012 |
| 1d — Entity-reactions | vehicle-likes+bookmarks | 29 | 67 | 50 | BUG-011,013 |
| 1e — List-reactions | playlist-likes+saves | 29 | 67 | 42 | (confirms prior) |
| 2 — Combined | All 10 aliases | 56 | 137 | 89 | BUG-014 |
| 3 — Toggles | Combined + toggles | 24 | 61 | 45 | BUG-015 |
| 4 — Knobs | Combined + orchestrator | 62 | 152 | 89 | BUG-016 |

---

## Bug Catalog

### BUG-003: Create Response Schemas Missing Domain Fields (HIGH) — ✅ SOLVED

Fixed in branch `fix/mutation-response-schemas-missing-domain-fields`. POST response schemas now include domain-specific fields.

---

### BUG-005: Children/Parents GET Returns Object Instead of Array (MEDIUM) — ✅ SOLVED

Fixed in branch `fix/BUG-005-children-get-response-missing-properties`. All children/parents GET endpoints now correctly return `type: array`.

---

### BUG-007: Required Fields Missing in Request Schemas (HIGH)

All `New*` request schemas have `required: []` even when the config explicitly specifies required fields.

| Schema | Config Required | Actual Required |
|--------|----------------|-----------------|
| NewVehicle | ["myVehiclePlateNumber"] | [] |
| NewPlaylist | ["myPlaylistGenre"] | [] |
| NewMembership | ["myMembershipRole"] | [] |
| NewVehicleLike | ["myLikeEmoji"] | [] |
| NewPlaylistLike | ["myReactionEmoji"] | [] |

The `required` constraints from config DO appear on GET response schemas (e.g., `Vehicle.required = ['_name', 'myVehiclePlateNumber']`) but NOT on create request schemas.

---

### BUG-009: Children/Parents Route Overrides Not Applied (HIGH)

Custom `operationId`, `summary`, and `tags` configured for children routes are completely IGNORED. The auto-generated (often truncated, per BUG-001) values are used instead.

**Entities example** (engines as children of vehicles):
| Route | Config operationId | Config tags | Actual operationId | Actual tags |
|-------|-------------------|-------------|-------------------|-------------|
| createEntityChild | createVehicleEngine | [VehicleEngines] | createVehiclEngin | [Vehicles] |
| findEntityChildren | listVehicleEngines | [VehicleEngines] | listVehiclEngines | [Vehicles] |

**Lists example** (tracks as children of playlists):
| Route | Config operationId | Config tags | Actual operationId | Actual tags |
|-------|-------------------|-------------|-------------------|-------------|
| createChildList | addTrackToPlaylist | [PlaylistTracks] | createPlaylistTrack | [Playlists] |
| findChildrenByListId | listPlaylistTracks | [PlaylistTracks] | listPlaylistTracks | [Playlists] |

---

### BUG-010: Auto-Generated OperationId Pattern Varies by Controller (LOW)

Different controller types use different auto-generated operationId prefixes, which is inconsistent:

| Controller | GET all | GET by ID | POST |
|------------|---------|-----------|------|
| entities | `listAlias` | `getAliasById` | `createAlias` |
| lists | `listAlias` | `getAliasById` | `createAlias` |
| relations | `findRelationsAlias` | `findRelationByIdAlias` | `createAlias` |
| entityReactions | `findEntityReactionsAlias` | `findEntityReactionByIdAlias` | `createAlias` |
| listReactions | `findListReactionsAlias` | `findListReactionByIdAlias` | `createAlias` |

---

### BUG-013: Hyphenated Alias Names in Auto-Generated OperationIds (MEDIUM)

When alias names contain hyphens (e.g., `vehicle-bookmarks`), auto-generated operationIds preserve the hyphens:
- `createVehicle-bookmark`
- `findEntityReactionsVehicle-bookmarks`
- `updateEntityReactionByIdVehicle-likes`

OpenAPI convention is camelCase operationIds without hyphens.

---

### BUG-014: Near-Duplicate Tags for Same Alias (MEDIUM)

When an alias has some routes with custom tag overrides and other routes without, two near-duplicate tags are created:

| Alias | Custom Tag (overridden routes) | Auto-Gen Tag (non-overridden routes) |
|-------|-------------------------------|-------------------------------------|
| vehicle-likes | VehicleLikes | Vehicle-likes |
| playlist-likes | PlaylistLikes | Playlist-likes |

Operations for the same alias are split across two tags with slightly different names.

---

### BUG-015: Route Toggles Completely Non-Functional (CRITICAL)

`app.toggles.routes.off[0]=<routeId>` has **no effect**. All `TransformedPath` objects are created with `routeId = null` (using the 2-argument constructor). The `isRouteDisabled()` method returns `false` for null routeIds.

**Root Cause**: `OasTransformationEngine.java` line 776/786/795/837/865 — all `new TransformedPath(virtualPath, pathItem)` calls use the 2-arg constructor that sets `routeId = null`. The 3-arg constructor `TransformedPath(virtualPath, pathItem, routeId)` exists but is never called.

**Controller toggles** and **tag toggles** work correctly:
- `app.toggles.controllers.off[0]=relations` → removes all relations paths ✅
- `app.toggles.tags.off[0]=Albums` → removes all album paths ✅
- `app.toggles.tags.off[1]=generic` → removes all base controller paths ✅

---

### BUG-016: autoGenerateOperationIds=false Causes Mass Duplicates (HIGH)

When `app.oas.orchestrator.transformation.autoGenerateOperationIds=false`, aliases without custom operationIds inherit the backend's operationId, which collides with generic routes.

**40 duplicate operationIds** in Phase 4:
- `findEntities` used by both `GET /api/v1/entities` and `GET /api/v1/entities/garages`
- `replaceEntityById` used by `PUT /api/v1/entities/{id}`, `/vehicles/{id}`, and `/garages/{id}` (3x)
- Pattern repeats across all controllers

Additionally, `autoGenerateOperationIds=false` does NOT prevent auto-generation for children routes — they still get truncated auto-gen IDs (`listVehiclEngines`, `createVehiclEngin`).

---

## Correct Behaviors Verified

| Feature | Status | Notes |
|---------|--------|-------|
| API Identity Override | ✅ | title, version, description, contact, servers all correctly overridden |
| Custom operationIds (top-level) | ✅ | Route config operationIds apply correctly for alias routes |
| Custom tags (top-level) | ✅ | VehicleWrite, PlaylistLikes, etc. applied to correct operations |
| Route-level schema override | ✅ | children request schemas correctly include route-specific fields |
| Domain field isolation | ✅ | Different aliases have different domain fields |
| All alias paths generated | ✅ | Correct URL structure for all 5 controllers |
| Error response patterns | ✅ | Consistent 400/401/403/404/409/422/429/500 |
| bearerAuth security | ✅ | Global security scheme correctly added |
| OPA field pruning | ✅ | Internal fields removed from appropriate per-role schemas |
| Controller-specific base fields | ✅ | entities→_reactions, lists→_entities/_relationMetadata, relations→_entityId/_listId, reactions→_entityId/_listId |
| Controller toggle OFF | ✅ | `toggles.controllers.off` removes all paths for controller |
| Tag toggle OFF | ✅ | `toggles.tags.off` removes alias paths for that tag |
| Generic tag toggle | ✅ | `toggles.tags.off=generic` removes all base controller paths |
| includeGenericEndpoints | ✅ | =true adds cross-controller paths (entities/{id}/lists, reactions) + ping + system-info |
| autoGenerateOperationIds=false | ✅ Partial | Custom operationIds preserved; non-overridden routes use backend IDs (but cause duplicates) |
| No operationId collisions (default) | ✅ | With default auto-generation, all IDs unique across 10 aliases |
| Schema deduplication | ✅ | No schema name collisions in combined mode |

---

## Knob Effects Summary

| Knob | Default | When Toggled | Effect |
|------|---------|-------------|--------|
| `includeGenericEndpoints` | `false` | `true` | Adds 6 paths: cross-controller (entities↔lists, entities↔reactions, lists↔reactions) + ping + system-info. Base controller generic paths present regardless. |
| `simplifySchemaNames` | `true` | `false` | No visible effect on schema names |
| `autoGenerateOperationIds` | `true` | `false` | Non-overridden routes use backend operationIds; causes mass duplicates with generic paths (BUG-016) |
| `includeInternalFields` | `true` | `false` | No visible effect; internal fields (`_*`) still present in all schemas |

---

## Bug Priority Matrix

| Priority | Bugs | Impact |
|----------|------|--------|
| **CRITICAL** | BUG-015 | Route toggles non-functional |
| **HIGH** | BUG-007, BUG-009, BUG-016 | Missing validation; children overrides ignored; mass duplicates |
| **MEDIUM** | BUG-013, BUG-014 | Naming inconsistencies; duplicate tags |
| **LOW** | BUG-010 | Cosmetic inconsistency |
