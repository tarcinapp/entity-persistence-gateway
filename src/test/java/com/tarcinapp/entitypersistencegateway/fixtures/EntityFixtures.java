package com.tarcinapp.entitypersistencegateway.fixtures;

import java.time.Instant;
import java.util.UUID;

/**
 * Pre-built entity payloads and responses for tests.
 */
public class EntityFixtures {

    // ==================== Simple Entities ====================

    /**
     * Simple entity with minimal fields.
     */
    public static String simpleEntity() {
        return """
            {
                "name": "Test Entity",
                "kind": "generic-entity",
                "description": "A test entity for testing"
            }
            """;
    }

    /**
     * Simple entity with a specific ID.
     */
    public static String simpleEntityWithId(String id) {
        return String.format("""
            {
                "id": "%s",
                "name": "Test Entity",
                "kind": "generic-entity",
                "description": "A test entity for testing"
            }
            """, id);
    }

    /**
     * Generate a simple entity with a random ID.
     */
    public static String simpleEntityWithRandomId() {
        return simpleEntityWithId(UUID.randomUUID().toString());
    }

    // ==================== Complete Entities ====================

    /**
     * Complete entity with all managed fields.
     */
    public static String completeEntity() {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        return String.format("""
            {
                "id": "%s",
                "name": "Test Entity",
                "kind": "generic-entity",
                "description": "A complete test entity",
                "_createdDateTime": "%s",
                "lastUpdatedDateTime": "%s",
                "createdBy": "test-user-id",
                "lastUpdatedBy": "test-user-id",
                "ownerUsers": ["test-user-id"],
                "ownerGroups": [],
                "viewerUsers": [],
                "viewerGroups": [],
                "visibility": "private"
            }
            """, id, now, now);
    }

    /**
     * Complete entity with a specific ID.
     */
    public static String completeEntityWithId(String id) {
        String now = Instant.now().toString();

        return String.format("""
            {
                "id": "%s",
                "name": "Test Entity",
                "kind": "generic-entity",
                "description": "A complete test entity",
                "_createdDateTime": "%s",
                "lastUpdatedDateTime": "%s",
                "createdBy": "test-user-id",
                "lastUpdatedBy": "test-user-id",
                "ownerUsers": ["test-user-id"],
                "ownerGroups": [],
                "viewerUsers": [],
                "viewerGroups": [],
                "visibility": "private"
            }
            """, id, now, now);
    }

    /**
     * Complete entity with custom owner.
     */
    public static String completeEntityWithOwner(String id, String ownerId) {
        String now = Instant.now().toString();

        return String.format("""
            {
                "id": "%s",
                "name": "Test Entity",
                "kind": "generic-entity",
                "description": "A complete test entity",
                "_createdDateTime": "%s",
                "lastUpdatedDateTime": "%s",
                "createdBy": "%s",
                "lastUpdatedBy": "%s",
                "ownerUsers": ["%s"],
                "ownerGroups": [],
                "viewerUsers": [],
                "viewerGroups": [],
                "visibility": "private"
            }
            """, id, now, now, ownerId, ownerId, ownerId);
    }

    // ==================== Entity Lists ====================

    /**
     * Generate a list of entities.
     */
    public static String entityList(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(simpleEntityWithId(UUID.randomUUID().toString()));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Generate a list of complete entities.
     */
    public static String completeEntityList(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(completeEntityWithId(UUID.randomUUID().toString()));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Empty entity list.
     */
    public static String emptyEntityList() {
        return "[]";
    }

    // ==================== Specific Entity Types ====================

    /**
     * Book entity for kind alias testing.
     */
    public static String bookEntity() {
        return """
            {
                "name": "The Great Gatsby",
                "kind": "book",
                "author": "F. Scott Fitzgerald",
                "isbn": "978-0-7432-7356-5",
                "publishYear": 1925
            }
            """;
    }

    /**
     * Book entity with ID.
     */
    public static String bookEntityWithId(String id) {
        return String.format("""
            {
                "id": "%s",
                "name": "The Great Gatsby",
                "kind": "book",
                "author": "F. Scott Fitzgerald",
                "isbn": "978-0-7432-7356-5",
                "publishYear": 1925
            }
            """, id);
    }

    // ==================== List Records ====================

    /**
     * Simple list record.
     */
    public static String simpleListRecord() {
        return """
            {
                "name": "Test List",
                "kind": "generic-list",
                "description": "A test list"
            }
            """;
    }

    /**
     * List record with ID.
     */
    public static String listRecordWithId(String id) {
        return String.format("""
            {
                "id": "%s",
                "name": "Test List",
                "kind": "generic-list",
                "description": "A test list"
            }
            """, id);
    }

    /**
     * Generate a list of list records.
     */
    public static String listRecordList(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(listRecordWithId(UUID.randomUUID().toString()));
        }
        sb.append("]");
        return sb.toString();
    }

    // ==================== Reactions ====================

    /**
     * Simple reaction.
     */
    public static String simpleReaction() {
        return """
            {
                "kind": "like",
                "targetId": "target-entity-id"
            }
            """;
    }

    /**
     * Reaction with ID.
     */
    public static String reactionWithId(String id) {
        return String.format("""
            {
                "id": "%s",
                "kind": "like",
                "targetId": "target-entity-id"
            }
            """, id);
    }

    // ==================== Update Payloads ====================

    /**
     * Partial update payload.
     */
    public static String updatePayload() {
        return """
            {
                "name": "Updated Entity Name",
                "description": "Updated description"
            }
            """;
    }

    /**
     * Update payload with forbidden field (for testing rejection).
     */
    public static String updatePayloadWithForbiddenField() {
        return """
            {
                "name": "Updated Entity Name",
                "ownerUsers": ["malicious-user"]
            }
            """;
    }

    // ==================== Error Responses ====================

    /**
     * Not found error response.
     */
    public static String notFoundError() {
        return """
            {
                "error": {
                    "statusCode": 404,
                    "name": "NotFoundError",
                    "message": "Entity not found"
                }
            }
            """;
    }

    /**
     * Validation error response.
     */
    public static String validationError() {
        return """
            {
                "error": {
                    "statusCode": 422,
                    "name": "ValidationError",
                    "message": "Validation failed",
                    "details": [
                        {
                            "field": "name",
                            "message": "Name is required"
                        }
                    ]
                }
            }
            """;
    }

    // ==================== Count Response ====================

    /**
     * Count response.
     */
    public static String countResponse(int count) {
        return String.format("{\"count\": %d}", count);
    }

    // ==================== Utility Methods ====================

    /**
     * Generate a random UUID.
     */
    public static String randomId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generate current timestamp.
     */
    public static String currentTimestamp() {
        return Instant.now().toString();
    }
}
