# Route Toggle Configuration

## 1. FEATURE IDENTITY & PURPOSE

**Route Toggles** provide runtime control over which API endpoints are accessible. Without redeployment, you can enable or disable routes by their ID, entire controllers, or groups of routes by tag.

### Use Cases

- **Feature Flagging:** Hide unreleased endpoints from production
- **Security Hardening:** Disable DELETE operations globally
- **Maintenance Mode:** Temporarily disable write operations
- **Domain Scoping:** Expose only the subset of routes relevant to your business
- **Gradual Rollout:** Enable new routes incrementally

---

## 2. CONFIGURATION STRUCTURE

**File:** `application-route-toggles.yml`

```yaml
app:
  toggles:
    routes:
      off:
        - route1
        - route2
      on:
        - route3
        - route4
    controllers:
      off:
        - controller1
        - controller2
      on:
        - controller3
        - controller4
    tags:
      off:
        - tag1
        - tag2
      on:
        - tag3
        - tag4
```

### 2.1 Toggle Sections

| Section | Scope | Description |
|---------|-------|-------------|
| `routes` | Individual route IDs | Toggle specific routes by their ID (e.g., `createEntity`, `findEntitiesByKindAlias`) |
| `controllers` | Entire controllers | Toggle all routes belonging to a controller (e.g., `entities`, `lists`) |
| `tags` | Route tags | Toggle groups of routes by their semantic tags (e.g., `write`, `destructive`) |

**References:**
- **Route IDs:** See [10-ROUTES.md](10-ROUTES.md) for the complete route inventory
- **Controllers:** `entities`, `lists`, `relations`, `entityReactions`, `listReactions` — see [10-ROUTES.md](10-ROUTES.md)
- **Tags:** See [ROUTE-TAGS-REPORT.md](ROUTE-TAGS-REPORT.md) for available tags and their distribution

### 2.2 On/Off Lists

Each section has two lists:
- **`off`**: Routes matching this criteria are disabled (404 Not Found)
- **`on`**: Routes matching this criteria are explicitly enabled

---

## 3. EVALUATION ORDER & PRECEDENCE

The `CheckIfRouteEnabled` filter evaluates toggles in a **sequential short-circuit** manner:

1. **Controller-level** is evaluated first — if disabled, returns 404 immediately
2. **Tag-level** is evaluated second — if disabled, returns 404 immediately
3. **Route-level** is evaluated last — if disabled, returns 404

**Key Implication:** A route disabled at the controller level cannot be enabled at the route level. The evaluation stops as soon as any level returns "disabled."

### 3.1 On/Off Logic Within Each Level

Within each level, the `on` and `off` lists work as follows:

- **If `on` is non-empty:** Whitelist mode — only items explicitly listed in `on` are enabled. All other routes are disabled
- **If `on` is empty but `off` is non-empty:** Blacklist mode — items listed in `off` are disabled only

**`on` takes precedence over `off`** — when `on` is configured, the `off` list is ignored.

### 3.2 Example: Controller Disabled Overrides Route Enabled

```yaml
app:
  toggles:
    routes:
      on:
        - findEntities          # Attempting to enable this route
    controllers:
      off:
        - entities              # Entire controller disabled
```

Result: `findEntities` is **DISABLED** — controller-level evaluation happens first and blocks the request before route-level is even checked.

### 3.3 Example: Whitelist Mode

```yaml
app:
  toggles:
    tags:
      on:
        - read-only             # Only read-only routes are enabled
      off:
        - destructive           # This is IGNORED because 'on' is configured
```

Result: Only routes tagged with `read-only` are enabled. The `off` list has no effect.

---

## 4. TOGGLE VALUE REFERENCE

### 4.1 Controllers

The gateway has five controllers, each managing a specific resource type:

| Controller | Description |
|------------|-------------|
| `entities` | Core business objects |
| `lists` | Grouped collections of entities |
| `relations` | Relationships between entities |
| `entityReactions` | Reactions (likes, votes, etc.) on entities |
| `listReactions` | Reactions on lists |

### 4.2 Route IDs

Route IDs follow a predictable naming pattern:

- **Standard routes:** `{action}{Controller}` — e.g., `createEntity`, `findLists`, `deleteRelationById`
- **Kind alias routes:** `{baseRouteId}ByKindAlias` — e.g., `createEntityByKindAlias`, `findEntitiesByKindAlias`
- **Through-routes:** `{action}{Target}By{Source}Id` — e.g., `findEntitiesByListId`, `findListsByEntityId`

> **Complete Reference:** See [10-ROUTES.md](10-ROUTES.md) for the full route inventory.

### 4.3 Tags

Tags categorize routes by their behavior and purpose:

| Tag Category | Examples | Description |
|--------------|----------|-------------|
| **Operation type** | `write`, `read-only`, `manage` | What the route does |
| **Risk level** | `destructive` | Routes that delete data |
| **Route type** | `kind-alias`, `hierarchical`, `through` | Special routing behavior |
| **HTTP method** | `post`, `get`, `patch`, `delete` | HTTP verb used |

> **Complete Reference:** See [ROUTE-TAGS-REPORT.md](ROUTE-TAGS-REPORT.md) for all tags and their distribution across routes.

---

## 5. EXAMPLE SCENARIOS

### 5.1 Disable All Destructive Operations (Using Tags)

```yaml
app:
  toggles:
    tags:
      off:
        - destructive
```

This disables all 10 delete routes in a single line.

### 5.2 Disable All Write Operations (Using Tags)

```yaml
app:
  toggles:
    tags:
      off:
        - write
```

This disables all 40 write routes (create, update, replace, delete) across all controllers.

### 5.3 Disable Relations Controller Entirely

```yaml
app:
  toggles:
    controllers:
      off:
        - relations
```

### 5.4 Read-Only Mode (Disable Writes, Keep Reads)

Using tags for a cleaner configuration:

```yaml
app:
  toggles:
    tags:
      off:
        - write
        - manage
```

### 5.5 Enable Only Specific Routes (Whitelist Approach)

To enable only specific routes, use the `on` list at the route level:

```yaml
app:
  toggles:
    routes:
      on:
        - findEntitiesByKindAlias
        - findEntityByIdByKindAlias
```

Result: Only `findEntitiesByKindAlias` and `findEntityByIdByKindAlias` are enabled. All other routes return 404.

> **Note:** Since controller-level is evaluated before route-level, do NOT combine controller `off` with route `on` expecting the route to override — it won't work. Use route-level `on` alone for whitelist mode.

---

## 6. RUNTIME BEHAVIOR

### 6.1 CheckIfRouteEnabled Filter

The `CheckIfRouteEnabled` gateway filter evaluates toggles on every request in a **sequential short-circuit** manner:

1. Extracts route ID, controller name, and tags from the matched route
2. **Controller-level check** — if disabled, returns 404 immediately
3. **Tag-level check** — if disabled, returns 404 immediately
4. **Route-level check** — if disabled, returns 404
5. If all checks pass, the request continues to the next filter

This evaluation order means broader scopes (controllers) are checked before narrower scopes (individual routes).

### 6.2 OAS Integration

Disabled routes are automatically excluded from the generated OpenAPI specification. Users never see documentation for routes they cannot access.

---

## 7. RELATED DOCUMENTATION

- **[55-FEATURE-DOMAIN-PROJECTION.md](55-FEATURE-DOMAIN-PROJECTION.md)** — Kind aliases and domain-specific API configuration
- **[10-ROUTES.md](10-ROUTES.md)** — Complete route inventory and naming conventions
- **[11-ROUTE-TAGS.md](11-ROUTE-TAGS.md)** — Route tag definitions and usage
- **[ROUTE-TAGS-REPORT.md](ROUTE-TAGS-REPORT.md)** — Complete tag distribution and statistics
- **[60-FEATURE-DYNAMIC-OAS-GENERATION.md](60-FEATURE-DYNAMIC-OAS-GENERATION.md)** — How toggles affect generated documentation

---

**Last Updated:** February 2026
