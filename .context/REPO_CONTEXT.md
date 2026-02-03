# 01_REPO_CONTEXT.md

## REPOSITORY IDENTITY: entity-persistence-gateway

**MISSION & PURPOSE:**
The **`entity-persistence-gateway`** functions as the primary **API Gateway** and **Policy Enforcement Point (PEP)** for the Tarcinapp ecosystem.

It sits directly in front of the **Generic Data Contract** (implemented by `entity-persistence-service` or compatible Orchestrators), acting as the guardian and interpreter of all traffic. Its fundamental role is to **project a specific Business Domain onto a Generic JSON REST Backend** at runtime.

Instead of writing custom backend code for every new SaaS feature, this Gateway configures a "Virtual API" that enforces strict enterprise requirements on raw data flow:

* **Security & Access:** Enforces **Authentication (JWT)** and Fine-Grained **Authorization (OPA)** down to the field level (**Field Masking** & Redaction).
* **Runtime Domain Projection:** Instantly transforms generic resources (e.g., `/entities`) into domain-specific REST endpoints (e.g., `/products`, `/orders`) via **Kind Path Aliasing**, handling all `_kind` injections and query rewriting transparently.
* **Data Integrity:** Guarantees atomicity via **Distributed Locking** (Redis based) and enforces strict **JSON Schema Validation** on incoming payloads.
* **Performance & Stability:** Protects downstream services with **Rate Limiting**, manages **HTTP Caching** headers, and handles **Response Shaping** (Field Sets) to prevent over-fetching.
* **Observability:** Provides full visibility through **Distributed Tracing** and ensures standard **Audit** attributes (`_lastUpdatedBy`) are injected.

**TECHNOLOGY STACK:**
* **Framework:** Spring Boot 3.4.0 + Spring Cloud Gateway 2024.0.0
* **Paradigm:** Reactive (WebFlux / Netty)
* **Language:** Java 21
* **Storage & State:** Redis (Reactive) + Redisson 3.41.0
* **Security:** Auth0 JWKS + JJWT 0.11.5
* **Validation:** NetworkNT JSON Schema Validator 1.5.0

---

## 1. CONFIGURATION STRUCTURE

The application follows a **Modular Configuration Architecture**. The `application.yml` acts as the root orchestrator.

* **`application.yml`**: The root configuration. It sets up the Netty server, Redis connections, and imports specific feature configurations via `spring.config.import`.
* **`application-routes.yaml`**: The **Single Source of Truth** for all exposed endpoints. It contains:
    1.  The Base Generic Routes (approx. 67 endpoints matching the backend contract).
    2.  The Kind Alias Routes (Domain-specific wrappers like `/books` -> `/entities`).
* **Feature Configs**: Specific settings (Rate Limits, Locks, Auth) are isolated in their own YAML files (e.g., `application-locks.yml`, `application-auth.yml`) and imported by the root file.

---

## 2. ROUTING TOPOLOGY & NAMING CONVENTION

All routes are defined in `application-routes.yaml`. The routing topology is highly structured, following a predictive grammar based on the controller type and operation scope.

For a comprehensive inventory of all available routes, including specific ID patterns and HTTP methods, refer to the ROUTES.md file.

### A. The Two Dimensions of Routing
We categorize routes based on **Context** (Core vs. Domain) and **Topology** (Root vs. Traversal).

1.  **Generic Core Routes (The Base 67):**
    Direct mappings to the backend's REST interface. Used for raw data access.
2.  **Kind Alias Routes:**
    Domain-specific wrappers (e.g., `/books` -> `/entities`). They mirror the Generic structure but act on a specific `_kind`.

---

### B. Naming Convention Grammar

Route IDs are not random; they describe the operation's topology.

#### 1. Standard Root Controllers
*Applies to: `Entities`, `Lists`, `Relations`, `EntityReactions`, `ListReactions`*

These controllers manage the resource directly.

| Scope | ID Pattern | HTTP | URI Pattern |
| :--- | :--- | :--- | :--- |
| **Collection** | `create{Res}` | `POST` | `/api/v1/{res}` |
| **Collection** | `find{Res}` | `GET` | `/api/v1/{res}` |
| **Collection** | `count{Res}` | `GET` | `/api/v1/{res}/count` |
| **Single Item** | `find{Res}ById` | `GET` | `/api/v1/{res}/{id}` |
| **Single Item** | `update{Res}ById` | `PATCH` | `/api/v1/{res}/{id}` |
| **Single Item** | `replace{Res}ById` | `PUT` | `/api/v1/{res}/{id}` |
| **Single Item** | `delete{Res}ById` | `DELETE` | `/api/v1/{res}/{id}` |
| **Hierarchy** | `find{Res}Children` | `GET` | `/api/v1/{res}/{id}/children` |
| **Hierarchy** | `create{Res}Child` | `POST` | `/api/v1/{res}/{id}/children` |
| **Hierarchy** | `find{Res}Parents` | `GET` | `/api/v1/{res}/{id}/parents` |

*Note: `Relations` controller is "Flat"; it supports Collection and Single Item ops, but NO Hierarchy ops.*

#### 2. Traversal ("Through") Controllers
*Applies to: `EntitiesThroughList`, `ListsThroughEntity`, `ReactionsThrough...`*

These controllers manage the **Many-to-Many** graph connections explicitly.

| Action | ID Pattern | HTTP | URI Pattern |
| :--- | :--- | :--- | :--- |
| **Find** | `find{Target}Through{Source}ById` | `GET` | `/api/v1/{source}/{id}/{target}` |
| **Count** | `count{Target}Through{Source}ById` | `GET` | `/api/v1/{source}/{id}/{target}/count` |
| **Link** | `add{Target}To{Source}` | `POST` | `/api/v1/{source}/{id}/{target}` |
| **Unlink** | `remove{Target}From{Source}` | `DELETE` | `/api/v1/{source}/{id}/{target}/{targetId}` |

#### 3. Kind Alias Suffix Rule
For routes configured as **Kind Aliases** (Domain Routes), the standard ID is preserved but appended with a suffix.

* **Pattern:** `{StandardID}ByKindAlias`
* **Example:** `createEntity` (Generic) becomes `createEntityByKindAlias` (Domain).
* **Path Change:** `/api/v1/entities` becomes `/api/v1/{kind-alias}` (e.g., `/api/v1/products`).

---
### B. Route Categories
1.  **Generic Core Routes:**
    Direct mappings to the backend's REST interface.
    * *Path:* `/entities/**`, `/lists/**`, `/relations/**`
    * *Goal:* Raw access to generic types (usually for internal tools or generic clients).

2.  **Kind Alias (Domain) Routes:**
    Specialized routes that inject domain context.
    * *Path:* Custom (e.g., `/books`, `/orders`)
    * *Goal:* Presenting a specific business API while using the generic backend.

---



## 3. THE FILTER ARSENAL (Feature Implementation)

Features described in the System Manifest are implemented here as specific **GatewayFilterFactory** classes. Below is the definitive mapping of Logical Features to actual Implementation Classes and configuration keys found in `application-routes.yaml`.

### A. Quick Reference Table

#### Built-in Spring Cloud Gateway Filters

| Filter Name | Category | Purpose |
|------------|----------|---------|
| `RewritePath` | Routing | Rewrites request URL paths |
| `RemoveRequestHeader` | Transformation | Removes specific headers |
| `RequestSize` | Validation | Limits request body size |
| `RequestRateLimiter` | Protection | Rate limiting per user/IP |
| `SetStatus` | Response | Sets HTTP status code |

#### Custom Application Filters

| Filter Name | Category | Purpose |
|------------|----------|---------|
| `CheckIfRouteEnabled` | Control | Enables/disables routes dynamically |
| `AuthenticateRequest` | Security | Validates authentication tokens |
| `GenerateRequestId` | Tracing | Generates unique request identifiers |
| `FetchForbiddenFields` | Authorization | Retrieves field-level permissions |
| `AuthorizeRequest` | Security | Policy-based authorization |
| `AcquireLockForCreation` | Concurrency | Distributed locking for creates |
| `AcquireLockForUpdate` | Concurrency | Distributed locking for updates |
| `AddManagedFieldsInCreation` | Transformation | Adds system-managed fields |
| `AddManagedFieldsFromOriginalToPayloadInReplace` | Transformation | Preserves managed fields in PUT |
| `AddForbiddenFieldsFromOriginalToPayloadInReplace` | Transformation | Preserves forbidden fields in PUT |
| `ApplyFieldsetConfig` | Transformation | Applies fieldset projections |
| `FieldFilter` | Transformation | Filters response fields |
| `PreventStringifiedJsonFilter` | Validation | Prevents stringified JSON queries |
| `ConvertSimplerQueriesToBackendFormat` | Transformation | Converts query syntax |
| `AddSetsToEntityListOrReactionViaRecordQuery` | Transformation | Adds set filters for entities/lists |
| `AddSetsToRelationQuery` | Transformation | Adds set filters for relations |
| `AddSetsToReactionsQuery` | Transformation | Adds set filters for reactions |
| `AddSetsToThroughRecordQuery` | Transformation | Adds set filters for through records |
| `PreventQueryByForbiddenFields` | Validation | Blocks queries on forbidden fields |
| `DynamicLocalCache` | Performance | Local caching with TTL |
| `PlaceKindNameIntoPayload` | Transformation | Injects kind into request body |
| `ValidateRequestBodyByKindSchema` | Validation | Schema validation by kind |
| `KindResolution` | Transformation | Resolves kind aliases to names |
| `HierarchyKindAliasResolver` | Routing | Resolves hierarchical kind aliases to technical paths |
| `ConvertKindAliasToKindQuery` | Transformation | Converts kind alias in queries |
| `DynamicTimeout` | Configuration | Sets request timeouts dynamically |
| `DynamicRequestSizeFilter` | Validation | Dynamic request size limits |
| `DynamicRateLimiter` | Protection | Dynamic rate limiting |

> **For detailed filter documentation including configuration examples, behavior specifications, and complete filter chains by route, see [FILTERS.md](FILTERS.md)**

---

### B. Detailed Feature Breakdown

### B. Feature Overview (Basic to Sophisticated)

#### 1. Request Tracing & Observability
Every request entering the gateway receives a unique identifier for distributed tracing. This enables tracking requests across all services, logs, and audit trails. The gateway generates a composite ID containing the application shortcode, authorized party, precise timestamp, and random suffix (for example, `X-Request-Id: APP-AUTH0-20260203091534247-A7K9M`) so each request can be reliably correlated end-to-end.

**Filters:** `GenerateRequestId`

---

#### 2. Route Management & Traffic Control
The gateway can dynamically enable or disable specific routes without redeployment. This is crucial for maintenance windows, gradual feature rollouts, or emergency shutdowns of problematic endpoints. It is also central to the gateway’s domain-projection role: by toggling routes and route groups, the gateway can expose only the domain-specific surface area while keeping the generic backend endpoints hidden. Additionally, the gateway enforces rate limits to protect backend services from overload, using a Redis-backed token bucket algorithm that allows different limits per endpoint.

**Filters:** `CheckIfRouteEnabled`, `RequestRateLimiter`, `DynamicRateLimiter`

---

#### 3. Request Size Protection
Before processing any request, the gateway validates that request bodies do not exceed configured size limits. This prevents memory exhaustion attacks and ensures predictable resource usage. Size limits can be configured globally or per route, with dynamic sizing for kind-specific endpoints.

**Filters:** `RequestSize`, `DynamicRequestSizeFilter`

---

#### 4. Path Routing & Header Management
The gateway translates external API paths to internal backend paths. This routing is flexible and driven by configuration: by adjusting the inbound controller base paths (for example, mapping a domain alias like `/api/v1/books` to the generic `entities` controller), the gateway can forward `/api/v1/books` to `/entities` without changing code. After authentication, sensitive headers like Authorization are stripped to prevent leaking credentials to downstream services.

**Filters:** `RewritePath`, `RemoveRequestHeader`

---

#### 5. Authentication
All protected routes require valid JWT tokens. The gateway verifies tokens against the identity provider's public keys (JWKS), validates signatures and expiration, then extracts user identity including user ID, roles, groups, and email verification status. The configuration supports multiple JWT providers (multiple issuers and key sources) so the gateway can accept tokens from several identity systems in parallel. This information is stored in the reactive context and made available to all downstream filters for authorization and audit decisions.

**Filters:** `AuthenticateRequest`

---

#### 6. Schema Validation (Fail Fast)
The gateway validates request bodies against JSON schemas that are specific to each entity kind. This ensures data integrity on a schema-less backend, prevents malformed payloads from being persisted, specialized the generic backend to the business domain, and gives clients immediate, actionable validation errors.

**Filters:** `ValidateRequestBodyByKindSchema`

---

#### 7. Request Hygiene & Query Safety
The gateway blocks unsafe query patterns such as stringified JSON in query parameters. This reduces injection risks and keeps query parsing predictable.

**Filters:** `PreventStringifiedJsonFilter`

---

#### 8. Developer Experience (Simplified Query Language)
Developers can use a simpler, friendly query syntax at the gateway edge. The gateway automatically translates those simple query strings into the backend's more complex query structure, reducing client complexity and keeping the backend query dialect internal. Additionally, developers can specify which fields they want in responses using fieldsets, allowing the gateway to project and return only the requested fields. 

**Filters:** `ConvertSimplerQueriesToBackendFormat`, `ApplyFieldsetConfig`

---

#### 9. Policy-Based Authorization
Every authenticated request is evaluated against authorization policies using Open Policy Agent (OPA). The gateway sends the user context, request details, and route metadata to OPA and waits for an allow/deny decision. This enables fine-grained, policy-driven access control that can be updated without code changes.

**Filters:** `AuthorizeRequest`

---

#### 10. Field-Level Security & Response Filtering
Data privacy is enforced through multiple layers. First, the gateway fetches field-level access policies from OPA to determine which fields the current user should not see. Then, before queries execute, the gateway modifies user queries to exclude forbidden fields, making the system behave as if those fields don't exist for that user. After the backend responds, the gateway physically removes any forbidden fields from the JSON response to ensure users never see data they shouldn't. During replacement operations (PUT), since forbidden fields are hidden from the user, the gateway preserves values of forbidden fields from the original record by injecting them into the replacement payload, preventing accidental deletion of those restricted fields.

**Filters:** `FetchForbiddenFields`, `PreventQueryByForbiddenFields`, `FieldFilter`, `AddForbiddenFieldsFromOriginalToPayloadInReplace`

---

#### 11. System Metadata Management
System-managed fields like creation timestamps, modification dates, and ownership information are protected by default and cannot be directly set by regular users. During creation operations, the gateway automatically injects these fields, pulling data from the authenticated user context and system clock. However, authorized users (such as administrators or users with field-level edit permissions) may be allowed to modify these fields. During replacement (PUT) operations, the gateway fetches the original record and preserves all managed fields unless the user has explicit authorization to modify them, preventing unauthorized tampering with audit trails while allowing legitimate administrative overrides.

**Filters:** `AddManagedFieldsInCreation`, `AddManagedFieldsFromOriginalToPayloadInReplace`

---

#### 12. Domain Mapping (Kind Aliasing)
This is the engine that transforms a generic backend into domain-specific APIs. When a request arrives at `/products`, the gateway resolves "products" to the underlying kind name (e.g., "product"), injects `"_kind": "product"` into the request body, and rewrites queries to filter by that kind. This allows exposing business-specific REST APIs without writing custom backend code. Each domain endpoint can have its own validation schema, rate limits, and timeout configurations.

**Filters:** `KindResolution`, `HierarchyKindAliasResolver`, `PlaceKindNameIntoPayload`, `ConvertKindAliasToKindQuery`

---

#### 13. Dynamic OpenAPI Generation
The gateway can generate a personalized OpenAPI specification at runtime (`/openapi.json` or `/openapi.yaml`). The spec is virtualized to match configured domain aliases and route toggles, and it is pruned based on the caller’s field-level visibility so the documentation reflects exactly what the user can see and do.

**Components:** `DynamicOasHandler`, `BackendOasClient`, `OasTransformationEngine`, `OasFieldPermissionService`, `OasSchemaPruner`, `OasCacheService`

---

#### 14. Distributed Concurrency Control
To prevent race conditions in a distributed system, the gateway uses Redis-based distributed locks. When creating resources, a lock ensures no duplicate creates happen simultaneously. When updating resources, a lock prevents lost updates from concurrent modifications. Lock keys are often dynamic (e.g., based on list ID), ensuring operations on different resources don't block each other while operations on the same resource are properly serialized.

**Filters:** `AcquireLockForCreation`, `AcquireLockForUpdate`

---

#### 15. Performance Optimization
The gateway maintains a local in-memory cache for GET responses, with configurable TTL and size per route. Cache keys include the URL and query parameters to ensure different queries don't collide. Additionally, the gateway allows configuring connection and response timeouts per route, preventing slow operations from tying up resources. These timeout configurations can be dynamically adjusted for kind-specific endpoints.

**Filters:** `DynamicLocalCache`, `DynamicTimeout`