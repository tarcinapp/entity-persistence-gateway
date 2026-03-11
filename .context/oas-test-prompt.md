# Prompt: Rigorously Test Dynamic OAS Generation

> **Usage:** Copy this entire prompt into a new chat session. Adjust the test configuration in §2 if needed.

---

## Goal

Rigorously test whether the dynamically generated OpenAPI Specification (OAS) **correctly reflects the intended schema and runtime behavior** for a user with the `member` role. The gateway transforms a generic backend OAS into a domain-projected, field-pruned, toggle-filtered spec. You must verify that every aspect of this transformation is correct.

---

## §1 — Preparation: Discover All Testable Dimensions

**Before writing any configuration or running any test**, read the implementation to build a complete mental model of what the OAS transformation engine can do. You need to understand every knob that affects the generated spec.

### 1.1 Files to Read (in order)

Read these files and extract every configuration dimension that affects the OAS output:

1. **`src/main/java/com/tarcinapp/entitypersistencegateway/config/OpenApiProperties.java`** — The config model. Extract every field from `AliasConfig`, `RouteConfig`, `ControllerConfig`, `Tag`, `Server`, `Contact`. Understand the hierarchy: controller → alias → routes, alias → children/parents.
2. **`src/main/java/com/tarcinapp/entitypersistencegateway/config/TogglesProperties.java`** — Three toggle dimensions: `routes.on/off`, `controllers.on/off`, `tags.on/off`. Understand how they interact.
3. **`src/main/java/com/tarcinapp/entitypersistencegateway/oas/config/OasOrchestratorProperties.java`** — Transformation knobs: `includeGenericEndpoints`, `simplifySchemaNames`, `autoGenerateOperationIds`. Understand defaults.
4. **`src/main/resources/app-oas.yml`** — Default config structure: `title`, `version`, `description`, `contact`, `servers[]`, `controllers{}`, `tags[]`.
5. **`src/main/resources/app-inbound.yml`** — `baseUri`, all `controllerBasePaths.*` (entities, lists, relations, entityReactions, listReactions, entitiesThroughList, listsThroughEntity, reactionsThroughEntity, reactionsThroughList, explorer), accessor segments (`defaultChildrenAccessor`, `defaultParentsAccessor`, per-record-type overrides).
6. **`src/main/resources/application-routes.yml`** — All route IDs grouped by controller. Extract the complete list of route IDs per controller (you'll need these to write alias route configs). Also examine the metadata tags on each route. Check .context/ROUTES.md
7. **`src/main/java/com/tarcinapp/entitypersistencegateway/oas/transformation/OasTransformationEngine.java`** — The core transform pipeline. Understand the processing order: `buildInfo()` → `buildServers()` → `buildTags()` → `transformPaths()` → `transformComponents()` → `addMergedDomainSchemas()` → `bindRequestBodiesToDomainSchemas()` → `addGatewayErrorResponses()` → `addForbiddenResponseToAllOperations()` → `fixBrokenRefs()` → `fixSchemaValidationKeywords()` → `deduplicateParameters()` → `applyGlobalSecurity()`. Understand how `transformPaths()` generates base controller routes vs. alias routes, and how toggle filtering is applied at controller, tag, and route levels.
8. **`src/main/java/com/tarcinapp/entitypersistencegateway/oas/transformation/OasSchemaPruner.java`** — Per-user field pruning via OPA. Understand that it queries OPA with 3 parallel requests (FIND/CREATE/UPDATE) and removes forbidden fields per-operation.
9. **`src/main/java/com/tarcinapp/entitypersistencegateway/oas/transformation/RouteMetadataService.java`** — How route metadata (tags, controllerName, recordType) is bridged to OAS transformation.

### 1.2 Build a Dimension Inventory

After reading, produce a structured list of **every configuration dimension** that affects OAS output, grouped as:

| Category | Dimension | Config Key | Default | Effect on OAS |
|----------|-----------|------------|---------|---------------|
| API Identity | Title | `app.oas.title` | (from app-oas.yml) | `info.title` in spec |
| API Identity | Version | `app.oas.version` | ... | `info.version` |
| ... | ... | ... | ... | ... |
| Path Structure | Base URI | `app.inbound.baseUri` | `/api/v1/` | Prefixes all paths |
| Path Structure | Controller base paths | `app.inbound.controllerBasePaths.entities` | `entities` | Path segment for entities |
| ... | ... | ... | ... | ... |
| Alias | Alias name | `app.oas.controllers.{ctrl}.aliases[n].alias` | — | Replaces controller segment in path |
| Alias | Kind | `app.oas.controllers.{ctrl}.aliases[n].kind` | — | Used for schema naming |
| ... | ... | ... | ... | ... |

This inventory determines the scope of testing. **Do not proceed to §2 until this inventory is complete.**

---

## §2 — Define Custom Test Configuration

**CRITICAL: Do NOT use any existing commented-out configuration from `application-dev.properties`.** You must define your own configuration from scratch.

Design a test configuration that exercises the maximum number of dimensions from the inventory in §1.2. The configuration must include:

### 2.1 Required Test Aliases

Define aliases that cover all 5 controllers, with at least one alias per controller having children and/or parents, at least one having route-level overrides, and at least one having route-level schema overrides. The schema properties you define must be **distinctive per alias** so you can verify they appear in the correct place.

**Required structure** (adapt based on your §1 reading):

Every controller MUST have **2+ top-level aliases** to enable the cross-record-type consistency test (§4.1.5). At least one alias per controller must have hierarchy (children and/or parents) to test hierarchy path generation.

```
# ENTITIES controller: 2+ top-level aliases
#   - Alias A: has children + parents, route-level schema override on create-child, custom operationIds
#   - Alias B: minimal (no children, no route overrides) — exists to enable cross-record-type comparison
# LISTS controller: 2+ top-level aliases
#   - Alias A: has children, route-level overrides
#   - Alias B: minimal
# RELATIONS controller: 2+ top-level aliases
#   - Alias A: with route-level overrides
#   - Alias B: minimal
# ENTITY REACTIONS: 2+ top-level aliases
#   - Alias A: has children, route-level overrides
#   - Alias B: minimal
# LIST REACTIONS: 2+ top-level aliases
#   - Alias A: has children, route-level overrides
#   - Alias B: minimal
```

For each alias schema, include:
- At least one `required` field and one optional field
- Different field types (`string`, `integer`, `boolean`) across aliases
- Fields with names that are easy to grep for later (e.g., `myCustomBookTitle` not just `title`)
- At least one field with `_` prefix (e.g., `_myCustomScore`) — verifies user-defined underscore fields appear in the output alongside system `_` fields

### 2.2 Required Route-Level Overrides

For at least one alias per controller, define route-level overrides testing:
- `operationId` override (does the custom ID appear instead of auto-generated?)
- `summary` override
- `description` override
- `tags[]` override (does the route get a custom tag?)
- `schema` override at route level (does the route-specific schema override the alias-level schema?)
- `validationEnabled` override (test true/false at route level)

### 2.3 Required Hierarchy Route Schema Override

For at least one alias, define a children config where:
- The child has an alias-level schema
- The child's `createEntityChild` (or equivalent) route has a **different** route-level schema
- This lets you verify priority: route-level schema > alias-level schema > parent schema

### 2.4 Incremental Configuration Approach

**Do NOT write all configuration at once.** Configuration is written incrementally — one feature at a time — following the test execution order in §4. Each test cycle adds only the configuration needed for that specific test.

Use the format:
```
app.oas.controllers.{controller}.aliases[{n}].{property}={value}
```

The incremental order is:
1. **Phase 0:** No alias config at all (zero-config baseline)
2. **Phase 1:** Add one controller's aliases → test → stop → add next controller's aliases → test → etc.
3. **Phase 2:** All controllers combined (accumulated from Phase 1)
4. **Phase 3:** Add toggle config on top of Phase 2
5. **Phase 4:** Swap in transformation knob overrides

API identity config (`app.oas.title`, `app.oas.version`, `app.oas.description`, `app.oas.servers`, `app.oas.tags`) should be written before Phase 0 so it's verified in the baseline.

---

## §3 — Infrastructure

### 3.1 Environment

- **Gateway:** `localhost:8081`, dev profile
- **Backend:** LoopBack on `localhost:3000` (serves the raw OAS at `/openapi.json`)
- **Redis:** `localhost:6379`, password from `application-dev.properties` (`app.outbound.redis.password`)
- **OPA:** `localhost:8181`
- **JWT Token (member role):**
  ```
  eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE4NTkzODgxNTYsImlhdCI6MTc1OTM4ODE1NiwianRpIjoiZjhmODExZWQtZmVlYy00NDRkLTlkNTQtMmVhOWQ2ZjIzNGRkIiwiaXNzIjoidGFyY2luYXBwLWlkbSIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiJkZWZhdWx0LXVzZXItaWQiLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJwb3N0bWFuIiwic2Vzc2lvbl9zdGF0ZSI6ImE1OGUyNDFiLWE2ZjItNDMzNy1hZGQyLWU3MzlmNzM2ZDU1OCIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiLyoiXSwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbInRhcmNpbmFwcC5tZW1iZXIiLCJkZWZhdWx0LXJvbGVzLXRhcmNpbmFwcCIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJncm91cHMiOlsiZGVmYXVsdC11c2VyLWdyb3VwIl0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJzaWQiOiJhNThlMjQxYi1hNmYyLTQzMzctYWRkMi1lNzM5ZjczNmQ1NTgiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwicm9sZXMiOlsidGFyY2luYXBwLm1lbWJlciIsImRlZmF1bHQtcm9sZXMtdGFyY2luYXBwIiwib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJ1c2VyLWJhc2ljLXZlcmlmaWVkLW1lbWJlci0xIiwiZ2l2ZW5fbmFtZSI6IiIsImZhbWlseV9uYW1lIjoiIn0.kC4DAqHeEFIPfi5ap8HWSIwtZWZdh1YzhIk6sw4iBTwtLYm8nG7ypjJD2GeOpc-yctQw-2LTEA7x1EFoxmZYLhC8YEYEKq7weSsnVicoyet77t805sHWFIsroSaqYFeVFTIWUl2OWS0cWUCJeAkpoaKLFA9vu_2QrMMo0w-I-JJSgLWOvCat3HvHuFlNkWb_Zw0cb8SHFmMkodnljVUf_AXdr1wjEOtVDJFDxiVUHZ9GIc-bFvh4O_q0FuoKLhhfgVKi4vEWYBJITHBLIbYQj7bAD_lcSF-bQVFt9pIeUmo7KMJj1NKi5kC1LmkjfU8um4zdF43Bwf60bZfRoi1oXg
  ```

### 3.2 Procedure

1. **Flush Redis:** `redis-cli -a {password} FLUSHDB`
2. **Start gateway:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
3. **Wait for ready:** Poll `curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/openapi.json -H "Authorization: Bearer {token}"` until 200
4. **Fetch OAS:** `curl -s http://localhost:8081/openapi.json -H "Authorization: Bearer {token}" -o /tmp/gateway-oas.json`
5. **Also fetch backend OAS for comparison:** `curl -s http://localhost:3000/openapi.json -o /tmp/backend-oas.json`
6. **Stop gateway:** `lsof -t -i:8081 | xargs kill -9` (NEVER use `pkill -f`)

---

## §4 — Test Execution Plan

Tests follow a **baseline → isolated → combined** structure:
1. **Phase 0:** Zero-config baseline — no aliases, no toggles. Verifies the gateway's transformation of the raw backend OAS (field pruning, error responses, security, forbidden fields) works even without any domain projection.
2. **Phase 1:** Isolated per-controller tests — one controller's aliases at a time.
3. **Phase 2:** Combined full-projection — all controllers together for cross-controller analysis.
4. **Phase 3:** Toggle tests.
5. **Phase 4:** Transformation knob tests.

**Test cycle procedure** (repeat for every individual test):
1. Write ONLY the config needed for this cycle to `application-dev.properties` (add new lines; comment out lines not relevant to this cycle)
2. Flush Redis
3. Start gateway
4. Wait for ready
5. Fetch OAS to `/tmp/gateway-oas-{test-name}.json`
6. Also fetch backend OAS to `/tmp/backend-oas.json` (first cycle only — it doesn't change)
7. Run all analyses for that cycle
8. Stop gateway
9. **Save tested config:** Copy the current `application-dev.properties` config block for this cycle into `.context/oas-tested-configs.md` under a heading for this test (e.g., `## Phase 1 — Entities Controller`). Include: the exact properties written, the OAS file path, and a one-line pass/fail verdict.
10. Proceed to next cycle

### Phase 0: Zero-Config Baseline (no aliases, no toggles)

**Config for this cycle:** Remove ALL `app.oas.controllers.*` alias configurations and ALL `app.toggles.*` configurations. The gateway has no domain projection at all — it only has the raw backend OAS to transform.

This test verifies: even without any alias/domain configuration, the gateway STILL applies its transformation pipeline to the backend OAS. Specifically:

#### 4.0.1 Fetch Both Specs
- Fetch the **raw backend OAS** from `http://localhost:3000/openapi.json` → `/tmp/backend-oas-raw.json`
- Fetch the **gateway-transformed OAS** from `http://localhost:8081/openapi.json` → `/tmp/gateway-oas-baseline.json`

#### 4.0.2 Structural Comparison
Compare the two specs structurally:
- **Path count:** Are paths identical, or does the gateway add/remove any?
- **Schema count:** Does the gateway add error schemas, security schemas, or remove any?
- **Operation count:** Same across both?

#### 4.0.3 OPA Field Pruning Verification (Critical)
The gateway queries OPA to determine which fields the `member` role is allowed to see/create/update for each record type. Even without aliases, this pruning must happen on the generic endpoints.

For each base record type's schemas (the generic entity/list/relation/reaction schemas from the backend):
- Compare the **backend's create request schema** vs. the **gateway's create request schema**
  - Are fields like `_ownerUsers`, `_ownerGroups`, or other admin-only/system-managed fields removed from the gateway's version?
  - List every field present in backend but absent in gateway, with rationale
  - List every field present in both
- Compare the **backend's response schema** vs. the **gateway's response schema**
  - Are any read-restricted fields removed?
- Compare the **backend's update request schema** vs. the **gateway's update request schema**
  - Are system-managed fields correctly excluded from update?

Use the same exhaustive field table format:

| # | Field Name | Type | In Backend? | In Gateway? | Rationale |
|---|-----------|------|-------------|-------------|-----------|
| 1 | _ownerUsers | array | ✅ | ❌ | System-managed, member cannot set on create |
| 2 | _name | string | ✅ | ✅ | User-editable field, allowed |
| ... | ... | ... | ... | ... | ... |

#### 4.0.4 Error Response Injection
- Does the gateway add error response schemas (4xx, 5xx) that don't exist in the backend?
- List all schemas present in gateway but absent in backend
- Does every operation now have `403 Forbidden`? (from `addForbiddenResponseToAllOperations()`)

#### 4.0.5 Security Scheme
- Does the gateway add `securitySchemes` and/or global `security` that the backend doesn't have?
- Compare `components.securitySchemes` between backend and gateway

#### 4.0.6 API Identity
- Does the gateway override `info.title`, `info.version`, `info.description` with its own config (from `app-oas.yml`)?
- Does it replace `servers[]`?

**This phase establishes the baseline transformation.** All subsequent phases build on top of this — they add alias/domain projection ON TOP of the baseline field pruning, error injection, and security. If Phase 0 finds that `_ownerUsers` is NOT pruned, that's a critical bug regardless of whether aliases are configured.

---

### Phase 1: Isolated Controller Tests

For **each of the 5 controllers** (entities, lists, relations, entityReactions, listReactions), run a **separate test cycle** with ONLY that controller's aliases configured. All other controller aliases must be commented out.

#### 4.1 Per-Controller Test Cycle

Repeat the entire block below for each controller. Name each OAS file distinctly (e.g., `/tmp/gateway-oas-entities.json`, `/tmp/gateway-oas-lists.json`, etc.).

##### 4.1.1 API Identity Verification (first cycle only)
- Verify `info.title`, `info.version`, `info.description` match your config
- Verify `info.contact` fields
- Verify `servers[]` entries

##### 4.1.2 Path Inventory — Exhaustive
For every path in the spec:
- List the path
- List all HTTP methods on that path
- For each operation: `operationId`, `summary`, `description`, `tags[]`
- State which alias this path belongs to
- State which route ID from `application-routes.yml` this maps to

Cross-check:
- **Expected path count:** Calculate from this controller's aliases: for each alias, how many routes does that controller define? Include hierarchy routes (children/parents). Compare to actual.
- **Missing paths:** Any route that should exist but doesn't? Explain why.
- **Unexpected paths:** Any path that shouldn't exist (e.g., from other controllers)? Why?

##### 4.1.3 Schema Inventory — Exhaustive
For every schema in `components.schemas`:
- Name
- Source: is this from backend (unmapped), merged (domain-projected), or gateway-added (error schemas)?
- For merged schemas: which alias does it belong to?

Cross-check:
- **Expected schema count:** Calculate. For each alias: response schema, `New{Alias}` request schema, possible `{Alias}WithRelations`. For hierarchy children/parents: separate schemas. Sum all + error schemas + any shared/base schemas.
- **Missing schemas:** Any expected schema not found?
- **Unexpected schemas:** Any schema that shouldn't exist?

##### 4.1.4 Field Analysis — Per Schema, Per Operation

**This is the most critical section.** For EVERY schema in `components.schemas` — domain-projected schemas, error schemas, and any backend-carried schemas alike. **No schema type is excluded.**

###### 4.1.4.1 Response Schema Field Analysis

For each response schema (e.g., `Book`, `Chapter`, `Bookshelf`...):

| # | Field Name | Type | Present? | Source | Rationale |
|---|-----------|------|----------|--------|-----------|
| 1 | id | string | ✅ | Backend base | Standard record identifier |
| 2 | _name | string | ✅ | Alias schema | Defined in alias config with required constraint |
| 3 | isbn | string | ✅ | Alias schema | Custom domain field from alias config |
| 4 | _kind | string | ✅ | Backend base | System-managed field, always included |
| 5 | _createdBy | string | ✅ | Backend base | Audit field present in backend base schema |
| 6 | someField | string | ❌ MISSING | — | Expected from alias schema but not found — BUG? |
| ... | ... | ... | ... | ... | ... |

- **List ALL fields present** in the schema, not just a sample
- **List ALL fields expected but missing** with rationale for why you expected them
- **List ALL unexpected fields** with rationale for why they shouldn't be there
- For each field, state its **source**: backend base schema, alias schema, or unknown

###### 4.1.4.2 Request Schema Field Analysis

For each `New{Alias}` schema (used in POST/PUT/PATCH request bodies):

Same table format as above, but additionally:
- Note which fields are in `required[]` — do they match the alias config's `required` array?
- Note if any backend-only fields (like `id`, `_createdAt`) are correctly excluded from the request schema
- If a route-level schema override exists for this operation, verify the override schema is used instead of the alias-level schema

###### 4.1.4.3 Error Schema Field Analysis

For each error response schema in `components.schemas` (4xx, 5xx types):

| # | Field Name | Type | Present? | Rationale |
|---|-----------|------|----------|-----------|
| 1 | statusCode | integer | ✅ | HTTP status code |
| 2 | name | string | ✅ | Error name/type |
| 3 | message | string | ✅ | Human-readable error message |
| ... | ... | ... | ... | ... |

- **List ALL error schemas** with complete field inventories
- Verify: does every operation reference the correct error response schemas?
- Check: does every operation have `403 Forbidden` response? (from `addForbiddenResponseToAllOperations()`)
- Are error schemas identical across all operations, or do they vary? Document any variance.

###### 4.1.4.4 Route-Level Schema Override Verification

For any route where you defined a route-level schema (§2.2, §2.3):
- Fetch the request body schema `$ref` for that specific operation
- Verify it points to the route-level schema, NOT the alias-level schema
- List the field diff between the route-level and alias-level schema

##### 4.1.5 Cross-Record-Type Consistency (if controller has 2+ aliases)

If this controller has 2+ top-level aliases, compare their schemas for each operation type:

| Field | AliasA | AliasB | Explanation |
|-------|--------|--------|-------------|
| id | ✅ | ✅ | Both have standard ID |
| _kind | ✅ | ✅ | Both have system kind field |
| customFieldA | ✅ | ❌ | AliasA-specific — correct |
| customFieldB | ❌ | ✅ | AliasB-specific — correct |
| _createdAt | ✅ | ✅ | Both have audit fields |
| ... | ... | ... | ... |

Verify:
- **System/base fields are identical across all record types** within the same controller
- **Custom fields are unique to their alias** and not leaking across
- Any inconsistency in base fields is a bug

##### 4.1.6 Cross-Operation Consistency

For each alias in this controller, compare the schema across different operations:

| Field | GET response | POST request | PATCH request | PUT request | DELETE response |
|-------|-------------|-------------|---------------|-------------|-----------------|
| id | ✅ | ❌ | ❌ | ❌ | ✅ |
| isbn | ✅ | ✅ | ✅ | ✅ | ❌ |
| _kind | ✅ | ❌ | ❌ | ❌ | ✅ |
| _createdAt | ✅ | ❌ | ❌ | ❌ | ✅ |
| ... | ... | ... | ... | ... | ... |

For each cell, state whether presence/absence is **correct** and why:
- `id` absent in POST = correct (server-generated)
- `_createdAt` absent in PATCH = correct (server-managed)
- `isbn` absent in PATCH = **BUG** if it's a user-editable field

##### 4.1.7 OperationId Analysis

For each operation in this controller's spec:
- What is the actual `operationId`?
- If a custom operationId was configured in the alias route, was it used?
- If auto-generated, is the generation correct? (Check singularization, alias name usage)
- List any malformed, truncated, or grammatically incorrect operationIds

##### 4.1.8 Tag Analysis

- List all tags in the spec's `tags[]` section
- For each tag, which operations reference it?
- Were custom tags from alias route configs included?
- Are there operations with NO tags? (May be a bug)

---

### Phase 2: Full Domain Projection (all controllers combined)

**New test cycle:** Configure ALL aliases from §2 simultaneously, no toggle configuration. Flush Redis, start gateway, fetch OAS to `/tmp/gateway-oas-combined.json`.

#### 4.2 Combined Spec Metrics
- File size, path count, operation count, schema count, tag count
- Compare: sum of isolated controller path counts vs. combined path count (should match)
- Compare: sum of isolated controller schema counts vs. combined (schemas may be shared — document which)

#### 4.3 Cross-Controller Consistency Analysis

Compare schemas **across controllers** (not possible during isolated tests):

For structurally similar operations (e.g., `create` across entities, lists, relations, entityReactions, listReactions):
- Are the base/system fields the same shape? (e.g., do all response schemas have `id`, `_kind`, `_createdAt`?)
- Are field types consistent? (e.g., is `id` always `string`, `_createdAt` always same type?)
- Do error schemas differ per controller or are they shared?

#### 4.4 Hierarchy Path Consistency

Compare hierarchy (children/parents) paths across controllers:
- Do entity children paths use the same accessor segment as list children paths?
- Are hierarchy schemas correctly scoped (hierarchy schema ≠ top-level schema for same kind)?

#### 4.5 Full OperationId Uniqueness
- Extract all operationIds from the combined spec
- Verify: no duplicates
- Verify: naming pattern is consistent across controllers

---

### Phase 3: Toggle Tests

Start from the full combined config (all aliases from Phase 2). For each toggle test, add toggle configuration, then run a full test cycle (flush Redis, start, fetch, analyze, stop).

#### 4.6 Whitelist Mode (`tags.on`)
- Set `app.toggles.tags.on[0]=entities`
- Verify: ONLY entities-controller paths appear
- Verify: schema count reduced (only entities-related schemas remain)
- Count paths and schemas, compare to Phase 2

#### 4.7 Blacklist Mode (`tags.off`)
- Set `app.toggles.tags.off[0]=count`
- Verify: all controllers present, but count operations removed
- Verify: other operations unaffected

#### 4.8 Controller Toggle
- Set `app.toggles.controllers.off[0]=entityReactions`
- Verify: all entityReactions paths removed, others intact

#### 4.9 Route Toggle
- Set `app.toggles.routes.off[0]={specific-route-id}`
- Verify: only that one route removed

#### 4.10 Combined Toggles
- Set both `tags.on` and `tags.off` simultaneously
- Document the behavior (known issue: `tags.on` may override `tags.off`)

---

### Phase 4: Transformation Knob Tests (if time permits)

Each is a separate test cycle starting from full combined config.

#### 4.11 `includeGenericEndpoints=true`
- Set `app.oas-orchestrator.transformation.includeGenericEndpoints=true`
- Verify: generic (non-aliased) endpoints now appear alongside aliased ones
- Count added paths

#### 4.12 `simplifySchemaNames=false`
- Set `app.oas-orchestrator.transformation.simplifySchemaNames=false`
- Verify: schema names revert to verbose backend format

---

## §5 — Output Format Requirements

### 5.1 Analysis Script Approach

Use Python scripts (run via `python3 -c "..."`) to extract and analyze OAS JSON. **Never rely on manual inspection alone.** For every claim you make, the script output must support it.

### 5.2 Field Comparison Tables

Every field comparison MUST use the table format from §4.4. **Never say "fields look correct"** — enumerate every field explicitly with its presence/absence, source, and rationale.

### 5.3 Counts and Checksums

For each OAS fetch, report:
- File size in bytes
- Number of paths
- Number of operations (sum of all methods across all paths)
- Number of schemas
- Number of tags

### 5.4 Bug Reporting

For each bug found, document:
- **ID:** BUG-N
- **Severity:** Critical / Major / Minor / Cosmetic
- **Category:** Path, Schema, Field, OperationId, Tag, Toggle, Description, Validation
- **Description:** What is wrong
- **Expected:** What should happen
- **Actual:** What actually happens
- **Evidence:** The specific data (path, schema name, field name, value) that proves the bug
- **Config that triggered it:** The exact config lines

### 5.5 Summary Document

Produce the final results in `.context/oas-test-results.md` with:
1. Executive summary with pass/fail verdict
2. Dimension inventory from §1.2
3. Test configuration used (§2)
4. Full analysis results for each section of §4
5. Bug list
6. Recommendations

---

## §6 — Cleanup

After all tests are complete:
1. **Restore `application-dev.properties`** to original state (all alias configs commented out, no toggle configs active)
2. **Stop the gateway**
3. **Report** what you changed and what you restored

---

## §7 — Anti-Patterns to Avoid

These are mistakes from a previous test session. **Do not repeat them.**

1. **Never reuse existing commented-out config.** The commented configs in `application-dev.properties` are examples. Design your OWN test configs with distinctive field names.
2. **Never say "fields look correct" without enumerating every field.** Every schema must have a complete field table.
3. **Never skip the dimension inventory.** If you don't know what `app.inbound.controllerBasePaths` does, you can't test whether it's working.
4. **Never analyze record types in isolation.** Always cross-compare same-controller aliases and cross-compare operations within one alias.
5. **Never skip error schemas.** They are part of the spec and must be verified.
6. **Never assume a field's source.** Trace every field back to either the backend base schema or the alias config.
7. **Never conflate response schemas and request schemas.** They have different fields. Analyze them separately.
8. **Never batch-summarize hierarchy schemas.** Hierarchy (children/parents) schemas may differ from top-level schemas for the same kind. Compare them explicitly.
