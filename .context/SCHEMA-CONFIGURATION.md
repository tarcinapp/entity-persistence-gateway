# Schema Configuration Guide

This guide explains how schemas are configured and resolved for the Entity Persistence Gateway's OpenAPI generation and request validation.

---

## Table of Contents

1. [Basic Concepts](#basic-concepts)
2. [Schema Hierarchy Overview](#schema-hierarchy-overview)
3. [Configuration Levels](#configuration-levels)
4. [Schema Resolution Order](#schema-resolution-order)
5. [Configuration Examples](#configuration-examples)
6. [Hierarchical Paths (Children/Parents)](#hierarchical-paths-childrenparents)
7. [Route-Level Schema Overrides](#route-level-schema-overrides)
8. [Consistency Between Validation and OAS Generation](#consistency-between-validation-and-oas-generation)

---

## Basic Concepts

### What is a Kind Alias?

A **kind alias** maps a user-friendly URL path to a specific `_kind` value in the database.

Example:
- URL: `/api/v1/entities/books`
- Maps to: entities where `_kind = "book"`

### What is a Schema?

A **schema** defines the structure of data:
- What fields are allowed
- What fields are required
- What types each field should have

Schemas are used for:
1. **Request Validation** - Reject invalid requests before they reach the backend
2. **OpenAPI Generation** - Document the API structure for consumers

---

## Schema Hierarchy Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    SCHEMA RESOLUTION HIERARCHY                   │
│                    (Highest to Lowest Priority)                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  1. ROUTE-LEVEL SCHEMA                                          │
│     Most specific - overrides everything below                  │
│     Example: aliases[0].routes.createEntity.schema              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. KIND-LEVEL SCHEMA                                           │
│     Applies to all routes for this kind alias                   │
│     Example: aliases[0].schema                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. BACKEND BASE SCHEMA                                         │
│     Default schema from the backend service                     │
│     Fetched from backend's /openapi.json                        │
└─────────────────────────────────────────────────────────────────┘
```

---

## Configuration Levels

### Level 1: Controller Level

The top-level grouping by record type.

```properties
app.oas.controllers.entities.aliases[0]...
app.oas.controllers.lists.aliases[0]...
app.oas.controllers.relations.aliases[0]...
app.oas.controllers.entityReactions.aliases[0]...
app.oas.controllers.listReactions.aliases[0]...
```

### Level 2: Kind Alias Level

Defines a URL path alias and its associated kind.

```properties
# Basic alias configuration
app.oas.controllers.entities.aliases[0].alias=books          # URL path segment
app.oas.controllers.entities.aliases[0].kind=book            # _kind value in database
app.oas.controllers.entities.aliases[0].description="..."    # OpenAPI description
app.oas.controllers.entities.aliases[0].validationEnabled=true
app.oas.controllers.entities.aliases[0].schema={...}         # Kind-level schema (JSON)
```

### Level 3: Route Level

Override settings for specific operations.

```properties
# Route-level overrides
app.oas.controllers.entities.aliases[0].routes.createEntity.operationId=createBook
app.oas.controllers.entities.aliases[0].routes.createEntity.description="Create a new book"
app.oas.controllers.entities.aliases[0].routes.createEntity.schema={...}   # Route-level schema
app.oas.controllers.entities.aliases[0].routes.findEntities.schema={...}
app.oas.controllers.entities.aliases[0].routes.findEntityById.schema={...}
```

### Level 4: Hierarchical Relationships (Children/Parents)

Define nested resource relationships.

```properties
# Children configuration
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].schema={...}
app.oas.controllers.entities.aliases[0].children[0].validationEnabled=true

# Parents configuration  
app.oas.controllers.entities.aliases[0].parents[0].alias=authors
app.oas.controllers.entities.aliases[0].parents[0].kind=author
app.oas.controllers.entities.aliases[0].parents[0].schema={...}
app.oas.controllers.entities.aliases[0].parents[0].validationEnabled=true
```

---

## Schema Resolution Order

### For Main Alias Paths (e.g., `/api/v1/entities/books`)

```
Request: POST /api/v1/entities/books

Schema Resolution:
1. Check: aliases[0].routes.createEntity.schema  → If exists, USE IT
2. Check: aliases[0].schema                       → If exists, USE IT
3. Fallback: Backend base schema (NewEntity)
```

### For Children Paths (e.g., `/api/v1/entities/books/{id}/children`)

```
Request: POST /api/v1/entities/books/{id}/children

Schema Resolution:
1. Check: Does the request specify a _kind?
   └─ Yes: Look for an alias with that kind
      └─ Check: that alias's routes.createEntity.schema
      └─ Check: that alias's schema
   └─ No: Use the children[n] configuration
      └─ Check: children[n].schema
2. Fallback: Backend base schema (NewEntity)
```

### For Parents Paths (e.g., `/api/v1/entities/books/{id}/parents`)

```
Request: POST /api/v1/entities/books/{id}/parents

Schema Resolution:
1. Check: Does the request specify a _kind?
   └─ Yes: Look for an alias with that kind
      └─ Check: that alias's routes.createEntity.schema
      └─ Check: that alias's schema
   └─ No: Use the parents[n] configuration
      └─ Check: parents[n].schema
2. Fallback: Backend base schema (NewEntity)
```

---

## Configuration Examples

### Example 1: Basic Kind Alias with Schema

```properties
# URL: /api/v1/entities/books
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].validationEnabled=true
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"_name":{"type":"string"},"isbn":{"type":"string"}},"required":["_name","isbn"]}
```

Result:
- All routes (`createEntity`, `findEntities`, `updateEntityById`, etc.) use this schema
- Request validation enforces `_name` and `isbn` as required
- OpenAPI shows `NewBook`, `Book`, `PatchBook` schemas with these properties

### Example 2: Route-Level Schema Override

```properties
# Base schema for all book operations
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"_name":{"type":"string"},"isbn":{"type":"string"}},"required":["_name","isbn"]}

# Override for createEntity only - add extra required field
app.oas.controllers.entities.aliases[0].routes.createEntity.schema={"type":"object","properties":{"_name":{"type":"string"},"isbn":{"type":"string"},"publishedYear":{"type":"integer"}},"required":["_name","isbn","publishedYear"]}

# Override for findEntities response - include computed field
app.oas.controllers.entities.aliases[0].routes.findEntities.schema={"type":"object","properties":{"_name":{"type":"string"},"isbn":{"type":"string"},"availability":{"type":"string"}},"required":["_name","isbn"]}
```

Result:
- `POST /books` requires `_name`, `isbn`, AND `publishedYear`
- `GET /books` response shows `availability` field
- Other routes use the base schema (only `_name` and `isbn`)

### Example 3: Hierarchical Configuration (Books → Chapters)

```properties
# Parent: books
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"_name":{"type":"string"},"isbn":{"type":"string"}},"required":["_name","isbn"]}

# Children: chapters (accessed via /books/{id}/children)
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].schema={"type":"object","properties":{"_name":{"type":"string"},"pageCount":{"type":"integer"}},"required":["_name","pageCount"]}
```

Result:
- `POST /books` validates with book schema
- `POST /books/{id}/children` validates with chapter schema (if `_kind=chapter`)
- OpenAPI shows `Chapter`, `NewChapter` schemas

### Example 4: Multiple Children Types

```properties
# A book can have chapters (children) and authors (parents)
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book

# First child type: chapters
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].schema={"type":"object","properties":{"_name":{"type":"string"},"pageCount":{"type":"integer"}},"required":["_name"]}

# Second child type: appendices
app.oas.controllers.entities.aliases[0].children[1].alias=appendices
app.oas.controllers.entities.aliases[0].children[1].kind=appendix
app.oas.controllers.entities.aliases[0].children[1].schema={"type":"object","properties":{"_name":{"type":"string"},"content":{"type":"string"}},"required":["_name"]}

# Parent type: authors
app.oas.controllers.entities.aliases[0].parents[0].alias=authors
app.oas.controllers.entities.aliases[0].parents[0].kind=author
app.oas.controllers.entities.aliases[0].parents[0].schema={"type":"object","properties":{"_name":{"type":"string"},"bio":{"type":"string"}},"required":["_name"]}
```

---

## Hierarchical Paths (Children/Parents)

### URL Structure

```
Main resource:     /api/v1/entities/{alias}
                   /api/v1/entities/books

By ID:             /api/v1/entities/{alias}/{id}
                   /api/v1/entities/books/123

Children:          /api/v1/entities/{alias}/{id}/children
                   /api/v1/entities/books/123/children

Parents:           /api/v1/entities/{alias}/{id}/parents
                   /api/v1/entities/books/123/parents
```

### Schema Selection for Children/Parents

When creating a child entity at `/books/{id}/children`:

1. **If request body contains `_kind`**:
   - Gateway looks for a top-level alias with matching kind
   - Uses that alias's schema (or its route-specific schema)
   
2. **If request body does NOT contain `_kind`**:
   - Gateway uses the `children[n].schema` configuration
   - The `children[n].kind` is automatically injected into the request

### How Children Configuration Affects Schema

```properties
# When you configure:
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].schema={...chapter schema...}
```

This means:
- Requests to `/books/{id}/children` with `_kind=chapter` use the chapter schema
- The OpenAPI shows `/books/{id}/children` with chapter schema in request/response
- Validation uses chapter schema when `_kind=chapter`

### Overriding Children Route Schemas

Currently, route-level overrides for children paths are NOT directly supported.

**Workaround**: Define the child as a top-level alias with route overrides:

```properties
# Define chapters as top-level alias (for route-level control)
app.oas.controllers.entities.aliases[1].alias=chapters
app.oas.controllers.entities.aliases[1].kind=chapter
app.oas.controllers.entities.aliases[1].schema={...base chapter schema...}
app.oas.controllers.entities.aliases[1].routes.createEntity.schema={...create-specific schema...}

# Reference as child (for hierarchical paths)
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
# Schema will be looked up from aliases[1] based on kind=chapter
```

---

## Route-Level Schema Overrides

### Available Route IDs

For **entities** controller:

| Route ID | HTTP Method | Path | Purpose |
|----------|-------------|------|---------|
| `createEntity` | POST | `/{alias}` | Create new entity |
| `findEntities` | GET | `/{alias}` | List entities |
| `findEntityById` | GET | `/{alias}/{id}` | Get single entity |
| `updateEntityById` | PATCH | `/{alias}/{id}` | Partial update |
| `replaceEntityById` | PUT | `/{alias}/{id}` | Full replace |
| `deleteEntityById` | DELETE | `/{alias}/{id}` | Delete entity |
| `countEntities` | GET | `/{alias}/count` | Count entities |

Similar patterns exist for `lists`, `relations`, `entityReactions`, and `listReactions`.

### Schema Usage by Route Type

| Route Type | Schema Purpose | Schema Name Pattern |
|------------|----------------|---------------------|
| `createEntity` | Request body validation | `New{Alias}CreateEntity` |
| `findEntities` | Response body structure | `{Alias}FindEntities` |
| `findEntityById` | Response body structure | `{Alias}FindEntityById` |
| `updateEntityById` | Request body validation | `Patch{Alias}UpdateEntityById` |
| `replaceEntityById` | Request body validation | `{Alias}ReplaceEntityById` |

---

## Consistency Between Validation and OAS Generation

### The Golden Rule

> **The schema used for runtime validation MUST match the schema shown in the OpenAPI spec.**

### How Consistency is Achieved

1. **Single Source of Truth**: Both validation and OAS generation read from `OpenApiProperties`

2. **Same Resolution Logic**: Both use the same hierarchy:
   - Route-level schema (highest priority)
   - Kind-level schema
   - Backend base schema (fallback)

3. **Validation Code** (`ValidateRequestBodyByKindSchema.java`):
   ```
   Schema resolution:
   1. Get alias config from OpenApiProperties
   2. Check route-specific schema
   3. Fall back to kind-level schema
   4. Validate request against selected schema
   ```

4. **OAS Generation** (`OasTransformationEngine.java`):
   ```
   Schema resolution:
   1. Get alias config from OpenApiProperties
   2. Check route-specific schema → create "New{Alias}{RouteId}" schema
   3. Fall back to kind-level schema → create "New{Alias}" schema
   4. Bind operations to correct schema reference
   ```

### Potential Inconsistency Points

| Scenario | Validation | OAS Generation | Status |
|----------|------------|----------------|--------|
| Kind-level schema only | ✅ Uses it | ✅ Uses it | Consistent |
| Route-level schema | ✅ Uses it | ✅ Uses it | Consistent |
| Children with schema | ✅ Uses children schema | ✅ Uses children schema | Consistent |
| Children without schema | ⚠️ Falls back to base | ⚠️ Falls back to base | Consistent |

---

## Quick Reference

### Minimal Configuration

```properties
# Just alias and kind - uses backend defaults
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
```

### With Validation

```properties
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].validationEnabled=true
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"_name":{"type":"string"}},"required":["_name"]}
```

### With Route Override

```properties
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].validationEnabled=true
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"_name":{"type":"string"}},"required":["_name"]}
app.oas.controllers.entities.aliases[0].routes.createEntity.schema={"type":"object","properties":{"_name":{"type":"string"},"isbn":{"type":"string"}},"required":["_name","isbn"]}
```

### With Children

```properties
app.oas.controllers.entities.aliases[0].alias=books
app.oas.controllers.entities.aliases[0].kind=book
app.oas.controllers.entities.aliases[0].schema={...}
app.oas.controllers.entities.aliases[0].children[0].alias=chapters
app.oas.controllers.entities.aliases[0].children[0].kind=chapter
app.oas.controllers.entities.aliases[0].children[0].schema={...}
```

---

## Troubleshooting

### Schema Not Being Used

1. Check `validationEnabled=true` is set
2. Verify JSON schema syntax is valid
3. Check property file parsing (escape special characters if needed)
4. Clear Redis cache and restart gateway

### Children Schema Not Working

1. Ensure `_kind` in request matches `children[n].kind`
2. Check if the kind has a top-level alias that might override
3. Verify the children configuration index is correct

### Route Override Not Applied

1. Verify route ID is correct (e.g., `createEntity` not `createBook`)
2. Check the schema JSON is valid
3. Look for typos in property path
