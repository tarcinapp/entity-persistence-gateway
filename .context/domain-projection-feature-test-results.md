# Dynamic OAS Generation - Feature Test Results

**Test Date:** 2026-02-25  
**Role Tested:** `tarcinapp.member` (member role user)  
**Gateway:** Spring Boot on `localhost:8081` (dev profile)  
**Backend:** LoopBack on `localhost:3000` (29 paths, 52 schemas)

---

## Executive Summary

The Dynamic OpenAPI Specification (OAS) generation feature was rigorously tested across two phases. **Phase 1** validated core controller endpoints and route toggle mechanics. **Phase 2** validated domain projection with aliases, schema merging, hierarchy endpoints, and route-level overrides.

### Overall Verdict: FUNCTIONAL with identified issues

**Working correctly:**
- All 5 controllers generate correct base paths and operations
- Route toggle whitelist (`tags.on`) and blacklist (`tags.off`) modes work independently
- Domain projection aliases generate correct virtualized paths
- Schema merging (base system fields + alias custom fields) works for all controllers
- Hierarchy endpoints (children/parents) generate correct paths and schemas
- Route-level schema overrides work (including hierarchy route-level overrides)
- Route-level operationId and description overrides work
- Request body binding to domain-specific `New{Alias}` schemas works
- Response schemas include proper domain-specific fields
- Top-level alias schemas differ correctly from hierarchy schemas for the same kind

**Bugs found:**
1. **Singularization bug** in auto-generated operation IDs
2. **Description value includes literal quotes** from properties file
3. **Hierarchy description grammar issues** ("a author" instead of "an author", truncated singulars)
4. **`tags.on` overrides `tags.off`** when both are configured simultaneously

---

## Phase 1: Core Endpoints & Route Toggles

### 1.1 Individual Controller Tests

Each controller was tested in isolation using `tags.on[0]={controllerTag}`.

#### Entities Controller (`tags.on[0]=entities`)

| Metric | Value |
|--------|-------|
| OAS Size | 104,166 bytes |
| Paths | 5 |
| Schemas | 8 gateway-only + shared |

**Paths:**
- `GET/PATCH/POST /api/v1/entities`
- `GET /api/v1/entities/count`
- `DELETE/GET/PATCH/PUT /api/v1/entities/{id}`
- `GET/POST /api/v1/entities/{id}/children`
- `GET /api/v1/entities/{id}/parents`

**Key Schemas:**

| Schema | Fields | Notes |
|--------|--------|-------|
| `NewEntity` | `_kind, _ownerGroups, _viewerUsers, _viewerGroups, _visibility, _name, _parents` (7 fields) | Stripped 9 backend fields (managed/system) |
| `PatchEntity` | `_ownerUsers, _ownerGroups, _viewerUsers, _viewerGroups, _visibility, _name, _parents` | Includes `_ownerUsers` not in NewEntity |
| `Entity` (response) | 17 fields including `_reactions` | Full response with system fields |
| `GatewayInternalError` | `statusCode, error, message, requestId` | Gateway-only error schema |
| `GatewayValidationError` | Object with `error` property | Gateway-only validation error |

#### Lists Controller (`tags.on[0]=lists`)

| Metric | Value |
|--------|-------|
| OAS Size | 109,759 bytes |
| Paths | 5 (same structure as entities) |

**Key differences from entities:**
- `List` response includes `_relationMetadata`, `_entities`, `_reactions`
- `NewList` has same fields as `NewEntity`

#### Entity Reactions Controller (`tags.on[0]=entityReactions`)

| Metric | Value |
|--------|-------|
| OAS Size | 116,319 bytes |
| Paths | 5 |

**Key differences:**
- `NewEntityReaction` includes `_entityId` (no `_name`)
- `PatchEntityReaction` excludes `_entityId` (cannot change target entity)

#### List Reactions Controller (`tags.on[0]=listReactions`)

| Metric | Value |
|--------|-------|
| OAS Size | 116,101 bytes |
| Paths | 5 |

**Key differences:**
- `NewListReaction` includes `_listId`
- Response schemas mirror entity reactions but with `_listId`

#### Relations Controller (`tags.on[0]=relations`)

| Metric | Value |
|--------|-------|
| OAS Size | 76,467 bytes |
| Paths | 3 (no children/parents) |

**Paths:**
- `GET/PATCH/POST /api/v1/relations`
- `GET /api/v1/relations/count`
- `DELETE/GET/PATCH/PUT /api/v1/relations/{id}`

**Key differences:**
- `NewRelation` is minimal: `_kind, _listId, _entityId` (3 fields)
- `PatchRelation` has empty properties `[]`
- No hierarchy endpoints (children/parents)

---

### 1.2 Complex Toggle Scenarios

#### Test: Entities + Lists ON

**Config:** `tags.on[0]=entities`, `tags.on[1]=lists`  
**Result:** 210,781 bytes, 10 paths (5 entities + 5 lists)  
**Verdict:** PASS - Both controllers' paths visible, no cross-contamination

#### Test: Read-Only ON

**Config:** `tags.on[0]=read-only`  
**Result:** 294,043 bytes, 23 GET-only paths  
**Verdict:** PASS - All 5 controllers' GET endpoints visible, no write operations

#### Test: Entities ON + Count/Delete OFF (Conflicting Tags)

**Config:** `tags.on[0]=entities`, `tags.off[0]=count`, `tags.off[1]=delete`  
**Result:** Count endpoint and DELETE operations STILL VISIBLE  
**Verdict:** **FINDING** - When `tags.on` is configured, `tags.off` values are IGNORED

> **Important Behavioral Discovery:** The `tags.on` whitelist takes absolute priority. When any `tags.on` value is set, the `tags.off` blacklist has no effect. The OFF mechanism only works when no ON tags are configured.

#### Test: Blacklist-Only Mode (tags.off without tags.on)

**Config:** `tags.off[0]=count`, `tags.off[1]=delete`, `tags.off[2]=entityReactions`, `tags.off[3]=listReactions`, `tags.off[4]=relations`  
**Result:** 194,110 bytes, 8 paths  
**Verdict:** PASS - Blacklist correctly excludes:
- `/count` endpoints removed
- `DELETE` operations removed from `/{id}` paths
- `entityReactions`, `listReactions`, `relations` controller paths completely removed
- Only entities and lists paths remain (without count/delete)

**Remaining paths:**
```
/api/v1/entities: [get, patch, post]
/api/v1/entities/{id}: [get, patch, put]  (no delete)
/api/v1/entities/{id}/children: [get, post]
/api/v1/entities/{id}/parents: [get]
/api/v1/lists: [get, patch, post]
/api/v1/lists/{id}: [get, patch, put]  (no delete)
/api/v1/lists/{id}/children: [get, post]
/api/v1/lists/{id}/parents: [get]
```

---

## Phase 2: Domain Projection (Aliases, Schemas, Hierarchies)

**Config:** All alias configurations uncommented, no tag toggles active  
**Result:** 1,457,311 bytes, **61 paths**, **103 schemas**

### 2.1 Path Structure

Domain projection aliases create paths nested under the controller base path:

```
/api/v1/{controller}/{alias}           → Collection (GET/PATCH/POST)
/api/v1/{controller}/{alias}/count     → Count (GET)
/api/v1/{controller}/{alias}/{id}      → Instance (DELETE/GET/PATCH/PUT)
/api/v1/{controller}/{alias}/{id}/{child_alias}  → Children (GET/POST)
/api/v1/{controller}/{alias}/{id}/{parent_alias} → Parents (GET only)
```

Generic base controller routes (`/api/v1/entities`, `/api/v1/entities/{id}`, etc.) remain alongside aliased paths.

### 2.2 Entities Controller Aliases

#### Books (alias=books, kind=book)

**Paths:** `/api/v1/entities/books`, `/api/v1/entities/books/count`, `/api/v1/entities/books/{id}`, `/api/v1/entities/books/{id}/chapters`, `/api/v1/entities/books/{id}/authors`

**Schema Merging:**

| Schema | Base Fields | Custom Fields | Required |
|--------|-------------|---------------|----------|
| `NewBook` | `_kind, _ownerGroups, _viewerUsers, _viewerGroups, _visibility, _parents` | `_name, isbn` | None in New* |
| `Book` (response) | All 15 system fields + `_reactions` | `_name, isbn` | `_name, isbn` |
| `PatchBook` | `_ownerUsers, _ownerGroups, _viewerUsers, _viewerGroups, _visibility, _parents` | `_name, isbn` | None |
| `BookFindEntities` | All system fields + `_reactions` | `_name, isbn, createdFrom` | `_name, isbn, createdFrom` |

**Route-Level Overrides (WORKING):**
- `POST /entities/books`: operationId=`createBook` (from `routes.createEntity.operationId`)
- `POST /entities/books`: description=`"Create a new book"` (from `routes.createEntity.description`)
- `GET /entities/books`: response uses `BookFindEntities` schema with extra `createdFrom` field (from `routes.findEntities.schema`)

#### Authors (alias=authors, kind=author)

**Top-level paths:** `/api/v1/entities/authors`, `/api/v1/entities/authors/count`, `/api/v1/entities/authors/{id}`, `/api/v1/entities/authors/{id}/books`

| Schema | Custom Fields |
|--------|---------------|
| `NewAuthor` | `_name, country` |
| `Author` (response) | `_name, country` (REQUIRED) |
| `NewAuthorsChildBook` | `_name, isbn` |
| `AuthorsChildBook` (response) | `_name, isbn` |

#### Chapters (alias=chapters, kind=chapter)

**Top-level paths:** `/api/v1/entities/chapters`, `/api/v1/entities/chapters/count`, `/api/v1/entities/chapters/{id}`

| Schema | Custom Fields | Required |
|--------|---------------|----------|
| `NewChapter` (top-level) | `_name, pageCount, standalone` | None |
| `NewBooksChildChapterCreateEntityChild` (hierarchy) | `_name, pageCount, chapterNumber` | `_name, chapterNumber` |

> **Schema differentiation works:** Top-level `/chapters` uses `standalone` field, hierarchy `/books/{id}/chapters` uses `chapterNumber` field. Different schemas for the same kind depending on context.

### 2.3 Lists Controller Aliases

#### Bookshelves (alias=bookshelves, kind=bookshelf)

**Paths:** `/api/v1/lists/bookshelves`, `.../count`, `.../{id}`, `.../{id}/sections`, `.../{id}/libraries`

| Schema | Custom Fields |
|--------|---------------|
| `NewBookshelf` | `_name, capacity` |
| `Bookshelf` (response) | `_name, capacity` + list-specific fields (`_entities, _relationMetadata, _reactions`) |
| `NewBookshelvesChildSection` | `_name, genre` |
| `BookshelvesParentLibrary` (response) | `_name, city` (REQUIRED) |

#### Libraries (alias=libraries, kind=library)

**Paths:** `/api/v1/lists/libraries`, `.../count`, `.../{id}`, `.../{id}/bookshelves`

| Schema | Custom Fields |
|--------|---------------|
| `NewLibrary` | `_name, city` |
| `NewLibrariesChildBookshelf` | `_name` |

### 2.4 Relations Controller Aliases

#### Contains (alias=contains, kind=contains)

**Paths:** `/api/v1/relations/contains`, `.../count`, `.../{id}`

| Schema | Custom Fields | Required |
|--------|---------------|----------|
| `NewContains` | `_kind, _entityId, _listId, note, order` | None |
| `Contains` (response) | System fields + `note, order` | `_entityId, _listId, order` |
| `PatchContains` | `note, order` | None |

### 2.5 Entity Reactions Aliases

#### Comments (alias=comments, kind=comment)

**Paths:** `/api/v1/entity-reactions/comments`, `.../count`, `.../{id}`, `.../{id}/replies`

| Schema | Custom Fields | Required |
|--------|---------------|----------|
| `NewComment` | `_entityId, text` + base reaction fields | None |
| `Comment` (response) | `_entityId, text` + system fields | `_entityId, text` |
| `NewCommentsChildReply` | `_entityId, text, replyTo` | None |

#### Likes (alias=likes, kind=like)

**Paths:** `/api/v1/entity-reactions/likes`, `.../count`, `.../{id}`

| Schema | Custom Fields |
|--------|---------------|
| `NewLike` | `_entityId` + base reaction fields |
| `Like` (response) | `_entityId` + system fields |

### 2.6 List Reactions Aliases

#### Ratings (alias=ratings, kind=rating)

**Paths:** `/api/v1/list-reactions/ratings`, `.../count`, `.../{id}`, `.../{id}/responses`

| Schema | Custom Fields | Required |
|--------|---------------|----------|
| `NewRating` | `_listId, score` | None |
| `Rating` (response) | `_listId, score` | `_listId, score` |
| `NewRatingsChildResponse` | `_listId, message` | None |

#### Upvotes (alias=upvotes, kind=upvote)

**Paths:** `/api/v1/list-reactions/upvotes`, `.../count`, `.../{id}`

| Schema | Custom Fields |
|--------|---------------|
| `NewUpvote` | `_listId` |

### 2.7 Request Body & Response Bindings

All POST operations correctly bind to domain-specific schemas:

| Operation | Request Body | Response Schema |
|-----------|-------------|-----------------|
| `POST /entities/books` | `NewBook` | `BookCreateEntity` |
| `POST /entities/authors` | `NewAuthor` | `AuthorCreateEntity` |
| `POST /entities/chapters` | `NewChapter` | `ChapterCreateEntity` |
| `POST /books/{id}/chapters` | `NewBooksChildChapterCreateEntityChild` | `BooksChildChapterCreateEntityChild` |
| `POST /lists/bookshelves` | `NewBookshelf` | `BookshelfCreateList` |
| `POST /lists/libraries` | `NewLibrary` | `LibraryCreateList` |
| `POST /relations/contains` | `NewContains` | `ContainsCreateRelation` |
| `POST /entity-reactions/comments` | `NewComment` | `CommentCreateEntityReaction` |
| `POST /comments/{id}/replies` | `NewCommentsChildReply` | `CommentsChildReplyCreateEntityReactionChild` |
| `POST /list-reactions/ratings` | `NewRating` | `RatingCreateListReaction` |
| `POST /list-reactions/upvotes` | `NewUpvote` | `UpvoteCreateListReaction` |

GET list endpoints with route-level schema overrides reference specialized schemas:
- `GET /entities/books` → `BookFindEntities` (route-level override with `createdFrom`)
- `GET /entities/authors` → `Author` (no override, uses base response schema)

---

## Bugs & Issues Found

### BUG-1: Singularization Algorithm Defect (Severity: Medium)

**Affected:** Auto-generated operation IDs for aliases ending in `-es` or `-ves`

The singularization algorithm incorrectly strips trailing "es" instead of just "s", and doesn't handle irregular plurals:

| Alias | Generated Singular | Expected Singular | Example Operation ID |
|-------|--------------------|-------------------|---------------------|
| `likes` | `lik` | `like` | `createLik` → should be `createLike` |
| `upvotes` | `upvot` | `upvote` | `createUpvot` → should be `createUpvote` |
| `responses` | `respons` | `response` | `createRatingRespons` → should be `createRatingResponse` |
| `bookshelves` | `bookshelv` | `bookshelf` | `createBookshelv` → should be `createBookshelf` |

**Impact:** Operation IDs are grammatically incorrect and would cause issues for code generators.

**Affected paths and operations (complete list):**
- `/api/v1/lists/bookshelves`: `listBookshelv`, `createBookshelv`
- `/api/v1/lists/bookshelves/count`: `countBookshelv`
- `/api/v1/lists/bookshelves/{id}`: `deleteBookshelv`, `getBookshelvById`, `updateBookshelv`, `replaceBookshelv`
- `/api/v1/entity-reactions/likes`: `createLik`
- `/api/v1/list-reactions/upvotes`: `createUpvot`
- `/api/v1/list-reactions/ratings/{id}/responses`: `createRatingRespons`
- `/api/v1/lists/libraries/{id}/bookshelves`: `createLibraryBookshelv`
- `/api/v1/lists/bookshelves/{id}/sections`: `createBookshelvSection`, `listBookshelvSections`
- `/api/v1/lists/bookshelves/{id}/libraries`: `listBookshelvLibraries`

### BUG-2: Description Value Includes Literal Quotes (Severity: Low)

**Affected:** PathItem `description` field and operation `description` field

When `description="Manage books"` is set in properties file, the value includes the literal quote characters: `"Manage books"` instead of `Manage books`.

**Example:**
```json
{
  "/api/v1/entities/books": {
    "description": "\"Manage books\"",
    ...
  }
}
```

**Affected paths:** All paths derived from aliases with `description` property set.

### BUG-3: Hierarchy Description Grammar Issues (Severity: Low)

**Examples:**
- `"Books of a author"` → should be `"Books of an author"` (a/an rule)
- `"Libraries of a bookshelv"` → should be `"Libraries of a bookshelf"` (singularization)
- `"Sections of a bookshelv"` → should be `"Sections of a bookshelf"` (singularization)

### BUG-4: tags.on Silently Overrides tags.off (Severity: Medium)

When both `tags.on` and `tags.off` are configured simultaneously, the `tags.off` values are completely ignored. No warning is logged.

**Expected behavior options:**
1. `tags.off` should further filter within the `tags.on` whitelist
2. Or: A warning should be logged that `tags.off` has no effect when `tags.on` is set

**Current behavior:** `tags.off` is silently ignored.

---

## Schema Summary Table (103 Total Schemas)

### Base Controller Schemas (always present)

| Schema | Controller | Purpose |
|--------|-----------|---------|
| `Entity` | entities | Response |
| `EntityCreateEntity` | entities | POST response |
| `EntityCreateChildEntity` | entities | POST child response |
| `EntityUpdateEntities` | entities | PATCH collection response |
| `NewEntity` | entities | POST request body |
| `PatchEntity` | entities | PATCH request body |
| `List` | lists | Response |
| `ListCreateList` | lists | POST response |
| `ListCreateChildList` | lists | POST child response |
| `ListUpdateLists` | lists | PATCH collection response |
| `NewList` | lists | POST request body |
| `PatchList` | lists | PATCH request body |
| `Relation` | relations | Response |
| `RelationCreateRelation` | relations | POST response |
| `RelationUpdateRelations` | relations | PATCH collection response |
| `NewRelation` | relations | POST request body |
| `PatchRelation` | relations | PATCH request body |
| `EntityReaction` | entityReactions | Response |
| `EntityReactionCreateEntityReaction` | entityReactions | POST response |
| `EntityReactionCreateChildEntityReaction` | entityReactions | POST child response |
| `EntityReactionUpdateEntityReactions` | entityReactions | PATCH collection response |
| `NewEntityReaction` | entityReactions | POST request body |
| `PatchEntityReaction` | entityReactions | PATCH request body |
| `ListReaction` | listReactions | Response |
| `ListReactionCreateListReaction` | listReactions | POST response |
| `ListReactionCreateChildListReaction` | listReactions | POST child response |
| `ListReactionUpdateListReactions` | listReactions | PATCH collection response |
| `NewListReaction` | listReactions | POST request body |
| `PatchListReaction` | listReactions | PATCH request body |
| `GatewayInternalError` | gateway | Error response |
| `GatewayValidationError` | gateway | Validation error |
| `ValidationErrorDetail` | gateway | Validation detail |

### Domain Alias Schemas (generated from alias config)

**Entities Controller:**
- `Book`, `BookCreateEntity`, `BookFindEntities`, `BookUpdateEntities`, `NewBook`, `PatchBook`
- `Author`, `AuthorCreateEntity`, `AuthorUpdateEntities`, `NewAuthor`, `PatchAuthor`
- `Chapter`, `ChapterCreateEntity`, `ChapterUpdateEntities`, `NewChapter`, `PatchChapter`
- `BooksChildChapter`, `BooksChildChapterCreateEntityChild`, `NewBooksChildChapterCreateEntityChild`
- `BooksParentAuthor`
- `AuthorsChildBook`, `AuthorsChildBookCreateEntityChild`, `NewAuthorsChildBook`

**Lists Controller:**
- `Bookshelf`, `BookshelfCreateList`, `BookshelfUpdateLists`, `NewBookshelf`, `PatchBookshelf`
- `Library`, `LibraryCreateList`, `LibraryUpdateLists`, `NewLibrary`, `PatchLibrary`
- `BookshelvesChildSection`, `BookshelvesChildSectionCreateListChild`, `NewBookshelvesChildSection`
- `BookshelvesParentLibrary`
- `LibrariesChildBookshelf`, `LibrariesChildBookshelfCreateListChild`, `NewLibrariesChildBookshelf`

**Relations Controller:**
- `Contains`, `ContainsCreateRelation`, `ContainsUpdateRelations`, `NewContains`, `PatchContains`

**Entity Reactions Controller:**
- `Comment`, `CommentCreateEntityReaction`, `CommentUpdateEntityReactions`, `NewComment`, `PatchComment`
- `Like`, `LikeCreateEntityReaction`, `LikeUpdateEntityReactions`, `NewLike`, `PatchLike`
- `CommentsChildReply`, `CommentsChildReplyCreateEntityReactionChild`, `NewCommentsChildReply`

**List Reactions Controller:**
- `Rating`, `RatingCreateListReaction`, `RatingUpdateListReactions`, `NewRating`, `PatchRating`
- `Upvote`, `UpvoteCreateListReaction`, `UpvoteUpdateListReactions`, `NewUpvote`, `PatchUpvote`
- `RatingsChildResponse`, `RatingsChildResponseCreateListReactionChild`, `NewRatingsChildResponse`

---

## Test Configuration Reference

### Lifecycle Protocol

For each test scenario:
1. **Stop** gateway: `lsof -t -i:8081 | xargs kill -9`
2. **Flush** Redis: `redis-cli -a devpassword123 FLUSHDB`
3. **Modify** `application-dev.properties`
4. **Start** gateway: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
5. **Query** OAS: `curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/openapi.json`

### Saved OAS Files

| File | Description | Size |
|------|-------------|------|
| `/tmp/backend-oas.json` | Backend baseline | 327,973 bytes |
| `/tmp/gateway-oas-entities.json` | Entities only | 104,166 bytes |
| `/tmp/gateway-oas-lists.json` | Lists only | 109,759 bytes |
| `/tmp/gateway-oas-entityReactions.json` | Entity reactions only | 116,319 bytes |
| `/tmp/gateway-oas-listReactions.json` | List reactions only | 116,101 bytes |
| `/tmp/gateway-oas-relations.json` | Relations only | 76,467 bytes |
| `/tmp/gateway-oas-blacklist.json` | Blacklist mode | 194,110 bytes |
| `/tmp/gateway-oas-domain.json` | Full domain projection | 1,457,311 bytes |
