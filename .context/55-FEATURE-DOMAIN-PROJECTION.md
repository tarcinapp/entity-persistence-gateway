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

2. **Schema Override by Specificity:** General schema definitions can be overridden at more specific levels. Route-level schemas are always the most specific, taking precedence over broader definitions.

3. **Transparency to Backend:** The backend service remains unaware of domain projection. It receives standard requests with `_kind` filters—the gateway handles all translation.

4. **Composable Configuration:** Route toggles, schemas, timeouts, rate limits, and other behaviors can be configured independently per alias, per route, or per hierarchy level.

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

### 2.4 Route Toggles

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

> **See Also:** [50-FEATURE-ROUTE-TOGGLES.md](50-FEATURE-ROUTE-TOGGLES.md) for detailed toggle configuration and precedence rules.

### 2.5 Schema Override by Specificity

Request validation schemas follow a priority system where more specific definitions override general ones.

#### Standard Kind Alias Paths

For paths like `/products`:

| Priority | Level | Configuration Key | When Used |
|----------|-------|-------------------|-----------|
| **0 (Most Specific)** | Route | `routes.{routeId}.schema` | Specific operation |
| **1 (Fallback)** | Kind | `aliases[n].schema` | All operations |

#### Hierarchical Kind Alias Paths

For paths like `/books/{id}/chapters`:

| Priority | Level | Configuration Key | When Used |
|----------|-------|-------------------|-----------|
| **0 (Most Specific)** | Hierarchy-Route | `children[n].routes.{routeId}.schema` | Specific hierarchy operation |
| **1** | Hierarchy | `children[n].schema` | All hierarchy operations |
| **2** | Route | `routes.{routeId}.schema` | Operation-level for child kind |
| **3 (Fallback)** | Kind | Child kind's `aliases[n].schema` | General child kind schema |

**Example:** `POST /books/{id}/chapters` resolution order:
1. `children[0].routes.createEntityChild.schema` ✓ (if exists, use this)
2. `children[0].schema`
3. Route-level schema for `chapter` kind
4. Kind-level schema for `chapter`

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
      
      lists:
        aliases:
          - alias: categories
            kind: category
            description: Product categories
      
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
```

### 3.3 Route Toggle Configuration

**File:** `application-route-toggles.yml`

```yaml
app:
  route-toggles:
    entities:
      # Disable specific operations for products
      products:
        deleteEntityById:
          enabled: false
        replaceEntityById:
          enabled: false
      
      # Read-only for reference data
      countries:
        createEntity:
          enabled: false
        updateEntityById:
          enabled: false
        deleteEntityById:
          enabled: false
    
    lists:
      categories:
        deleteListById:
          enabled: false
```

### 3.4 Controller Base Paths

**File:** `application-routes.yml`

Controller base paths define where the gateway listens for domain-specific requests:

```yaml
app:
  inbound:
    controllers:
      entities:
        base-path: /api/v1/entities
      lists:
        base-path: /api/v1/lists
      relations:
        base-path: /api/v1/relations
      entity-reactions:
        base-path: /api/v1/entity-reactions
      list-reactions:
        base-path: /api/v1/list-reactions
```

With kind aliases, the gateway additionally listens on:
- `/api/v1/entities/products` → mapped from `products` alias
- `/api/v1/entities/books` → mapped from `books` alias
- `/api/v1/lists/categories` → mapped from `categories` alias

### 3.5 Route ID Reference

Available route IDs for `routes.{routeId}` configuration:

| Route ID | HTTP Method | Path Pattern | Description |
|----------|-------------|--------------|-------------|
| `createEntity` | POST | `/{alias}` | Create record |
| `findEntities` | GET | `/{alias}` | List records |
| `countEntities` | GET | `/{alias}/count` | Count records |
| `findEntityById` | GET | `/{alias}/{id}` | Get single record |
| `updateEntityById` | PATCH | `/{alias}/{id}` | Partial update |
| `replaceEntityById` | PUT | `/{alias}/{id}` | Full replace |
| `deleteEntityById` | DELETE | `/{alias}/{id}` | Delete record |
| `findEntityChildren` | GET | `/{alias}/{id}/children` | List children |
| `createEntityChild` | POST | `/{alias}/{id}/children` | Create child |
| `findEntityParents` | GET | `/{alias}/{id}/parents` | List parents |

*Replace `Entity` with appropriate record type for other controllers.*

---

## 4. RUNTIME BEHAVIOR

### 4.1 Request Flow

```
┌─────────────┐    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────┐
│   Client    │───▶│  Kind Resolver  │───▶│  Gateway Filters│───▶│   Backend   │
│             │    │  Filter         │    │  (Auth, Schema) │    │   Service   │
└─────────────┘    └─────────────────┘    └─────────────────┘    └─────────────┘
                          │
                          ▼
                   ┌─────────────────┐
                   │ • Resolve alias │
                   │ • Inject _kind  │
                   │ • Rewrite path  │
                   │ • Set schema key│
                   └─────────────────┘
```

### 4.2 Kind Resolution Filter

The `KindResolution` filter performs the core translation:

1. **Path Matching:** Identifies if the request path matches a configured alias
2. **Kind Injection:** Adds `_kind` field to request body (POST/PUT/PATCH)
3. **Query Rewriting:** Appends `filter[where][_kind]=X` to GET requests
4. **Schema Key Setting:** Sets the appropriate schema key for validation
5. **Path Rewriting:** Transforms `/products` to `/entities`

### 4.3 Hierarchy Resolution Filter

The `HierarchyKindAliasResolver` filter handles nested paths:

1. **Parent Resolution:** Validates parent alias (`/books`)
2. **Child Resolution:** Validates child alias (`chapters`)
3. **Route Determination:** Maps HTTP method to route ID (`POST` + `children` → `createEntityChild`)
4. **Schema Key Building:** Constructs hierarchy-specific schema key
5. **Path Rewriting:** Transforms `/books/{id}/chapters` to `/entities/{id}/children`

### 4.4 Validation Filter

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

## 7. TROUBLESHOOTING

| Issue | Cause | Solution |
|-------|-------|----------|
| 404 on aliased endpoint | Alias not configured or route disabled | Check `app-oas.yml` and route toggles |
| Wrong schema validation | Schema priority mismatch | Verify schema at correct level (route vs kind) |
| `_kind` not injected | KindResolution filter not in chain | Check route configuration includes `KindResolution` |
| Hierarchy path not working | Children not configured | Add `children[]` to parent alias |
| Validation bypassed | No schema at any priority level | Add schema to alias or route configuration |

---

## 8. FURTHER READING

- **[50-FEATURE-ROUTE-TOGGLES.md](50-FEATURE-ROUTE-TOGGLES.md)** — Detailed route toggle configuration and precedence rules
- **[60-FEATURE-DYNAMIC-OAS-GENERATION.md](60-FEATURE-DYNAMIC-OAS-GENERATION.md)** — How the gateway generates personalized OpenAPI documentation reflecting the projected domain
- **[20-FILTERS.md](20-FILTERS.md)** — Complete filter documentation including KindResolution and HierarchyKindAliasResolver
- **[10-ROUTES.md](10-ROUTES.md)** — Route inventory and naming conventions
- **[05-REPO-CONTEXT.md](05-REPO-CONTEXT.md)** — Overall gateway architecture

---

**Last Updated:** February 2026
