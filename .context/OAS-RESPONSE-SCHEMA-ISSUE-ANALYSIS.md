# Analysis: OAS Response Schema Mismatch (POST/PATCH)

## Summary
The gateway’s Dynamic OAS transformation **overwrites backend response schemas** for POST/PUT/PATCH with the **same domain response schema used by GET**. This causes inclusion fields like `_reactions` / `_entities` to appear in POST responses, and causes PATCH responses that are **count-only** in the backend to appear as **full resource objects** in the generated spec.

**Additionally:** Response schemas for POST/PUT/PATCH must still be **transformed and pruned** using **FIND** forbidden fields (responses are readable views), even when the backend response schema is minimal (e.g., `{ "count": 0 }`).

## Evidence in Code
In [OasTransformationEngine.java](src/main/java/com/tarcinapp/entitypersistencegateway/oas/transformation/OasTransformationEngine.java#L3980-L4048), the method `bindRequestBodiesToDomainSchemas()` explicitly binds **responses** for POST/PUT/PATCH to `schemaName`, which represents the **full domain response schema**:

- **POST:** `bindOperationResponse(pathItem.getPost(), schemaName, openApi, false)`
- **PUT:** `bindOperationResponse(pathItem.getPut(), schemaName, openApi, false)`
- **PATCH:** `bindOperationResponse(pathItem.getPatch(), schemaName, openApi, false)`

This is done regardless of the backend’s actual response schema for those operations.

## Why This Produces Incorrect Schemas
1. **Shared response schema for different operations**
   - The `schemaName` used here is the **same schema used for GET** (e.g., `Entity`, `List`, or alias-specific response schemas). These schemas include inclusion fields like `_reactions` and `_entities`.
   - Backend POST responses typically **omit inclusion fields**, but the gateway rebinds them to the GET schema, reintroducing those fields.

2. **PATCH responses are simplified in backend**
   - Backend PATCH operations (e.g., `PATCH /entities`) often return a **count-only** response like `{ "count": 0 }`.
   - The gateway still binds PATCH response to the full resource schema (`schemaName`), replacing the backend’s count-only response with a full object schema.

3. **No operation-specific response binding for POST/PUT/PATCH**
   - The code only uses `resolveResponseSchemaName(...)` for **GET** responses.
   - POST/PUT/PATCH responses do not attempt to find route-specific response schemas, and do not preserve backend response schemas.

## Root Cause
The transformation logic treats POST/PUT/PATCH **response schemas as if they are the same as GET**, rather than preserving backend response schemas or resolving per-operation response schemas.

## Scope of Impact
- **POST /entities, POST /lists, POST /entities/{id}/children, POST /lists/{id}/children**
  - Responses incorrectly show inclusion fields (`_reactions`, `_entities`) from GET schemas.

- **PATCH /entities, PATCH /lists, PATCH /relations, etc.**
  - Responses incorrectly show full object schemas instead of backend’s count-only response schema.

## Conclusion
The mismatch is caused by **response schema rebinding** in `bindRequestBodiesToDomainSchemas()`, where POST/PUT/PATCH responses are forcibly bound to the same schema used for GET. This overrides the backend response schemas and introduces fields that should not exist for those operations.

**Requirement:** Preserve or correctly transform the backend response schema per operation, then apply **FIND**-based pruning to those response schemas.

# Operational Guidelines & Environment Constraints

You must adhere to the following rules and technical constraints throughout this project:

## 1. Execution & Process Management
- **Dependency Management:** Dependencies are managed via `./dev.sh` (start, status, stop). Note: `./dev.sh` does NOT start the gateway. Currently, dependencies are running, the gateway is off, and Redis cache is clear.
- **Starting the Gateway:** Always use the `dev` profile. It takes approximately 60 seconds to start.
  - **Main Class:** com.tarcinapp.entitypersistencegateway.EntityPersistenceGatewayApplication
  - **Project Name:** entity-persistence-gateway
  - **Active Profile:** `--spring.profiles.active=dev`
  - **VM Args:** `-Dlog4j2.isThreadContextMapInheritable=true`
- **Safe Termination:** NEVER use `pkill -f "entity-persistence-gateway"`. It kills the `entity-persistence-gateway-policies` dependency. Use this instead:
  `lsof -t -i:8081 | xargs kill -9 2>/dev/null || echo 'Port 8081 is already free'`
- **Redis Cache Clearing:** Redis is password protected. Use this exact command to flush:
  `REDIS_PASS=$(grep 'app.outbound.redis.password=' ${workspaceFolder}/src/main/resources/application-dev.properties | cut -d'=' -f2); redis-cli -a $REDIS_PASS FLUSHDB 2>/dev/null && echo 'Redis flushed' || echo 'Redis flush failed'`

## 2. Documentation & State Management
You are responsible for keeping the project documentation synchronized with code changes:
- **Filter Changes:** If any logic or behavior of a filter is modified, you MUST update `.context/20-FILTERS.md` to reflect the change.
- **Domain Projection:** If any changes occur within the Domain Projection feature, you MUST reflect these updates in `.context/55-FEATURE-DOMAIN-PROJECTION.md`. If any changes occur within the generation process within the code, reflect to the `.context/60-FEATURE-DYNAMIC-OAS-GENERATION.md`
- Always ensure these files are accurate before concluding a task.
- **Route Changes:** If any modifications are made to `application-routes.yml`, you MUST reflect these changes in `.context/10-ROUTES.md` to keep the routing documentation up to date.

## 3. Configuration Strategy
- **Environment Settings:** Place all test or local configurations in `src/main/resources/application-dev.properties`. This file is prioritized when running with the `dev` profile.

## 4. API Testing & Authentication
- **OpenAPI Specs:** Access the specification at `http://localhost:8081/openapi.json`.
- **Strict Authentication:** Never disable authentication. Use the following curl command:
  `curl --location 'http://localhost:8081/openapi.json' \
  --header 'Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE4NTkzODgxNTYsImlhdCI6MTc1OTM4ODE1NiwianRpIjoiZjhmODExZWQtZmVlYy00NDRkLTlkNTQtMmVhOWQ2ZjIzNGRkIiwiaXNzIjoidGFyY2luYXBwLWlkbSIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiJkZWZhdWx0LXVzZXItaWQiLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJwb3N0bWFuIiwic2Vzc2lvbl9zdGF0ZSI6ImE1OGUyNDFiLWE2ZjItNDMzNy1hZGQyLWU3MzlmNzM2ZDU1OCIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiLyoiXSwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbInRhcmNpbmFwcC5tZW1iZXIiLCJkZWZhdWx0LXJvbGVzLXRhcmNpbmFwcCIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJncm91cHMiOlsiZGVmYXVsdC11c2VyLWdyb3VwIl0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJzaWQiOiJhNThlMjQxYi1hNmYyLTQzMzctYWRkMi1lNzM5ZjczNmQ1NTgiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwicm9sZXMiOlsidGFyY2luYXBwLm1lbWJlciIsImRlZmF1bHQtcm9sZXMtdGFyY2luYXBwIiwib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJ1c2VyLWJhc2ljLXZlcmlmaWVkLW1lbWJlci0xIiwiZ2l2ZW5fbmFtZSI6IiIsImZhbWlseV9uYW1lIjoiIn0.kC4DAqHeEFIPfi5ap8HWSIwtZWZdh1YzhIk6sw4iBTwtLYm8nG7ypjJD2GeOpc-yctQw-2LTEA7x1EFoxmZYLhC8YEYEKq7weSsnVicoyet77t805sHWFIsroSaqYFeVFTIWUl2OWS0cWUCJeAkpoaKLFA9vu_2QrMMo0w-I-JJSgLWOvCat3HvHuFlNkWb_Zw0cb8SHFmMkodnljVUf_AXdr1wjEOtVDJFDxiVUHZ9GIc-bFvh4O_q0FuoKLhhfgVKi4vEWYBJITHBLIbYQj7bAD_lcSF-bQVFt9pIeUmo7KMJj1NKi5kC1LmkjfU8um4zdF43Bwf60bZfRoi1oXg'`
- **Error Handling:** If you receive an "Authentication Required" error, STOP iterating. Do not hallucinate tokens; ask the user for a new one.