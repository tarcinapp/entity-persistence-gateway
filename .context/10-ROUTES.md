# Entity Persistence Gateway - Available Routes

This document lists all available routes in the Entity Persistence Gateway, organized by their respective controllers.

> **Base URI**: `/api/v1/` (configurable via `app.inbound.baseUri`)

---

## Entity Controller

Routes for managing generic entities.

### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntity` | POST | `/api/v1/entities` | Create a new entity |
| `findEntities` | GET | `/api/v1/entities` | Find/list all entities |
| `countEntities` | GET | `/api/v1/entities/count` | Count entities matching criteria |
| `updateAllEntities` | PATCH | `/api/v1/entities` | Update multiple entities (disabled by default) |

### Single Entity Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityById` | GET | `/api/v1/entities/{recordId}` | Find a specific entity by ID |
| `updateEntityById` | PATCH | `/api/v1/entities/{recordId}` | Update a specific entity by ID |
| `replaceEntityById` | PUT | `/api/v1/entities/{recordId}` | Replace a specific entity by ID |
| `deleteEntityById` | DELETE | `/api/v1/entities/{recordId}` | Delete a specific entity by ID |

### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityChildren` | GET | `/api/v1/entities/{recordId}/children` | Find child entities of a specific entity |
| `createEntityChild` | POST | `/api/v1/entities/{recordId}/children` | Create a child entity under a specific entity |
| `findEntityParents` | GET | `/api/v1/entities/{recordId}/parents` | Find parent entities of a specific entity |

---

## List Controller

Routes for managing lists.

### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createList` | POST | `/api/v1/lists` | Create a new list |
| `findLists` | GET | `/api/v1/lists` | Find/list all lists |
| `countLists` | GET | `/api/v1/lists/count` | Count lists matching criteria |
| `updateAllLists` | PATCH | `/api/v1/lists` | Update multiple lists (disabled by default) |

### Single List Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListById` | GET | `/api/v1/lists/{recordId}` | Find a specific list by ID |
| `updateListById` | PATCH | `/api/v1/lists/{recordId}` | Update a specific list by ID |
| `replaceListById` | PUT | `/api/v1/lists/{recordId}` | Replace a specific list by ID |
| `deleteListById` | DELETE | `/api/v1/lists/{recordId}` | Delete a specific list by ID |

### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListChildren` | GET | `/api/v1/lists/{recordId}/children` | Find child lists of a specific list |
| `createListChild` | POST | `/api/v1/lists/{recordId}/children` | Create a child list under a specific list |
| `findListParents` | GET | `/api/v1/lists/{recordId}/parents` | Find parent lists of a specific list |

---

## Relation Controller

Routes for managing relations between entities.

### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createRelation` | POST | `/api/v1/relations` | Create a new relation |
| `findRelations` | GET | `/api/v1/relations` | Find/list all relations |
| `countRelations` | GET | `/api/v1/relations/count` | Count relations matching criteria |
| `updateAllRelations` | PATCH | `/api/v1/relations` | Update multiple relations |

### Single Relation Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findRelationById` | GET | `/api/v1/relations/{recordId}` | Find a specific relation by ID |
| `updateRelationById` | PATCH | `/api/v1/relations/{recordId}` | Update a specific relation by ID |
| `replaceRelationById` | PUT | `/api/v1/relations/{recordId}` | Replace a specific relation by ID |
| `deleteRelationById` | DELETE | `/api/v1/relations/{recordId}` | Delete a specific relation by ID |

---

## Entity Reactions Controller

Routes for managing reactions to entities.

### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntityReaction` | POST | `/api/v1/entity-reactions` | Create a new entity reaction |
| `findEntityReactions` | GET | `/api/v1/entity-reactions` | Find/list all entity reactions |
| `countEntityReactions` | GET | `/api/v1/entity-reactions/count` | Count entity reactions matching criteria |
| `updateAllEntityReactions` | PATCH | `/api/v1/entity-reactions` | Update multiple entity reactions |

### Single Entity Reaction Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityReactionById` | GET | `/api/v1/entity-reactions/{recordId}` | Find a specific entity reaction by ID |
| `updateEntityReactionById` | PATCH | `/api/v1/entity-reactions/{recordId}` | Update a specific entity reaction by ID |
| `replaceEntityReactionById` | PUT | `/api/v1/entity-reactions/{recordId}` | Replace a specific entity reaction by ID |
| `deleteEntityReactionById` | DELETE | `/api/v1/entity-reactions/{recordId}` | Delete a specific entity reaction by ID |

### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findChildrenEntityReactionsByReactionId` | GET | `/api/v1/entity-reactions/{recordId}/children` | Find child reactions of a specific entity reaction |
| `createChildEntityReaction` | POST | `/api/v1/entity-reactions/{recordId}/children` | Create a child reaction under a specific entity reaction |
| `findParentsByEntityReactionId` | GET | `/api/v1/entity-reactions/{recordId}/parents` | Find parent reactions of a specific entity reaction |

---

## Reactions Through Entity Controller

Routes for managing reactions through entity context.

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createReactionByEntityId` | POST | `/api/v1/entities/{recordId}/reactions` | Create a reaction for a specific entity |
| `findReactionsByEntityId` | GET | `/api/v1/entities/{recordId}/reactions` | Find all reactions for a specific entity |
| `updateReactionsByEntityId` | PATCH | `/api/v1/entities/{recordId}/reactions` | Update reactions for a specific entity |
| `deleteReactionsByEntityId` | DELETE | `/api/v1/entities/{recordId}/reactions` | Delete reactions for a specific entity |

### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findChildrenEntityReactionsByReactionId` | GET | `/api/v1/entity-reactions/{recordId}/children` | Find child reactions of a specific entity reaction |
| `createChildEntityReaction` | POST | `/api/v1/entity-reactions/{recordId}/children` | Create a child reaction under a specific entity reaction |
| `findParentsByEntityReactionId` | GET | `/api/v1/entity-reactions/{recordId}/parents` | Find parent reactions of a specific entity reaction |

---

## List Reactions Controller

Routes for managing reactions to lists.

### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createListReaction` | POST | `/api/v1/list-reactions` | Create a new list reaction |
| `findListReactions` | GET | `/api/v1/list-reactions` | Find/list all list reactions |
| `countListReactions` | GET | `/api/v1/list-reactions/count` | Count list reactions matching criteria |
| `updateAllListReactions` | PATCH | `/api/v1/list-reactions` | Update multiple list reactions |

### Single List Reaction Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListReactionById` | GET | `/api/v1/list-reactions/{recordId}` | Find a specific list reaction by ID |
| `updateListReactionById` | PATCH | `/api/v1/list-reactions/{recordId}` | Update a specific list reaction by ID |
| `replaceListReactionById` | PUT | `/api/v1/list-reactions/{recordId}` | Replace a specific list reaction by ID |
| `deleteListReactionById` | DELETE | `/api/v1/list-reactions/{recordId}` | Delete a specific list reaction by ID |

### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findChildrenListReactionsByReactionId` | GET | `/api/v1/list-reactions/{recordId}/children` | Find child reactions of a specific list reaction |
| `createChildListReaction` | POST | `/api/v1/list-reactions/{recordId}/children` | Create a child reaction under a specific list reaction |
| `findParentsByListReactionId` | GET | `/api/v1/list-reactions/{recordId}/parents` | Find parent reactions of a specific list reaction |

---

## Reactions Through List Controller

Routes for managing reactions through list context.

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createReactionByListId` | POST | `/api/v1/lists/{recordId}/reactions` | Create a reaction for a specific list |
| `findReactionsByListId` | GET | `/api/v1/lists/{recordId}/reactions` | Find all reactions for a specific list |
| `updateReactionsByListId` | PATCH | `/api/v1/lists/{recordId}/reactions` | Update reactions for a specific list |
| `deleteReactionsByListId` | DELETE | `/api/v1/lists/{recordId}/reactions` | Delete reactions for a specific list |

---

## Entities Through List Controller

Routes for managing entities through list context.

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntityByListId` | POST | `/api/v1/lists/{recordId}/entities` | Create an entity associated with a specific list |
| `findEntitiesByListId` | GET | `/api/v1/lists/{recordId}/entities` | Find all entities associated with a specific list |
| `updateEntitiesByListId` | PATCH | `/api/v1/lists/{recordId}/entities` | Update entities associated with a specific list |
| `deleteEntitiesByListId` | DELETE | `/api/v1/lists/{recordId}/entities` | Delete entities associated with a specific list |

---

## Lists Through Entity Controller

Routes for managing lists through entity context.

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListsByEntityId` | GET | `/api/v1/entities/{recordId}/lists` | Find all lists associated with a specific entity |

---

## Kind Alias Mapping Controller

Routes for entity management using kind-based paths (configurable via `app.kindAliasPaths`).

### Entity Kind Alias Routes

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntityByKindAlias` | POST | `/api/v1/entities/{kindAlias}` | Create a new entity of a specific kind |
| `findAllEntitiesByKindAlias` | GET | `/api/v1/entities/{kindAlias}` | Find/list all entities of a specific kind |
| `countEntitiesByKindAlias` | GET | `/api/v1/entities/{kindAlias}/count` | Count entities of a specific kind |
| `updateAllEntitiesByKindAlias` | PATCH | `/api/v1/entities/{kindAlias}` | Update multiple entities of a specific kind (disabled by default) |

#### Single Entity Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityByIdByKindAlias` | GET | `/api/v1/entities/{kindAlias}/{recordId}` | Find a specific entity by ID within a kind alias |
| `updateEntityByIdByKindAlias` | PATCH | `/api/v1/entities/{kindAlias}/{recordId}` | Update a specific entity by ID within a kind alias |
| `replaceEntityByIdByKindAlias` | PUT | `/api/v1/entities/{kindAlias}/{recordId}` | Replace a specific entity by ID within a kind alias |
| `deleteEntityByIdByKindAlias` | DELETE | `/api/v1/entities/{kindAlias}/{recordId}` | Delete a specific entity by ID within a kind alias |

#### Hierarchical Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityChildrenByKindAlias` | GET | `/api/v1/entities/{kindAlias}/{recordId}/children` | Find child entities of a specific entity within a kind alias |
| `createEntityChildByKindAlias` | POST | `/api/v1/entities/{kindAlias}/{recordId}/children` | Create a child entity under a specific entity within a kind alias |
| `findEntityParentsByKindAlias` | GET | `/api/v1/entities/{kindAlias}/{recordId}/parents` | Find parent entities of a specific entity within a kind alias |

#### Dynamic Hierarchy Routes by Kind Alias (Domain-Driven URLs)

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityHierarchyByKindAlias` | GET | `/api/v1/entities/{kindAlias}/{recordId}/{hierarchyAlias}` | Find child or parent entities using domain-driven hierarchy alias |
| `createEntityHierarchyByKindAlias` | POST | `/api/v1/entities/{kindAlias}/{recordId}/{hierarchyAlias}` | Create child entity using domain-driven hierarchy alias |

### List Kind Alias Routes

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createListByKindAlias` | POST | `/api/v1/lists/{kindAlias}` | Create a new list of a specific kind |
| `findAllListsByKindAlias` | GET | `/api/v1/lists/{kindAlias}` | Find/list all lists of a specific kind |
| `countListsByKindAlias` | GET | `/api/v1/lists/{kindAlias}/count` | Count lists of a specific kind |
| `updateAllListsByKindAlias` | PATCH | `/api/v1/lists/{kindAlias}` | Update multiple lists of a specific kind (disabled by default) |

#### Single List Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListByIdByKindAlias` | GET | `/api/v1/lists/{kindAlias}/{recordId}` | Find a specific list by ID within a kind alias |
| `updateListByIdByKindAlias` | PATCH | `/api/v1/lists/{kindAlias}/{recordId}` | Update a specific list by ID within a kind alias |
| `replaceListByIdByKindAlias` | PUT | `/api/v1/lists/{kindAlias}/{recordId}` | Replace a specific list by ID within a kind alias |
| `deleteListByIdByKindAlias` | DELETE | `/api/v1/lists/{kindAlias}/{recordId}` | Delete a specific list by ID within a kind alias |

#### Hierarchical Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListChildrenByKindAlias` | GET | `/api/v1/lists/{kindAlias}/{recordId}/children` | Find child lists of a specific list within a kind alias |
| `createListChildByKindAlias` | POST | `/api/v1/lists/{kindAlias}/{recordId}/children` | Create a child list under a specific list within a kind alias |
| `findListParentsByKindAlias` | GET | `/api/v1/lists/{kindAlias}/{recordId}/parents` | Find parent lists of a specific list within a kind alias |

#### Dynamic Hierarchy Routes by Kind Alias (Domain-Driven URLs)

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListHierarchyByKindAlias` | GET | `/api/v1/lists/{kindAlias}/{recordId}/{hierarchyAlias}` | Find child or parent lists using domain-driven hierarchy alias |
| `createListHierarchyByKindAlias` | POST | `/api/v1/lists/{kindAlias}/{recordId}/{hierarchyAlias}` | Create child list using domain-driven hierarchy alias |

### Relation Kind Alias Routes

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createRelationByKindAlias` | POST | `/api/v1/relations/{kindAlias}` | Create a new relation of a specific kind |
| `findAllRelationsByKindAlias` | GET | `/api/v1/relations/{kindAlias}` | Find/list all relations of a specific kind |
| `countRelationsByKindAlias` | GET | `/api/v1/relations/{kindAlias}/count` | Count relations of a specific kind |
| `updateAllRelationsByKindAlias` | PATCH | `/api/v1/relations/{kindAlias}` | Update multiple relations of a specific kind |

#### Single Relation Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findRelationByIdByKindAlias` | GET | `/api/v1/relations/{kindAlias}/{recordId}` | Find a specific relation by ID within a kind alias |
| `updateRelationByIdByKindAlias` | PATCH | `/api/v1/relations/{kindAlias}/{recordId}` | Update a specific relation by ID within a kind alias |
| `replaceRelationByIdByKindAlias` | PUT | `/api/v1/relations/{kindAlias}/{recordId}` | Replace a specific relation by ID within a kind alias |
| `deleteRelationByIdByKindAlias` | DELETE | `/api/v1/relations/{kindAlias}/{recordId}` | Delete a specific relation by ID within a kind alias |

### Entity Reaction Kind Alias Routes

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntityReactionByKindAlias` | POST | `/api/v1/entity-reactions/{kindAlias}` | Create a new entity reaction of a specific kind |
| `findAllEntityReactionsByKindAlias` | GET | `/api/v1/entity-reactions/{kindAlias}` | Find/list all entity reactions of a specific kind |
| `countEntityReactionsByKindAlias` | GET | `/api/v1/entity-reactions/{kindAlias}/count` | Count entity reactions of a specific kind |
| `updateAllEntityReactionsByKindAlias` | PATCH | `/api/v1/entity-reactions/{kindAlias}` | Update multiple entity reactions of a specific kind |

#### Single Entity Reaction Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityReactionByIdByKindAlias` | GET | `/api/v1/entity-reactions/{kindAlias}/{recordId}` | Find a specific entity reaction by ID within a kind alias |
| `updateEntityReactionByIdByKindAlias` | PATCH | `/api/v1/entity-reactions/{kindAlias}/{recordId}` | Update a specific entity reaction by ID within a kind alias |
| `replaceEntityReactionByIdByKindAlias` | PUT | `/api/v1/entity-reactions/{kindAlias}/{recordId}` | Replace a specific entity reaction by ID within a kind alias |
| `deleteEntityReactionByIdByKindAlias` | DELETE | `/api/v1/entity-reactions/{kindAlias}/{recordId}` | Delete a specific entity reaction by ID within a kind alias |

#### Hierarchical Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findChildrenEntityReactionsByReactionIdByKindAlias` | GET | `/api/v1/entity-reactions/{kindAlias}/{recordId}/children` | Find child reactions of a specific entity reaction within a kind alias |
| `createChildEntityReactionByKindAlias` | POST | `/api/v1/entity-reactions/{kindAlias}/{recordId}/children` | Create a child reaction under a specific entity reaction within a kind alias |
| `findParentsByEntityReactionIdByKindAlias` | GET | `/api/v1/entity-reactions/{kindAlias}/{recordId}/parents` | Find parent reactions of a specific entity reaction within a kind alias |

#### Dynamic Hierarchy Routes by Kind Alias (Domain-Driven URLs)

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityReactionHierarchyByKindAlias` | GET | `/api/v1/entity-reactions/{kindAlias}/{recordId}/{hierarchyAlias}` | Find child or parent entity reactions using domain-driven hierarchy alias |
| `createEntityReactionHierarchyByKindAlias` | POST | `/api/v1/entity-reactions/{kindAlias}/{recordId}/{hierarchyAlias}` | Create child entity reaction using domain-driven hierarchy alias |

### List Reaction Kind Alias Routes

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createListReactionByKindAlias` | POST | `/api/v1/list-reactions/{kindAlias}` | Create a new list reaction of a specific kind |
| `findAllListReactionsByKindAlias` | GET | `/api/v1/list-reactions/{kindAlias}` | Find/list all list reactions of a specific kind |
| `countListReactionsByKindAlias` | GET | `/api/v1/list-reactions/{kindAlias}/count` | Count list reactions of a specific kind |
| `updateAllListReactionsByKindAlias` | PATCH | `/api/v1/list-reactions/{kindAlias}` | Update multiple list reactions of a specific kind |

#### Single List Reaction Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListReactionByIdByKindAlias` | GET | `/api/v1/list-reactions/{kindAlias}/{recordId}` | Find a specific list reaction by ID within a kind alias |
| `updateListReactionByIdByKindAlias` | PATCH | `/api/v1/list-reactions/{kindAlias}/{recordId}` | Update a specific list reaction by ID within a kind alias |
| `replaceListReactionByIdByKindAlias` | PUT | `/api/v1/list-reactions/{kindAlias}/{recordId}` | Replace a specific list reaction by ID within a kind alias |
| `deleteListReactionByIdByKindAlias` | DELETE | `/api/v1/list-reactions/{kindAlias}/{recordId}` | Delete a specific list reaction by ID within a kind alias |

#### Hierarchical Operations by Kind Alias

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findChildrenListReactionsByReactionIdByKindAlias` | GET | `/api/v1/list-reactions/{kindAlias}/{recordId}/children` | Find child reactions of a specific list reaction within a kind alias |
| `createChildListReactionByKindAlias` | POST | `/api/v1/list-reactions/{kindAlias}/{recordId}/children` | Create a child reaction under a specific list reaction within a kind alias |
| `findParentsByListReactionIdByKindAlias` | GET | `/api/v1/list-reactions/{kindAlias}/{recordId}/parents` | Find parent reactions of a specific list reaction within a kind alias |

#### Dynamic Hierarchy Routes by Kind Alias (Domain-Driven URLs)

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListReactionHierarchyByKindAlias` | GET | `/api/v1/list-reactions/{kindAlias}/{recordId}/{hierarchyAlias}` | Find child or parent list reactions using domain-driven hierarchy alias |
| `createListReactionHierarchyByKindAlias` | POST | `/api/v1/list-reactions/{kindAlias}/{recordId}/{hierarchyAlias}` | Create child list reaction using domain-driven hierarchy alias |

---

## Ping Controller

Health check and connectivity testing.

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `ping` | GET | `/api/v1/ping` | Ping endpoint for health checks |

---

## Explorer Controller

API exploration endpoint (currently disabled).

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `explorer` | * | `/api/v1/explorer` | API explorer endpoint (returns 404) |

---

## Notes

### Record ID Format

All routes that accept a `{recordId}` parameter expect a UUID in the format:
```
[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}
```

### Configuration

- **Base URI** is configurable via `app.inbound.baseUri` (default: `/api/v1/`).
- **Controller base paths** are configurable via:
  - `app.inbound.controllerBasePaths.entities` (default: `entities`)
  - `app.inbound.controllerBasePaths.lists` (default: `lists`)
  - `app.inbound.controllerBasePaths.relations` (default: `relations`)
  - `app.inbound.controllerBasePaths.entityReactions` (default: `entity-reactions`)

### Include Alias Projection

- Query routes using include filters now support domain aliases in `filter[include][...][relation]`.
- Request-side normalization is handled by `ConvertDomainIncludeAliasToGenericRelation` before `AddSetsTo*` scoping filters.
- Response-side projection is handled by `ProjectDomainIncludeAliasInResponse` before final `FieldFilter` output filtering.
- Example behavior: caller sends `filter[include][0][relation]=books`, backend receives generic relation (`_entities`) plus include `_kind` scope, caller receives response with `books` field instead of `_entities`.
  - `app.inbound.controllerBasePaths.listReactions` (default: `list-reactions`)
  - `app.inbound.controllerBasePaths.reactionsThroughEntity` (default: `reactions`)
  - `app.inbound.controllerBasePaths.reactionsThroughList` (default: `reactions`)
  - `app.inbound.controllerBasePaths.explorer` (default: `explorer`)
- **Hierarchy accessor segments** are configurable via:
  - `app.inbound.controllerBasePaths.defaultChildrenAccessor` (default: `children`)
  - `app.inbound.controllerBasePaths.defaultParentsAccessor` (default: `parents`)
  - Per-controller overrides (e.g., `entitiesChildrenAccessor`, `listsParentsAccessor`, `entityReactionsChildrenAccessor`)
