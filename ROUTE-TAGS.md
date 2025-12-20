# Route Tagging Strategy

## Overview
This document describes the comprehensive tagging system applied to all routes in `application-routes.yml`. Tags enable logical grouping, filtering, and organization of the 89 routes across the API gateway.

## Tag Categories

### 1. HTTP Method Tags
Tags based on the HTTP verb used:
- `get` - GET requests
- `post` - POST requests  
- `put` - PUT requests
- `patch` - PATCH requests
- `delete` - DELETE requests

**Usage**: 89/89 routes (100%)

### 2. Operation Type Tags
Tags describing the primary operation:
- `find` - Retrieve/query operations (30 routes)
- `count` - Count operations (7 routes)
- `create` - Resource creation (16 routes)
- `update` - Resource modification (17 routes)
- `update-all` - Bulk update operations (7 routes)
- `replace` - Full resource replacement (7 routes)
- `delete` - Resource deletion (10 routes)

### 3. Access Pattern Tags
Tags describing read vs write access:
- `read-only` - GET operations that don't modify data (38 routes)
- `write` - POST, PUT, PATCH operations that modify data (40 routes)
- `destructive` - DELETE operations (10 routes)
- `manage` - Operations that create, update, or replace resources (combines create/update/replace)

### 4. Data Scope Tags
Tags for operation scope:
- `by-id` - Operations on a specific resource by ID (28 routes)
- `single-record` - Operations affecting one record
- `collection` - Operations on multiple records
- `bulk` - Bulk operations like updateAll (7 routes)

### 5. Record Type Tags
Tags matching the `recordType` metadata field:
- `entities` - Entity resources (26 routes)
- `lists` - List resources (23 routes)
- `relations` - Relation resources (8 routes)
- `entityReactions` - Entity reaction resources (15 routes)
- `listReactions` - List reaction resources (15 routes)

### 6. Controller Tags
Tags matching the `controllerName`:
- `entities` - Entity controller (11 routes)
- `lists` - List controller (11 routes)
- `relations` - Relation controller (8 routes)
- `entityReactions` - Entity reaction controller (11 routes)
- `listReactions` - List reaction controller (11 routes)
- `entitiesKindAlias` - Entity kind-alias controller (11 routes)
- `listKindAlias` - List kind-alias controller (11 routes)
- `entitiesThroughList` - Entities accessed through lists (4 routes)
- `listsThroughEntity` - Lists accessed through entities (1 route)
- `reactionsThroughEntity` - Reactions through entities (4 routes)
- `reactionsThroughList` - Reactions through lists (4 routes)
- `ping` - Ping utility (1 route)
- `explorer` - API explorer (1 route)

### 7. Architectural Pattern Tags
Tags for special routing patterns:
- `generic` - Direct routes without kind-alias (67 routes)
- `kind-alias` - Routes using kind-alias path resolution (22 routes)
- `through` - Routes accessing resources through parent resources (9 routes)

### 8. Hierarchical Tags
Tags for parent-child relationships:
- `hierarchical` - Routes dealing with hierarchies (12 routes)
- `children` - Routes operating on child resources
- `parents` - Routes operating on parent resources

### 9. Domain-Specific Tags
Tags for specific features:
- `reaction` - Routes for reaction features (26 routes)

### 10. Utility Tags
Tags for non-business routes:
- `utility` - Helper/system routes (2 routes)
- `health-check` - Health monitoring (ping)
- `api-discovery` - API documentation (explorer)

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
- Contains "updateAll" or "All" + "update" → `update-all` + `bulk` tags

### Rule 3: Access Pattern
Based on HTTP method:
- GET → `read-only`
- POST/PUT/PATCH → `write`
- DELETE → `destructive`
- create/update/replace operations → `manage`

### Rule 4: Record Type
The `recordType` from metadata is added as a tag.

### Rule 5: Controller
The `controllerName` from CheckIfRouteEnabled filter args is added as a tag.

### Rule 6: Architectural Pattern
- Route ID contains "KindAlias" → `kind-alias` tag
- Route ID contains "Through" or "ByListId" or "ByEntityId" → `through` tag
- Neither of above and not utility → `generic` tag

### Rule 7: Hierarchical
- Route ID contains "children" → `hierarchical` + `children` tags
- Route ID contains "parent" → `hierarchical` + `parents` tags

### Rule 8: By ID
- Route ID contains "ById" → `by-id` + `single-record` tags

### Rule 9: Domain-Specific
- Route ID contains "reaction" → `reaction` tag

### Rule 10: Utility
- Route ID is "ping" → `utility` + `health-check` tags
- Route ID is "explorer" → `utility` + `api-discovery` tags

## Example Tag Assignments

### createEntity
```yaml
tags:
  - post
  - create
  - write
  - manage
  - entities
  - entities  # controller
  - generic
```

### findEntityChildrenByKindAlias
```yaml
tags:
  - get
  - find
  - read-only
  - entities
  - entitiesKindAlias
  - kind-alias
  - hierarchical
  - children
```

### createReactionByListId
```yaml
tags:
  - post
  - create
  - write
  - manage
  - listReactions
  - reactionsThroughList
  - through
  - reaction
```

### updateAllEntities
```yaml
tags:
  - patch
  - update
  - update-all
  - bulk
  - write
  - manage
  - entities
  - entities  # controller
  - generic
  - collection
```

## Usage Patterns

### Query Examples

**Find all read-only routes:**
```
Filter by tag: read-only
Result: 38 routes
```

**Find all entity-related routes:**
```
Filter by tag: entities
Result: 26 routes (record type) + 11 routes (controller)
```

**Find all bulk operations:**
```
Filter by tag: bulk
Result: 7 routes
```

**Find all kind-alias routes:**
```
Filter by tag: kind-alias
Result: 22 routes
```

**Find all hierarchical operations:**
```
Filter by tag: hierarchical
Result: 12 routes
```

**Find all reaction-related operations:**
```
Filter by tag: reaction
Result: 26 routes
```

## Statistics

- **Total routes**: 89
- **Average tags per route**: ~6-8 tags
- **Most common tags**: get (38), read-only (38), entities (37), generic (67)
- **Rarest tags**: api-discovery (1), health-check (1), listsThroughEntity (1)

## Benefits

1. **Logical Grouping**: Easily find related routes (e.g., all read-only operations)
2. **Impact Analysis**: Identify affected routes when modifying features (e.g., all reaction routes)
3. **Access Control**: Apply policies to tag groups (e.g., restrict bulk operations)
4. **Monitoring**: Track metrics by operation type or resource
5. **Documentation**: Auto-generate API documentation by tag category
6. **Testing**: Target test suites at specific tag combinations
