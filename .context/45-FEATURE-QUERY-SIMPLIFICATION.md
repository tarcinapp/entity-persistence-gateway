# Query Simplification: Client-Friendly API Query Parameters

## 1. FEATURE IDENTITY & PURPOSE

**Query Simplification** is a gateway-layer translation feature that converts clean, human-readable query parameters into the backend's native `filter[*]` notation—without exposing the underlying persistence technology to API consumers.

### The Hard Problem Solved

The `entity-persistence-service` backend uses its own filter notation for all queries. While powerful, this notation leaks implementation details and is verbose to write:

```
GET /entities?filter[where][_name][regexp]=.*laptop.*&filter[limit]=10&filter[skip]=20&filter[order]=price
```

For API consumers—frontend developers, mobile apps, third-party integrators—this is unnecessary complexity. They should be able to write:

```
GET /products?s=laptop&limit=10&skip=20&order=price
```

**Query Simplification** closes this gap. The `ConvertSimplerQueriesToBackendFormat` filter intercepts every request on query-capable routes and:

1. **Translates simplified parameters** into their backend `filter[*]` equivalents.
2. **Expands saved (predefined) queries** defined in configuration into full backend query strings.
3. **Optionally blocks raw backend notation** so that implementation details are never visible to clients.

### Core Philosophy

1. **Technology Hiding:** API consumers interact using clean, domain-neutral parameters. The gateway translates silently. Clients never need to know what backend technology is used.
2. **Configuration-Driven Queries:** Complex, reusable queries can be defined once in configuration as **Saved Queries** and invoked by name. This avoids duplication in client code and centralizes query logic.
3. **SpEL-Powered Saved Queries:** Saved queries leverage Spring Expression Language (SpEL), allowing them to embed dynamic values—such as the currently authenticated user's ID—at evaluation time.
4. **Opt-In Backend Passthrough:** For advanced use-cases (internal tooling, admin dashboards), raw backend notation can optionally be allowed alongside simplified parameters.

---

## 2. QUERY PARAMETER MAPPINGS

The filter translates the following simplified parameters into their backend equivalents. All translations are transparent—the backend only ever sees the converted form.

### 2.1 Parameter Translation Table

The filter adapts its output to the backend query family required by each route. `find*` routes use `filter[*]` notation; `updateAll*`, `count*`, and `deleteAll*` routes use `where[*]` notation.

| Simplified Parameter | On `find*` routes | On `updateAll*`/`count*`/`deleteAll*` routes |
|---|---|---|
| `?s=foo` or `?search=foo` | `filter[where][_name][regexp]=.*foo.*` | `where[_name][regexp]=.*foo.*` |
| `?limit=N` | `filter[limit]=N` | *(dropped — no equivalent in `where[*]`)* |
| `?skip=N` | `filter[skip]=N` | *(dropped — no equivalent in `where[*]`)* |
| `?order=fieldName` | `filter[order]=fieldName` | *(dropped — no equivalent in `where[*]`)* |
| `?fields=f1,f2` | `filter[fields][f1]=true&filter[fields][f2]=true` | *(dropped — no equivalent in `where[*]`)* |
| `?q=queryName` or `?query=queryName` | *(resolved from config)* | *(resolved from config)* |

### 2.2 Backend Notation Passthrough

Any parameter beginning with a recognized backend prefix is classified as raw backend notation. The filter recognizes two families:

| Prefix Family | Used By | Example |
|---|---|---|
| `filter[where]`, `filter[fields]`, `filter[include]`, `filter[limit]`, `filter[order]`, `filter[skip]` | `find*` routes | `filter[where][price][lt]=100` |
| `where[`, `entityWhere[`, `listWhere[` | `updateAll*`, `count*`, `deleteAll*` routes | `where[_name][regexp]=.*laptop.*` |

The behavior for all these parameters is controlled by `app.allowBackendQueryNotation`:

| `app.allowBackendQueryNotation` | Effect |
|---|---|
| `true` (default) | Raw backend parameters are passed through unchanged |
| `false` | Raw backend parameters are silently dropped |

When `false`, clients that send `filter[where][price][lt]=100` will have the parameter discarded, enforcing clean API contracts.

---

## 3. SAVED QUERIES

Saved queries allow complex, reusable query strings to be defined once in configuration and referenced by name in API calls using `?q=<name>` or `?query=<name>`.

### 3.1 Configuration

**File:** `application-queries.yml` (or `application-dev.properties` for environment-specific overrides)

```yaml
app:
  queries:
    actives: "'sets[actives]'"
    inactives: "'sets[inactives]'"
    my: "'sets[owners][userIds]=' + #userId"
    name: "'filter[where][_name]=' + #query['name']"
    slug: "'filter[where][_slug]=' + #query['slug']"
```

Each entry is a **Spring Expression Language (SpEL)** expression that evaluates to a URL-encoded query string.

### 3.2 SpEL Context Variables

When a saved query is evaluated, the following variables are available in the SpEL context:

| Variable | Type | Description |
|---|---|---|
| `#userId` | `String` | The authenticated user's ID extracted from the JWT token. `null` for unauthenticated requests. |
| `#query` | `Map<String, String>` | A map of all query parameters present on the incoming request. Allows combining `?q=` with additional ad-hoc parameters. |

### 3.3 Saved Query Examples

**Static saved query:**
```yaml
app:
  queries:
    actives: "'sets[actives]'"
```
Usage: `GET /products?q=actives`  
Expands to: `GET /products?sets[actives]`

---

**User-scoped query (uses `#userId`):**
```yaml
app:
  queries:
    my: "'sets[owners][userIds]=' + #userId"
```
Usage: `GET /products?q=my`  
Expands to: `GET /products?sets[owners][userIds]=<authenticated-user-id>`

---

**Parameterized query (uses `#query` map):**
```yaml
app:
  queries:
    name: "'filter[where][_name]=' + #query['name']"
```
Usage: `GET /products?q=name&name=Widget`  
Expands to: `GET /products?filter[where][_name]=Widget`

---

**Environment-variable override (`.properties` format):**
```properties
app.queries.by-book-name='filter[where][_slug]=' + #query['book-name']
```
Usage: `GET /books?q=by-book-name&book-name=the-overcoat`  
Expands to: `GET /books?filter[where][_slug]=the-overcoat`

---

### 3.4 Query Name Resolution

If the client sends `?q=unknown-query-name` and no matching entry exists in `app.queries`, the parameter is silently dropped and a `WARN` log entry is emitted. No error is returned to the client.

---

## 4. CONFIGURATION REFERENCE

### 4.1 Configuration Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `app.allowBackendQueryNotation` | `boolean` | `true` | When `false`, raw `filter[*]` parameters sent by clients are silently dropped |
| `app.queries.<name>` | `String` (SpEL) | — | Defines a saved query identified by `<name>` |

### 4.2 Configuration Files

| File | Purpose |
|---|---|
| `application-queries.yml` | Primary file for saved query definitions |
| `application-dev.properties` | Environment-specific overrides (e.g., `app.allowBackendQueryNotation=true`) |

### 4.3 Example: Restricting to Simplified Notation Only

```yaml
# application.yml or env variable
app:
  allowBackendQueryNotation: false
```

```properties
# application-dev.properties
app.allowBackendQueryNotation=true    # Override: allow raw notation in dev
```

With this setup, production clients are forced to use simplified parameters. Development environments can bypass the restriction for debugging.

---

## 5. EXAMPLE REQUESTS

### 5.1 Search by Name

```
GET /products?s=laptop
```
Becomes:
```
GET /entities?filter[where][name][regexp]=.*laptop.*&filter[where][_kind]=product
```

---

### 5.2 Pagination

```
GET /products?limit=10&skip=20
```
Becomes:
```
GET /entities?filter[limit]=10&filter[skip]=20&filter[where][_kind]=product
```

---

### 5.3 Field Selection

```
GET /products?fields=_name,price,sku
```
Becomes:
```
GET /entities?filter[fields][_name]=true&filter[fields][price]=true&filter[fields][sku]=true&filter[where][_kind]=product
```

---

### 5.4 Saved Query — Current User's Records

```
GET /products?q=my
```
Becomes (where `abc-123` is the authenticated user's ID):
```
GET /entities?sets[owners][userIds]=abc-123&filter[where][_kind]=product
```

---

### 5.5 Combining Saved Query with Additional Parameters

```
GET /products?q=name&name=Widget
```
Becomes:
```
GET /entities?filter[where][_name]=Widget&filter[where][_kind]=product
```

---

### 5.6 Raw Backend Notation (When Allowed)

```
GET /products?filter[where][price][lt]=100&filter[order]=price
```
When `app.allowBackendQueryNotation=true`, passed through unchanged.  
When `app.allowBackendQueryNotation=false`, both parameters are dropped.

---

## 6. FILTER CHAIN POSITION

The `ConvertSimplerQueriesToBackendFormat` filter runs **after** `PreventStringifiedJsonFilter` and `ApplyFieldsetConfig`, and **before** `AddSets*` filters. This ordering ensures:

1. Injection-style stringified JSON queries are rejected first.
2. Fieldset projections are normalized before backend parameter translation.
3. Downstream set-scoping filters receive query parameters already in backend format.

---

## 7. ROUTES

The `ConvertSimplerQueriesToBackendFormat` filter is applied to all routes that accept query parameters. Routes fall into two categories based on the backend query family they accept.

### 7.1 Find Routes — `filter[*]` family

These routes accept the full backend `filter[*]` query family. All simplified parameters (`s=`, `limit=`, `skip=`, `order=`, `fields=`, `q=`) are meaningful here.

#### Entities
| Route ID | HTTP Method | Path |
|---|---|---|
| `findEntities` | GET | `/entities` |
| `findEntityChildren` | GET | `/entities/{id}/children` |
| `findEntityParents` | GET | `/entities/{id}/parents` |

#### Lists
| Route ID | HTTP Method | Path |
|---|---|---|
| `findLists` | GET | `/lists` |
| `findListChildren` | GET | `/lists/{id}/children` |
| `findListParents` | GET | `/lists/{id}/parents` |

#### Relations
| Route ID | HTTP Method | Path |
|---|---|---|
| `findRelations` | GET | `/relations` |

#### Entity Reactions
| Route ID | HTTP Method | Path |
|---|---|---|
| `findEntityReactions` | GET | `/entity-reactions` |
| `findChildrenEntityReactionsByReactionId` | GET | `/entity-reactions/{id}/children` |
| `findParentsByEntityReactionId` | GET | `/entity-reactions/{id}/parents` |
| `findReactionsByEntityId` | GET | `/entities/{id}/entity-reactions` |

#### List Reactions
| Route ID | HTTP Method | Path |
|---|---|---|
| `findListReactions` | GET | `/list-reactions` |
| `findChildrenListReactionsByReactionId` | GET | `/list-reactions/{id}/children` |
| `findParentsByListReactionId` | GET | `/list-reactions/{id}/parents` |
| `findReactionsByListId` | GET | `/lists/{id}/list-reactions` |

#### Through-Record Find Routes
| Route ID | HTTP Method | Path |
|---|---|---|
| `findEntitiesByListId` | GET | `/lists/{id}/entities` |
| `findListsByEntityId` | GET | `/entities/{id}/lists` |

#### Kind-Alias Find Routes
| Route ID | Description |
|---|---|
| `findAllEntitiesByKindAlias` | List entities of a specific kind |
| `findEntityChildrenByKindAlias` | List children of a specific entity kind |
| `findEntityParentsByKindAlias` | List parents of a specific entity kind |
| `findEntityHierarchyByKindAlias` | Fetch entity with full hierarchy context |
| `findAllListsByKindAlias` | List records of a specific list kind |
| `findListChildrenByKindAlias` | List children of a specific list kind |
| `findListParentsByKindAlias` | List parents of a specific list kind |
| `findListHierarchyByKindAlias` | Fetch list record with full hierarchy context |
| `findAllRelationsByKindAlias` | List relations of a specific kind |
| `findAllEntityReactionsByKindAlias` | List entity reactions of a specific kind |
| `findChildrenEntityReactionsByReactionIdByKindAlias` | List children of a specific entity reaction kind |
| `findParentsByEntityReactionIdByKindAlias` | List parents of a specific entity reaction kind |
| `findEntityReactionHierarchyByKindAlias` | Fetch entity reaction with full hierarchy context |
| `findReactionsByEntityIdByKindAlias` | Find reactions on a specific entity (kind alias) |
| `findAllListReactionsByKindAlias` | List list reactions of a specific kind |
| `findChildrenListReactionsByReactionIdByKindAlias` | List children of a specific list reaction kind |
| `findParentsByListReactionIdByKindAlias` | List parents of a specific list reaction kind |
| `findListReactionHierarchyByKindAlias` | Fetch list reaction with full hierarchy context |
| `findReactionsByListIdByKindAlias` | Find reactions on a specific list (kind alias) |
| `findEntitiesByListIdByKindAlias` | Find entities through a kind-alias list |
| `findListsByEntityIdByKindAlias` | Find lists containing a kind-alias entity |

---

### 7.2 Where-Query Routes — `where[*]` family

These routes target bulk operations: `updateAll`, `count`, and `deleteAll`. The backend accepts only the `where[*]` query family on these routes. The filter is configured with `useWhereNotation: true` for all routes in this section, which changes its behavior:

- `?s=` / `?search=` → translated to `where[_name][regexp]=.*foo.*` (correct `where[*]` format)
- `?q=` / `?query=` → resolved from saved query config (same as find routes)
- `?limit=`, `?skip=`, `?order=`, `?fields=` → **silently dropped** (no equivalent exists in `where[*]` notation)
- Raw `where[*]` params → passed through (subject to `app.allowBackendQueryNotation`)

#### updateAll Routes
| Route ID | HTTP Method | Path |
|---|---|---|
| `updateAllEntities` | PATCH | `/entities` |
| `updateAllLists` | PATCH | `/lists` |
| `updateAllRelations` | PATCH | `/relations` |
| `updateAllEntityReactions` | PATCH | `/entity-reactions` |
| `updateAllListReactions` | PATCH | `/list-reactions` |
| `updateReactionsByEntityId` | PATCH | `/entities/{id}/entity-reactions` |
| `updateReactionsByListId` | PATCH | `/lists/{id}/list-reactions` |
| `updateEntitiesByListId` | PATCH | `/lists/{id}/entities` |
| `updateAllEntitiesByKindAlias` | PATCH | `/{entities}/{kindAlias}` |
| `updateAllListsByKindAlias` | PATCH | `/{lists}/{kindAlias}` |
| `updateAllRelationsByKindAlias` | PATCH | `/{relations}/{kindAlias}` |
| `updateAllEntityReactionsByKindAlias` | PATCH | `/{entity-reactions}/{kindAlias}` |
| `updateAllListReactionsByKindAlias` | PATCH | `/{list-reactions}/{kindAlias}` |
| `updateReactionsByEntityIdByKindAlias` | PATCH | `/{entities}/{kindAlias}/{id}/reactions/{throughAlias}` |
| `updateReactionsByListIdByKindAlias` | PATCH | `/{lists}/{kindAlias}/{id}/reactions/{throughAlias}` |
| `updateEntitiesByListIdByKindAlias` | PATCH | `/{lists}/{kindAlias}/{id}/entities/{throughAlias}` |

#### count Routes
| Route ID | HTTP Method | Path |
|---|---|---|
| `countEntities` | GET | `/entities/count` |
| `countLists` | GET | `/lists/count` |
| `countRelations` | GET | `/relations/count` |
| `countEntityReactions` | GET | `/entity-reactions/count` |
| `countListReactions` | GET | `/list-reactions/count` |
| `countEntitiesByKindAlias` | GET | `/{entities}/{kindAlias}/count` |
| `countListsByKindAlias` | GET | `/{lists}/{kindAlias}/count` |
| `countRelationsByKindAlias` | GET | `/{relations}/{kindAlias}/count` |
| `countEntityReactionsByKindAlias` | GET | `/{entity-reactions}/{kindAlias}/count` |
| `countListReactionsByKindAlias` | GET | `/{list-reactions}/{kindAlias}/count` |

#### deleteAll Routes (through-record only)
| Route ID | HTTP Method | Path |
|---|---|---|
| `deleteEntitiesByListId` | DELETE | `/lists/{id}/entities` |
| `deleteReactionsByEntityId` | DELETE | `/entities/{id}/entity-reactions` |
| `deleteReactionsByListId` | DELETE | `/lists/{id}/list-reactions` |
| `deleteEntitiesByListIdByKindAlias` | DELETE | `/{lists}/{kindAlias}/{id}/entities/{throughAlias}` |
| `deleteReactionsByEntityIdByKindAlias` | DELETE | `/{entities}/{kindAlias}/{id}/reactions/{throughAlias}` |
| `deleteReactionsByListIdByKindAlias` | DELETE | `/{lists}/{kindAlias}/{id}/reactions/{throughAlias}` |

---

## 8. LIMITATIONS

### 8.1 Pagination and Field-Selection Params Are Not Applicable on Where-Query Routes

The `limit=`, `skip=`, `order=`, and `fields=` parameters have no equivalent in `where[*]` notation and are silently dropped on `updateAll*`, `count*`, and `deleteAll*` routes. Sending these parameters to a where-query route has no effect.

### 8.2 Single-Field Order Only

The simplified `?order=fieldName` parameter maps to a single `filter[order]` value. The backend supports multi-field ordering:

```
?filter[order][0]=price&filter[order][1]=_name
```

This multi-field form is only available when `app.allowBackendQueryNotation=true`. Clients using simplified notation are limited to ordering by one field.

### 8.2 Search Targets `_name` Only

The `?s=` / `?search=` parameter generates a regex filter exclusively on the `_name` field:

```
filter[where][_name][regexp]=.*<value>.*
```

Domain-specific full-text search across multiple fields must be expressed via saved queries or (if allowed) raw backend notation.

### 8.3 Saved Query Values Must Evaluate to Valid Query Strings

The SpEL expression for a saved query must evaluate to a valid URL query string (without the leading `?`). Malformed expressions will cause the parameter to be discarded, and the error will be logged at `ERROR` level during startup or at request time.

### 8.4 Null Query Parameter Values

Query parameters with `null` values are filtered out before the `#query` map is populated in the SpEL context. Saved queries that reference keys which might be absent should use conditional SpEL expressions to avoid `NullPointerException`.
