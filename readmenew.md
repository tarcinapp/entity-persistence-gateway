# Entity Persistence Gateway

## Comprehensive Technical Documentation

---

## Table of Contents

- [1. Overview](#1-overview)
  - [1.1 What is Entity Persistence Gateway?](#11-what-is-entity-persistence-gateway)
  - [1.2 The Tarcinapp Suite Ecosystem](#12-the-tarcinapp-suite-ecosystem)
  - [1.3 Key Capabilities](#13-key-capabilities)
  - [1.4 Transforming Generic Backend into Domain-Specific APIs](#14-transforming-generic-backend-into-domain-specific-apis)
- [2. Architecture](#2-architecture)
  - [2.1 High-Level Architecture](#21-high-level-architecture)
  - [2.2 Filter Chain Architecture](#22-filter-chain-architecture)ng
  - [2.3 Request-Response Flow](#23-request-response-flow)
- [3. Core Concepts](#3-core-concepts)
  - [3.1 Managed Fields](#31-managed-fields)
  - [3.2 Field Masking](#32-field-masking)
  - [3.3 Fieldsets](#33-fieldsets)
  - [3.4 Kind Alias Routing](#34-kind-alias-routing)
  - [3.5 Query Abstraction](#35-query-abstraction)
  - [3.6 Saved Queries](#36-saved-queries)
- [4. Configuration Reference](#4-configuration-reference)
  - [4.1 Core Application Configuration](#41-core-application-configuration)
  - [4.2 Inbound Configuration](#42-inbound-configuration)
  - [4.3 Outbound Configuration](#43-outbound-configuration)
  - [4.4 Authentication Configuration](#44-authentication-configuration)
  - [4.5 Rate Limiting Configuration](#45-rate-limiting-configuration)
  - [4.6 Timeout Configuration](#46-timeout-configuration)
  - [4.7 Distributed Locks Configuration](#47-distributed-locks-configuration)
  - [4.8 Local Caching Configuration](#48-local-caching-configuration)
  - [4.9 Request Size Configuration](#49-request-size-configuration)
  - [4.10 Fieldsets Configuration](#410-fieldsets-configuration)
  - [4.11 Route Toggles Configuration](#411-route-toggles-configuration)
- [5. Gateway Filters](#5-gateway-filters)
  - [5.1 Base Filter Classes](#51-base-filter-classes)
  - [5.2 Request Filters](#52-request-filters)
  - [5.3 Response Filters](#53-response-filters)
- [6. Authentication & Authorization](#6-authentication--authorization)
  - [6.1 JWT Authentication](#61-jwt-authentication)
  - [6.2 Multi-Provider Support](#62-multi-provider-support)
  - [6.3 Authorization via OPA](#63-authorization-via-opa)
  - [6.4 Role-Based Access Control](#64-role-based-access-control)
- [7. Routes](#7-routes)
  - [7.1 Route Structure](#71-route-structure)
  - [7.2 Entity Routes](#72-entity-routes)
  - [7.3 List Routes](#73-list-routes)
  - [7.4 Relation Routes](#74-relation-routes)
  - [7.5 Reaction Routes](#75-reaction-routes)
  - [7.6 Kind Alias Routes](#76-kind-alias-routes)
  - [7.7 Route Tags](#77-route-tags)
- [8. Services](#8-services)
- [9. Development](#9-development)
  - [9.1 Local Development Setup](#91-local-development-setup)
  - [9.2 Logging Configuration](#92-logging-configuration)
  - [9.3 Testing](#93-testing)
- [10. Deployment](#10-deployment)
- [11. Troubleshooting](#11-troubleshooting)

---

## 1. Overview

### 1.1 What is Entity Persistence Gateway?

The **Entity Persistence Gateway** is a sophisticated API gateway built on the [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway) framework. It serves as the front door to the Tarcinapp Suite ecosystem, acting as a reverse proxy to the Entity Persistence Service while providing comprehensive cross-cutting concerns including:

- **Authentication** - JWT token validation with multi-provider support
- **Authorization** - Policy-based access control via Open Policy Agent (OPA)
- **Request/Response Transformation** - Field masking, managed fields injection
- **Rate Limiting** - Redis-backed request rate control using token bucket algorithm
- **Distributed Locking** - Prevention of duplicate requests and race conditions
- **Caching** - Local (Caffeine) response caching for burst protection
- **Validation** - JSON Schema validation for request payloads
- **Observability** - Metrics, logging, tracing with MDC support

The gateway ensures that clients interact with the backend services in a secure, controlled, and efficient manner.

### 1.2 The Tarcinapp Suite Ecosystem

The Entity Persistence Gateway is part of a larger microservices ecosystem called the **Tarcinapp Suite**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Clients                                         │
│                    (Web Apps, Mobile Apps, APIs)                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Entity Persistence Gateway                               │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐   │
│  │   Auth      │ │   Rate      │ │   Field     │ │   Distributed       │   │
│  │   Filter    │ │   Limiter   │ │   Masking   │ │   Locking           │   │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
          │                                              │
          ▼                                              ▼
┌─────────────────────────┐              ┌─────────────────────────────────────┐
│  Entity Persistence     │              │  Entity Persistence Gateway         │
│  Service                │              │  Policies (OPA)                     │
│  (Loopback 4/Node.js)   │              │  (Rego Policy Language)             │
└─────────────────────────┘              └─────────────────────────────────────┘
          │                                              
          ▼                                              
┌─────────────────────────┐              ┌─────────────────────────────────────┐
│      MongoDB            │              │         Redis                        │
│  (Data Persistence)     │              │  (Rate Limiting, Locks)             │
└─────────────────────────┘              └─────────────────────────────────────┘
```

**Related Repositories:**

| Repository | Description | Technology |
|------------|-------------|------------|
| `entity-persistence-service` | Backend REST API providing CRUD operations | Loopback 4 / Node.js |
| `entity-persistence-gateway-policies` | Authorization policies for access control | Open Policy Agent (Rego) |
| `entity-persistence-gateway` | This project - API Gateway | Spring Cloud Gateway / Java |

### 1.3 Key Capabilities

| Capability | Description |
|------------|-------------|
| **Request Size Limiting** | Enforces configurable limits on incoming HTTP request sizes (default: 2KB) |
| **CORS Support** | Configurable Cross-Origin Resource Sharing with allowed origins, methods, headers |
| **Rate Limiting** | Token bucket algorithm via Redis for fair usage and system stability |
| **JWT Validation** | Multi-provider JWT token validation with JWKS and static public key support |
| **Policy Execution** | OPA-based authorization determining who can access what resources |
| **Managed Fields** | Automatic population of metadata fields (createdBy, ownerUsers, timestamps) |
| **Field Masking** | Role-based field visibility in responses |
| **Query Scope Reduction** | Automatic query filtering based on user permissions |
| **Distributed Locks** | Redis-based locks preventing duplicate operations |
| **Query Abstraction** | Simplified query syntax hiding backend implementation |
| **Fieldsets** | Pre-defined field collections for easier querying |
| **Predefined Queries** | SPEL-enabled query templates with context variables |
| **Kind Alias Routing** | Semantic URLs mapping to entity kinds |
| **JSON Schema Validation** | Request body validation against configurable schemas |
| **Observability** | Metrics, Prometheus endpoint, JMX support, structured logging |

### 1.4 Transforming Generic Backend into Domain-Specific APIs

One of the most powerful architectural patterns enabled by the Entity Persistence Gateway is the ability to **transform a generic, reusable backend into domain-specific APIs** without modifying the backend code. This approach provides significant benefits:

#### The Problem: Generic vs Domain-Specific

The **Entity Persistence Service** is intentionally designed as a **generic CRUD engine**. It doesn't know about "products", "users", "orders", or "blog posts" — it only knows about "entities" with "kinds". This is by design:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Entity Persistence Service (Generic)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  POST   /entities              → Creates any entity                          │
│  GET    /entities              → Lists any entities                          │
│  GET    /entities/{id}         → Gets any entity by ID                       │
│  PATCH  /entities/{id}         → Updates any entity                          │
│  DELETE /entities/{id}         → Deletes any entity                          │
│                                                                              │
│  Entities are differentiated only by their "kind" field:                     │
│  { "kind": "product", ... }  or  { "kind": "blog-post", ... }               │
└─────────────────────────────────────────────────────────────────────────────┘
```

While this generic approach maximizes code reuse, it creates challenges for API consumers:
- **Lack of semantic clarity**: `/entities` doesn't communicate business intent
- **No domain validation**: Any structure can be saved as any kind
- **Uniform security**: Same rules apply to all entity types
- **Confusing documentation**: OpenAPI specs describe generic operations, not business operations

#### The Solution: Gateway as Domain Adapter

The Entity Persistence Gateway solves this by acting as a **domain adapter layer** that sits in front of the generic backend:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Client Request                                  │
│                         POST /products                                       │
│                         { "name": "iPhone", "price": 999 }                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Entity Persistence Gateway                               │
│                                                                              │
│  1. Kind Alias Routing:    /products → /entities?filter[where][kind]=product │
│  2. Schema Validation:     Validates against product JSON schema             │
│  3. Domain Authorization:  Checks product-specific OPA policies              │
│  4. Rate Limiting:         Applies product-specific rate limits              │
│  5. Field Injection:       Adds kind="product" automatically                 │
│  6. Managed Fields:        Adds createdBy, ownerUsers, timestamps            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Entity Persistence Service                                │
│                    POST /entities                                            │
│                    { "kind": "product", "name": "iPhone", ... }             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Key Transformation Mechanisms

| Mechanism | Generic Backend | Domain-Specific via Gateway |
|-----------|-----------------|-----------------------------|
| **URL Routing** | `/entities` | `/products`, `/orders`, `/users` via Kind Alias |
| **Validation** | No schema validation | JSON Schema per entity type |
| **Authorization** | Uniform access control | Kind-specific OPA policies |
| **Rate Limiting** | Global limits | Per-kind rate limits |
| **Field Visibility** | All fields visible | Role-based field masking per kind |
| **Query Filtering** | Manual filtering | Auto-scoped queries per kind |
| **Request Size** | Global limit | Per-kind size limits |
| **Timeouts** | Global timeouts | Per-kind timeout configuration |

#### Example: Building a Multi-Tenant E-Commerce API

Using the gateway, you can expose domain-specific endpoints without changing the backend:

**Kind Alias Configuration (via `app.oas.controllers`):**

The gateway uses the `app.oas` configuration structure to define kind aliases per controller. Each controller (entities, lists, relations, entityReactions, etc.) can have multiple aliases:

```yaml
# In app-oas.yml or via properties file
app:
  oas:
    controllers:
      entities:
        aliases:
          - alias: products        # URL path: /products
            kind: product          # Maps to kind=product
            validationEnabled: true
            schema: |
              {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "price": { "type": "number" },
                  "category": { "type": "string" }
                },
                "required": ["name", "price"]
              }
          - alias: orders
            kind: order
            validationEnabled: true
            schema: |
              {
                "type": "object",
                "properties": {
                  "productId": { "type": "string" },
                  "quantity": { "type": "integer" },
                  "shippingAddress": { "type": "string" }
                },
                "required": ["productId", "quantity"]
              }
      lists:
        aliases:
          - alias: catalogs
            kind: catalog
            validationEnabled: true
```

Or in properties format (as used in `application-dev.properties`):
```properties
# Entity aliases
app.oas.controllers.entities.aliases[0].alias=products
app.oas.controllers.entities.aliases[0].kind=product
app.oas.controllers.entities.aliases[0].validationEnabled=true
app.oas.controllers.entities.aliases[0].schema={"type":"object","properties":{"name":{"type":"string"},"price":{"type":"number"}},"required":["name","price"]}

app.oas.controllers.entities.aliases[1].alias=orders
app.oas.controllers.entities.aliases[1].kind=order
app.oas.controllers.entities.aliases[1].validationEnabled=true

# List aliases
app.oas.controllers.lists.aliases[0].alias=catalogs
app.oas.controllers.lists.aliases[0].kind=catalog
```

**Domain-Specific Rate Limits:**

Rate limits follow a hierarchical structure: global → controller → kind → route. Kind-specific configurations are nested **inside** the resource type (entities, lists, etc.):

```yaml
app:
  rate-limits:
    # Global default
    default:
      replenishRate: 10
      burstCapacity: 20
    
    # Entities controller
    entities:
      default:
        replenishRate: 10
        burstCapacity: 20
      
      # Kind-specific overrides (nested inside entities)
      kinds:
        product:
          default:
            replenishRate: 100
            burstCapacity: 200
          createEntityByKindAlias:
            replenishRate: 50
            burstCapacity: 100
        order:
          default:
            replenishRate: 50    # Orders need tighter control
            burstCapacity: 100
    
    # Lists controller
    lists:
      default:
        replenishRate: 10
        burstCapacity: 20
      kinds:
        catalog:
          default:
            replenishRate: 30
            burstCapacity: 60
```

**Role-Based Authorization via OPA:**

Tarcinapp uses a sophisticated role-based access control system with four hierarchical access levels. The complete authorization model is documented in the [entity-persistence-gateway-policies](https://github.com/tarcinapp/entity-persistence-gateway-policies) repository.

**Base Roles:**

| Role | Description |
|------|-------------|
| **Admin** | Full administrative access; can perform all operations including sensitive modifications. Can access resources in any state (pending, active, expired). |
| **Editor** | Elevated privileges for content management; can create and modify resources. Can access their own expired resources. |
| **Member** | Standard user access; can interact with resources based on ownership and visibility rules. Cannot access expired resources even if they own them. |
| **Visitor** | Limited read-only access; can view public resources with restrictions. Cannot see internal system fields. |

**Three-Tier Role Granularity:**

Roles can be assigned at multiple scopes to provide flexible access control:

1. **Application-Level**: Applies to all operations across all resource types
   - Example: `tarcinapp.admin` grants admin access to everything

2. **Resource-Type Level**: Applies to all operations on a specific resource type
   - Example: `tarcinapp.entities.editor` grants editor access to all entity operations
   - Supported resource types: `entities`, `lists`, `relations`, `entityReactions`, `listReactions`
   - Aliases: `records` (applies to both entities and lists), `reactions` (applies to both entityReactions and listReactions)

3. **Operation-Level**: Applies to specific operations on a resource type
   - Example: `tarcinapp.entities.create.member` allows members to create entities only
   - Operations: `create`, `find`, `update`, `updateall`, `delete`, `count`

**Field-Level Roles:**

Beyond operation access, field-level roles control access to individual fields:
- Format: `tarcinapp.<scope>.fields.<fieldName>.<operation>`
- Operations: `find` (view), `create`, `update`, `manage` (all operations)
- Example: `tarcinapp.entities.fields._visibility.update` allows updating the `_visibility` field

**Role Examples:**

| Role | Meaning |
|------|---------|
| `tarcinapp.admin` | Admin access to all operations |
| `tarcinapp.records.admin` | Admin access to all record operations |
| `tarcinapp.entities.create.editor` | Editor can create entities |
| `tarcinapp.lists.find.member` | Member can query (read) lists |
| `tarcinapp.entities.fields._visibility.find` | Can read the `_visibility` field of entities |

> 📚 For complete authorization documentation including ownership, visibility, temporal access control, and field-level policies, see the [entity-persistence-gateway-policies README](https://github.com/tarcinapp/entity-persistence-gateway-policies).

**Resulting Domain-Specific API:**
```bash
# Create a product (requires appropriate role: admin, editor, or member with create permission)
POST /products
Content-Type: application/json
Authorization: Bearer <jwt-with-tarcinapp.entities.create.editor>
{
  "name": "MacBook Pro",
  "price": 2499,
  "category": "electronics"
}

# List products (visitor can view public resources, member/editor/admin have broader access)
GET /products
Authorization: Bearer <jwt-with-tarcinapp.entities.find.visitor>
# → Returns public, active products based on visibility and ownership rules
```

#### Benefits of This Architecture

1. **Single Backend, Multiple Domains**: One Entity Persistence Service can power unlimited domain-specific APIs

2. **Zero Backend Changes**: Adding new entity types requires only gateway configuration

3. **Separation of Concerns**:
   - Backend: Generic CRUD, data persistence, relationships
   - Gateway: Domain rules, security, validation, transformation
   - Policies: Access control logic in dedicated repository

4. **Independent Scaling**: Gateway and backend can scale independently based on load

5. **Rapid Prototyping**: New domain APIs can be created in minutes through configuration

6. **Consistent Cross-Cutting Concerns**: Authentication, logging, rate limiting applied uniformly

7. **Type Safety at the Edge**: JSON Schema validation catches invalid data before it reaches the backend

8. **Flexible Authorization**: OPA policies can implement complex business rules per domain

#### Configuration-Driven Domain APIs

The gateway enables a **configuration-driven** approach to building domain APIs:

| Configuration File | Domain Customization |
|-------------------|----------------------|
| `app-oas.yml` + properties | Define semantic URLs (aliases), schemas, and validation per controller/kind |
| `application-route-toggles.yml` | Enable/disable operations per kind |
| `application-rate-limits.yml` | Set rate limits per kind |
| `application-timeouts.yml` | Configure timeouts per kind |
| `application-request-sizes.yml` | Limit request sizes per kind |
| `application-fieldsets.yml` | Define field visibility per kind |
| `application-queries.yml` | Create saved queries per kind |
| OPA Policies | Implement authorization rules per kind |

This architecture demonstrates how the Entity Persistence Gateway acts as a **powerful domain adapter**, allowing organizations to build sophisticated, domain-specific APIs on top of a simple, generic persistence layer — maximizing code reuse while maintaining full control over business logic, security, and validation at the API boundary.

---

## 2. Architecture

### 2.1 High-Level Architecture

The Entity Persistence Gateway follows a **filter chain architecture** where each incoming request passes through a series of filters before reaching the backend service. Each filter handles a specific cross-cutting concern.

```
                              ┌──────────────────────────────────────┐
                              │         Incoming Request              │
                              └──────────────────────────────────────┘
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            GATEWAY FILTER CHAIN                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ 1. RequestSize          - Validate request body size                     │   │
│  │ 2. CheckIfRouteEnabled  - Verify route is enabled                        │   │
│  │ 3. RewritePath          - Transform inbound to backend path              │   │
│  │ 4. AuthenticateRequest  - Validate JWT, build security context           │   │
│  │ 5. GenerateRequestId    - Create/propagate correlation ID                │   │
│  │ 6. RequestRateLimiter   - Enforce rate limits via Redis                  │   │
│  │ 7. FetchForbiddenFields - Get field restrictions from OPA                │   │
│  │ 8. AuthorizeRequest     - Execute authorization policy via OPA           │   │
│  │ 9. AcquireLock          - Distributed locking for writes                 │   │
│  │ 10. AddManagedFields    - Inject managed fields for new records          │   │
│  │ 11. ApplyFieldsetConfig - Apply fieldset to queries                      │   │
│  │ 12. ConvertQueries      - Transform simplified queries                   │   │
│  │ 13. ValidatePayload     - JSON Schema validation                         │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
                              ┌──────────────────────────────────────┐
                              │        Backend Service                │
                              │    (Entity Persistence Service)       │
                              └──────────────────────────────────────┘
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          RESPONSE FILTER CHAIN                                   │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ 1. DynamicLocalCache    - Cache response locally (Caffeine)              │   │
│  │ 2. FieldFilter          - Remove forbidden fields from response          │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
                              ┌──────────────────────────────────────┐
                              │         Outgoing Response             │
                              └──────────────────────────────────────┘
```

### 2.2 Filter Chain Architecture

The gateway implements filters using Spring Cloud Gateway's `GatewayFilterFactory` pattern. Filters are organized into base classes providing reusable functionality:

| Base Class | Purpose | Use Case |
|------------|---------|----------|
| `AbstractGatewayFilterFactory` | Standard Spring filter base | Simple filters without payload modification |
| `AbstractRequestPayloadModifierFilterFactory` | Modify request bodies | Adding managed fields, validation |
| `AbstractResponsePayloadModifierFilterFactory` | Modify response bodies | Field filtering |
| `AbstractPolicyAwareFilterFactory` | Filters requiring OPA policy data | Authorization, forbidden fields |
| `AbstractPolicyAwareResponsePayloadModifierFilterFactory` | Response modification with policy awareness | Role-based field masking |

### 2.3 Request-Response Flow

Here's a detailed flow for a typical `createEntity` operation:

```
1. CLIENT REQUEST
   POST /api/v1/entities
   Authorization: Bearer <jwt>
   Content-Type: application/json
   { "name": "My Entity", "data": {...} }

2. REQUEST SIZE FILTER
   ✓ Check: Request body ≤ 2KB (configurable)
   ✗ Reject: HTTP 413 Payload Too Large

3. ROUTE ENABLED CHECK
   ✓ Check: 'entities' controller enabled
   ✗ Reject: HTTP 405 Method Not Allowed

4. PATH REWRITE
   /api/v1/entities → /entities

5. AUTHENTICATION
   ✓ Extract JWT from Authorization header
   ✓ Identify issuer from token payload
   ✓ Validate signature using configured provider
   ✓ Build GatewaySecurityContext (subject, roles, groups)
   ✗ Reject: HTTP 401 Unauthorized

6. REQUEST ID GENERATION
   ✓ Generate/propagate X-Request-Id for tracing

7. RATE LIMITING
   ✓ Check token bucket (replenishRate, burstCapacity)
   ✗ Reject: HTTP 429 Too Many Requests

8. FETCH FORBIDDEN FIELDS
   → Call OPA: /policies/fields/entities/policy
   ← Receive: ["_ownerUsers", "_visibility", ...]

9. AUTHORIZATION
   → Call OPA: /policies/auth/routes/entities/createEntity/policy
   ← Receive: { "allow": true/false }
   ✗ Reject: HTTP 403 Forbidden

10. ACQUIRE LOCK
    ✓ Redis distributed lock by idempotency key
    ✗ Reject: HTTP 429 Lock acquisition failed

11. ADD MANAGED FIELDS
    + _createdDateTime: "2025-12-28T10:00:00Z"
    + _lastUpdatedDateTime: "2025-12-28T10:00:00Z"
    + _ownerUsers: ["user-123"]
    + _createdBy: "user-123"
    + _lastUpdatedBy: "user-123"

12. FORWARD TO BACKEND
    POST http://entity-persistence-service:80/entities
    { "name": "My Entity", "_createdDateTime": "...", ... }

13. BACKEND RESPONSE
    HTTP 201 Created
    { "_id": "abc-123", "name": "My Entity", "_visibility": "private", ... }

14. FIELD FILTER (Response)
    Remove forbidden fields based on user role
    { "_id": "abc-123", "name": "My Entity" }

15. CLIENT RESPONSE
    HTTP 201 Created
    { "_id": "abc-123", "name": "My Entity" }
```

---

## 3. Core Concepts

### 3.1 Managed Fields

The gateway automatically manages certain metadata fields during entity creation and updates. These fields are populated based on the authenticated user's context.

| Field | On Create | On Update | Description |
|-------|-----------|-----------|-------------|
| `_createdDateTime` | ✓ Set | - | ISO-8601 timestamp of record creation |
| `_lastUpdatedDateTime` | ✓ Set | ✓ Set | ISO-8601 timestamp of last modification |
| `_ownerUsers` | ✓ Set | - | Array containing the creator's user ID |
| `_createdBy` | ✓ Set | - | User ID of the creator |
| `_lastUpdatedBy` | ✓ Set | ✓ Set | User ID of the last modifier |

**Implementation Details:**

```java
// From ManagedField.java enum
public enum ManagedField {
    OWNER_USERS("_ownerUsers"),
    VIEWER_USERS("_viewerUsers"),
    CREATED_BY("_createdBy"),
    LAST_UPDATED_BY("_lastUpdatedBy"),
    CREATED_DATE_TIME("_createdDateTime"),
    LAST_UPDATED_DATE_TIME("_lastUpdatedDateTime");
}
```

**Role-Based Behavior:**
- **Administrators**: May be authorized to directly set managed fields (determined by OPA policies)
- **Editors/Members/Visitors**: Gateway automatically populates managed fields; attempts to set them directly result in `HTTP 401 Unauthorized`

**Configuration:**

Managed fields can be excluded per-route using the filter configuration:

```yaml
filters:
  - name: AddManagedFieldsInCreation
    args:
      excludeFields:
        - OWNER_USERS  # Don't set _ownerUsers automatically
```

### 3.2 Field Masking

Field masking restricts which fields users can see based on their roles. The gateway queries OPA policies to determine forbidden fields for each request.

**How It Works:**

1. **FetchForbiddenFields** filter calls OPA with policy data
2. OPA returns list of fields the user cannot see
3. On response, **FieldFilter** removes those fields from the payload
4. On request, if user attempts to set a forbidden field, gateway returns `401 Unauthorized`

**For Replace (PUT) Operations:**

When replacing a record, clients cannot see forbidden fields but the record retains those values:

1. Gateway fetches original record from backend
2. Gateway copies forbidden field values from original to new payload
3. Request proceeds with preserved forbidden field values

**Example Policy Response from OPA:**

```json
{
  "result": {
    "forbiddenFields": ["_ownerUsers", "_visibility", "_viewerUsers"]
  }
}
```

### 3.3 Fieldsets

Fieldsets are pre-defined collections of fields that simplify querying. Instead of specifying individual fields, clients can request a fieldset.

**Global Fieldsets (available for all record types):**

| Fieldset Name | Mode | Description |
|---------------|------|-------------|
| `show-all` | hide | Shows all fields (hides nothing) |
| `show-managed-all` | show | Shows only managed fields |
| `hide-managed-all` | hide | Hides all managed fields |
| `hide-managed-except-id` | hide | Hides managed fields except `_id` |
| `hide-managed-except-id-kind-name` | hide | Keeps `_id`, `_kind`, `_name` |

**Configuration Example:**

```yaml
app:
  fieldsets:
    global:
      show-managed-all:
        mode: show
        fields:
          - _id
          - _kind
          - _name
          - _slug
          - _visibility
          - _version
          - _ownerUsers
          - _ownerGroups
          - _createdDateTime
          - _lastUpdatedDateTime
          # ... more fields
      
      hide-managed-all:
        mode: hide
        fields:
          - _id
          - _kind
          # ... same fields as above
    
    entities:
      defaultFieldset: null  # No default fieldset
    
    lists:
      defaultFieldset: null
```

**Usage:**

```http
GET /api/v1/entities?fieldset=hide-managed-except-id
```

**Custom Fieldsets:**

```yaml
# Via environment variable
APP_FIELDSETS_BOOKINFO_MODE=show
APP_FIELDSETS_BOOKINFO_FIELDS=_id,_name,author,isbn
```

```http
GET /api/v1/entities/books?fieldset=bookinfo
```

### 3.4 Kind Alias Routing

Kind alias routing maps semantic URLs to entity kinds, providing cleaner API endpoints.

**Configuration:**

```yaml
app:
  kindAliasPaths:
    - alias: books      # URL path segment
      name: book        # _kind field value
      schema: '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"title":{"type":"string"},"author":{"type":"string"}},"required":["title","author"]}'
```

**How It Works:**

| Request | Transformation |
|---------|----------------|
| `GET /api/v1/entities/books` | `GET /entities?filter[where][_kind]=book` |
| `POST /api/v1/entities/books` | `POST /entities` with `{ "_kind": "book", ... }` |
| `GET /api/v1/entities/books/{id}` | `GET /entities/{id}` (validates kind) |

**Benefits:**
- Semantic, resource-specific URLs
- Automatic kind filtering on queries
- JSON Schema validation per kind
- Independent rate limits/timeouts per kind

### 3.5 Query Abstraction

The gateway provides a simplified query syntax that hides the backend's Loopback query notation.

**Query Parameter Mappings:**

| Simplified | Backend Format | Description |
|------------|----------------|-------------|
| `?s=foo` | `?filter[where][_name][regexp]=.*foo.*` | Search by name |
| `?order=name` | `?filter[order]=name` | Sort results |
| `?skip=10` | `?filter[skip]=10` | Pagination offset |
| `?limit=20` | `?filter[limit]=20` | Results per page |

**Example:**

```http
# Simplified
GET /api/v1/entities?s=book&order=_name&limit=10&skip=0

# Transforms to
GET /entities?filter[where][_name][regexp]=.*book.*&filter[order]=_name&filter[limit]=10&filter[skip]=0
```

### 3.6 Saved Queries

Predefined queries allow clients to use shortcuts for common query patterns. Queries support **Spring Expression Language (SPEL)** for dynamic values.

**Configuration:**

```yaml
app:
  queries:
    my: "'sets[owners][userIds]='+#userId"
    actives: "'sets[actives]'"
    inactives: "'sets[inactives]'"
    name: "'filter[where][_name]=' + #query['name']"
    slug: "'filter[where][_slug]=' + #query['slug']"
```

**Available Context Variables:**

| Variable | Description |
|----------|-------------|
| `#userId` | Authenticated user's ID |
| `#query` | Map of current query parameters |
| `#now` | Current timestamp |

**Usage Examples:**

```http
# Get entities owned by current user
GET /api/v1/entities?q=my
# Transforms to: /entities?sets[owners][userIds]=user-123

# Get entities by name
GET /api/v1/entities?q=name&name=MyBook
# Transforms to: /entities?filter[where][_name]=MyBook

# Combine multiple
GET /api/v1/entities?q=my&order=_name&limit=5
```

---

## 4. Configuration Reference

The gateway uses a modular YAML configuration structure. Configuration is split across multiple files imported via `spring.config.import`.

### 4.1 Core Application Configuration

**File:** `application.yml`

```yaml
app:
  # Application name (used in Spring context)
  name: entity-persistence-gateway

  # Shortcode used as prefix for roles, request IDs, JMX domain
  shortcode: "tarcinapp"

  # Enable Spring debug mode (very verbose)
  debug: false

  # Custom request ID header name for tracing
  requestId: "X-Request-Id"

  # Error response configuration
  error:
    include-message: never      # never, always, on_param
    include-stacktrace: never   # never, always, on_param

  # Base JSON Schema for all record types (managed fields)
  commonBaseSchema: '{"$schema":"http://json-schema.org/draft-07/schema#",...}'

  # Relations-specific base schema (requires _entityId and _listId as UUIDs)
  relationsBaseSchema: '{"$schema":"http://json-schema.org/draft-07/schema#",...}'
```

**Environment Variable Mapping:**

| Property | Environment Variable |
|----------|---------------------|
| `app.name` | `APP_NAME` |
| `app.shortcode` | `APP_SHORTCODE` |
| `app.debug` | `APP_DEBUG` |
| `app.requestId` | `APP_REQUESTID` |

### 4.2 Inbound Configuration

**File:** `app-inbound.yml`

```yaml
app:
  inbound:
    # Server binding
    address: 0.0.0.0           # Bind to all interfaces
    port: 8081                 # Listen port
    baseUri: /api/v1/          # API base path

    # Metrics and management
    metricsEnabled: true
    exposedEndpoints: "gateway,health,metrics,prometheus,jolokia,env,info"

    # Controller base paths (URL segments)
    controllerBasePaths:
      entities: entities
      lists: lists
      relations: relations
      explorer: explorer
      entityReactions: entity-reactions
      listReactions: list-reactions
      reactionsThroughEntity: reactions
      reactionsThroughList: reactions
      
      # Hierarchical accessors
      defaultChildrenAccessor: children
      defaultParentsAccessor: parents
      entitiesChildrenAccessor: ${app.inbound.controllerBasePaths.defaultChildrenAccessor}
      entitiesParentsAccessor: ${app.inbound.controllerBasePaths.defaultParentsAccessor}
      # ... similar for lists, reactions

    # CORS Configuration
    cors:
      allowedOrigins:
        - "http://localhost:8080"
      allowedMethods:
        - "GET"
        - "POST"
        - "PUT"
        - "PATCH"
        - "DELETE"
        - "OPTIONS"
        - "HEAD"
      allowedHeaders:
        - "Accept"
        - "Authorization"
        - "Content-Type"
        - "X-Requested-With"
        - ${app.requestId}
        # ... more headers
      exposedHeaders:
        - "Location"
        - "ETag"
        - "X-Total-Count"
        - ${app.requestId}
      allowCredentials: true
      maxAge: 3600
```

### 4.3 Outbound Configuration

**File:** `app-outbound.yml`

```yaml
app:
  outbound:
    # Main backend service (Entity Persistence Service)
    routing-target:
      protocol: http
      host: entity-persistence-service
      port: 80
      baseUri: '/'
      
      # Connection settings
      connectTimeoutMs: ${app.timeouts.default.connectTimeoutMs}
      readTimeoutMs: 3000
      writeTimeoutMs: 3000
      responseTimeout: ${app.timeouts.default.responseTimeoutMs}
      
      # Connection pool
      pool:
        maxConnections: 500
        acquireTimeoutMs: 3000

    # Policy source (for fetching original records during auth)
    policy-source:
      protocol: ${app.outbound.routing-target.protocol}
      host: ${app.outbound.routing-target.host}
      port: ${app.outbound.routing-target.port}
      baseUri: ${app.outbound.routing-target.baseUri}
      
      # Fast timeouts for security layer
      connectTimeoutMs: 1000
      readTimeoutMs: 1000
      writeTimeoutMs: 1000
      responseTimeout: PT1S
      
      pool:
        maxConnections: 200
        acquireTimeoutMs: 1000

    # Open Policy Agent
    opa:
      protocol: https
      host: entity-persistence-gateway-policies
      port: 443
      connectTimeoutMs: ${app.timeouts.default.connectTimeoutMs}
      readTimeoutMs: 300
      writeTimeoutMs: 300
      responseTimeout: ${app.timeouts.default.responseTimeoutMs}

    # Redis (rate limiting, distributed locks)
    redis:
      host: gateway-redis-master
      port: 6379
      database: 0
      password: your-password-from-k8s-secret
```

### 4.4 Authentication Configuration

**File:** `application-auth.yml`

The gateway supports multiple JWT authentication providers. Each provider can use either JWKS (JSON Web Key Set) or a static public key.

```yaml
app:
  auth:
    providers:
      # JWKS-based provider (e.g., Firebase, Auth0, Keycloak)
      - issuer: "https://securetoken.google.com/project-id"
        jwk-set-uri: "https://www.googleapis.com/service_accounts/v1/jwk/..."
        clockSkewSeconds: 60

      # Static public key provider (internal services)
      - issuer: "tarcinapp-internal"
        public-key: "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
        clockSkewSeconds: 10
```

**Provider Properties:**

| Property | Description |
|----------|-------------|
| `issuer` | The `iss` claim value in JWT tokens |
| `jwk-set-uri` | URL to fetch JWKS (mutually exclusive with `public-key`) |
| `public-key` | Base64-encoded RSA public key (mutually exclusive with `jwk-set-uri`) |
| `clockSkewSeconds` | Tolerance for token expiration (accounts for clock drift) |

**JWT Token Requirements:**

```json
{
  "iss": "tarcinapp-internal",      // Must match configured issuer
  "sub": "user-123",                // User ID (becomes authSubject)
  "roles": ["tarcinapp.member"],    // Array of roles
  "groups": ["group-a", "group-b"], // Optional: group memberships
  "email_verified": true,           // Optional: email verification status
  "exp": 1735344000,                // Expiration timestamp
  "iat": 1735340400                 // Issued at timestamp
}
```

### 4.5 Rate Limiting Configuration

**File:** `application-rate-limits.yml`

Rate limiting uses Redis-backed token bucket algorithm. Configuration follows a hierarchical structure.

```yaml
app:
  rate-limits:
    # Level 1: Global defaults
    default:
      replenishRate: 10    # Tokens per second
      burstCapacity: 20    # Max tokens in bucket

    # Level 2: Controller defaults
    entities:
      default:
        replenishRate: ${app.rate-limits.default.replenishRate}
        burstCapacity: ${app.rate-limits.default.burstCapacity}

      # Level 3: Route-specific overrides
      createEntity:
        replenishRate: ${app.rate-limits.entities.default.replenishRate}
        burstCapacity: ${app.rate-limits.entities.default.burstCapacity}
      findEntities:
        replenishRate: ${app.rate-limits.entities.default.replenishRate}
        burstCapacity: ${app.rate-limits.entities.default.burstCapacity}
      findEntityById:
        replenishRate: ${app.rate-limits.entities.default.replenishRate}
        burstCapacity: ${app.rate-limits.entities.default.burstCapacity}

      # Level 4: Kind-specific overrides (optional)
      # kinds:
      #   book:
      #     default:
      #       replenishRate: 20
      #       burstCapacity: 40
      #     createEntityByKindAlias:
      #       replenishRate: 30
      #       burstCapacity: 60

    lists:
      default:
        replenishRate: ${app.rate-limits.default.replenishRate}
        burstCapacity: ${app.rate-limits.default.burstCapacity}
      # ... route-specific configs

    relations:
      # ... similar structure

    reactions:
      # ... similar structure
```

**Key Resolution:**
- **Authenticated requests**: Rate limited by user ID (`sub` claim)
- **Anonymous requests**: Rate limited by client IP address

### 4.6 Timeout Configuration

**File:** `application-timeouts.yml`

```yaml
app:
  timeouts:
    # Level 1: Global defaults
    default:
      connectTimeoutMs: 3000     # TCP connection timeout (3s)
      responseTimeoutMs: 30000   # Response wait timeout (30s)

    # Level 2: Controller defaults
    entities:
      default:
        connectTimeoutMs: ${app.timeouts.default.connectTimeoutMs}
        responseTimeoutMs: ${app.timeouts.default.responseTimeoutMs}

      # Level 3: Route-specific
      createEntity:
        connectTimeoutMs: ${app.timeouts.entities.default.connectTimeoutMs}
        responseTimeoutMs: ${app.timeouts.entities.default.responseTimeoutMs}
      findEntities:
        connectTimeoutMs: ${app.timeouts.entities.default.connectTimeoutMs}
        responseTimeoutMs: ${app.timeouts.entities.default.responseTimeoutMs}

      # Level 4: Kind-specific (optional)
      # kinds:
      #   book:
      #     default:
      #       connectTimeoutMs: 2000
      #       responseTimeoutMs: 20000

    lists:
      # ... similar structure

    relations:
      # ... similar structure

    reactions:
      # ... similar structure
```

### 4.7 Distributed Locks Configuration

**File:** `application-locks.yml`

Distributed locks prevent race conditions during create/update operations using Redis (Redisson).

```yaml
app:
  locks:
    default:
      waitTime: 3s      # Max time to wait for lock acquisition
      leaseTime: 30s    # Auto-release time (prevents deadlocks)

    entities:
      create:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      update:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      createChild:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}

    lists:
      create:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      update:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      createChild:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}

    relations:
      create:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      update:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}

    reactions:
      create:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      update:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
      createChild:
        waitTime: ${app.locks.default.waitTime}
        leaseTime: ${app.locks.default.leaseTime}
```

**Lock Key Resolution:**
- **Create operations**: Lock by idempotency key header or payload hash
- **Update operations**: Lock by record ID

### 4.8 Local Caching Configuration

**File:** `application-local-caching.yml`

Local caching uses Caffeine for in-memory response caching, designed for burst protection rather than long-term caching.

```yaml
app:
  local-cache:
    # Global constraints
    global-total-size-mb: null      # null = no global limit
    heap-usage-percentage: 0.20     # Max 20% of heap for caches
    stats-enabled: false            # Caffeine stats collection

    # Defaults (disabled by default)
    default:
      size: 0MB                     # 0 = disabled
      timeToLive: 30s               # Short TTL for burst protection

    entities:
      default:
        size: ${app.local-cache.default.size}
        timeToLive: ${app.local-cache.default.timeToLive}
      findEntities:
        size: ${app.local-cache.entities.default.size}
        timeToLive: ${app.local-cache.entities.default.timeToLive}
      findEntityById:
        size: ${app.local-cache.entities.default.size}
        timeToLive: ${app.local-cache.entities.default.timeToLive}
      countEntities:
        size: ${app.local-cache.entities.default.size}
        timeToLive: ${app.local-cache.entities.default.timeToLive}

      # Kind-specific caching (optional)
      # kinds:
      #   book:
      #     default:
      #       size: 10MB
      #       timeToLive: 1h
      #     findAllEntitiesByKindAlias:
      #       size: ${app.local-cache.kinds.book.default.size}
      #       timeToLive: ${app.local-cache.kinds.book.default.timeToLive}

    lists:
      # ... similar structure

    relations:
      # ... similar structure

    entityReactions:
      # ... similar structure

    listReactions:
      # ... similar structure
```

### 4.9 Request Size Configuration

**File:** `application-request-sizes.yml`

```yaml
app:
  request-sizes:
    default:
      create: 2KB
      update: 2KB

    entities:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}
      # kinds:
      #   book:
      #     create: 10KB
      #     update: 8KB

    lists:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}

    relations:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}

    reactions:
      create: ${app.request-sizes.default.create}
      update: ${app.request-sizes.default.update}
```

### 4.10 Fieldsets Configuration

**File:** `application-fieldsets.yml`

See [Section 3.3 Fieldsets](#33-fieldsets) for detailed documentation.

### 4.11 Route Toggles Configuration

**File:** `application-route-toggles.yml`

Routes can be enabled/disabled at multiple levels: by route ID, controller name, or tags.

```yaml
app:
  toggles:
    routes:
      off:
        - updateAllEntities           # Disable specific routes
        - updateAllEntitiesByKindAlias
      on:
        - someDisabledRoute           # Re-enable if previously disabled

    controllers:
      off:
        - explorer                    # Disable entire controller
      on: []

    tags:
      off:
        - bulk                        # Disable all routes with 'bulk' tag
        - destructive                 # Disable all delete operations
      on: []
```

**Priority:** Route-specific → Controller → Tags (first match wins)

---

## 5. Gateway Filters

### 5.1 Base Filter Classes

The gateway provides abstract base classes that handle common filter patterns:

| Class | Location | Purpose |
|-------|----------|---------|
| `AbstractGatewayFilterFactory` | Spring Cloud Gateway | Standard filter base class |
| `AbstractRequestPayloadModifierFilterFactory` | `filters/base/` | Modifies request body before forwarding |
| `AbstractResponsePayloadModifierFilterFactory` | `filters/base/` | Modifies response body before returning |
| `AbstractPolicyAwareFilterFactory` | `filters/base/` | Filters that need OPA policy evaluation |
| `AbstractPolicyAwareResponsePayloadModifierFilterFactory` | `filters/base/` | Response modification with policy data |

### 5.2 Request Filters

#### Authentication Filter (`AuthenticateRequest`)

**Purpose:** Validates JWT tokens and builds security context.

**Location:** `filters/common/request/AuthenticateRequest.java`

**Behavior:**
1. Extracts JWT from `Authorization: Bearer <token>` header
2. Extracts issuer from token payload (without validation)
3. Finds matching parser from `TokenParserRegistry`
4. Validates token signature and claims
5. Populates `GatewaySecurityContext` with subject, roles, groups
6. Initializes `PolicyData` for authorization filters

**Configuration:** No filter-specific config; uses `app.auth.providers`

**Response on Failure:** `401 Unauthorized`

---

#### Authorization Filter (`AuthorizeRequest`)

**Purpose:** Executes OPA policies to determine if request is allowed.

**Location:** `filters/common/request/AuthorizeRequest.java`

**Configuration:**
```yaml
filters:
  - name: AuthorizeRequest
    args:
      policyName: /policies/auth/routes/entities/createEntity/policy
```

**Policy Data Sent to OPA:**
```json
{
  "policyName": "/policies/auth/routes/entities/createEntity/policy",
  "appShortcode": "tarcinapp",
  "httpMethod": "POST",
  "requestPath": "/api/v1/entities",
  "queryParams": {},
  "encodedJwt": "eyJhbGciOiJSUzI1NiIs...",
  "requestPayload": { "name": "My Entity" },
  "originalRecord": null
}
```

**Response on Failure:** `403 Forbidden`

---

#### Fetch Forbidden Fields Filter (`FetchForbiddenFieldsGatewayFilterFactory`)

**Purpose:** Retrieves list of fields the user cannot see/modify from OPA.

**Location:** `filters/common/request/FetchForbiddenFieldsGatewayFilterFactory.java`

**Behavior:**
1. Calls OPA with policy data
2. Stores `ForbiddenFieldsLibrary` in exchange attributes
3. Used by `FieldFilter` for response masking
4. Used by validation filters to reject forbidden field modifications

---

#### Rate Limiter Filter (`DynamicRateLimiter`)

**Purpose:** Enforces rate limits using Redis-backed token bucket.

**Location:** `filters/common/request/DynamicRateLimiter.java`

**Configuration:**
```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter:
        replenishRate: ${app.rate-limits.entities.createEntity.replenishRate}
        burstCapacity: ${app.rate-limits.entities.createEntity.burstCapacity}
```

**Response on Failure:** `429 Too Many Requests`

---

#### Lock Acquisition Filters

**AcquireLockForCreation**

**Purpose:** Acquires distributed lock for create operations.

**Location:** `filters/common/request/AcquireLockForCreation.java`

**Lock Key:** Idempotency key header or hash of request payload

**Configuration:**
```yaml
filters:
  - name: AcquireLockForCreation
    args:
      waitTime: ${app.locks.entities.create.waitTime}
      leaseTime: ${app.locks.entities.create.leaseTime}
```

**AcquireLockForUpdate**

**Purpose:** Acquires distributed lock for update operations.

**Location:** `filters/common/request/AcquireLockForUpdate.java`

**Lock Key:** Record ID from path

**Response on Failure:** `429 Too Many Requests` (lock not acquired)

---

#### Managed Fields Filter (`AddManagedFieldsInCreation`)

**Purpose:** Automatically adds managed fields to create requests.

**Location:** `filters/common/request/AddManagedFieldsInCreation.java`

**Fields Added:**
- `_createdDateTime` - Current timestamp
- `_lastUpdatedDateTime` - Current timestamp
- `_ownerUsers` - Array containing authenticated user ID
- `_createdBy` - Authenticated user ID
- `_lastUpdatedBy` - Authenticated user ID

**Configuration:**
```yaml
filters:
  - name: AddManagedFieldsInCreation
    args:
      excludeFields:
        - OWNER_USERS    # Skip _ownerUsers (e.g., for relations)
```

---

#### Managed Fields Preservation Filters

**AddManagedFieldsFromOriginalToPayloadInReplace**

**Purpose:** Preserves managed field values when replacing (PUT) a record.

**AddForbiddenFieldsFromOriginalToPayloadInReplace**

**Purpose:** Preserves forbidden field values when replacing (PUT) a record.

**Configuration:**
```yaml
filters:
  - name: AddForbiddenFieldsFromOriginalToPayloadInReplace
    args:
      policyName: /policies/fields/entities/policy
```

---

#### Fieldset Filter (`ApplyFieldsetConfig`)

**Purpose:** Applies fieldset configuration to query parameters.

**Location:** `filters/common/request/ApplyFieldsetConfig.java`

**Behavior:**
1. Checks for `fieldset` query parameter
2. Looks up fieldset in configuration
3. Modifies `filter[fields]` query parameter accordingly

---

#### Query Transformation Filters

**ConvertSimplerQueriesToBackendFormat**

**Purpose:** Transforms simplified queries to Loopback format.

**Mappings:**
- `?s=foo` → `?filter[where][_name][regexp]=.*foo.*`
- `?order=name` → `?filter[order]=name`
- `?skip=10` → `?filter[skip]=10`
- `?limit=20` → `?filter[limit]=20`

**ConvertKindAliasToKindQuery**

**Purpose:** Adds `_kind` filter for kind-alias routes.

**AddSetsToEntityListOrReactionViaRecordQuery**

**Purpose:** Adds predefined query sets to requests.

---

#### Validation Filters

**ValidateRequestBodyByKindSchema**

**Purpose:** Validates request body against JSON Schema.

**Location:** `filters/common/request/ValidateRequestBodyByKindSchema.java`

**Schema Sources:**
- `app.commonBaseSchema` - Base schema for all records
- `app.relationsBaseSchema` - Additional schema for relations
- Kind-specific schema from `app.kindAliasPaths[].schema`

**Response on Failure:** `422 Unprocessable Entity` with validation details

**PreventStringifiedJsonFilter**

**Purpose:** Rejects requests with stringified JSON (malformed payloads).

**PreventQueryByForbiddenFields**

**Purpose:** Blocks queries that filter on forbidden fields.

---

#### Route Toggle Filter (`CheckIfRouteEnabled`)

**Purpose:** Checks if route is enabled based on toggle configuration.

**Location:** `filters/common/request/CheckIfRouteEnabled.java`

**Configuration:**
```yaml
filters:
  - name: CheckIfRouteEnabled
    args:
      controllerName: entities
```

**Response on Failure:** `405 Method Not Allowed`

---

#### Request Size Filter (`DynamicRequestSizeFilter`)

**Purpose:** Validates request body size.

**Configuration:**
```yaml
filters:
  - name: RequestSize
    args:
      maxSize: ${app.request-sizes.entities.create}
```

**Response on Failure:** `413 Payload Too Large`

---

#### Timeout Filter (`DynamicTimeoutGatewayFilterFactory`)

**Purpose:** Applies per-route timeout configuration.

**Configured via route metadata:**
```yaml
metadata:
  connect-timeout: ${app.timeouts.entities.createEntity.connectTimeoutMs}
  response-timeout: ${app.timeouts.entities.createEntity.responseTimeoutMs}
```

---

#### Caching Filter (`DynamicLocalCacheGatewayFilterFactory`)

**Purpose:** Caches responses locally using Caffeine.

**Configuration:**
```yaml
filters:
  - name: DynamicLocalCache
    args:
      timeToLive: ${app.local-cache.entities.findEntities.timeToLive}
      size: ${app.local-cache.entities.findEntities.size}
```

---

#### Request ID Filter (`GenerateRequestId`)

**Purpose:** Generates or propagates request correlation ID.

**Location:** `filters/common/request/GenerateRequestId.java`

**Behavior:**
1. Checks for existing `X-Request-Id` header
2. If missing, generates new UUID
3. Adds to MDC for logging
4. Propagates to backend and response

---

#### MDC Filter (`InitializeMdcContextFilter`)

**Purpose:** Initializes MDC (Mapped Diagnostic Context) for logging.

**Location:** `filters/common/request/InitializeMdcContextFilter.java`

---

#### Kind Resolution Filter (`KindResolutionGatewayFilterFactory`)

**Purpose:** Resolves kind alias to actual kind name.

**PlaceKindNameIntoPayload**

**Purpose:** Adds `_kind` field to request payload for kind-alias routes.

### 5.3 Response Filters

#### Field Filter (`FieldFilterGatewayFilterFactory`)

**Purpose:** Removes forbidden fields from response body.

**Location:** `filters/common/response/FieldFilterGatewayFilterFactory.java`

**Behavior:**
1. Reads `ForbiddenFieldsLibrary` from exchange attributes
2. Parses response JSON
3. Removes all forbidden fields recursively
4. Returns modified response

---

## 6. Authentication & Authorization

### 6.1 JWT Authentication

The gateway validates JWT tokens using RS256 (RSA Signature with SHA-256). Authentication is handled by the `AuthenticateRequest` filter.

**Authentication Flow:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          JWT Authentication Flow                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. Extract JWT from Authorization header                                     │
│    Authorization: Bearer eyJhbGciOiJSUzI1NiIs...                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. Extract issuer from token payload (without validation)                    │
│    { "iss": "https://securetoken.google.com/my-project", ... }              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. Find matching parser in TokenParserRegistry                               │
│    Lookup by issuer → JwtParser with correct signing key                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. Validate token                                                            │
│    ✓ Signature verification (RS256)                                         │
│    ✓ Expiration check (with clock skew tolerance)                           │
│    ✓ Issuer validation                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. Build GatewaySecurityContext                                              │
│    - authSubject: sub claim (user ID)                                        │
│    - roles: roles claim array                                                │
│    - groups: groups claim array                                              │
│    - encodedJwt: original token (for forwarding)                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

**GatewaySecurityContext Structure:**

```java
public class GatewaySecurityContext {
    public static final String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    
    private String authSubject;      // User ID from 'sub' claim
    private String authParty;        // Authorized party
    private String encodedJwt;       // Original JWT for forwarding
    private ArrayList<String> roles; // User roles from 'roles' claim
    private ArrayList<String> groups; // User groups from 'groups' claim
}
```

### 6.2 Multi-Provider Support

The gateway supports multiple JWT providers simultaneously. This is useful when:
- Supporting both external (Google, Auth0) and internal token issuers
- Migrating between authentication providers
- Supporting multiple identity providers

**Provider Types:**

| Type | Configuration | Use Case |
|------|---------------|----------|
| **JWKS Provider** | `jwk-set-uri` | External IdPs (Google, Firebase, Auth0, Keycloak) |
| **Static Key Provider** | `public-key` | Internal services, custom token issuers |

**Configuration Example:**

```yaml
app:
  auth:
    providers:
      # Firebase Authentication
      - issuer: "https://securetoken.google.com/my-firebase-project"
        jwk-set-uri: "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        clockSkewSeconds: 60

      # Auth0
      - issuer: "https://my-tenant.auth0.com/"
        jwk-set-uri: "https://my-tenant.auth0.com/.well-known/jwks.json"
        clockSkewSeconds: 60

      # Internal service-to-service
      - issuer: "tarcinapp-internal"
        public-key: |
          MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
        clockSkewSeconds: 10
```

**Token Parser Registry:**

The `TokenParserRegistry` manages JWT parsers for each configured provider:

```java
// Simplified from TokenParserRegistry.java
public JwtParser getParser(String issuer) {
    return parsers.get(issuer);  // Returns configured parser or null
}
```

### 6.3 Authorization via OPA

Authorization decisions are delegated to **Open Policy Agent (OPA)** running the `entity-persistence-gateway-policies` bundle.

**Authorization Flow:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          OPA Authorization Flow                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. AuthorizeRequest filter builds PolicyData                                 │
│    {                                                                         │
│      "policyName": "/policies/auth/routes/entities/createEntity/policy",    │
│      "appShortcode": "tarcinapp",                                           │
│      "httpMethod": "POST",                                                   │
│      "requestPath": "/api/v1/entities",                                     │
│      "queryParams": { "kind": "book" },                                     │
│      "encodedJwt": "eyJhbGciOiJSUzI1NiIs...",                               │
│      "requestPayload": { "name": "My Book", "author": "..." },              │
│      "originalRecord": null                                                  │
│    }                                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. POST to OPA                                                               │
│    POST https://opa-host:443/v1/data/policies/auth/routes/entities/...      │
│    Body: { "input": <PolicyData> }                                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. OPA evaluates Rego policy                                                 │
│    - Decodes JWT, extracts claims                                           │
│    - Checks user roles against policy rules                                  │
│    - Evaluates record ownership, visibility                                  │
│    - Returns decision                                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. OPA response                                                              │
│    { "result": { "allow": true } }    → Request proceeds                    │
│    { "result": { "allow": false } }   → 403 Forbidden                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

**PolicyData Structure:**

```java
public class PolicyData implements Cloneable {
    public static final String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private String policyName;           // OPA policy path
    private String appShortcode;         // Application prefix (e.g., "tarcinapp")
    private String operation;            // Operation name
    private HttpMethod httpMethod;       // GET, POST, PUT, PATCH, DELETE
    private RequestPath requestPath;     // Full request path
    private MultiValueMap<String, String> queryParams;  // Query parameters
    private String encodedJwt;           // JWT for OPA to decode
    private Object requestPayload;       // Request body (for create/update)
    private Object originalRecord;       // Original record (for update/replace)
}
```

**Policy Data Builders:**

Different operations require different policy data. The gateway uses specialized builders:

| Builder | Use Case |
|---------|----------|
| `BasicPolicyDataBuilderWithPayload` | Create operations |
| `BasicPolicyDataBuilderWithPayloadAndOriginal` | Update/Replace operations |
| `BasicPolicyDataBuilderWithoutPayload` | Read operations |
| `PolicyDataBuilderForReactionCreation` | Reaction creates |
| `PolicyDataBuilderForRelationCreation` | Relation creates |
| `PolicyDataBuilderWithPayloadAndParent` | Child creates |

### 6.4 Role-Based Access Control

Roles are extracted from the JWT `roles` claim and prefixed with the application shortcode.

**Role Naming Convention:**

```
{app.shortcode}.{role}
```

**Standard Roles:**

| Role | Description |
|------|-------------|
| `tarcinapp.admin` | Full administrative access |
| `tarcinapp.editor` | Can edit any record |
| `tarcinapp.member` | Standard user access |
| `tarcinapp.visitor` | Read-only access |

**JWT Example:**

```json
{
  "sub": "user-123",
  "iss": "tarcinapp-internal",
  "roles": [
    "tarcinapp.member",
    "tarcinapp.editor"
  ],
  "groups": [
    "engineering",
    "product"
  ],
  "exp": 1735344000
}
```

**Role-Based Behavior:**

| Role | Can Set Managed Fields | Field Visibility | Query Scope |
|------|------------------------|------------------|-------------|
| `admin` | Yes (via policy) | All fields | All records |
| `editor` | No | Most fields | Owned + public records |
| `member` | No | Limited fields | Owned + public records |
| `visitor` | No | Minimal fields | Public records only |

**Policy Structure (in entity-persistence-gateway-policies):**

```rego
# Example from entity-persistence-gateway-policies
package policies.auth.routes.entities.createEntity

default allow = false

allow {
    has_role("admin")
}

allow {
    has_role("member")
    not has_forbidden_fields
}

has_role(role) {
    input.roles[_] == concat(".", [input.appShortcode, role])
}
```

---

## 7. Routes

### 7.1 Route Structure

The gateway defines **89 routes** across multiple controllers. Routes are configured in `application-routes.yml` using Spring Cloud Gateway route definitions.

**Route Definition Structure:**

```yaml
- id: createEntity                    # Unique route identifier
  uri: ${app.outbound.routing-target.protocol}://...  # Backend URL
  predicates:
    - Path=${app.inbound.baseUri}${app.inbound.controllerBasePaths.entities}
    - Method=POST
  filters:
    - name: RequestSize
      args:
        maxSize: ${app.request-sizes.entities.create}
    - name: CheckIfRouteEnabled
      args:
        controllerName: entities
    - RewritePath=...
    - AuthenticateRequest
    - GenerateRequestId
    - name: RequestRateLimiter
      args:
        redis-rate-limiter:
          replenishRate: ${app.rate-limits.entities.createEntity.replenishRate}
          burstCapacity: ${app.rate-limits.entities.createEntity.burstCapacity}
    - FetchForbiddenFields
    - name: AuthorizeRequest
      args:
        policyName: /policies/auth/routes/entities/createEntity/policy
    - name: AcquireLockForCreation
      args:
        waitTime: ${app.locks.entities.create.waitTime}
        leaseTime: ${app.locks.entities.create.leaseTime}
    - AddManagedFieldsInCreation
    - ApplyFieldsetConfig
    - RemoveRequestHeader=Authorization
    - FieldFilter
  metadata:
    recordType: entities
    controllerName: entities
    connect-timeout: ${app.timeouts.entities.createEntity.connectTimeoutMs}
    response-timeout: ${app.timeouts.entities.createEntity.responseTimeoutMs}
    tags:
      - post
      - create
      - write
      - manage
      - entities
      - generic
```

### 7.2 Entity Routes

**Base Path:** `/api/v1/entities`

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntity` | POST | `/entities` | Create a new entity |
| `findEntities` | GET | `/entities` | Find/list all entities |
| `countEntities` | GET | `/entities/count` | Count entities matching criteria |
| `updateAllEntities` | PATCH | `/entities` | Update multiple entities (disabled by default) |

#### Single Entity Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityById` | GET | `/entities/{recordId}` | Find entity by UUID |
| `updateEntityById` | PATCH | `/entities/{recordId}` | Partial update entity |
| `replaceEntityById` | PUT | `/entities/{recordId}` | Replace entire entity |
| `deleteEntityById` | DELETE | `/entities/{recordId}` | Delete entity |

#### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findEntityChildren` | GET | `/entities/{recordId}/children` | Find child entities |
| `createEntityChild` | POST | `/entities/{recordId}/children` | Create child entity |
| `findEntityParents` | GET | `/entities/{recordId}/parents` | Find parent entities |

### 7.3 List Routes

**Base Path:** `/api/v1/lists`

#### Collection Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createList` | POST | `/lists` | Create a new list |
| `findLists` | GET | `/lists` | Find/list all lists |
| `countLists` | GET | `/lists/count` | Count lists |
| `updateAllLists` | PATCH | `/lists` | Update multiple lists (disabled by default) |

#### Single List Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListById` | GET | `/lists/{recordId}` | Find list by UUID |
| `updateListById` | PATCH | `/lists/{recordId}` | Partial update list |
| `replaceListById` | PUT | `/lists/{recordId}` | Replace entire list |
| `deleteListById` | DELETE | `/lists/{recordId}` | Delete list |

#### Hierarchical Operations

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `findListChildren` | GET | `/lists/{recordId}/children` | Find child lists |
| `createListChild` | POST | `/lists/{recordId}/children` | Create child list |
| `findListParents` | GET | `/lists/{recordId}/parents` | Find parent lists |

### 7.4 Relation Routes

**Base Path:** `/api/v1/relations`

Relations connect entities to lists.

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createRelation` | POST | `/relations` | Create entity-list relation |
| `findRelations` | GET | `/relations` | Find all relations |
| `countRelations` | GET | `/relations/count` | Count relations |
| `updateAllRelations` | PATCH | `/relations` | Update multiple relations |
| `findRelationById` | GET | `/relations/{recordId}` | Find relation by UUID |
| `updateRelationById` | PATCH | `/relations/{recordId}` | Update relation |
| `replaceRelationById` | PUT | `/relations/{recordId}` | Replace relation |
| `deleteRelationById` | DELETE | `/relations/{recordId}` | Delete relation |

**Relation Schema Requirements:**
- `_entityId` - Required UUID of the entity
- `_listId` - Required UUID of the list

### 7.5 Reaction Routes

Reactions represent user interactions (likes, votes, comments) on entities or lists.

#### Entity Reactions

**Base Path:** `/api/v1/entity-reactions`

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntityReaction` | POST | `/entity-reactions` | Create reaction on entity |
| `findEntityReactions` | GET | `/entity-reactions` | Find all entity reactions |
| `countEntityReactions` | GET | `/entity-reactions/count` | Count entity reactions |
| `updateAllEntityReactions` | PATCH | `/entity-reactions` | Update multiple reactions |
| `findEntityReactionById` | GET | `/entity-reactions/{recordId}` | Find reaction by UUID |
| `updateEntityReactionById` | PATCH | `/entity-reactions/{recordId}` | Update reaction |
| `replaceEntityReactionById` | PUT | `/entity-reactions/{recordId}` | Replace reaction |
| `deleteEntityReactionById` | DELETE | `/entity-reactions/{recordId}` | Delete reaction |

#### Reactions Through Entity

**Base Path:** `/api/v1/entities/{recordId}/reactions`

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createReactionByEntityId` | POST | `/entities/{recordId}/reactions` | Create reaction for entity |
| `findReactionsByEntityId` | GET | `/entities/{recordId}/reactions` | Find reactions on entity |
| `updateReactionsByEntityId` | PATCH | `/entities/{recordId}/reactions` | Update reactions on entity |
| `deleteReactionsByEntityId` | DELETE | `/entities/{recordId}/reactions` | Delete reactions on entity |

#### List Reactions

**Base Path:** `/api/v1/list-reactions`

Similar structure to entity reactions.

### 7.6 Kind Alias Routes

Kind alias routes provide semantic URLs for specific entity/list types.

**Configuration:**

```yaml
app:
  kindAliasPaths:
    - alias: books
      name: book
      schema: '{"type":"object","properties":{"title":{"type":"string"}}}'
```

**Generated Routes:**

| Route ID | Method | Path | Description |
|----------|--------|------|-------------|
| `createEntityByKindAlias` | POST | `/entities/books` | Create a book |
| `findAllEntitiesByKindAlias` | GET | `/entities/books` | Find all books |
| `countEntitiesByKindAlias` | GET | `/entities/books/count` | Count books |
| `updateAllEntitiesByKindAlias` | PATCH | `/entities/books` | Update all books |
| `findEntityByIdByKindAlias` | GET | `/entities/books/{recordId}` | Find book by ID |
| `updateEntityByIdByKindAlias` | PATCH | `/entities/books/{recordId}` | Update book |
| `replaceEntityByIdByKindAlias` | PUT | `/entities/books/{recordId}` | Replace book |
| `deleteEntityByIdByKindAlias` | DELETE | `/entities/books/{recordId}` | Delete book |
| `findEntityChildrenByKindAlias` | GET | `/entities/books/{recordId}/children` | Find book children |
| `createEntityChildByKindAlias` | POST | `/entities/books/{recordId}/children` | Create book child |
| `findEntityParentsByKindAlias` | GET | `/entities/books/{recordId}/parents` | Find book parents |

### 7.7 Route Tags

Routes are tagged for organization, filtering, and policy application.

**Tag Categories:**

| Category | Tags | Description |
|----------|------|-------------|
| **HTTP Method** | `get`, `post`, `put`, `patch`, `delete` | HTTP verb used |
| **Operation** | `find`, `count`, `create`, `update`, `replace`, `delete` | Primary operation |
| **Access Pattern** | `read-only`, `write`, `destructive`, `manage` | Access type |
| **Scope** | `by-id`, `single-record`, `collection`, `bulk` | Operation scope |
| **Record Type** | `entities`, `lists`, `relations`, `entityReactions`, `listReactions` | Data type |
| **Pattern** | `generic`, `kind-alias`, `through`, `hierarchical` | Route pattern |

**Tag Distribution (89 routes):**

| Tag | Count | Percentage |
|-----|-------|------------|
| `generic` | 52 | 58.4% |
| `write` | 40 | 44.9% |
| `manage` | 40 | 44.9% |
| `get` | 38 | 42.7% |
| `read-only` | 38 | 42.7% |
| `find` | 30 | 33.7% |
| `reaction` | 30 | 33.7% |
| `by-id` | 28 | 31.5% |
| `single-record` | 28 | 31.5% |
| `entities` | 26 | 29.2% |
| `kind-alias` | 22 | 24.7% |

**Using Tags for Configuration:**

```yaml
# Disable all destructive operations
app:
  toggles:
    tags:
      off:
        - destructive

# Apply different rate limits to read-only routes
# (Would require custom implementation)
```

---

## 8. Services

The gateway includes several service classes that encapsulate business logic and are used across filters.

| Service | Location | Purpose |
|---------|----------|---------|
| `JwtAuthenticationService` | `services/` | JWT token validation and claims extraction |
| `SecurityContextBuilder` | `services/` | Build `GatewaySecurityContext` from JWT claims |
| `FieldFilterService` | `services/` | Remove forbidden fields from payloads |
| `FieldsetService` | `services/` | Apply fieldset configurations to queries |
| `DynamicLocalCacheService` | `services/` | Manage Caffeine-based local response cache |
| `OriginalRecordFetcher` | `services/` | Fetch original records for update operations |
| `RequestIdService` | `services/` | Generate/propagate request correlation IDs |

### Key Service Details

#### JwtAuthenticationService

```java
@Service
public class JwtAuthenticationService {
    
    // Check if any auth provider is configured
    public boolean isConfigured();
    
    // Authenticate request and return claims
    public Mono<Claims> authenticate(ServerWebExchange exchange);
    
    // Extract issuer without validation (for provider lookup)
    private String extractIssuerWithoutValidation(String jwt);
}
```

#### SecurityContextBuilder

```java
@Service
public class SecurityContextBuilder {
    
    // Initialize empty security context in exchange
    public void initializeSecurityContext(ServerWebExchange exchange);
    
    // Populate context from JWT claims
    public void populateFromClaims(ServerWebExchange exchange, Claims claims);
}
```

#### Policy Data Builders

Located in `services/policydata/`, these builders create `PolicyData` objects for different operation types:

| Builder | Description |
|---------|-------------|
| `BasicPolicyDataBuilderWithPayload` | For create operations with request body |
| `BasicPolicyDataBuilderWithPayloadAndOriginal` | For update/replace with original record |
| `BasicPolicyDataBuilderWithoutPayload` | For read operations |
| `PolicyDataBuilderForReactionCreation` | Specialized for reaction creates |
| `PolicyDataBuilderForRelationCreation` | Specialized for relation creates |
| `PolicyDataBuilderWithPayloadAndParent` | For child entity creates |

---

## 9. Development

### 9.1 Local Development Setup

#### Prerequisites

- Java 17+
- Maven 3.8+
- Redis 6+
- Docker (optional, for dependencies)

#### Quick Start

```bash
# Clone the repository
git clone https://github.com/tarcinapp/entity-persistence-gateway.git
cd entity-persistence-gateway

# Start dependencies (Redis, OPA, Backend)
./dev.sh start

# Check status
./dev.sh status

# Run the gateway
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Stop dependencies
./dev.sh stop
```

#### Manual Dependency Setup

```bash
# Redis
redis-server --daemonize yes

# OPA (requires entity-persistence-gateway-policies)
opa run --ignore=*_test.rego --server --log-level=debug \
    ~/git/github/entity-persistence-gateway-policies/policies

# Backend (requires entity-persistence-service)
cd ~/git/github/entity-persistence-service
./start-with-env-file.sh dev.env
```

#### VS Code Integration

1. Open Command Palette (`Ctrl+Shift+P`)
2. Select "Tasks: Run Task"
3. Choose "Start All Dependencies"

Or use the "Debug with Dependencies" launch configuration.

#### Service URLs

| Service | URL | Purpose |
|---------|-----|---------|
| Gateway | http://localhost:8081 | API Gateway |
| Redis | localhost:6379 | Rate limiting, locks |
| OPA | http://localhost:8181 | Authorization policies |
| Backend | http://localhost:3000 | Entity Persistence Service |

### 9.2 Logging Configuration

The gateway uses a layered logging configuration:

**Configuration Files:**
- `application-logging.yml` - Log level properties
- `logback-spring.xml` - Logback configuration with Spring property binding

**Log Categories:**

| Property | Logger | Default | Description |
|----------|--------|---------|-------------|
| `app.logging.tarcinapp` | `com.tarcinapp.*` | INFO | Application code |
| `app.logging.http` | `org.springframework.web.HttpLogging` | INFO | HTTP request/response |
| `app.logging.gateway` | `org.springframework.cloud.gateway` | INFO | Gateway lifecycle |
| `app.logging.redisson` | `org.redisson` | WARN | Redis client |

**Common Configurations:**

```yaml
# Enable request/response tracing
app:
  logging:
    http: TRACE

# Increase gateway verbosity
app:
  logging:
    gateway: DEBUG

# Enable application debug logs
app:
  logging:
    tarcinapp: DEBUG

# Reduce Redis noise
app:
  logging:
    redisson: WARN
```

**Log Pattern:**

```
[timestamp][level][thread][class][RequestId]: message
```

The `RequestId` is populated from the `X-Request-Id` header via MDC.

### 9.3 Testing

#### Running Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=AuthenticateRequestTest

# Run with coverage
./mvnw test jacoco:report
```

#### Test Configuration

Test configuration is in `src/test/resources/application-test.yml`.

#### Test Structure

```
src/test/java/com/tarcinapp/entitypersistencegateway/
├── config/
│   └── AdditionalTestConfig.java
├── fixtures/
│   └── ... test fixtures
├── test/
│   └── ... integration tests
├── unit/
│   └── ... unit tests
└── util/
    └── ... test utilities
```

---

## 10. Deployment

### Docker Deployment

**Dockerfile:**

```dockerfile
FROM eclipse-temurin:17-jre-alpine
COPY target/entity-persistence-gateway-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

**Build and Run:**

```bash
# Build
./mvnw clean package -DskipTests
docker build -t entity-persistence-gateway:latest .

# Run
docker run -p 8081:8081 \
  -e APP_OUTBOUND_ROUTING-TARGET_HOST=backend \
  -e APP_OUTBOUND_OPA_HOST=opa \
  -e APP_OUTBOUND_REDIS_HOST=redis \
  entity-persistence-gateway:latest
```

### Kubernetes Deployment

**Key Environment Variables:**

| Variable | Description |
|----------|-------------|
| `APP_INBOUND_PORT` | Server port (default: 8081) |
| `APP_OUTBOUND_ROUTING-TARGET_HOST` | Backend service hostname |
| `APP_OUTBOUND_ROUTING-TARGET_PORT` | Backend service port |
| `APP_OUTBOUND_OPA_HOST` | OPA service hostname |
| `APP_OUTBOUND_OPA_PORT` | OPA service port |
| `APP_OUTBOUND_REDIS_HOST` | Redis hostname |
| `APP_OUTBOUND_REDIS_PORT` | Redis port |
| `APP_OUTBOUND_REDIS_PASSWORD` | Redis password |
| `APP_AUTH_PROVIDERS_0_ISSUER` | JWT issuer (first provider) |
| `APP_AUTH_PROVIDERS_0_JWK-SET-URI` | JWKS URL (first provider) |

### Health Checks

| Endpoint | Purpose |
|----------|---------|
| `/api/v1/ping` | Basic health check |
| `/actuator/health` | Spring Boot health (if enabled) |
| `/actuator/prometheus` | Prometheus metrics (if enabled) |

### Scaling Considerations

- **Stateless**: Gateway is stateless; scale horizontally
- **Redis**: Single Redis instance for rate limiting consistency
- **Connection Pools**: Configure `app.outbound.routing-target.pool.maxConnections`
- **Memory**: Local cache uses heap; configure `app.local-cache.heap-usage-percentage`

---

## 11. Troubleshooting

### Common Issues

#### 401 Unauthorized

**Causes:**
- Missing or malformed `Authorization` header
- Expired JWT token
- Unknown issuer (not configured in `app.auth.providers`)
- Invalid token signature

**Debug:**
```yaml
app:
  logging:
    tarcinapp: DEBUG
```

Check logs for JWT validation errors.

#### 403 Forbidden

**Causes:**
- User lacks required role
- Policy denies the operation
- OPA unreachable

**Debug:**
1. Check user roles in JWT
2. Verify OPA is running and accessible
3. Test policy directly against OPA

#### 429 Too Many Requests

**Causes:**
- Rate limit exceeded
- Lock acquisition failed

**Solutions:**
- Increase `replenishRate`/`burstCapacity` for the route
- Increase `waitTime` for locks
- Check Redis connectivity

#### 413 Payload Too Large

**Cause:** Request body exceeds configured limit

**Solution:**
```yaml
app:
  request-sizes:
    entities:
      create: 10KB  # Increase limit
```

#### 422 Unprocessable Entity

**Cause:** JSON Schema validation failed

**Debug:** Check response body for validation details:
```json
{
  "error": {
    "name": "ValidationError",
    "status": 422,
    "message": "The request is not valid.",
    "details": [
      {
        "code": "1029",
        "field": "$.author",
        "message": "$.author: string found, integer expected"
      }
    ]
  }
}
```

#### 504 Gateway Timeout

**Causes:**
- Backend too slow
- Network issues

**Solutions:**
- Increase `responseTimeoutMs` for the route
- Check backend health
- Check network connectivity

### Log Analysis

**Request ID Tracking:**

All logs include `RequestId` for correlation:
```
[2025-12-28T10:00:00Z][INFO][reactor-http-nio-1][AuthenticateRequest][abc-123-def]: Authentication filter started
```

Use the Request ID to trace a request through all filters.

**Enable Full Request/Response Logging:**

```yaml
app:
  logging:
    http: TRACE
```

### Redis Connectivity

**Check Redis connection:**
```bash
redis-cli -h <host> -p <port> -a <password> ping
```

**Common Redis issues:**
- Connection refused: Check host/port
- Authentication failed: Check password
- Out of memory: Check Redis memory usage

### OPA Connectivity

**Check OPA health:**
```bash
curl http://opa-host:8181/health
```

**Test policy directly:**
```bash
curl -X POST http://opa-host:8181/v1/data/policies/auth/routes/entities/createEntity/policy \
  -H "Content-Type: application/json" \
  -d '{"input": {"appShortcode": "tarcinapp", ...}}'
```

---

## Appendix

### A. Configuration File Reference

| File | Purpose |
|------|---------|
| `application.yml` | Core configuration, imports |
| `app-inbound.yml` | Server binding, CORS |
| `app-outbound.yml` | Backend, OPA, Redis connections |
| `application-auth.yml` | JWT providers |
| `application-routes.yml` | Route definitions |
| `application-route-toggles.yml` | Route enable/disable |
| `application-rate-limits.yml` | Rate limiting |
| `application-timeouts.yml` | Connection/response timeouts |
| `application-locks.yml` | Distributed locks |
| `application-local-caching.yml` | Local cache settings |
| `application-request-sizes.yml` | Request size limits |
| `application-fieldsets.yml` | Field set definitions |
| `application-queries.yml` | Saved queries |
| `application-logging.yml` | Log levels |
| `application-management.yml` | Actuator endpoints |
| `application-jmx.yml` | JMX settings |

### B. HTTP Status Codes

| Code | Meaning | Common Cause |
|------|---------|--------------|
| 200 | OK | Successful GET/PATCH/PUT |
| 201 | Created | Successful POST |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Malformed request |
| 401 | Unauthorized | Invalid/missing token |
| 403 | Forbidden | Policy denied |
| 404 | Not Found | Resource not found |
| 405 | Method Not Allowed | Route disabled |
| 413 | Payload Too Large | Request body too big |
| 422 | Unprocessable Entity | Validation failed |
| 429 | Too Many Requests | Rate limit/lock failed |
| 500 | Internal Server Error | Server error |
| 502 | Bad Gateway | Backend unreachable |
| 504 | Gateway Timeout | Backend timeout |

### C. Related Documentation

- [Entity Persistence Service](https://github.com/tarcinapp/entity-persistence-service)
- [Entity Persistence Gateway Policies](https://github.com/tarcinapp/entity-persistence-gateway-policies)
- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Open Policy Agent](https://www.openpolicyagent.org)
- [Loopback 4](https://loopback.io)

---

*Last updated: December 28, 2025*


