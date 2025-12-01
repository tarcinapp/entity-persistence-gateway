# DynamicLocalCache Filter Usage Summary

This document provides a comprehensive overview of all `DynamicLocalCache` filter usages across all routes in the application, organized by controller.

**Total Routes with DynamicLocalCache:** 32

---

## Entity Controller

### 1. findEntities
**Route ID:** `findEntities`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntities.timeToLive}
    size: ${app.local-cache.entities.findEntities.size}
    recordType: entities
```

---

### 2. countEntities
**Route ID:** `countEntities`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/count`

**Filter Position:**
- **After:** `AddSetsToEntityListOrReactionViaRecordQuery`
- **Before:** (end of filter chain, before metadata)

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.countEntities.timeToLive}
    size: ${app.local-cache.entities.countEntities.size}
    recordType: entities
```

---

### 3. findEntityById
**Route ID:** `findEntityById`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{recordId}`

**Filter Position:**
- **After:** `AuthorizeRequest`
- **Before:** `ApplyFieldsetConfig`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntityById.timeToLive}
    size: ${app.local-cache.entities.findEntityById.size}
    recordType: entities
```

---

### 4. findEntityChildren
**Route ID:** `findEntityChildren`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{recordId}/children`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntityChildren.timeToLive}
    size: ${app.local-cache.entities.findEntityChildren.size}
    recordType: entities
```

---

### 5. findEntityParents
**Route ID:** `findEntityParents`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{recordId}/parents`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntityParents.timeToLive}
    size: ${app.local-cache.entities.findEntityParents.size}
    recordType: entities
```

---

## List Controller

### 6. findLists
**Route ID:** `findLists`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.lists.findLists.timeToLive}
    size: ${app.local-cache.lists.findLists.size}
    recordType: lists
```

---

### 7. countLists
**Route ID:** `countLists`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}/count`

**Filter Position:**
- **After:** `AddSetsToEntityListOrReactionViaRecordQuery`
- **Before:** (end of filter chain, before metadata)

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.lists.countLists.timeToLive}
    size: ${app.local-cache.lists.countLists.size}
    recordType: lists
```

---

### 8. findListById
**Route ID:** `findListById`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}/{recordId}`

**Filter Position:**
- **After:** `AuthorizeRequest`
- **Before:** `ApplyFieldsetConfig`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.lists.findListById.timeToLive}
    size: ${app.local-cache.lists.findListById.size}
    recordType: lists
```

---

### 9. findListChildren
**Route ID:** `findListChildren`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}/{recordId}/children`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.lists.findListChildren.timeToLive}
    size: ${app.local-cache.lists.findListChildren.size}
    recordType: lists
```

---

### 10. findListParents
**Route ID:** `findListParents`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}/{recordId}/parents`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.lists.findListParents.timeToLive}
    size: ${app.local-cache.lists.findListParents.size}
    recordType: lists
```

---

## Relations Controller

### 11. findRelations
**Route ID:** `findRelations`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.relations}`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.relations.findRelations.timeToLive}
    size: ${app.local-cache.relations.findRelations.size}
    recordType: relations
```

---

### 12. countRelations
**Route ID:** `countRelations`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.relations}/count`

**Filter Position:**
- **After:** `AddSetsToRelationQuery`
- **Before:** (end of filter chain, before RemoveRequestHeader)

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.relations.countRelations.timeToLive}
    size: ${app.local-cache.relations.countRelations.size}
    recordType: relations
```

---

### 13. findRelationById
**Route ID:** `findRelationById`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.relations}/{recordId}`

**Filter Position:**
- **After:** `AuthorizeRequest`
- **Before:** `ApplyFieldsetConfig`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.relations.findRelationById.timeToLive}
    size: ${app.local-cache.relations.findRelationById.size}
    recordType: relations
```

---

## Entity Reactions Controller

### 14. findEntityReactions
**Route ID:** `findEntityReactions`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}entity-reactions`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entityReactions.findEntityReactions.timeToLive}
    size: ${app.local-cache.entityReactions.findEntityReactions.size}
    recordType: entityReactions
```

---

### 15. countEntityReactions
**Route ID:** `countEntityReactions`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}entity-reactions/count`

**Filter Position:**
- **After:** `AddSetsToReactionsQuery`
- **Before:** (end of filter chain, before metadata)

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entityReactions.countEntityReactions.timeToLive}
    size: ${app.local-cache.entityReactions.countEntityReactions.size}
    recordType: entityReactions
```

---

### 16. findEntityReactionById
**Route ID:** `findEntityReactionById`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}entity-reactions/{recordId}`

**Filter Position:**
- **After:** `AuthorizeRequest`
- **Before:** `ApplyFieldsetConfig`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entityReactions.findEntityReactionById.timeToLive}
    size: ${app.local-cache.entityReactions.findEntityReactionById.size}
    recordType: entityReactions
```

---

### 17. findChildrenEntityReactionsByReactionId
**Route ID:** `findChildrenEntityReactionsByReactionId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}entity-reactions/{recordId}/children`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entityReactions.findChildrenEntityReactionsByReactionId.timeToLive}
    size: ${app.local-cache.entityReactions.findChildrenEntityReactionsByReactionId.size}
    recordType: entityReactions
```

---

### 18. findParentsByEntityReactionId
**Route ID:** `findParentsByEntityReactionId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}entity-reactions/{recordId}/parents`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entityReactions.findParentsByEntityReactionId.timeToLive}
    size: ${app.local-cache.entityReactions.findParentsByEntityReactionId.size}
    recordType: entityReactions
```

---

## Reactions Through Entity Controller

### 19. findReactionsByEntityId
**Route ID:** `findReactionsByEntityId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{recordId}/reactions`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entityReactions.findEntityReactions.timeToLive}
    size: ${app.local-cache.entityReactions.findEntityReactions.size}
    recordType: entityReactions
```

---

## List Reactions Controller

### 20. findListReactions
**Route ID:** `findListReactions`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}list-reactions`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.listReactions.findListReactions.timeToLive}
    size: ${app.local-cache.listReactions.findListReactions.size}
    recordType: listReactions
```

---

### 21. countListReactions
**Route ID:** `countListReactions`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}list-reactions/count`

**Filter Position:**
- **After:** `AddSetsToReactionsQuery`
- **Before:** (end of filter chain, before metadata)

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.listReactions.countListReactions.timeToLive}
    size: ${app.local-cache.listReactions.countListReactions.size}
    recordType: listReactions
```

---

### 22. findListReactionById
**Route ID:** `findListReactionById`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}list-reactions/{recordId}`

**Filter Position:**
- **After:** `AuthorizeRequest`
- **Before:** `ApplyFieldsetConfig`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.listReactions.findListReactionById.timeToLive}
    size: ${app.local-cache.listReactions.findListReactionById.size}
    recordType: listReactions
```

---

### 23. findChildrenListReactionsByReactionId
**Route ID:** `findChildrenListReactionsByReactionId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}list-reactions/{recordId}/children`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.listReactions.findChildrenListReactionsByReactionId.timeToLive}
    size: ${app.local-cache.listReactions.findChildrenListReactionsByReactionId.size}
    recordType: listReactions
```

---

### 24. findParentsByListReactionId
**Route ID:** `findParentsByListReactionId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}list-reactions/{recordId}/parents`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.listReactions.findParentsByListReactionId.timeToLive}
    size: ${app.local-cache.listReactions.findParentsByListReactionId.size}
    recordType: listReactions
```

---

## Reactions Through List Controller

### 25. findReactionsByListId
**Route ID:** `findReactionsByListId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}/{recordId}/reactions`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.listReactions.findListReactions.timeToLive}
    size: ${app.local-cache.listReactions.findListReactions.size}
    recordType: listReactions
```

---

## Entities Through List Controller

### 26. findEntitiesByListId
**Route ID:** `findEntitiesByListId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.lists}/{recordId}/entities`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntities.timeToLive}
    size: ${app.local-cache.entities.findEntities.size}
    recordType: entities
```

---

## Lists Through Entity Controller

### 27. findListsByEntityId
**Route ID:** `findListsByEntityId`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{recordId}/lists`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.lists.findLists.timeToLive}
    size: ${app.local-cache.lists.findLists.size}
    recordType: lists
```

---

## Entity Kind Alias Routes

### 28. findAllEntitiesByKindAlias
**Route ID:** `findAllEntitiesByKindAlias`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{kindAlias}`

**Filter Position:**
- **After:** `AddSetsToEntityListOrReactionViaRecordQuery`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntities.timeToLive}
    size: ${app.local-cache.entities.findEntities.size}
    recordType: entities
```

---

### 29. countEntitiesByKindAlias
**Route ID:** `countEntitiesByKindAlias`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{kindAlias}/count`

**Filter Position:**
- **After:** `AddSetsToEntityListOrReactionViaRecordQuery`
- **Before:** (end of filter chain, before next route)

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.countEntities.timeToLive}
    size: ${app.local-cache.entities.countEntities.size}
    recordType: entities
```

---

### 30. findEntityByIdByKindAlias
**Route ID:** `findEntityByIdByKindAlias`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{kindAlias}/{recordId}`

**Filter Position:**
- **After:** `AuthorizeRequest`
- **Before:** `ApplyFieldsetConfig`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntityById.timeToLive}
    size: ${app.local-cache.entities.findEntityById.size}
    recordType: entities
```

---

### 31. findEntityChildrenByKindAlias
**Route ID:** `findEntityChildrenByKindAlias`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{kindAlias}/{recordId}/children`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntityChildren.timeToLive}
    size: ${app.local-cache.entities.findEntityChildren.size}
    recordType: entities
```

---

### 32. findEntityParentsByKindAlias
**Route ID:** `findEntityParentsByKindAlias`  
**Method:** GET  
**Path:** `${app.inbound.baseUri}${app.inbound.controllerPaths.entities}/{kindAlias}/{recordId}/parents`

**Filter Position:**
- **After:** `PreventQueryByForbiddenFields`
- **Before:** `DropFieldsForMultiItemResponses`

**Configuration:**
```yaml
- name: DynamicLocalCache
  args:
    timeToLive: ${app.local-cache.entities.findEntityParents.timeToLive}
    size: ${app.local-cache.entities.findEntityParents.size}
    recordType: entities
```

---

## Summary Statistics

### By Controller
- **Entity Controller:** 5 routes
- **List Controller:** 5 routes
- **Relations Controller:** 3 routes
- **Entity Reactions Controller:** 5 routes
- **List Reactions Controller:** 5 routes
- **Through Record Controllers:** 3 routes
- **Entity Kind Alias Routes:** 5 routes
- **Reactions Through Entity:** 1 route

### By Record Type
- **entities:** 13 routes
- **lists:** 7 routes
- **relations:** 3 routes
- **entityReactions:** 6 routes
- **listReactions:** 6 routes

### Common Filter Patterns

**Pattern 1: After PreventQueryByForbiddenFields, Before DropFieldsForMultiItemResponses**
- Used in: findEntities, findEntityChildren, findEntityParents, findLists, findListChildren, findListParents, findRelations, findEntityReactions, findChildrenEntityReactionsByReactionId, findParentsByEntityReactionId, findReactionsByEntityId, findListReactions, findChildrenListReactionsByReactionId, findParentsByListReactionId, findReactionsByListId, findEntitiesByListId, findListsByEntityId, findAllEntitiesByKindAlias, findEntityChildrenByKindAlias, findEntityParentsByKindAlias

**Pattern 2: After AuthorizeRequest, Before ApplyFieldsetConfig**
- Used in: findEntityById, findListById, findRelationById, findEntityReactionById, findListReactionById, findEntityByIdByKindAlias

**Pattern 3: After Query Filters, Before End of Chain**
- Used in: countEntities, countLists, countRelations, countEntityReactions, countListReactions, countEntitiesByKindAlias

---

## Configuration Reference

All `DynamicLocalCache` filters use three required arguments:
1. **timeToLive**: References the corresponding operation's TTL configuration from `application-local-caching.yml`
2. **size**: References the corresponding operation's size configuration from `application-local-caching.yml`
3. **recordType**: Specifies the record type (entities, lists, relations, entityReactions, listReactions)

The filter supports dynamic configuration overrides based on kind names, allowing per-kind customization of cache behavior.
