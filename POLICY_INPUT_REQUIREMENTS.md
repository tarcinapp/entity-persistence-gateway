# Policy Input Requirements

This document describes which policies require `originalRecord` and/or `requestPayload`, and the expected format and enrichment requirements for each.

## Overview

OPA policies evaluate authorization based on two key input fields:

- **`originalRecord`**: The existing resource state, required for single-record operations
- **`requestPayload`**: The request body, required for create/update/replace operations

## Quick Reference Table

| Operation Type | originalRecord | requestPayload | Notes |
|---------------|----------------|----------------|-------|
| **find-by-id** (single record) | ✅ Required | ❌ Not used | Includes enriched metadata for derived resources |
| **find/count** (list) | ❌ Not used | ❌ Not used | Gateway must filter queries |
| **create** | ❌ Not used | ✅ Required | May require enriched `_relationMetadata` |
| **update** | ✅ Required | ✅ Required | Policies compare old vs new state |
| **replace** | ✅ Required | ✅ Required | Policies compare old vs new state |
| **delete** | ✅ Required | ❌ Not used | Based on existing record only |
| **findParents/findChildren** | ✅ Required | ❌ Not used | Parent/child record for visibility check |

## Resource Types

### 1. Entities

#### List Operations (findEntities, countEntities)
- **originalRecord**: ❌ Not required
- **requestPayload**: ❌ Not required
- **Notes**: Gateway must filter queries to return only visible records

#### Single Record Read (findEntityById)
- **originalRecord**: ✅ Required
- **requestPayload**: ❌ Not used
- **Format**:
```json
{
  "originalRecord": {
    "_id": "entity-123",
    "_visibility": "public|protected|private",
    "_validFromDateTime": "2024-01-01T00:00:00Z",
    "_validUntilDateTime": "2025-01-01T00:00:00Z",
    "_ownerUsers": ["user-id-1"],
    "_ownerGroups": ["group-1"],
    "_viewerUsers": ["user-id-2"],
    "_viewerGroups": ["group-2"],
    "...": "other entity fields"
  }
}
```

#### Create (createEntity, createEntityChild)
- **originalRecord**: ⚠️ For `createEntityChild` only - the parent entity
- **requestPayload**: ✅ Required
- **Format**:
```json
{
  "requestPayload": {
    "_visibility": "public|protected|private",
    "_ownerUsers": ["user-id"],
    "_ownerGroups": ["group-id"],
    "...": "other entity fields"
  }
}
```
- **Notes**: Members cannot specify `_ownerGroups` not in their token groups

#### Update/Replace (updateEntityById, replaceEntityById)
- **originalRecord**: ✅ Required
- **requestPayload**: ✅ Required
- **Notes**: 
  - Policies compare fields between old and new state
  - Restricted fields must not change
  - Ownership changes are restricted based on role

#### Delete (deleteEntityById)
- **originalRecord**: ✅ Required
- **requestPayload**: ❌ Not used

#### Parent/Child Navigation (findEntityParents, findEntityChildren)
- **originalRecord**: ✅ Required - the child/parent entity being queried
- **requestPayload**: ❌ Not used
- **Notes**: Used to verify caller can see the source entity

---

### 2. Relations

**IMPORTANT**: Relations are special because they connect two resources (source and target). The policies need metadata from **both** connected resources to evaluate derived authorization.

#### List Operations (findRelations, countRelations)
- **originalRecord**: ❌ Not required
- **requestPayload**: ❌ Not required
- **Notes**: Gateway must filter queries to return only relations where caller can see both source and target

#### Single Record Read (findRelationById)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ❌ Not used
- **Enrichment Format**:
```json
{
  "originalRecord": {
    "_id": "relation-123",
    "_from": "list-id",
    "_to": "entity-id",
    "_type": "cites",
    "_fromMetadata": {
      "_id": "list-id",
      "_visibility": "public|protected|private",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_validUntilDateTime": "2025-01-01T00:00:00Z",
      "_ownerUsers": ["user-id-1"],
      "_ownerGroups": ["group-1"],
      "_viewerUsers": ["user-id-2"],
      "_viewerGroups": ["group-2"]
    },
    "_toMetadata": {
      "_id": "entity-id",
      "_visibility": "public|protected|private",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_validUntilDateTime": "2025-01-01T00:00:00Z",
      "_ownerUsers": ["user-id-3"],
      "_ownerGroups": ["group-3"],
      "_viewerUsers": ["user-id-4"],
      "_viewerGroups": ["group-4"]
    }
  }
}
```
- **Gateway Responsibility**: Fetch and attach `_fromMetadata` and `_toMetadata` before calling policy

#### Create (createRelation)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ✅ Required
- **Enrichment Format**:
```json
{
  "originalRecord": {
    "_fromMetadata": {
      "_id": "list-id",
      "_visibility": "public|protected|private",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_validUntilDateTime": "2025-01-01T00:00:00Z",
      "_ownerUsers": ["user-id-1"],
      "_ownerGroups": ["group-1"]
    },
    "_toMetadata": {
      "_id": "entity-id",
      "_visibility": "public|protected|private",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_validUntilDateTime": "2025-01-01T00:00:00Z",
      "_ownerUsers": ["user-id-2"],
      "_ownerGroups": ["group-2"]
    }
  },
  "requestPayload": {
    "_from": "list-id",
    "_to": "entity-id",
    "_type": "cites"
  }
}
```
- **Gateway Responsibility**: 
  1. Extract `_from` and `_to` from `requestPayload`
  2. Fetch metadata for both referenced resources
  3. Attach as `_fromMetadata` and `_toMetadata` in `originalRecord`

#### Update/Replace (updateRelationById, replaceRelationById)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ✅ Required
- **Notes**: Same enrichment as findRelationById - must include `_fromMetadata` and `_toMetadata`

#### Delete (deleteRelationById)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ❌ Not used
- **Notes**: Same enrichment as findRelationById

---

### 3. Entity Reactions (entityReactions / entity-reactions)

**IMPORTANT**: Entity reactions are children of entities. Policies need the parent entity metadata to evaluate derived authorization.

#### List Operations (findEntityReactions, countEntityReactions)
- **originalRecord**: ❌ Not required
- **requestPayload**: ❌ Not required
- **Notes**: Gateway must filter queries

#### Single Record Read (findEntityReactionById)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ❌ Not used
- **Enrichment Format**:
```json
{
  "originalRecord": {
    "_id": "reaction-123",
    "_visibility": "public|protected|private",
    "_validFromDateTime": "2024-01-01T00:00:00Z",
    "_validUntilDateTime": "2025-01-01T00:00:00Z",
    "_ownerUsers": ["user-id-1"],
    "_ownerGroups": ["group-1"],
    "_viewerUsers": ["user-id-2"],
    "_viewerGroups": ["group-2"],
    "_relationMetadata": {
      "_id": "parent-entity-id",
      "_visibility": "public|protected|private",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_validUntilDateTime": "2025-01-01T00:00:00Z",
      "_ownerUsers": ["user-id-3"],
      "_ownerGroups": ["group-3"],
      "_viewerUsers": ["user-id-4"],
      "_viewerGroups": ["group-4"]
    }
  }
}
```
- **Gateway Responsibility**: Fetch parent entity metadata and attach as `_relationMetadata`

#### Create (createEntityReaction, createReactionByEntityId)
- **originalRecord**: ❌ Not used for top-level creation
- **requestPayload**: ✅ Required with **enrichment**
- **Enrichment Format**:
```json
{
  "requestPayload": {
    "_visibility": "public|protected|private",
    "_ownerUsers": ["user-id"],
    "_ownerGroups": ["group-id"],
    "_relationMetadata": {
      "_id": "parent-entity-id",
      "_visibility": "public|protected|private",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_validUntilDateTime": "2025-01-01T00:00:00Z",
      "_ownerUsers": ["user-id-2"],
      "_ownerGroups": ["group-2"],
      "_viewerUsers": ["user-id-3"],
      "_viewerGroups": ["group-3"]
    }
  }
}
```
- **Gateway Responsibility**: Fetch parent entity metadata and attach as `_relationMetadata` in `requestPayload`

#### Create Child (createChildEntityReaction)
- **originalRecord**: ✅ Required with **enrichment** - the parent reaction
- **requestPayload**: ✅ Required
- **Notes**: 
  - `originalRecord` contains parent reaction with its `_relationMetadata` (grandparent entity)
  - Policy checks visibility of both parent reaction and grandparent entity

#### Update/Replace (updateEntityReactionById, replaceEntityReactionById)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ✅ Required
- **Notes**: `originalRecord` must include `_relationMetadata` with parent entity information

#### Delete (deleteEntityReactionById)
- **originalRecord**: ✅ Required with **enrichment**
- **requestPayload**: ❌ Not used
- **Notes**: `originalRecord` must include `_relationMetadata`

#### Parent/Child Navigation (findParentsByEntityReactionId, findChildrenEntityReactionsByReactionId)
- **originalRecord**: ✅ Required - the child/parent reaction
- **requestPayload**: ❌ Not used
- **Notes**: Used to verify caller can see the source reaction

---

### 4. List Reactions (listReactions)

Similar pattern to Entity Reactions but parent is a List instead of Entity.

#### Single Record Operations
- Same enrichment pattern as Entity Reactions
- **`_relationMetadata`** contains the parent **list** metadata

---

### 5. Lists

Lists follow the same pattern as Entities for simple operations.

#### Through-Entity Operations (listsThroughEntity)
- When creating/finding lists through an entity, the **entity metadata** must be provided
- Similar enrichment to relations

#### Parent/Child Operations
- Same pattern as entities with parent/child navigation

---

## Enrichment Responsibility Summary

| Resource Type | Operation | Enrichment Required | Gateway Must Fetch |
|--------------|-----------|---------------------|-------------------|
| **Entity** | Single-record ops | None | Nothing extra |
| **Relation** | All single-record ops | `_fromMetadata` + `_toMetadata` | Both source and target metadata |
| **Entity Reaction** | Create | `_relationMetadata` in `requestPayload` | Parent entity metadata |
| **Entity Reaction** | Other single-record | `_relationMetadata` in `originalRecord` | Parent entity metadata |
| **List Reaction** | Create | `_relationMetadata` in `requestPayload` | Parent list metadata |
| **List Reaction** | Other single-record | `_relationMetadata` in `originalRecord` | Parent list metadata |

---

## Common Metadata Fields

All metadata objects (whether in `originalRecord`, `_fromMetadata`, `_toMetadata`, or `_relationMetadata`) should include:

```json
{
  "_id": "resource-id",
  "_visibility": "public|protected|private",
  "_validFromDateTime": "2024-01-01T00:00:00Z",
  "_validUntilDateTime": "2025-01-01T00:00:00Z",
  "_ownerUsers": ["user-id-1", "user-id-2"],
  "_ownerGroups": ["group-1", "group-2"],
  "_viewerUsers": ["user-id-3"],
  "_viewerGroups": ["group-3"]
}
```

### Field Descriptions

- **`_visibility`**: Access level - `public`, `protected`, or `private`
- **`_validFromDateTime`**: When the record becomes active (RFC3339 format)
- **`_validUntilDateTime`**: When the record expires (RFC3339 format, optional)
- **`_ownerUsers`**: User IDs with ownership rights
- **`_ownerGroups`**: Group IDs with ownership rights
- **`_viewerUsers`**: User IDs with explicit view permission
- **`_viewerGroups`**: Group IDs with explicit view permission

---

## Implementation Guidelines

### For Relations

When calling policy for relation operations:

1. **Extract identifiers**: Get `_from` and `_to` from the relation record or request payload
2. **Fetch metadata**: Query the database for the full metadata of both referenced resources
3. **Attach metadata**: Add `_fromMetadata` and `_toMetadata` to `originalRecord`
4. **Call policy**: Invoke OPA with enriched input

Example code pattern:
```javascript
// For createRelation
const fromMetadata = await fetchResourceMetadata(requestPayload._from);
const toMetadata = await fetchResourceMetadata(requestPayload._to);

const policyInput = {
  encodedJwt: token,
  requestPayload: requestPayload,
  originalRecord: {
    _fromMetadata: fromMetadata,
    _toMetadata: toMetadata
  }
};
```

### For Entity/List Reactions

When calling policy for reaction operations:

1. **Determine parent**: Get parent entity/list ID from the relation or request
2. **Fetch parent metadata**: Query for the parent's full metadata
3. **Attach metadata**: Add `_relationMetadata` to either `requestPayload` (create) or `originalRecord` (other ops)
4. **Call policy**: Invoke OPA with enriched input

Example code pattern:
```javascript
// For createEntityReaction
const parentEntityMetadata = await fetchEntityMetadata(parentEntityId);

const policyInput = {
  encodedJwt: token,
  requestPayload: {
    ...requestPayload,
    _relationMetadata: parentEntityMetadata
  }
};

// For findEntityReactionById
const reaction = await fetchReaction(reactionId);
const parentEntityMetadata = await fetchEntityMetadata(reaction.parentEntityId);

const policyInput = {
  encodedJwt: token,
  originalRecord: {
    ...reaction,
    _relationMetadata: parentEntityMetadata
  }
};
```

---

## Special Cases

### Hierarchical Resources (Parent/Child)

For operations like `createEntityChild` or `findEntityParents`:
- **`originalRecord`** contains the immediate parent/child being validated
- Gateway must ensure the caller can see this record before proceeding with the operation

### Bulk Update Operations

For operations like `updateAllEntities` or `updateAllEntityReactions`:
- **`originalRecord`**: ❌ Not provided (list operation)
- **`requestPayload`**: ✅ Required (the update payload)
- Gateway must filter the affected records through query narrowing

---

## Helper Utilities

The policies use shared helper functions from `policies/util/common/originalRecord.rego`:

- `is_active`: Checks if record's `_validFromDateTime` and `_validUntilDateTime` are valid
- `is_passive`: Checks if record has expired
- `is_public/is_protected/is_private`: Checks visibility level
- `is_belong_to_user`: Checks if user is in `_ownerUsers`
- `is_belong_to_users_groups`: Checks if user's groups are in `_ownerGroups`
- `is_user_in_viewerUsers`: Checks if user is in `_viewerUsers`
- `is_user_in_viewerGroups`: Checks if user's groups are in `_viewerGroups`

These helpers expect `input.originalRecord` to be properly populated with the necessary fields.

---

## Testing Policy Inputs

Use the provided `input.json` file as a reference for testing. Ensure your test inputs include:

1. Valid JWT in `encodedJwt`
2. Properly enriched `originalRecord` when required
3. Complete `requestPayload` when required
4. All required metadata fields with correct formats

Example test input:
```json
{
  "encodedJwt": "eyJ...",
  "requestPayload": {
    "_from": "list-123",
    "_to": "entity-456",
    "_type": "cites"
  },
  "originalRecord": {
    "_fromMetadata": {
      "_id": "list-123",
      "_visibility": "public",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_ownerUsers": ["user-1"],
      "_ownerGroups": ["group-1"]
    },
    "_toMetadata": {
      "_id": "entity-456",
      "_visibility": "protected",
      "_validFromDateTime": "2024-01-01T00:00:00Z",
      "_ownerUsers": ["user-2"],
      "_ownerGroups": ["group-2"]
    }
  }
}
```

---

## References

- Main README: `README.md`
- Common utilities: `policies/util/common/`
- Sample input: `input.json`
- Individual route READMEs: `policies/auth/routes/<resource>/<operation>/README.md`
