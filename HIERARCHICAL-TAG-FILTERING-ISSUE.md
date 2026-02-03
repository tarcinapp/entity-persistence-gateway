# Issue: Hierarchical Route Tag Filtering Not Working

## Problem Description

Routes with the `hierarchical` tag are still being rendered in the OpenAPI specification despite being disabled in the configuration.

**Configuration:**
```properties
# File: src/main/resources/application-dev.properties
app.toggles.tags.off[0]=hierarchical
app.toggles.tags.off[1]=listReactions
app.toggles.tags.off[2]=entityReactions
```

**Expected Behavior:**
- Routes tagged with `hierarchical` should NOT appear in the generated OpenAPI spec
- Specifically: `/api/v1/entities/{id}/children`, `/api/v1/entities/{id}/parents`, `/api/v1/lists/{id}/children`, `/api/v1/lists/{id}/parents` should be excluded

**Actual Behavior:**
- All 13 paths are still being generated, including the 4 hierarchical paths that should be filtered out
- Only 9 paths should appear (entities, lists, relations with their base CRUD operations)

## Root Cause Analysis

### What We Fixed (Partial Solution)

1. **Removed hardcoded tag checks** - Changed from `isTagDisabled("hierarchical")` to `isRouteDisabledByTags(controllerName, hierarchyType)`
2. **Implemented metadata-driven approach** - Created `isRouteDisabledByTags()` method that looks up route metadata dynamically
3. **Fixed route ID construction** - Corrected singularization logic for controller names (entities → Entity, lists → List, relations → Relation)

### Current Problem

The `isRouteDisabledByTags()` method is not working because:

**Route metadata cache lookup is failing:**
```java
// Line ~2640 in OasTransformationEngine.java
RouteMetadata metadata = routeMetadataCache.get(routeId);

// Returns NULL for routeId = "findEntityChildren"
```

**Debug Output from Logs:**
```
[2026-01-20 19:35:02,035] Checking if route 'findEntityChildren' should be disabled (controller=entities, hierarchy=children)
[2026-01-20 19:35:02,035] Route 'findEntityChildren' metadata not found or has no tags, allowing generation
[2026-01-20 19:35:02,036] Added base controller route: /api/v1/entities/{id}/children
```

### Why Metadata Lookup Fails

The route metadata cache is populated from `RouteMetadataService.getAllRouteMetadata()` at construction time, but the lookup using the constructed route ID fails. Possible reasons:

1. **Route IDs in cache don't match constructed IDs** - The cache might use different keys than what we're constructing
2. **Route metadata not loaded** - Some routes might not be in the cache
3. **Timing issue** - Cache might be empty at construction time

## Files Involved

### Primary File (Needs Modification)
- `src/main/java/com/tarcinapp/entitypersistencegateway/oas/transformation/OasTransformationEngine.java`
  - **Line 577-588**: Children route generation with `isRouteDisabledByTags()` call
  - **Line 590-600**: Parents route generation with `isRouteDisabledByTags()` call
  - **Line 683-686**: Alias hierarchy path generation
  - **Line 2610-2678**: `isRouteDisabledByTags()` method implementation (THE PROBLEM)
  - **Line 204-206**: Route metadata cache initialization

### Supporting Files
- `src/main/java/com/tarcinapp/entitypersistencegateway/oas/service/RouteMetadataService.java`
  - Provides `getAllRouteMetadata()` method
  - Returns `Map<String, RouteMetadata>` with route IDs as keys

- `src/main/resources/application-routes.yml`
  - Contains route definitions with IDs: `findEntityChildren`, `findListChildren`, `findEntityParents`, `findListParents`
  - Each route has metadata with tags array including `hierarchical`

- `src/main/resources/application-dev.properties`
  - Line 154-156: Tag disable configuration

## How to Fix

### Option 1: Debug Why Cache Lookup Fails (Quick Fix)

**Steps:**
1. Add debug logging to see what keys are actually in `routeMetadataCache`:
   ```java
   // In constructor after line 204
   log.debug("Route metadata cache keys: {}", routeMetadataCache.keySet());
   log.debug("Looking for keys like: findEntityChildren, findListChildren, findEntityParents, findListParents");
   ```

2. Check if route IDs in cache match what we're constructing:
   - Verify `routeMetadataCache.containsKey("findEntityChildren")`
   - Check if keys are case-sensitive or have different format

3. Fix the route ID construction in `isRouteDisabledByTags()` to match actual cache keys

### Option 2: Iterate Through Cache (Robust Solution - RECOMMENDED)

Instead of constructing a route ID and looking it up, iterate through the cache to find routes for the controller:

```java
private boolean isRouteDisabledByTags(String controllerName, String hierarchyType) {
    if (controllerName == null || hierarchyType == null) {
        return false;
    }
    
    log.debug("Checking if {}  {} routes should be disabled", controllerName, hierarchyType);
    
    // Find all routes for this controller that match the hierarchy type
    List<RouteMetadata> matchingRoutes = routeMetadataCache.values().stream()
        .filter(metadata -> metadata.getControllerName() != null 
                && metadata.getControllerName().equals(controllerName))
        .filter(metadata -> metadata.getRouteId() != null 
                && metadata.getRouteId().toLowerCase().contains(hierarchyType.toLowerCase()))
        .collect(Collectors.toList());
    
    if (matchingRoutes.isEmpty()) {
        log.debug("No routes found for controller '{}' with hierarchy type '{}'", 
            controllerName, hierarchyType);
        return false;
    }
    
    // Check if any matching route has disabled tags
    List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());
    if (tagsOff.isEmpty()) {
        return false;
    }
    
    for (RouteMetadata metadata : matchingRoutes) {
        if (metadata.getTags() == null || metadata.getTags().isEmpty()) {
            continue;
        }
        
        // Check if route has any disabled tags
        for (String tag : metadata.getTags()) {
            if (tagsOff.contains(tag)) {
                log.debug("Route '{}' DISABLED - has disabled tag '{}'", 
                    metadata.getRouteId(), tag);
                return true;
            }
        }
    }
    
    log.debug("No disabled tags found for {} {} routes", controllerName, hierarchyType);
    return false;
}
```

**Why This Is Better:**
- ✅ No hardcoded route ID construction
- ✅ No assumptions about naming conventions
- ✅ Works with any route naming pattern
- ✅ Finds routes by controller + hierarchy type pattern matching
- ✅ Checks actual tags from metadata
- ✅ Truly metadata-driven

### Option 3: Use Existing isRouteFilteredByTags() Method

There's already a method `isRouteFilteredByTags(String routeId)` at line ~2630 that does route-level tag filtering. We could:

1. Construct proper route IDs: `findEntityChildren`, `findListChildren`, etc.
2. Call `isRouteFilteredByTags(routeId)` instead of our custom method
3. This reuses existing, tested logic

## Testing the Fix

### Build and Deploy
```bash
cd /home/kdrkrst/git/github/entity-persistence-gateway
./mvnw clean package -DskipTests -q
ps aux | grep "entity-persistence-gateway.*jar" | awk '{print $2}' | xargs -r kill -9
redis-cli -a devpassword123 FLUSHDB 2>/dev/null
nohup java -jar target/entity-persistence-gateway-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev > /tmp/gateway-test.log 2>&1 &
sleep 25
```

### Verify Fix
```bash
# Fetch OpenAPI spec and check paths
curl --location 'http://localhost:8081/openapi.yaml' \
  --header 'Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE4NTkzODgxNTYsImlhdCI6MTc1OTM4ODE1NiwianRpIjoiZjhmODExZWQtZmVlYy00NDRkLTlkNTQtMmVhOWQ2ZjIzNGRkIiwiaXNzIjoidGFyY2luYXBwLWlkbSIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiJkZWZhdWx0LXVzZXItaWQiLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJwb3N0bWFuIiwic2Vzc2lvbl9zdGF0ZSI6ImE1OGUyNDFiLWE2ZjItNDMzNy1hZGQyLWU3MzlmNzM2ZDU1OCIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiLyoiXSwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbInRhcmNpbmFwcC5tZW1iZXIiLCJkZWZhdWx0LXJvbGVzLXRhcmNpbmFwcCIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJncm91cHMiOlsiZGVmYXVsdC11c2VyLWdyb3VwIl0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJzaWQiOiJhNThlMjQxYi1hNmYyLTQzMzctYWRkMi1lNzM5ZjczNmQ1NTgiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwicm9sZXMiOlsidGFyY2luYXBwLm1lbWJlciIsImRlZmF1bHQtcm9sZXMtdGFyY2luYXBwIiwib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJ1c2VyLWJhc2ljLXZlcmlmaWVkLW1lbWJlci0xIiwiZ2l2ZW5fbmFtZSI6IiIsImZhbWlseV9uYW1lIjoiIn0.kC4DAqHeEFIPfi5ap8HWSIwtZWZdh1YzhIk6sw4iBTwtLYm8nG7ypjJD2GeOpc-yctQw-2LTEA7x1EFoxmZYLhC8YEYEKq7weSsnVicoyet77t805sHWFIsroSaqYFeVFTIWUl2OWS0cWUCJeAkpoaKLFA9vu_2QrMMo0w-I-JJSgLWOvCat3HvHuFlNkWb_Zw0cb8SHFmMkodnljVUf_AXdr1wjEOtVDJFDxiVUHZ9GIc-bFvh4O_q0FuoKLhhfgVKi4vEWYBJITHBLIbYQj7bAD_lcSF-bQVFt9pIeUmo7KMJj1NKi5kC1LmkjfU8um4zdF43Bwf60bZfRoi1oXg' \
  2>/dev/null | grep -E "^\s+/api" | sort -u

# Expected output (9 paths):
#   /api/v1/entities/count:
#   /api/v1/entities/{id}:
#   /api/v1/entities:
#   /api/v1/lists/count:
#   /api/v1/lists/{id}:
#   /api/v1/lists:
#   /api/v1/relations/count:
#   /api/v1/relations/{id}:
#   /api/v1/relations:

# Check logs for debug messages
grep -E "Route.*DISABLED|Checking if route" /tmp/gateway-test.log | tail -20
```

**Success Criteria:**
- ✅ Only 9 paths in OpenAPI spec (NO children/parents paths)
- ✅ Logs show: "Route 'findEntityChildren' DISABLED - has disabled tag 'hierarchical'"
- ✅ Logs show: "Route 'findListChildren' DISABLED - has disabled tag 'hierarchical'"

## Additional Context

### Why Previous Hardcoded Approach Worked

The previous implementation with `isTagDisabled("hierarchical")` worked because it simply checked if the string "hierarchical" was in the disabled tags list. It was hardcoded but functional:

```java
// OLD CODE (worked but was bad practice)
if (!isTagDisabled("hierarchical")) {
    // generate hierarchy paths
}
```

### Why New Metadata-Driven Approach Fails

The new approach tries to be more flexible by looking up actual route metadata, but the cache lookup fails:

```java
// NEW CODE (doesn't work due to cache lookup failure)
if (!isRouteDisabledByTags(controllerName, "children")) {
    // generate hierarchy paths
}
```

The method constructs `routeId = "findEntityChildren"` but `routeMetadataCache.get(routeId)` returns null.

## Recommendation

**Use Option 2 (Iterate Through Cache)** - This is the most robust solution that:
- Doesn't rely on route ID construction
- Works with any naming convention
- Truly reads from metadata
- Matches the spirit of "metadata-driven" architecture

Replace the entire `isRouteDisabledByTags()` method with the implementation from Option 2 above.
