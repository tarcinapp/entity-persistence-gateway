# Field Masking: Multi-Layer Protection for Read, Query, Sort, Count, and Replace Flows

## 1. FEATURE IDENTITY & PURPOSE

**Field Masking** is the gateway capability that enforces field-level access rules consistently across:
- response payload visibility (what a caller can read),
- query predicates (what a caller can filter by),
- ordering (what a caller can sort by),
- count-style queries (what a caller can use to scope counting),
- full replacement flows (preventing blind deletion of hidden data).

The core objective is simple: **if a field is forbidden for the caller in the current operation context, that field must not become observable or usable through any API shape**.

### The Problem

Field-level restrictions are not safe if they exist only in one place.

If the gateway only redacts response JSON, a caller could still:
- infer data through filtered lookups,
- infer ordering differences by sorting on hidden fields,
- scope count queries by hidden fields,
- accidentally wipe hidden fields during `PUT` replace operations.

### The Solution

The gateway applies field masking as a **defense-in-depth pipeline**:
1. Fetch field permissions from OPA (`FetchForbiddenFields`).
2. Enforce request-time protections (`PreventQueryByForbiddenFields`) for query/filter/order/count semantics.
3. Enforce response-time redaction (`FieldFilter`) for root and related records.
4. Preserve hidden fields during replace (`AddForbiddenFieldsFromOriginalToPayloadInReplace`) to prevent blind updates.

Together, these layers make forbidden fields effectively non-existent from the caller's perspective.

---

## 2. AUTHORIZATION CONCEPTS FOR FIELDS

### 2.1 Forbidden-to-Read

A **forbidden-to-read** field is a field that must never appear in response payloads for the caller.

Enforcement mechanisms:
- `FieldFilter` removes forbidden fields from root records.
- `FieldFilter` also sanitizes included/related/lookup data.
- lookup constraint auditing removes unsafe lookup targets when filters relied on forbidden fields.

### 2.2 Forbidden-to-Update

A **forbidden-to-update** field is a field that the caller must not be able to mutate.

In partial update/query-driven operations, query checks prevent using forbidden fields for restricted targeting behavior.
In full replace (`PUT`) flows, the gateway merges forbidden fields from the original record back into payload to avoid unauthorized overwrite/removal.

### 2.3 Forbidden-to-Create

A **forbidden-to-create** field is a field that should not be caller-controlled at creation time.

Conceptually, this is enforced by route authorization policy decisions and downstream field governance (for example managed/system fields). Even if a caller submits such fields, effective policy and server-side transformation determine what survives as authoritative persisted values.

### 2.4 Operation Context Matters

Field restrictions are operation-aware through `PolicyData.operation` (`find` vs `update`) and route context. This enables OPA to return different forbidden sets by operation semantics while using a single policy endpoint.

---

## 3. OPA'S ROLE (POLICY SOURCE OF TRUTH)

The gateway is the **Policy Enforcement Point (PEP)**. OPA is the **Policy Decision Point (PDP)**.

### 3.1 Central Forbidden-Fields Policy

Field masking rules are retrieved through:

```text
/policies/gateway/forbidden_fields/policy/result
```

`FetchForbiddenFields` clones current `PolicyData`, sets this policy path, and executes the authorization client.

### 3.2 Returned Rule Model

The response maps forbidden fields by record type and kind:
- record type scope (`entities`, `lists`, `relations`, `entityReactions`, `listReactions`),
- default fields for all kinds,
- kind-specific fields.

`ForbiddenFieldsLibrary.resolveForbiddenFields(recordType, kind)` merges default + kind-specific rules for enforcement.

### 3.3 Fail-Closed Behavior

If forbidden-fields retrieval fails (OPA/client timeout/error), the request is blocked (`500`) rather than continuing without policy.

This prevents accidental data exposure during policy system instability.

---

## 4. RUNTIME ENFORCEMENT PIPELINE

### 4.1 Request Phase: Query/Sort/Count Protections

`PreventQueryByForbiddenFields` inspects query params and applies two strategies:

1. **Where-clause sanitization**
- Forbidden predicates are replaced with an impossible condition (`[_id]=__FORBIDDEN__`).
- This neutralizes hidden-field filtering and prevents inference by selection.

2. **Order-clause stripping**
- Forbidden sort fields are removed from `order` directives.
- This prevents side-channel inference through sorted result behavior.

Coverage includes:
- root query clauses,
- include scope clauses,
- nested include scope clauses,
- count/update-all/delete-all style where notation (after query normalization).

Lookup query keys are intentionally skipped in this request-phase filter because, at query time, looked-up targets are only reference/property strings and their effective `_recordType`/`_kind` cannot be resolved reliably; enforcement is therefore completed in response-phase audit logic after concrete records are materialized.

### 4.2 Response Phase: Definitive Redaction

`FieldFilter` + `FieldFilterService` apply final masking to outgoing JSON:
- root records are cleaned by record type + kind rules,
- targeted related data from includes/lookups is cleaned,
- nested paths are traversed and deleted (dot-notation aware).

This ensures hidden fields never appear in final responses, even if upstream data included them.

### 4.3 Replace Phase: Blind-Update Protection

`AddForbiddenFieldsFromOriginalToPayloadInReplace` runs on `PUT` replace routes:
- reads original record from `PolicyData.originalRecord`,
- resolves forbidden fields for that record,
- injects original forbidden values back into payload.

Result: callers cannot accidentally erase or overwrite fields they were never allowed to control.

---

## 5. LOOKUPS & RELATED DATA SECURITY

Field masking for related data is not limited to top-level response redaction.

### 5.1 Includes and Nested Includes

Query-time checks already sanitize forbidden `where` and `order` fields in include scopes.

Response-time filtering then removes forbidden fields from included records recursively.

### 5.2 Lookup Constraint Audit (Polymorphic Safety)

For lookups, the gateway performs a dedicated audit:
1. `QueryStringTargetAnalyzer` maps lookup property targets and fields used in lookup `scope[where]`.
2. `FieldFilterService` checks whether any used lookup filter field is forbidden for returned record types/kinds.
3. If violation is detected, the entire lookup target is removed from the response.

This blocks inference attacks where a caller tries to filter polymorphic lookup data using forbidden fields.

### 5.3 If Caller Explicitly Requests Forbidden Data

Even if client query/include/lookup intent explicitly references restricted data:
- forbidden predicates are neutralized or removed in request phase,
- forbidden fields are removed from response payload in response phase,
- unsafe lookup results are dropped when filter constraints are forbidden.

So explicit requests do not bypass field visibility policy.

---

## 6. QUERYING, SORTING, AND COUNTING BY FORBIDDEN FIELDS

### 6.1 Querying by Forbidden Fields

Forbidden `where` clauses are rewritten into impossible `_id` conditions. Practical effect:
- no matching records are returned based on hidden fields,
- caller cannot learn hidden values by probing predicates.

### 6.2 Sorting by Forbidden Fields

Forbidden sort keys are removed from `order` clauses. Practical effect:
- result ordering cannot encode hidden-field information,
- side-channel sorting leaks are prevented.

### 6.3 Counting by Forbidden Fields

Count routes normalize to where-style notation and run through `PreventQueryByForbiddenFields` before backend call. Practical effect:
- forbidden filter criteria cannot scope count results,
- count endpoints do not leak hidden-field distribution.

---

## 7. ROUTE WIRING PATTERN

Typical protected read/count route filter sequence:

```yaml
filters:
  - AuthenticateRequest
  - FetchForbiddenFields
  - name: AuthorizeRequest
    args:
      policyName: /policies/auth/routes/<controller>/<routeId>/policy
  - ConvertSimplerQueriesToBackendFormat
  - AddSetsTo*Query
  - PreventQueryByForbiddenFields
  - InjectTypeHintsToQuery
  - RemoveRequestHeader=Authorization
  - FieldFilter
```

Replace (`PUT`) routes include:

```yaml
- FetchForbiddenFields
- AddForbiddenFieldsFromOriginalToPayloadInReplace
```

This ordering is critical: policy fetch must happen before query/payload/response enforcement steps.

---

## 8. ERROR HANDLING & SECURITY POSTURE

- **Fail-closed on policy fetch errors:** request blocked if forbidden-field rules are unavailable.
- **Sanitization over silent trust:** forbidden query intent is actively rewritten/stripped.
- **Final response redaction:** last-mile guarantee before payload leaves gateway.
- **Replace preservation:** prevents hidden-field loss in full-document writes.

Net effect: field masking remains enforced even when clients submit aggressive or explicit hidden-field queries.

---

## 9. INTEGRATION WITH OTHER FEATURES

| Feature | Integration |
|---------|-------------|
| **Authorization** | Same `PolicyData` context and OPA decision flow; route allow/deny and field visibility remain consistent |
| **Query Simplification** | Simplified queries are first normalized; forbidden-field query guard then acts on normalized structure |
| **Domain Projection** | Includes/lookups can be alias-projected, but field masking still evaluates actual response records by `_recordType`/`_kind` |
| **Dynamic OAS** | Reuses the same forbidden-fields policy path to produce permission-aware schema visibility |

---

## 10. FURTHER READING

- **[45-FEATURE-AUTHORIZATION.md](45-FEATURE-AUTHORIZATION.md)** - route-level authorization and policy-data model
- **[80-FEATURE-QUERY-SIMPLIFICATION.md](80-FEATURE-QUERY-SIMPLIFICATION.md)** - normalization of simpler query syntax
- **[70-FEATURE-DOMAIN-PROJECTION.md](70-FEATURE-DOMAIN-PROJECTION.md)** - include alias projection and hierarchy context
- **[20-FILTERS.md](20-FILTERS.md)** - low-level filter behavior and route filter ordering
- **[90-FEATURE-DYNAMIC-OAS-GENERATION.md](90-FEATURE-DYNAMIC-OAS-GENERATION.md)** - permission-aware OpenAPI pruning

---

**Last Updated:** March 2026