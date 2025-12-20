# Route Tags Implementation Report

**Date**: /home/kdrkrst/git/github/entity-persistence-gateway
**Total Routes**: 89
**Routes with Tags**: 89

## Tag Distribution

| Tag | Count | % of Routes |
|-----|-------|-------------|
| generic | 52 | 58.4% |
| write | 40 | 44.9% |
| manage | 40 | 44.9% |
| get | 38 | 42.7% |
| read-only | 38 | 42.7% |
| find | 30 | 33.7% |
| reaction | 30 | 33.7% |
| by-id | 28 | 31.5% |
| single-record | 28 | 31.5% |
| entities | 26 | 29.2% |
| collection | 24 | 27.0% |
| lists | 23 | 25.8% |
| kind-alias | 22 | 24.7% |
| patch | 17 | 19.1% |
| update | 17 | 19.1% |
| post | 16 | 18.0% |
| create | 16 | 18.0% |
| entityReactions | 15 | 16.9% |
| listReactions | 15 | 16.9% |
| through | 13 | 14.6% |
| hierarchical | 12 | 13.5% |
| entitiesKindAlias | 11 | 12.4% |
| listKindAlias | 11 | 12.4% |
| delete | 10 | 11.2% |
| destructive | 10 | 11.2% |
| relations | 8 | 9.0% |
| update-all | 7 | 7.9% |
| bulk | 7 | 7.9% |
| count | 7 | 7.9% |
| put | 7 | 7.9% |
| replace | 7 | 7.9% |
| children | 6 | 6.7% |
| parents | 6 | 6.7% |
| reactionsThroughEntity | 4 | 4.5% |
| reactionsThroughList | 4 | 4.5% |
| entitiesThroughList | 4 | 4.5% |
| utility | 2 | 2.2% |
| listsThroughEntity | 1 | 1.1% |
| ping | 1 | 1.1% |
| health-check | 1 | 1.1% |
| explorer | 1 | 1.1% |
| api-discovery | 1 | 1.1% |

## Popular Tag Combinations

Top 5 most common tag combinations:

- 2 routes: create, entities, generic, manage, post, write
- 2 routes: create, generic, lists, manage, post, write
- 2 routes: create, entityReactions, generic, manage, post, reaction, write
- 2 routes: create, generic, listReactions, manage, post, reaction, write
- 2 routes: create, entities, entitiesKindAlias, kind-alias, manage, post, write

## Routes by Category

### Read-Only (38 routes)

- findEntities
- countEntities
- findEntityById
- findEntityChildren
- findEntityParents
- findLists
- countLists
- findListById
- findListChildren
- findListParents
- ... and 28 more

### Write Operations (40 routes)

- createEntity
- updateAllEntities
- updateEntityById
- replaceEntityById
- createEntityChild
- createList
- updateAllLists
- updateListById
- replaceListById
- createListChild
- ... and 30 more

### Destructive (10 routes)

- deleteEntityById
- deleteListById
- deleteRelationById
- deleteEntityReactionById
- deleteReactionsByEntityId
- deleteListReactionById
- deleteReactionsByListId
- deleteEntitiesByListId
- deleteEntityByIdByKindAlias
- deleteListByIdByKindAlias

### Bulk Operations (7 routes)

- updateAllEntities
- updateAllLists
- updateAllRelations
- updateAllEntityReactions
- updateAllListReactions
- updateAllEntitiesByKindAlias
- updateAllListsByKindAlias

### Management (40 routes)

- createEntity
- updateAllEntities
- updateEntityById
- replaceEntityById
- createEntityChild
- createList
- updateAllLists
- updateListById
- replaceListById
- createListChild
- ... and 30 more

### Kind-Alias (22 routes)

- createEntityByKindAlias
- findAllEntitiesByKindAlias
- replaceEntityByIdByKindAlias
- countEntitiesByKindAlias
- updateAllEntitiesByKindAlias
- findEntityByIdByKindAlias
- updateEntityByIdByKindAlias
- deleteEntityByIdByKindAlias
- findEntityChildrenByKindAlias
- createEntityChildByKindAlias
- ... and 12 more

### Through Operations (13 routes)

- createReactionByEntityId
- updateReactionsByEntityId
- findReactionsByEntityId
- deleteReactionsByEntityId
- createReactionByListId
- updateReactionsByListId
- findReactionsByListId
- deleteReactionsByListId
- createEntityByListId
- updateEntitiesByListId
- ... and 3 more

### Hierarchical (12 routes)

- findEntityChildren
- findEntityParents
- findListChildren
- findListParents
- findChildrenEntityReactionsByReactionId
- findParentsByEntityReactionId
- findChildrenListReactionsByReactionId
- findParentsByListReactionId
- findEntityChildrenByKindAlias
- findEntityParentsByKindAlias
- ... and 2 more

### Reactions (30 routes)

- createEntityReaction
- updateAllEntityReactions
- findEntityReactions
- countEntityReactions
- findEntityReactionById
- updateEntityReactionById
- replaceEntityReactionById
- deleteEntityReactionById
- findChildrenEntityReactionsByReactionId
- createChildEntityReaction
- ... and 20 more

### By ID (28 routes)

- findEntityById
- updateEntityById
- replaceEntityById
- deleteEntityById
- findListById
- updateListById
- replaceListById
- deleteListById
- findRelationById
- updateRelationById
- ... and 18 more

### Utility (2 routes)

- ping
- explorer
