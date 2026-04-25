# Route Tagging Strategy

## Overview
This document describes the comprehensive tagging system applied to all routes in `application-routes.yml`. Tags enable logical grouping, filtering, and organization of the **140 routes** across the API gateway.

## Benefits

Tags are the primary targeting mechanism for the **Route Toggles** feature — a single tag in the `off` list can disable dozens of routes without redeployment. For example, toggling off the `generic` tag hides all 65 base generic routes, leaving only the kind-alias endpoints active; combined with domain projection configuration this produces a clean, domain-native API surface with no raw `/entities` paths exposed to consumers. See [75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md) for the full configuration reference and examples.

## Tag Categories

### 1. HTTP Method Tags
Tags based on the HTTP verb used:
- `get` - GET requests (59 routes)
- `post` - POST requests (28 routes)
- `put` - PUT requests (10 routes)
- `patch` - PATCH requests (26 routes)
- `delete` - DELETE requests (16 routes)

### 2. Operation Type Tags
Tags describing the primary operation:
- `find` - Retrieve/query operations (48 routes)
- `count` - Count operations (10 routes)
- `create` - Resource creation (28 routes)
- `update` - Resource modification (26 routes)
- `update-all` - Bulk update operations (10 routes)
- `replace` - Full resource replacement (10 routes)
- `delete` - Resource deletion (16 routes)

### 3. Access Pattern Tags
Tags describing read vs write access:
- `read-only` - GET operations that don't modify data (59 routes)
- `write` - POST, PUT, PATCH operations that modify data (64 routes)
- `destructive` - DELETE operations (16 routes)
- `manage` - Operations that create, update, or replace resources (64 routes)

### 4. Data Scope Tags
Tags for operation scope:
- `by-id` - Operations on a specific resource by ID (40 routes)
- `single-record` - Operations affecting one record (40 routes)
- `collection` - Operations on multiple records (38 routes)
- `bulk` - Bulk operations like updateAll (10 routes)

### 5. Record Type Tags
Tags identifying the resource type operated on:
- `entities` - Entity resources (32 routes)
- `lists` - List resources (26 routes)
- `relations` - Relation resources (16 routes)
- `entityReactions` - Entity reaction resources (32 routes)
- `listReactions` - List reaction resources (32 routes)

### 6. Controller Tags
Tags identifying the controller handling the route.

**Base Generic Root Controllers** — controller tag overlaps with the record type tag for these:
- `entities` - Entity controller (see record type tag)
- `lists` - List controller (see record type tag)
- `relations` - Relation controller (see record type tag)
- `entityReactions` - Entity reaction controller (see record type tag)
- `listReactions` - List reaction controller (see record type tag)

**Base Generic Traversal Controllers:**
- `reactionsThroughEntity` - Reactions through entity controller (4 routes)
- `reactionsThroughList` - Reactions through list controller (4 routes)
- `entitiesThroughList` - Entities through list controller (4 routes)
- `listsThroughEntity` - Lists through entity controller (1 route)

**Kind Alias Root Controllers:**
- `entitiesKindAlias` - Entity kind-alias controller (13 routes)
- `listKindAlias` - List kind-alias controller (13 routes)
- `relationsKindAlias` - Relation kind-alias controller (8 routes)
- `entityReactionsKindAlias` - Entity reaction kind-alias controller (13 routes)
- `listReactionsKindAlias` - List reaction kind-alias controller (13 routes)

**Kind Alias Traversal Controllers:**
- `reactionKindAliasThroughEntityKindAlias` - Reaction kind alias through entity kind alias controller (4 routes)
- `reactionKindAliasThroughListKindAlias` - Reaction kind alias through list kind alias controller (4 routes)
- `entityKindAliasThroughListKindAlias` - Entity kind alias through list kind alias controller (4 routes)
- `listKindAliasThroughEntityKindAlias` - List kind alias through entity kind alias controller (1 route)

**Utility Controllers:**
- `ping` - Ping utility controller (1 route)
- `explorer` - Explorer/API discovery controller (1 route)

### 7. Context Tags
Tags identifying whether a route targets the generic backend path or a domain-projected kind-alias path:
- `generic` - Direct routes without kind-alias path resolution (65 routes)
- `kind-alias` - Routes using kind-alias path resolution (73 routes)

### 8. Topology Tags
Tags identifying the routing topology:
- `through` - Routes accessing resources through a parent resource path segment (26 routes)

### 9. Hierarchy Tags
Tags for parent-child relationship routes:
- `hierarchical` - Routes dealing with hierarchies (32 routes)
- `children` - Routes operating on child resources (16 routes)
- `parents` - Routes operating on parent resources (8 routes)
- `domain-driven` - Dynamic hierarchy routes using `{hierarchyAlias}` path segment instead of fixed `/children` or `/parents` segments; only available in kind alias controllers (8 routes)

### 10. Domain-Specific Tags
Tags for specific resource features:
- `reaction` - Routes for reaction features; applied to base generic reaction controllers and all traversal reaction controllers (both base and kind alias), but not to kind alias root reaction controllers (38 routes)

### 11. Utility Tags
Tags for non-business routes:
- `utility` - Helper/system routes (2 routes)
- `health-check` - Health monitoring route (1 route)
- `api-discovery` - API documentation route (1 route)

## Tag Assignment Rules

### Rule 1: HTTP Method
Every route gets exactly one HTTP method tag based on its `Method=` predicate.

### Rule 2: Operation Type
Routes get operation tags based on route ID patterns:
- Contains "find" → `find` tag
- Contains "count" → `count` tag
- Contains "create" → `create` tag
- Contains "update" → `update` tag
- Contains "replace" → `replace` tag
- Contains "delete" → `delete` tag
- Contains "updateAll" or "All" + "update" → additionally `update-all` + `bulk` tags

### Rule 3: Access Pattern
Based on HTTP method:
- GET → `read-only`
- POST/PUT/PATCH → `write`
- DELETE → `destructive`
- create/update/replace operations → additionally `manage`

### Rule 4: Record Type
The record type tag is added based on the resource being managed: `entities`, `lists`, `relations`, `entityReactions`, or `listReactions`.

### Rule 5: Controller
The controller identifier tag is added. For base generic root controllers this overlaps with the record type tag. For traversal and kind alias controllers, a distinct controller tag is used (e.g., `reactionsThroughEntity`, `entitiesKindAlias`).

### Rule 6: Context
- Route ID contains "KindAlias" → `kind-alias` tag
- Route is not kind-alias and not utility → `generic` tag (all base generic routes including through routes carry this tag)

### Rule 7: Topology
- Route ID contains "Through", "ByListId", or "ByEntityId" → `through` tag

### Rule 8: Hierarchical
- Route ID contains "Children", "Child", or "create*Child" → `hierarchical` + `children` tags
- Route ID contains "Parent" or "Parents" → `hierarchical` + `parents` tags
- Route ID contains "Hierarchy" → `hierarchical` + `domain-driven` tags

### Rule 9: By ID
- Route ID contains "ById" that refers to a single record address (not a traversal source) → `by-id` + `single-record` tags

### Rule 10: Domain-Specific
- Route involves reactions as the operated resource in base generic or traversal controllers → `reaction` tag

### Rule 11: Utility
- Route ID is "ping" → `utility` + `health-check` tags
- Route ID is "explorer" → `utility` + `api-discovery` tags

## Route Inventory

### Context: Base Generic Controllers

#### Topology: Root Controllers

##### Entity Controller _(11 routes)_

| Route ID | Tags |
|---|---|
| `createEntity` | post, create, write, manage, entities, generic |
| `updateAllEntities` | patch, update, update-all, bulk, write, manage, entities, generic |
| `findEntities` | get, find, read-only, entities, generic, collection |
| `countEntities` | get, count, read-only, entities, generic |
| `findEntityById` | get, find, read-only, entities, generic, by-id, single-record |
| `updateEntityById` | patch, update, write, manage, entities, generic, by-id, single-record |
| `replaceEntityById` | put, replace, write, manage, entities, generic, by-id, single-record |
| `deleteEntityById` | delete, destructive, entities, generic, by-id, single-record |
| `findEntityChildren` | get, find, read-only, entities, generic, hierarchical, children, collection |
| `createEntityChild` | post, create, write, manage, entities, generic, hierarchical, children |
| `findEntityParents` | get, find, read-only, entities, generic, hierarchical, parents, collection |

##### List Controller _(11 routes)_

| Route ID | Tags |
|---|---|
| `createList` | post, create, write, manage, lists, generic |
| `updateAllLists` | patch, update, update-all, bulk, write, manage, lists, generic |
| `findLists` | get, find, read-only, lists, generic, collection |
| `countLists` | get, count, read-only, lists, generic |
| `findListById` | get, find, read-only, lists, generic, by-id, single-record |
| `updateListById` | patch, update, write, manage, lists, generic, by-id, single-record |
| `replaceListById` | put, replace, write, manage, lists, generic, by-id, single-record |
| `deleteListById` | delete, destructive, lists, generic, by-id, single-record |
| `findListChildren` | get, find, read-only, lists, generic, hierarchical, children, collection |
| `createListChild` | post, create, write, manage, lists, generic, hierarchical, children |
| `findListParents` | get, find, read-only, lists, generic, hierarchical, parents, collection |

##### Relation Controller _(8 routes)_

| Route ID | Tags |
|---|---|
| `createRelation` | post, create, write, manage, relations, generic |
| `updateAllRelations` | patch, update, update-all, bulk, write, manage, relations, generic |
| `findRelations` | get, find, read-only, relations, generic, collection |
| `countRelations` | get, count, read-only, relations, generic |
| `findRelationById` | get, find, read-only, relations, generic, by-id, single-record |
| `updateRelationById` | patch, update, write, manage, relations, generic, by-id, single-record |
| `replaceRelationById` | put, replace, write, manage, relations, generic, by-id, single-record |
| `deleteRelationById` | delete, destructive, relations, generic, by-id, single-record |

##### Entity Reactions Controller _(11 routes)_

| Route ID | Tags |
|---|---|
| `createEntityReaction` | post, create, write, manage, entityReactions, generic, reaction |
| `updateAllEntityReactions` | patch, update, update-all, bulk, write, manage, entityReactions, generic, reaction |
| `findEntityReactions` | get, find, read-only, entityReactions, generic, collection, reaction |
| `countEntityReactions` | get, count, read-only, entityReactions, generic, reaction |
| `findEntityReactionById` | get, find, read-only, entityReactions, generic, by-id, single-record, reaction |
| `updateEntityReactionById` | patch, update, write, manage, entityReactions, generic, by-id, single-record, reaction |
| `replaceEntityReactionById` | put, replace, write, manage, entityReactions, generic, by-id, single-record, reaction |
| `deleteEntityReactionById` | delete, destructive, entityReactions, generic, by-id, single-record, reaction |
| `findChildrenEntityReactionsByReactionId` | get, find, read-only, entityReactions, generic, hierarchical, children, collection, reaction |
| `createChildEntityReaction` | post, create, write, manage, entityReactions, generic, hierarchical, children, reaction |
| `findParentsByEntityReactionId` | get, find, read-only, entityReactions, generic, hierarchical, parents, collection, reaction |

##### List Reactions Controller _(11 routes)_

| Route ID | Tags |
|---|---|
| `createListReaction` | post, create, write, manage, listReactions, generic, reaction |
| `updateAllListReactions` | patch, update, update-all, bulk, write, manage, listReactions, generic, reaction |
| `findListReactions` | get, find, read-only, listReactions, generic, collection, reaction |
| `countListReactions` | get, count, read-only, listReactions, generic, reaction |
| `findListReactionById` | get, find, read-only, listReactions, generic, by-id, single-record, reaction |
| `updateListReactionById` | patch, update, write, manage, listReactions, generic, by-id, single-record, reaction |
| `replaceListReactionById` | put, replace, write, manage, listReactions, generic, by-id, single-record, reaction |
| `deleteListReactionById` | delete, destructive, listReactions, generic, by-id, single-record, reaction |
| `findChildrenListReactionsByReactionId` | get, find, read-only, listReactions, generic, hierarchical, children, collection, reaction |
| `createChildListReaction` | post, create, write, manage, listReactions, generic, hierarchical, children, reaction |
| `findParentsByListReactionId` | get, find, read-only, listReactions, generic, hierarchical, parents, collection, reaction |

#### Topology: Traversal ("Through") Controllers

##### Reactions Through Entity Controller _(4 routes)_

| Route ID | Tags |
|---|---|
| `createReactionByEntityId` | post, create, write, manage, entityReactions, reactionsThroughEntity, through, reaction, generic |
| `updateReactionsByEntityId` | patch, update, write, manage, entityReactions, reactionsThroughEntity, through, reaction, generic |
| `findReactionsByEntityId` | get, find, read-only, entityReactions, reactionsThroughEntity, through, collection, reaction, generic |
| `deleteReactionsByEntityId` | delete, destructive, entityReactions, reactionsThroughEntity, through, reaction, generic |

##### Reactions Through List Controller _(4 routes)_

| Route ID | Tags |
|---|---|
| `createReactionByListId` | post, create, write, manage, listReactions, reactionsThroughList, through, reaction, generic |
| `updateReactionsByListId` | patch, update, write, manage, listReactions, reactionsThroughList, through, reaction, generic |
| `findReactionsByListId` | get, find, read-only, listReactions, reactionsThroughList, through, collection, reaction, generic |
| `deleteReactionsByListId` | delete, destructive, listReactions, reactionsThroughList, through, reaction, generic |

##### Entities Through List Controller _(4 routes)_

| Route ID | Tags |
|---|---|
| `createEntityByListId` | post, create, write, manage, entities, entitiesThroughList, through, generic |
| `updateEntitiesByListId` | patch, update, write, manage, entities, entitiesThroughList, through, generic |
| `findEntitiesByListId` | get, find, read-only, entities, entitiesThroughList, through, collection, generic |
| `deleteEntitiesByListId` | delete, destructive, entities, entitiesThroughList, through, generic |

##### Lists Through Entity Controller _(1 route)_

| Route ID | Tags |
|---|---|
| `findListsByEntityId` | get, find, read-only, lists, listsThroughEntity, through, collection, generic |

---

### Context: Kind Alias Controllers

#### Topology: Root Controllers

##### Entity Kind Alias Routes _(13 routes)_

| Route ID | Tags |
|---|---|
| `createEntityByKindAlias` | post, create, write, manage, entities, entitiesKindAlias, kind-alias |
| `findAllEntitiesByKindAlias` | get, find, read-only, entities, entitiesKindAlias, kind-alias, collection |
| `countEntitiesByKindAlias` | get, count, read-only, entities, entitiesKindAlias, kind-alias |
| `updateAllEntitiesByKindAlias` | patch, update, update-all, bulk, write, manage, entities, entitiesKindAlias, kind-alias |
| `findEntityByIdByKindAlias` | get, find, read-only, entities, entitiesKindAlias, kind-alias, by-id, single-record |
| `updateEntityByIdByKindAlias` | patch, update, write, manage, entities, entitiesKindAlias, kind-alias, by-id, single-record |
| `replaceEntityByIdByKindAlias` | put, replace, write, manage, entities, entitiesKindAlias, kind-alias, by-id, single-record |
| `deleteEntityByIdByKindAlias` | delete, destructive, entities, entitiesKindAlias, kind-alias, by-id, single-record |
| `findEntityChildrenByKindAlias` | get, find, read-only, entities, entitiesKindAlias, kind-alias, hierarchical, children, collection |
| `createEntityChildByKindAlias` | post, create, write, manage, entities, entitiesKindAlias, kind-alias, hierarchical, children |
| `findEntityParentsByKindAlias` | get, find, read-only, entities, entitiesKindAlias, kind-alias, hierarchical, parents, collection |
| `findEntityHierarchyByKindAlias` | get, find, read-only, entities, entitiesKindAlias, kind-alias, hierarchical, domain-driven, collection |
| `createEntityHierarchyByKindAlias` | post, create, write, manage, entities, entitiesKindAlias, kind-alias, hierarchical, domain-driven |

##### List Kind Alias Routes _(13 routes)_

| Route ID | Tags |
|---|---|
| `createListByKindAlias` | post, create, write, manage, lists, listKindAlias, kind-alias |
| `findAllListsByKindAlias` | get, find, read-only, lists, listKindAlias, kind-alias, collection |
| `countListsByKindAlias` | get, count, read-only, lists, listKindAlias, kind-alias |
| `updateAllListsByKindAlias` | patch, update, update-all, bulk, write, manage, lists, listKindAlias, kind-alias |
| `findListByIdByKindAlias` | get, find, read-only, lists, listKindAlias, kind-alias, by-id, single-record |
| `updateListByIdByKindAlias` | patch, update, write, manage, lists, listKindAlias, kind-alias, by-id, single-record |
| `replaceListByIdByKindAlias` | put, replace, write, manage, lists, listKindAlias, kind-alias, by-id, single-record |
| `deleteListByIdByKindAlias` | delete, destructive, lists, listKindAlias, kind-alias, by-id, single-record |
| `findListChildrenByKindAlias` | get, find, read-only, lists, listKindAlias, kind-alias, hierarchical, children, collection |
| `createListChildByKindAlias` | post, create, write, manage, lists, listKindAlias, kind-alias, hierarchical, children |
| `findListParentsByKindAlias` | get, find, read-only, lists, listKindAlias, kind-alias, hierarchical, parents, collection |
| `findListHierarchyByKindAlias` | get, find, read-only, lists, listKindAlias, kind-alias, hierarchical, domain-driven, collection |
| `createListHierarchyByKindAlias` | post, create, write, manage, lists, listKindAlias, kind-alias, hierarchical, domain-driven |

##### Relation Kind Alias Routes _(8 routes)_

| Route ID | Tags |
|---|---|
| `createRelationByKindAlias` | post, create, write, manage, relations, relationsKindAlias, kind-alias |
| `findAllRelationsByKindAlias` | get, find, read-only, relations, relationsKindAlias, kind-alias, collection |
| `countRelationsByKindAlias` | get, count, read-only, relations, relationsKindAlias, kind-alias |
| `updateAllRelationsByKindAlias` | patch, update, update-all, bulk, write, manage, relations, relationsKindAlias, kind-alias |
| `findRelationByIdByKindAlias` | get, find, read-only, relations, relationsKindAlias, kind-alias, by-id, single-record |
| `updateRelationByIdByKindAlias` | patch, update, write, manage, relations, relationsKindAlias, kind-alias, by-id, single-record |
| `replaceRelationByIdByKindAlias` | put, replace, write, manage, relations, relationsKindAlias, kind-alias, by-id, single-record |
| `deleteRelationByIdByKindAlias` | delete, destructive, relations, relationsKindAlias, kind-alias, by-id, single-record |

##### Entity Reaction Kind Alias Routes _(13 routes)_

| Route ID | Tags |
|---|---|
| `createEntityReactionByKindAlias` | post, create, write, manage, entityReactions, entityReactionsKindAlias, kind-alias |
| `findAllEntityReactionsByKindAlias` | get, find, read-only, entityReactions, entityReactionsKindAlias, kind-alias, collection |
| `countEntityReactionsByKindAlias` | get, count, read-only, entityReactions, entityReactionsKindAlias, kind-alias |
| `updateAllEntityReactionsByKindAlias` | patch, update, update-all, bulk, write, manage, entityReactions, entityReactionsKindAlias, kind-alias |
| `findEntityReactionByIdByKindAlias` | get, find, read-only, entityReactions, entityReactionsKindAlias, kind-alias, by-id, single-record |
| `updateEntityReactionByIdByKindAlias` | patch, update, write, manage, entityReactions, entityReactionsKindAlias, kind-alias, by-id, single-record |
| `replaceEntityReactionByIdByKindAlias` | put, replace, write, manage, entityReactions, entityReactionsKindAlias, kind-alias, by-id, single-record |
| `deleteEntityReactionByIdByKindAlias` | delete, destructive, entityReactions, entityReactionsKindAlias, kind-alias, by-id, single-record |
| `findChildrenEntityReactionsByReactionIdByKindAlias` | get, find, read-only, entityReactions, entityReactionsKindAlias, kind-alias, hierarchical, children, collection |
| `createChildEntityReactionByKindAlias` | post, create, write, manage, entityReactions, entityReactionsKindAlias, kind-alias, hierarchical, children |
| `findParentsByEntityReactionIdByKindAlias` | get, find, read-only, entityReactions, entityReactionsKindAlias, kind-alias, hierarchical, parents, collection |
| `findEntityReactionHierarchyByKindAlias` | get, find, read-only, entityReactions, entityReactionsKindAlias, kind-alias, hierarchical, domain-driven, collection |
| `createEntityReactionHierarchyByKindAlias` | post, create, write, manage, entityReactions, entityReactionsKindAlias, kind-alias, hierarchical, domain-driven |

##### List Reaction Kind Alias Routes _(13 routes)_

| Route ID | Tags |
|---|---|
| `createListReactionByKindAlias` | post, create, write, manage, listReactions, listReactionsKindAlias, kind-alias |
| `findAllListReactionsByKindAlias` | get, find, read-only, listReactions, listReactionsKindAlias, kind-alias, collection |
| `countListReactionsByKindAlias` | get, count, read-only, listReactions, listReactionsKindAlias, kind-alias |
| `updateAllListReactionsByKindAlias` | patch, update, update-all, bulk, write, manage, listReactions, listReactionsKindAlias, kind-alias |
| `findListReactionByIdByKindAlias` | get, find, read-only, listReactions, listReactionsKindAlias, kind-alias, by-id, single-record |
| `updateListReactionByIdByKindAlias` | patch, update, write, manage, listReactions, listReactionsKindAlias, kind-alias, by-id, single-record |
| `replaceListReactionByIdByKindAlias` | put, replace, write, manage, listReactions, listReactionsKindAlias, kind-alias, by-id, single-record |
| `deleteListReactionByIdByKindAlias` | delete, destructive, listReactions, listReactionsKindAlias, kind-alias, by-id, single-record |
| `findChildrenListReactionsByReactionIdByKindAlias` | get, find, read-only, listReactions, listReactionsKindAlias, kind-alias, hierarchical, children, collection |
| `createChildListReactionByKindAlias` | post, create, write, manage, listReactions, listReactionsKindAlias, kind-alias, hierarchical, children |
| `findParentsByListReactionIdByKindAlias` | get, find, read-only, listReactions, listReactionsKindAlias, kind-alias, hierarchical, parents, collection |
| `findListReactionHierarchyByKindAlias` | get, find, read-only, listReactions, listReactionsKindAlias, kind-alias, hierarchical, domain-driven, collection |
| `createListReactionHierarchyByKindAlias` | post, create, write, manage, listReactions, listReactionsKindAlias, kind-alias, hierarchical, domain-driven |

#### Topology: Traversal ("Through") Controllers

##### Reactions Through Entity Kind Alias _(4 routes)_

| Route ID | Tags |
|---|---|
| `createReactionByEntityIdByKindAlias` | post, create, write, manage, entityReactions, reactionKindAliasThroughEntityKindAlias, kind-alias, through, reaction |
| `updateReactionsByEntityIdByKindAlias` | patch, update, write, manage, entityReactions, reactionKindAliasThroughEntityKindAlias, kind-alias, through, reaction |
| `findReactionsByEntityIdByKindAlias` | get, find, read-only, entityReactions, reactionKindAliasThroughEntityKindAlias, kind-alias, through, collection, reaction |
| `deleteReactionsByEntityIdByKindAlias` | delete, destructive, entityReactions, reactionKindAliasThroughEntityKindAlias, kind-alias, through, reaction |

##### Reactions Through List Kind Alias _(4 routes)_

| Route ID | Tags |
|---|---|
| `createReactionByListIdByKindAlias` | post, create, write, manage, listReactions, reactionKindAliasThroughListKindAlias, kind-alias, through, reaction |
| `updateReactionsByListIdByKindAlias` | patch, update, write, manage, listReactions, reactionKindAliasThroughListKindAlias, kind-alias, through, reaction |
| `findReactionsByListIdByKindAlias` | get, find, read-only, listReactions, reactionKindAliasThroughListKindAlias, kind-alias, through, collection, reaction |
| `deleteReactionsByListIdByKindAlias` | delete, destructive, listReactions, reactionKindAliasThroughListKindAlias, kind-alias, through, reaction |

##### Entities Through List Kind Alias _(4 routes)_

| Route ID | Tags |
|---|---|
| `createEntityByListIdByKindAlias` | post, create, write, manage, entities, entityKindAliasThroughListKindAlias, kind-alias, through |
| `updateEntitiesByListIdByKindAlias` | patch, update, write, manage, entities, entityKindAliasThroughListKindAlias, kind-alias, through |
| `findEntitiesByListIdByKindAlias` | get, find, read-only, entities, entityKindAliasThroughListKindAlias, kind-alias, through, collection |
| `deleteEntitiesByListIdByKindAlias` | delete, destructive, entities, entityKindAliasThroughListKindAlias, kind-alias, through |

##### Lists Through Entity Kind Alias _(1 route)_

| Route ID | Tags |
|---|---|
| `findListsByEntityIdByKindAlias` | get, find, read-only, lists, listKindAliasThroughEntityKindAlias, kind-alias, through, collection |

---

### Utility Controllers _(2 routes)_

| Route ID | Tags |
|---|---|
| `ping` | get, read-only, ping, utility, health-check |
| `explorer` | explorer, utility, api-discovery |
