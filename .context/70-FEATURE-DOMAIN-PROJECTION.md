# Domain Projection: Transforming Generic APIs into Business-Specific Endpoints

## 1. FEATURE IDENTITY & PURPOSE

**Domain Projection** is the gateway's core capability that transforms the generic data contract exposed by `entity-persistence-service` into business-specific REST APIs at runtime—without writing backend code.

### The Hard Problem Solved

The Tarcinapp ecosystem provides a **generic data contract** with five record types (`entities`, `lists`, `relations`, `entityReactions`, `listReactions`) and 67 REST endpoints. While this contract handles persistence, security, and complex relational operations, it speaks in technical terms:

```
POST /entities                          # Creates what?
GET  /entities?filter[where][_kind]=X   # What is X?
GET  /entities/{id}/children            # Children of what type?
```

For API consumers—frontend developers, mobile teams, third-party integrators—this is unusable. They need:

```
POST /products                          # Clear intent
GET  /orders?status=pending             # Domain language
GET  /books/{id}/chapters               # Intuitive hierarchy
```

**Domain Projection** bridges this gap. It creates a **virtual API layer** that:
- Exposes domain-specific endpoints (`/products`, `/orders`, `/books`)
- Automatically injects `_kind` values into requests
- Rewrites queries to filter by the appropriate kind
- Resolves hierarchical relationships into nested paths
- Applies domain-specific validation schemas
- Enforces route-level toggles and configurations

### Core Philosophy

1. **Configuration Over Code:** New business domains are exposed entirely through configuration. No Java code, no redeployment of the backend.

2. **Domain-Specific Validation:** Each kind alias can define its own JSON Schema for request validation. A `products` alias validates differently than an `orders` alias—enforcing domain rules at the gateway layer.

3. **Schema Override by Specificity:** When finer control is needed, schemas defined at the kind level can be overridden at more specific levels (per-route, per-hierarchy). The most specific schema always wins.

4. **Transparency to Backend:** The backend service remains unaware of domain projection. It receives standard requests with `_kind` filters—the gateway handles all translation.

5. **Composable Configuration:** Route toggles, schemas, timeouts, rate limits, and other behaviors can be configured independently per kind alias, per route, or per hierarchy level.

---

## 2. CORE CONCEPTS

### 2.1 Kind Aliases

A **Kind Alias** maps a domain-specific path segment to a `_kind` value in the generic backend.

| Configuration | Effect |
|--------------|--------|
| `alias: products` | Exposes `/products` endpoint |
| `kind: product` | Maps to `_kind=product` in backend |

**What Happens at Runtime:**

```
Client Request:                    Gateway Transforms To:
─────────────────                  ──────────────────────
POST /products                  →  POST /entities
     {"name": "Widget"}               {"_kind": "product", "name": "Widget"}

GET /products                   →  GET /entities?filter[where][_kind]=product

GET /products/abc123            →  GET /entities/abc123
                                      (with kind verification)
```

### 2.2 Controller Types

The gateway supports aliases for all five record types:

| Controller | Generic Path | Use Case |
|------------|-------------|----------|
| `entities` | `/entities` | Primary domain objects (Products, Orders, Users) |
| `lists` | `/lists` | Collections and groupings (Categories, Folders, Playlists) |
| `relations` | `/relations` | Directed links between records (Follows, Assignments) |
| `entityReactions` | `/entity-reactions` | User interactions on entities (Likes, Reviews) |
| `listReactions` | `/list-reactions` | User interactions on lists (Comments, Ratings) |

### 2.3 Hierarchical Kind Aliases

Records in Tarcinapp can have parent-child relationships. **Hierarchical Kind Aliases** expose these as nested REST paths.

**Configuration:**
```yaml
aliases:
  - alias: books
    kind: book
    children:
      - alias: chapters
        kind: chapter
```

**Generated Paths:**
```
GET    /books/{id}/chapters      # List chapters of a book
POST   /books/{id}/chapters      # Create chapter under a book
GET    /books/{id}/parents       # List parents of a book (if any)
```

**What Happens at Runtime:**

```
Client Request:                         Gateway Transforms To:
─────────────────                       ──────────────────────
POST /books/abc/chapters             →  POST /entities/abc/children
     {"_name": "Chapter 1"}                {"_kind": "chapter", "_name": "Chapter 1"}

GET /books/abc/chapters              →  GET /entities/abc/children
                                           ?filter[where][_kind]=chapter
```

### 2.4 Through Kind Aliases

**Through Kind Aliases** expose cross-controller access patterns as part of domain-specific paths. They let clients access associated records of a *different record type* through an existing domain record, without knowing the generic backend structure.

This is distinct from **Hierarchical Kind Aliases** (§2.3):
- **Hierarchy (`children`/`parents`):** Same controller type, parent-child tree within the same record space (e.g. entities → child entities of a different kind)
- **Through:** Cross-controller traversal to a fundamentally different record type (entities → reactions, lists → entities, etc.)

#### Supported Through Relationships

The `through` key can contain up to three sub-lists depending on the parent controller:

| Parent Controller | `through` sub-key | URL segment (see `app-inbound.yml`) | Target record type |
|------------------|-------------------|-------------------------------------|--------------------|
| `entities` | `reactions` | `reactionsThroughEntity` (default: `reactions`) | Entity reactions |
| `entities` | `lists` | `listsThroughEntity` (default: same as `lists` base) | Lists containing this entity |
| `lists` | `reactions` | `reactionsThroughList` (default: `reactions`) | List reactions |
| `lists` | `entities` | `entitiesThroughList` (default: same as `entities` base) | Entities within this list |

#### Configuration Example

```yaml
app:
  oas:
    controllers:
      entities:
        aliases:
          - alias: books
            kind: book
            through:
              reactions:
                - alias: reviews
                  kind: review
                  description: Reader reviews for a book
                  schema: |
                    {
                      "type": "object",
                      "properties": {
                        "rating": {"type": "integer", "minimum": 1, "maximum": 5},
                        "comment": {"type": "string"}
                      },
                      "required": ["rating"]
                    }
                  routes:
                    createReactionByEntityId:
                      # Through-route schema overrides through-level schema for POST
                      schema: |
                        {
                          "type": "object",
                          "properties": {
                            "rating": {"type": "integer", "minimum": 1, "maximum": 5},
                            "comment": {"type": "string"}
                          },
                          "required": ["rating", "comment"]
                        }
              lists:
                - alias: shelves
                  kind: bookshelf
                  description: Bookshelves that contain this book (GET only)
      lists:
        aliases:
          - alias: bookshelves
            kind: bookshelf
            through:
              entities:
                - alias: books
                  kind: book
              reactions:
                - alias: comments
                  kind: comment
```

**Generated Paths (entities controller, `books` alias):**
```
POST   /api/v1/entities/books/{id}/reactions/reviews    # Create a review for a book
GET    /api/v1/entities/books/{id}/reactions/reviews    # List reviews for a book
PATCH  /api/v1/entities/books/{id}/reactions/reviews    # Bulk-update reviews
DELETE /api/v1/entities/books/{id}/reactions/reviews    # Bulk-delete reviews
GET    /api/v1/entities/books/{id}/lists/shelves        # List bookshelves containing this book
```

**What Happens at Runtime:**
```
Client Request:                                     Gateway Transforms To:
──────────────────────────────────────────          ─────────────────────────────────────────────
POST /books/abc/reactions/reviews               →   POST /entities/abc/reactions
     {"rating": 5, "comment": "Great!"}                 {"_kind": "review", "rating": 5, "comment": "Great!"}

GET  /books/abc/reactions/reviews               →   GET  /entities/abc/reactions
                                                         ?filter[where][_kind]=review

GET  /books/abc/lists/shelves                   →   GET  /entities/abc/lists
                                                         ?filter[where][_kind]=bookshelf
```

#### Through Schema Priority

Schemas for through requests are resolved in this order (highest to lowest):

1. **Through-route schema** (most specific) — defined in `through.<type>[].routes.<routeId>.schema`
2. **Through-level schema** — defined in `through.<type>[].schema`
3. **Kind-level schema** — fallback from the through alias kind's own schema definition

#### Available Through Route IDs

Use these route IDs (without the `ByKindAlias` suffix) in `through.<type>[].routes.<routeId>`:

| through type on `entities` | Available route IDs |
|---------------------------|---------------------|
| `reactions` | `createReactionByEntityId`, `updateReactionsByEntityId`, `findReactionsByEntityId`, `deleteReactionsByEntityId` |
| `lists` | `findListsByEntityId` (GET only — no write routes) |

| through type on `lists` | Available route IDs |
|------------------------|---------------------|
| `reactions` | `createReactionByListId`, `updateReactionsByListId`, `findReactionsByListId`, `deleteReactionsByListId` |
| `entities` | `createEntityByListId`, `updateEntitiesByListId`, `findEntitiesByListId`, `deleteEntitiesByListId` |

#### Properties Format

```properties
# entities alias with through.reactions
app.oas.controllers.entities.aliases[0].through.reactions[0].alias=reviews
app.oas.controllers.entities.aliases[0].through.reactions[0].kind=review
app.oas.controllers.entities.aliases[0].through.reactions[0].schema={"type":"object",...}
app.oas.controllers.entities.aliases[0].through.reactions[0].routes.createReactionByEntityId.schema={"type":"object",...}

# entities alias with through.lists (GET only)
app.oas.controllers.entities.aliases[0].through.lists[0].alias=shelves
app.oas.controllers.entities.aliases[0].through.lists[0].kind=bookshelf

# lists alias with through.entities
app.oas.controllers.lists.aliases[0].through.entities[0].alias=books
app.oas.controllers.lists.aliases[0].through.entities[0].kind=book

# lists alias with through.reactions
app.oas.controllers.lists.aliases[0].through.reactions[0].alias=comments
app.oas.controllers.lists.aliases[0].through.reactions[0].kind=comment
```

---

### 2.5 Route Toggles

Routes can be enabled or disabled dynamically without affecting the backend.

**Configuration:**
```yaml
app:
  toggles:
    routes:
      off:
        - deleteEntityById           # Disable delete across all controllers
        - deleteEntityByIdByKindAlias
    controllers:
      off:
        - relations                  # Disable entire relations controller
    tags:
      off:
        - destructive                # Disable all destructive (delete) routes
```

Routes listed in `off` are disabled; routes listed in `on` are explicitly enabled. This allows feature flagging, security hardening, and domain scoping.

> **See Also:** [75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md) for detailed toggle configuration and precedence rules.

### 2.6 Request Validation with JSON Schema

Kind aliases can define **JSON Schemas** to validate incoming request bodies. The gateway validates requests before forwarding them to the backend, returning `400 Bad Request` if validation fails.

**Configuration:**
```yaml
aliases:
  - alias: products
    kind: product
    schema: |
      {
        "type": "object",
        "properties": {
          "_name": {"type": "string", "minLength": 1},
          "price": {"type": "number", "minimum": 0}
        },
        "required": ["_name"]
      }
```

With this configuration, all write operations (`POST`, `PUT`, `PATCH`) to `/products` are validated against this schema.

#### Overriding Schema for Specific Operations

Sometimes different operations need different validation rules. For example, creating a product may require more fields than updating one. You can override the kind-level schema at the **route level**:

```yaml
aliases:
  - alias: products
    kind: product
    schema: |
      {"type": "object", "properties": {"_name": {"type": "string"}}, "required": ["_name"]}
    
    routes:
      createEntity:
        schema: |
          {"type": "object", "properties": {"_name": {"type": "string"}, "sku": {"type": "string"}}, "required": ["_name", "sku"]}
```

- `POST /products` → Validates against route-level schema (requires `_name` AND `sku`)
- `PATCH /products/{id}` → Validates against kind-level schema (requires `_name` only)

The route-level schema always takes precedence when defined.

### 2.7 Domain-Specific Include Projection

Domain projection also supports domain language for backend include relations.

**Request-side projection:**
- Caller can use domain alias in include relation, for example:
  `GET /bookshelves?filter[include][0][relation]=books`
- Gateway rewrites include relation to backend generic relation and injects include-level `_kind` scope:
  `filter[include][0][relation]=_entities&filter[include][0][scope][where][_kind]=book`
- If include scope already has `where`, gateway merges safely using `and` to avoid overriding caller constraints.

**Response-side projection:**
- If include alias mapping was applied on request, gateway remaps response include field names back to the requested domain alias.
- Example: backend returns `_entities`; caller receives `books`.

**Filter chain intent:**
- Request normalization runs before query scoping (`AddSetsTo*`) so downstream filters operate on normalized generic query format.
- Response projection runs in response modifier stage before final field filtering output.

#### Hierarchical Path Schemas

For hierarchical paths like `/books/{id}/chapters`, schemas can be defined at multiple levels:

1. **Hierarchy-route schema** (most specific) — for a specific operation like `POST /books/{id}/chapters`
2. **Hierarchy-level schema** — default for all operations on the hierarchy path
3. **Kind-level schema** — fallback from the child kind's definition

The first schema found in this order is used.

---

## 3. CONFIGURATION

### 3.1 Configuration Files

Domain projection is configured through several files, all imported via `application.yml`:

| File | Purpose |
|------|---------|
| `app-oas.yml` | Kind aliases, schemas, hierarchies, route customizations |
| `application-route-toggles.yml` | Enable/disable specific routes per alias |
| `application-routes.yml` | Controller base paths and generic route definitions |

### 3.2 Kind Alias Configuration

#### YAML Format (Primary Configuration)

```yaml
app:
  oas:
    controllers:
      entities:
        aliases:
          # Simple alias
          - alias: products
            kind: product
            description: Product catalog management
            singular: product          # Optional: explicit singular form for operationId generation
            
            # Kind-level schema (applies to all operations unless overridden)
            schema: |
              {
                "type": "object",
                "properties": {
                  "_name": {"type": "string", "minLength": 1},
                  "sku": {"type": "string"},
                  "price": {"type": "number", "minimum": 0}
                },
                "required": ["_name"]
              }
            
            # Route-level customizations
            routes:
              createEntity:
                operationId: createProduct
                summary: Create a new product
                tags:
                  - Products
                # Route-level schema overrides kind-level
                schema: |
                  {
                    "type": "object",
                    "properties": {
                      "_name": {"type": "string", "minLength": 1},
                      "sku": {"type": "string", "pattern": "^[A-Z]{3}-[0-9]{4}$"},
                      "price": {"type": "number", "minimum": 0}
                    },
                    "required": ["_name", "sku", "price"]
                  }
          
          # Alias with hierarchical relationships
          - alias: books
            kind: book
            description: Book management
            schema: |
              {
                "type": "object",
                "properties": {
                  "_name": {"type": "string"},
                  "author": {"type": "string"},
                  "isbn": {"type": "string"}
                },
                "required": ["_name"]
              }
            
            children:
              - alias: chapters
                kind: chapter
                description: Book chapters
                
                # Hierarchy-level schema (for all operations on this child relationship)
                schema: |
                  {
                    "type": "object",
                    "properties": {
                      "_name": {"type": "string"},
                      "pageCount": {"type": "integer"}
                    },
                    "required": ["_name"]
                  }
                
                routes:
                  createEntityChild:
                    operationId: createBookChapter
                    summary: Add a chapter to a book
                    # Hierarchy-route schema (most specific)
                    schema: |
                      {
                        "type": "object",
                        "properties": {
                          "_name": {"type": "string"},
                          "pageCount": {"type": "integer"},
                          "chapterNumber": {"type": "integer", "minimum": 1}
                        },
                        "required": ["_name", "chapterNumber"]
                      }
            
            # Through: cross-controller access to associated records
            through:
              reactions:
                - alias: reviews
                  kind: review
                  description: Reader reviews for a book
                  # Through-level schema (fallback for all through operations)
                  schema: |
                    {
                      "type": "object",
                      "properties": {
                        "rating": {"type": "integer", "minimum": 1, "maximum": 5}
                      },
                      "required": ["rating"]
                    }
                  routes:
                    createReactionByEntityId:
                      # Through-route schema (specific to POST — highest priority)
                      schema: |
                        {
                          "type": "object",
                          "properties": {
                            "rating": {"type": "integer", "minimum": 1, "maximum": 5},
                            "comment": {"type": "string"}
                          },
                          "required": ["rating", "comment"]
                        }
              lists:
                - alias: shelves
                  kind: bookshelf
                  description: Bookshelves containing this book (GET only)
      
      lists:
        aliases:
          - alias: categories
            kind: category
            description: Product categories
          
          - alias: bookshelves
            kind: bookshelf
            description: Bookshelf collections
            # Through: cross-controller access
            through:
              entities:
                - alias: books
                  kind: book
                  description: Books within this bookshelf
              reactions:
                - alias: comments
                  kind: comment
                  description: Comments on this bookshelf
      
      relations:
        aliases:
          - alias: product-assignments
            kind: product-assignment
            description: Assign products to categories
```

#### Properties Format (Environment Overrides)

For environment-specific configuration (e.g., `application-dev.properties`):

```properties
# Basic alias
app.oas.controllers.entities.aliases[0].alias=products
app.oas.controllers.entities.aliases[0].kind=product
app.oas.controllers.entities.aliases[0].description=Product catalog

# Optional explicit singular (for irregular plurals the heuristic can't handle, e.g. heroes→hero)
# app.oas.controllers.entities.aliases[0].singular=product

# Schema (inline JSON)
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"_name":{"type":"string"}},"required":["_name"]}

# Route customization
app.oas.controllers.entities.aliases[0].routes.createEntity.operationId=createProduct
app.oas.controllers.entities.aliases[0].routes.createEntity.schema={"type":"object","properties":{"_name":{"type":"string"},"sku":{"type":"string"}},"required":["_name","sku"]}

# Hierarchical child
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].schema={"type":"object","properties":{"_name":{"type":"string"}}}

# Hierarchy-route schema
app.oas.controllers.entities.aliases[0].children[0].routes.createEntityChild.schema={"type":"object","properties":{"_name":{"type":"string"},"chapterNumber":{"type":"integer"}},"required":["_name","chapterNumber"]}

# Through config — entities alias with through.reactions
app.oas.controllers.entities.aliases[0].through.reactions[0].alias=reviews
app.oas.controllers.entities.aliases[0].through.reactions[0].kind=review
app.oas.controllers.entities.aliases[0].through.reactions[0].schema={"type":"object","properties":{"rating":{"type":"integer"}},"required":["rating"]}
app.oas.controllers.entities.aliases[0].through.reactions[0].routes.createReactionByEntityId.schema={"type":"object","properties":{"rating":{"type":"integer"},"comment":{"type":"string"}},"required":["rating","comment"]}

# Through config — entities alias with through.lists (GET only)
app.oas.controllers.entities.aliases[0].through.lists[0].alias=shelves
app.oas.controllers.entities.aliases[0].through.lists[0].kind=bookshelf

# Through config — lists alias with through.entities
app.oas.controllers.lists.aliases[0].through.entities[0].alias=books
app.oas.controllers.lists.aliases[0].through.entities[0].kind=book

# Through config — lists alias with through.reactions
app.oas.controllers.lists.aliases[0].through.reactions[0].alias=comments
app.oas.controllers.lists.aliases[0].through.reactions[0].kind=comment
```

### 3.3 Route Toggle Configuration

**File:** `application-route-toggles.yml`

Route toggles control which routes are accessible. Configuration is organized by scope:

```yaml
app:
  toggles:
    routes:
      off:
        - deleteEntityByIdByKindAlias    # Disable specific route
        - replaceEntityByIdByKindAlias
    controllers:
      off:
        - relations                       # Disable entire controller
    tags:
      off:
        - destructive                     # Disable by tag
```

> **See Also:** [75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md) for evaluation order, precedence rules, and available toggle values.

### 3.4 Base URI and Controller Base Paths

**File:** `app-inbound.yml`

Inbound routing is defined by a **base URI** plus per-controller **base paths**:

```yaml
app:
  inbound:
    baseUri: /api/v1/
    controllerBasePaths:
      entities: entities
      lists: lists
      relations: relations
      entityReactions: entity-reactions
      listReactions: list-reactions
      # Through-route URL segments (change to rename the through path segment)
      reactionsThroughEntity: reactions          # /entities/{alias}/{id}/reactions/{throughAlias}
      reactionsThroughList: reactions             # /lists/{alias}/{id}/reactions/{throughAlias}
      listsThroughEntity: lists                   # /entities/{alias}/{id}/lists/{throughAlias}
      entitiesThroughList: entities               # /lists/{alias}/{id}/entities/{throughAlias}
      # Hierarchy accessor segments
      defaultChildrenAccessor: children
      defaultParentsAccessor: parents
```

This yields the concrete base paths:
- `/api/v1/entities`
- `/api/v1/lists`
- `/api/v1/relations`
- `/api/v1/entity-reactions`
- `/api/v1/list-reactions`

If you change a controller base path, all endpoints for that controller move under the new path (including kind aliases and hierarchical routes).

**Examples:**
- `controllerBasePaths.entities: items` → base path becomes `/api/v1/items`
  - `/api/v1/items/products` (kind alias)
  - `/api/v1/items/{id}/children` (hierarchical route)
- `controllerBasePaths.relations: links` → base path becomes `/api/v1/links`
  - `/api/v1/links/{id}` (standard relation route)

With kind aliases, the gateway additionally listens on the **current controller base path**, not just the defaults. The alias segment is appended to whatever base path you configure.

**Examples (default paths):**
- `/api/v1/entities/products` → mapped from `products` alias
- `/api/v1/entities/books` → mapped from `books` alias
- `/api/v1/lists/categories` → mapped from `categories` alias

**Examples (custom base paths):**
- `controllerBasePaths.entities: items` → `/api/v1/items/products`, `/api/v1/items/books`
- `controllerBasePaths.lists: collections` → `/api/v1/collections/categories`

### 3.5 Route ID Naming (Quick Reference)

Route IDs follow a consistent naming pattern based on **action + resource + context**:

- **Standard routes:** `{action}{Resource}` or `{action}{Resource}ById`
  - Examples: `createEntity`, `findEntities`, `findEntityById`, `deleteEntityById`, `createList`, `findRelations`, `findEntityReactionsById`, `deleteListReactionsById`
- **Kind alias routes:** `{baseRouteId}ByKindAlias`
  - Examples: `findEntitiesByKindAlias`, `deleteEntityByIdByKindAlias`, `findListsByKindAlias`, `createRelationByKindAlias`

#### Hierarchy Route IDs

The routeId used in `children[].routes.<routeId>` or `parents[].routes.<routeId>` is **not** the same as the standard alias route IDs (e.g. `createEntity`). It is fixed by three factors: **parent controller + accessor (children/parents) + HTTP method**:

| Parent controller | Accessor | HTTP Method | routeId |
|------------------|----------|-------------|---------|
| `entities` | `children` | POST | `createEntityChild` |
| `entities` | `children` | GET | `findEntityChildren` |
| `entities` | `parents` | GET | `findEntityParents` |
| `lists` | `children` | POST | `createListChild` |
| `lists` | `children` | GET | `findListChildren` |
| `lists` | `parents` | GET | `findListParents` |
| `entityReactions` | `children` | POST | `createChildEntityReaction` |
| `entityReactions` | `children` | GET | `findChildrenEntityReactionsByReactionId` |
| `entityReactions` | `parents` | GET | `findParentsByEntityReactionId` |
| `listReactions` | `children` | POST | `createChildListReaction` |
| `listReactions` | `children` | GET | `findChildrenListReactionsByReactionId` |
| `listReactions` | `parents` | GET | `findParentsByListReactionId` |

#### Through Route IDs

The routeId used in `through.<type>[].routes.<routeId>` is derived from the matched gateway route ID by stripping the `ByKindAlias` suffix. Each through relationship has a fixed set:

| Parent controller | through key | HTTP Method | routeId |
|------------------|------------|-------------|---------|
| `entities` | `reactions` | POST | `createReactionByEntityId` |
| `entities` | `reactions` | PATCH | `updateReactionsByEntityId` |
| `entities` | `reactions` | GET | `findReactionsByEntityId` |
| `entities` | `reactions` | DELETE | `deleteReactionsByEntityId` |
| `entities` | `lists` | GET | `findListsByEntityId` (GET only) |
| `lists` | `reactions` | POST | `createReactionByListId` |
| `lists` | `reactions` | PATCH | `updateReactionsByListId` |
| `lists` | `reactions` | GET | `findReactionsByListId` |
| `lists` | `reactions` | DELETE | `deleteReactionsByListId` |
| `lists` | `entities` | POST | `createEntityByListId` |
| `lists` | `entities` | PATCH | `updateEntitiesByListId` |
| `lists` | `entities` | GET | `findEntitiesByListId` |
| `lists` | `entities` | DELETE | `deleteEntitiesByListId` |

> **Complete Reference:** See [10-ROUTES.md](10-ROUTES.md) for the full route ID inventory and naming conventions.

---

## 4. RUNTIME BEHAVIOR

### 4.1 Request Flow

```mermaid
flowchart LR
  A[Client] --> B[Kind Resolver Filter]
  B --> C[Gateway Filters<br/>(Auth, Schema)]
  C --> D[Backend Service]

  B -.-> E[Resolve alias<br/>Inject _kind<br/>Rewrite path<br/>Set schema key]
```

### 4.2 Kind Resolution Filter

The `KindResolution` filter performs the core translation:

1. **Path Matching:** Reads `kindAlias` from the route and resolves it against OAS configuration
2. **Kind Resolution:** Determines the target `kind` and controller context
3. **Context Setup:** Populates `KindAliasConfigAttr` for downstream filters (kind, alias, controller, recordType)
4. **Validation Context:** Sets validation flags and defaults used by schema validation
5. **Error Handling:** Returns 404 when the alias is not configured

### 4.3 Hierarchy Resolution Filter

The `HierarchyKindAliasResolver` filter handles nested paths:

1. **Parent Resolution:** Uses the root alias from `KindResolution` (`/books`)
2. **Child/Parent Resolution:** Resolves the hierarchy alias (`chapters` or `parents`) from OAS config
3. **Path Rewriting:** Rewrites to technical children/parents accessor (e.g., `/entities/{id}/children`)
4. **Query Injection:** Adds `filter[where][_kind]=<targetKind>`
5. **Context Update:** Updates `KindAliasConfigAttr` with hierarchy schema keys and validation flags
6. **Error Handling:** Returns 404 when the hierarchy alias is not configured

### 4.4 Through Resolution Filter

The `ThroughKindAliasResolver` filter handles cross-controller through paths. It runs after `KindResolution` and overwrites `KindAliasConfigAttr` with the target through kind's metadata:

1. **Root Resolution:** Retrieves `KindAliasConfigAttr` set by `KindResolution` (the parent alias, e.g. `books`)
2. **Through Alias Extraction:** Reads `{throughAlias}` from URI template variables
3. **Through List Lookup:** Based on the route's `throughSegment` arg (`reactions`, `entities`, or `lists`), selects the correct sub-list from the parent alias's `through` config
4. **Alias Matching:** Searches the selected list for the `{throughAlias}` value
5. **Context Update:** Overwrites `KindAliasConfigAttr` with through kind, through schema keys (`throughSchemaKey`, `throughRouteSchemaKey`), and marks request as `throughRequest: true`
6. **Query Injection (GET):** Adds `filter[where][_kind]=<targetKind>` for GET requests
7. **Error Handling:** Returns 404 when the through alias is not configured or the through sub-list is empty

> **Note:** Path rewriting for through routes is handled statically by `RewritePath` at the route level—not by this filter. The filter only resolves alias metadata.

### 4.5 Validation Filter

The `ValidateRequestBodyByKindSchema` filter:

1. **Schema Lookup:** Resolves schema using the priority system
2. **JSON Schema Validation:** Validates request body against resolved schema
3. **Error Response:** Returns 400 with validation errors if schema fails

---

## 5. EXAMPLE SCENARIOS

### 5.1 E-Commerce Domain

**Goal:** Expose product, order, and category management APIs.

```yaml
app:
  oas:
    controllers:
      entities:
        aliases:
          - alias: products
            kind: product
            schema: |
              {"type":"object","properties":{"_name":{"type":"string"},"sku":{"type":"string"},"price":{"type":"number"}},"required":["_name","sku"]}
          
          - alias: orders
            kind: order
            schema: |
              {"type":"object","properties":{"_name":{"type":"string"},"status":{"type":"string","enum":["pending","confirmed","shipped"]}}}
      
      lists:
        aliases:
          - alias: categories
            kind: category
      
      relations:
        aliases:
          - alias: product-categories
            kind: product-category
            description: Assign products to categories
```

**Exposed Endpoints:**
```
POST   /api/v1/entities/products
GET    /api/v1/entities/products
GET    /api/v1/entities/products/{id}
PATCH  /api/v1/entities/products/{id}
DELETE /api/v1/entities/products/{id}

POST   /api/v1/entities/orders
GET    /api/v1/entities/orders
...

POST   /api/v1/lists/categories
GET    /api/v1/lists/categories
...

POST   /api/v1/relations/product-categories
GET    /api/v1/relations/product-categories
...
```

### 5.2 Content Management with Hierarchy

**Goal:** Books with chapters, enforcing chapter number on creation.

```yaml
app:
  oas:
    controllers:
      entities:
        aliases:
          - alias: books
            kind: book
            schema: |
              {"type":"object","properties":{"_name":{"type":"string"},"author":{"type":"string"},"isbn":{"type":"string"}},"required":["_name"]}
            
            children:
              - alias: chapters
                kind: chapter
                schema: |
                  {"type":"object","properties":{"_name":{"type":"string"},"pageCount":{"type":"integer"}}}
                routes:
                  createEntityChild:
                    schema: |
                      {"type":"object","properties":{"_name":{"type":"string"},"chapterNumber":{"type":"integer"}},"required":["_name","chapterNumber"]}
```

**Behavior:**
- `POST /books` → Requires `_name` only
- `GET /books/{id}/chapters` → Lists chapters
- `POST /books/{id}/chapters` → Requires `_name` AND `chapterNumber`

### 5.3 Read-Only Reference Data

**Goal:** Expose countries list but prevent modifications.

```yaml
# app-oas.yml - Define the alias
app:
  oas:
    controllers:
      entities:
        aliases:
          - alias: countries
            kind: country
```

```yaml
# application-route-toggles.yml - Disable write operations
app:
  toggles:
    routes:
      off:
        - createEntityByKindAlias
        - updateEntityByIdByKindAlias
        - replaceEntityByIdByKindAlias
        - deleteEntityByIdByKindAlias
```

**Behavior:**
- `GET /countries` → Allowed
- `GET /countries/{id}` → Allowed
- `POST /countries` → 404 Not Found (route disabled)
- `PATCH /countries/{id}` → 404 Not Found (route disabled)

> **Note:** The above toggle configuration disables writes for **all** kind aliases. For more granular control, see [ROUTE-TOGGLES.md](ROUTE-TOGGLES.md).

---

## 6. INTEGRATION WITH OTHER FEATURES

Domain projection integrates seamlessly with other gateway features:

| Feature | Integration |
|---------|------------|
| **Authentication** | JWT validation occurs before kind resolution |
| **Authorization (OPA)** | Policies receive resolved `_kind` value for decisions |
| **Rate Limiting** | Limits can be configured per alias: `app.rate-limits.entities.products.requests-per-second=100` |
| **Timeouts** | Timeouts can be configured per alias: `app.timeouts.entities.products.read=5000` |
| **Distributed Locks** | Lock keys include resolved kind for isolation |
| **Field Filtering** | OPA forbidden fields apply to projected schemas |
| **Caching** | Cache keys include alias for proper isolation |

---

## 7. FURTHER READING

- **[75-FEATURE-ROUTE-TOGGLES.md](75-FEATURE-ROUTE-TOGGLES.md)** — Detailed route toggle configuration and precedence rules
- **[90-FEATURE-DYNAMIC-OAS-GENERATION.md](90-FEATURE-DYNAMIC-OAS-GENERATION.md)** — How the gateway generates personalized OpenAPI documentation reflecting the projected domain
- **[20-FILTERS.md](20-FILTERS.md)** — Complete filter documentation including KindResolution and HierarchyKindAliasResolver
- **[10-ROUTES.md](10-ROUTES.md)** — Route inventory and naming conventions
- **[05-REPO-CONTEXT.md](05-REPO-CONTEXT.md)** — Overall gateway architecture

---

**Last Updated:** February 2026
