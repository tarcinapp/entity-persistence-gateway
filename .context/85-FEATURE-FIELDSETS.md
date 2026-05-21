# Fieldsets: Named Field Projections Applied to Response Payloads

## 1. FEATURE IDENTITY & PURPOSE

**Fieldsets** are named, pre-defined field projection rules that the gateway applies to response payloads on behalf of clients. Instead of listing individual fields on every request, a client supplies a single short name and the gateway translates that into a precise keep-or-drop rule before returning data.

### The Hard Problem Solved

Without fieldsets, clients must either accept full response payloads (wasteful for list endpoints) or send verbose field projection parameters each time. For managed fields like `_ownerUsers`, `_createdBy`, or `_visibility`, clients need to know the exact internal field names—and repeat them in every request.

Additionally, in many deployments the operator wants a **safe default** applied to all queries without clients opting in. Without a server-side default mechanism, every client must explicitly project fields, or all managed fields leak by default.

Fieldsets solve both problems:
- Operators define named projections once in configuration.
- Clients reference projections by name via `?fieldset=<name>`.
- Default projections fire automatically when no explicit `fieldset` is requested.
- Clients that need the full payload can opt out of any default with a single parameter.

---

## 2. IMPLEMENTATION MODEL

The `ApplyFieldsetConfig` gateway filter is attached to every read route across all resource types. It runs in the **response phase**, after backend data is received but before the response is sent to the client.

Runtime flow:
1. Filter reads the `fieldset` query parameter from the incoming request.
2. If a `fieldset` name was provided, the filter resolves the named definition.
3. If no `fieldset` name was provided, the filter checks for a resource-level default.
4. If a definition is found, the filter applies it to the response JSON.
5. If no definition applies, the payload is returned unchanged.

Field definitions are loaded from `application-fieldsets.yml` and managed by `FieldSetsConfiguration`. The filtering logic—including JSON path traversal for nested and array fields—is handled by `FieldsetService`.

---

## 3. FIELDSET MODES

Every fieldset definition has a `mode` property that controls how the `fields` list is interpreted.

| Mode | Behavior |
|------|----------|
| `show` | **Whitelist.** Only the listed fields are kept in the response. All other fields are removed. |
| `hide` | **Blacklist.** The listed fields are removed from the response. All other fields are kept. |

Field paths support:
- Top-level fields: `_id`, `_name`
- Dot-notation nested fields: `user.address.city`
- Array element fields: `items[*].name`

An empty `fields` list with `hide` mode produces no change (nothing is removed). An empty `fields` list with `show` mode produces an empty object.

---

## 4. BUILT-IN GLOBAL FIELDSETS

The following fieldsets are pre-configured in `application-fieldsets.yml` and are available on every resource type without any additional configuration:

| Fieldset Name | Mode | Effect |
|---------------|------|--------|
| `show-all` | `hide` | Returns all fields (hides nothing; empty hide list) |
| `show-managed-all` | `show` | Returns only managed fields (`_id`, `_kind`, `_name`, `_slug`, `_visibility`, `_version`, `_ownerUsers`, `_ownerGroups`, `_createdDateTime`, `_lastUpdatedDateTime`, `_lastUpdatedBy`, `_createdBy`, `_validFromDateTime`, `_validUntilDateTime`, `_idempotencyKey`, `_viewerUsers`, `_viewerGroups`, `_parents`) |
| `hide-managed-all` | `hide` | Removes all managed fields; returns only application-defined fields |
| `hide-managed-except-id` | `hide` | Removes managed fields except `_id` |
| `hide-managed-except-id-kind-name` | `hide` | Removes managed fields except `_id`, `_kind`, `_name` |

---

## 5. FIELDSET RESOLUTION ALGORITHM

When a request arrives at the `ApplyFieldsetConfig` filter, the definition to apply is resolved in this order:

1. **Explicit request** — if `?fieldset=<name>` is present:
   a. Look for `<name>` in the resource-specific fieldsets (`app.fieldsets.<resourceType>.fieldsets`).
   b. If not found, look in global fieldsets (`app.fieldsets.global`).
   c. If still not found, log a warning and return payload unchanged.
2. **Resource default** — if no `fieldset` was specified, look for `app.fieldsets.<resourceType>.defaultFieldset`. If it names a valid definition, resolve it using the same resource → global lookup chain.
3. **No match** — return payload unchanged.

---

## 6. DEFAULT FIELDSET

Operators can set a default projection per resource type. When a client sends a read request **without** a `?fieldset=` parameter, the gateway automatically applies the configured default:

```yaml
app:
  fieldsets:
    entities:
      defaultFieldset: hide-managed-except-id
    lists:
      defaultFieldset: null   # no default; full payload returned
```

With `entities.defaultFieldset: hide-managed-except-id`, a plain `GET /api/v1/entities` returns records with managed fields hidden (except `_id`), with no client-side opt-in required.

When a default fieldset is configured and a client needs the **full, unfiltered payload**, it should use the built-in `show-all` fieldset:

```http
GET /api/v1/entities?fieldset=show-all
```

The `show-all` fieldset uses `hide` mode with an empty fields list, which effectively returns all fields.

---

## 7. CONFIGURATION REFERENCE

### 7.1 Configuration File

`application-fieldsets.yml` (imported via `application.yml`).

### 7.2 Structure

```yaml
app:
  fieldsets:
    global:                          # Available to all resource types
      <fieldset-name>:
        mode: show | hide
        fields:
          - <field-path>
          - <field-path>

    entities:
      defaultFieldset: <name> | null  # Applied when no ?fieldset= is sent
      fieldsets:                       # Resource-specific definitions
        <fieldset-name>:
          mode: show | hide
          fields:
            - <field-path>

    lists:
      defaultFieldset: null
      fieldsets: {}

    relations:
      defaultFieldset: null
      fieldsets: {}

    entityReactions:
      defaultFieldset: null
      fieldsets: {}

    listReactions:
      defaultFieldset: null
      fieldsets: {}
```

### 7.3 Environment Variable Overrides

Spring Boot relaxed binding supports configuring fieldsets via environment variables:

```bash
# Define a custom global fieldset named "bookinfo"
APP_FIELDSETS_GLOBAL_BOOKINFO_MODE=show
APP_FIELDSETS_GLOBAL_BOOKINFO_FIELDS=_id,_name,author,isbn

# Set it as the default for entity routes
APP_FIELDSETS_ENTITIES_DEFAULTFIELDSET=bookinfo
```

---

## 8. QUERY INTERFACE

| Parameter | Type | Description |
|-----------|------|-------------|
| `?fieldset=<name>` | string | Name of the fieldset to apply to this response. Use `show-all` to bypass any configured default and receive the full payload. |

### 8.1 Request Examples

**Use a built-in global fieldset:**
```http
GET /api/v1/entities?fieldset=hide-managed-all
```

**Use a custom resource-specific fieldset:**
```http
GET /api/v1/lists?fieldset=compact
```

**Bypass the default fieldset to receive the full payload:**
```http
GET /api/v1/entities?fieldset=show-all
```

---

## 9. RESOURCE COVERAGE

`ApplyFieldsetConfig` is wired on read routes across all resource types:

| Resource Type | Config Key | Routes |
|---------------|-----------|--------|
| Entities | `app.fieldsets.entities` | `findEntities`, `findEntityById`, and all kind-alias equivalents |
| Lists | `app.fieldsets.lists` | `findLists`, `findListById`, and all kind-alias equivalents |
| Relations | `app.fieldsets.relations` | `findRelations`, `findRelationById` |
| Entity Reactions | `app.fieldsets.entityReactions` | `findEntityReactions`, `findEntityReactionById` |
| List Reactions | `app.fieldsets.listReactions` | `findListReactions`, `findListReactionById` |

---

## 10. INTERACTION WITH OTHER FEATURES

**Field Masking** and fieldsets are distinct and both apply:
- Fieldsets are a **response-shaping convenience** feature. They do not carry security weight.
- Field Masking is a **security enforcement** feature driven by OPA policy decisions.
- Both run in the response phase; field masking always has final authority over what fields are visible regardless of fieldset settings.

If a fieldset's `show` mode includes a field that is also forbidden by policy, field masking removes it from the response after fieldset processing. Fieldset definitions have no power to override field masking policy.

---

## 11. TROUBLESHOOTING

### Fieldset not applied

- Verify `?fieldset=<name>` is present in the request URL.
- Confirm the name exists in `app.fieldsets.global` or `app.fieldsets.<resourceType>.fieldsets`.
- Confirm the route includes `ApplyFieldsetConfig` in its filter chain.

### Default fieldset not applied

- The default only fires when **no** `?fieldset=` parameter is sent.
- Verify `app.fieldsets.<resourceType>.defaultFieldset` references an existing defined fieldset name.
- Confirm the route includes `ApplyFieldsetConfig` in its filter chain.

### Fields still present after hide-mode fieldset

- Verify the fieldset mode is `hide`, not `show`.
- Check that field path strings exactly match response JSON keys (case-sensitive).
- For nested fields, confirm dot-notation path is correct (for example `user.address` not `address`).
- For fields inside arrays, use `items[*].fieldName` notation.

### Unexpected empty response after show-mode fieldset

- `show` mode is a strict whitelist; any field not in `fields` is removed.
- Ensure all required fields are listed, including `_id` if needed.
- Verify field names match the exact JSON keys returned by the backend.
