package com.tarcinapp.entitypersistencegateway.unit.services;

import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary.RecordTypeRules;
import com.tarcinapp.entitypersistencegateway.services.FieldFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FieldFilterService.
 * Tests field filtering/masking logic for different record types and scenarios.
 */
@DisplayName("FieldFilterService Unit Tests")
class FieldFilterServiceTest {

    private FieldFilterService fieldFilterService;

    @BeforeEach
    void setUp() {
        fieldFilterService = new FieldFilterService();
    }

    @Nested
    @DisplayName("Single Record Filtering Tests")
    class SingleRecordFilteringTests {

        @Test
        @DisplayName("Should remove forbidden fields from entity")
        void shouldRemoveForbiddenFieldsFromEntity() {
            // Given
            Map<String, Object> entity = createTestEntity();
            entity.put("ownerUsers", Arrays.asList("user1"));
            entity.put("_createdDateTime", "2024-01-01T00:00:00Z");
            entity.put("createdBy", "user1");

            ForbiddenFieldsLibrary library = createLibraryWithForbiddenFields(
                    "entities", null, Arrays.asList("ownerUsers", "_createdDateTime", "createdBy"));

            // When
            Object result = fieldFilterService.filterPayload(entity, library, Collections.emptyList());

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> filtered = (Map<String, Object>) result;
            assertThat(filtered).doesNotContainKey("ownerUsers");
            assertThat(filtered).doesNotContainKey("_createdDateTime");
            assertThat(filtered).doesNotContainKey("createdBy");
            assertThat(filtered).containsKey("name");
            assertThat(filtered).containsKey("kind");
        }

        @Test
        @DisplayName("Should keep allowed fields")
        void shouldKeepAllowedFields() {
            // Given
            Map<String, Object> entity = createTestEntity();

            ForbiddenFieldsLibrary library = createLibraryWithForbiddenFields(
                    "entities", null, Arrays.asList("ownerUsers"));

            // When
            Object result = fieldFilterService.filterPayload(entity, library, Collections.emptyList());

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> filtered = (Map<String, Object>) result;
            assertThat(filtered).containsKey("name");
            assertThat(filtered).containsKey("kind");
            assertThat(filtered).containsKey("description");
            assertThat(filtered).containsKey("id");
        }

        @Test
        @DisplayName("Should handle null library")
        void shouldHandleNullLibrary() {
            // Given
            Map<String, Object> entity = createTestEntity();

            // When
            Object result = fieldFilterService.filterPayload(entity, null, Collections.emptyList());

            // Then
            assertThat(result).isEqualTo(entity);
        }

        @Test
        @DisplayName("Should handle null payload")
        void shouldHandleNullPayload() {
            // Given
            ForbiddenFieldsLibrary library = createLibraryWithForbiddenFields(
                    "entities", null, Arrays.asList("ownerUsers"));

            // When
            Object result = fieldFilterService.filterPayload(null, library, Collections.emptyList());

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("List Filtering Tests")
    class ListFilteringTests {

        @Test
        @DisplayName("Should filter all items in a list")
        void shouldFilterAllItemsInList() {
            // Given
            List<Map<String, Object>> entities = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Map<String, Object> entity = createTestEntity();
                entity.put("ownerUsers", Arrays.asList("user" + i));
                entities.add(entity);
            }

            ForbiddenFieldsLibrary library = createLibraryWithForbiddenFields(
                    "entities", null, Arrays.asList("ownerUsers"));

            // When
            Object result = fieldFilterService.filterPayload(entities, library, Collections.emptyList());

            // Then
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filtered = (List<Map<String, Object>>) result;
            assertThat(filtered).hasSize(3);
            for (Map<String, Object> item : filtered) {
                assertThat(item).doesNotContainKey("ownerUsers");
                assertThat(item).containsKey("name");
            }
        }

        @Test
        @DisplayName("Should handle empty list")
        void shouldHandleEmptyList() {
            // Given
            List<Map<String, Object>> entities = new ArrayList<>();

            ForbiddenFieldsLibrary library = createLibraryWithForbiddenFields(
                    "entities", null, Arrays.asList("ownerUsers"));

            // When
            Object result = fieldFilterService.filterPayload(entities, library, Collections.emptyList());

            // Then
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filtered = (List<Map<String, Object>>) result;
            assertThat(filtered).isEmpty();
        }
    }

    @Nested
    @DisplayName("Kind-Specific Filtering Tests")
    class KindSpecificFilteringTests {

        @Test
        @DisplayName("Should apply kind-specific rules")
        void shouldApplyKindSpecificRules() {
            // Given
            Map<String, Object> bookEntity = createTestEntity();
            bookEntity.put("_kind", "book");
            bookEntity.put("isbn", "978-0-123456-78-9");
            bookEntity.put("secretField", "hidden");

            // Create library with kind-specific rule
            ForbiddenFieldsLibrary library = createLibraryWithKindSpecificFields(
                    "entities", "book", Arrays.asList("secretField"));

            // When
            Object result = fieldFilterService.filterPayload(bookEntity, library, Collections.emptyList());

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> filtered = (Map<String, Object>) result;
            assertThat(filtered).doesNotContainKey("secretField");
            assertThat(filtered).containsKey("isbn");
            assertThat(filtered).containsKey("name");
        }
    }

    @Nested
    @DisplayName("Nested Object Filtering Tests")
    class NestedObjectFilteringTests {

        @Test
        @DisplayName("Should filter target paths (lookups)")
        void shouldFilterTargetPaths() {
            // Given
            Map<String, Object> entity = createTestEntity();
            
            // Add nested lookup data
            Map<String, Object> author = new HashMap<>();
            author.put("_recordType", "entities");
            author.put("name", "John Doe");
            author.put("ownerUsers", Arrays.asList("author-owner"));
            entity.put("author", author);

            ForbiddenFieldsLibrary library = createLibraryWithForbiddenFields(
                    "entities", null, Arrays.asList("ownerUsers"));

            // When
            Object result = fieldFilterService.filterPayload(entity, library, Arrays.asList("author"));

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> filtered = (Map<String, Object>) result;
            @SuppressWarnings("unchecked")
            Map<String, Object> filteredAuthor = (Map<String, Object>) filtered.get("author");
            
            assertThat(filteredAuthor).doesNotContainKey("ownerUsers");
            assertThat(filteredAuthor).containsKey("name");
        }
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> createTestEntity() {
        Map<String, Object> entity = new HashMap<>();
        entity.put("id", UUID.randomUUID().toString());
        entity.put("name", "Test Entity");
        entity.put("kind", "generic-entity");
        entity.put("description", "A test entity");
        entity.put("_recordType", "entities");
        return entity;
    }

    /**
     * Creates a ForbiddenFieldsLibrary with default fields for a record type.
     */
    private ForbiddenFieldsLibrary createLibraryWithForbiddenFields(
            String recordType, String kind, List<String> forbiddenFields) {
        ForbiddenFieldsLibrary library = new ForbiddenFieldsLibrary();
        
        RecordTypeRules rules = new RecordTypeRules();
        
        if (kind == null) {
            // Set as default fields
            rules.setDefaultFields(new ArrayList<>(forbiddenFields));
        } else {
            // Set as kind-specific fields
            Map<String, List<String>> kinds = new HashMap<>();
            kinds.put(kind, new ArrayList<>(forbiddenFields));
            rules.setKinds(kinds);
        }
        
        library.addRecordTypeRule(recordType, rules);
        
        return library;
    }

    /**
     * Creates a ForbiddenFieldsLibrary with kind-specific forbidden fields.
     */
    private ForbiddenFieldsLibrary createLibraryWithKindSpecificFields(
            String recordType, String kind, List<String> forbiddenFields) {
        ForbiddenFieldsLibrary library = new ForbiddenFieldsLibrary();
        
        RecordTypeRules rules = new RecordTypeRules();
        rules.setDefaultFields(new ArrayList<>()); // Empty default
        
        Map<String, List<String>> kinds = new HashMap<>();
        kinds.put(kind, new ArrayList<>(forbiddenFields));
        rules.setKinds(kinds);
        
        library.addRecordTypeRule(recordType, rules);
        
        return library;
    }
}
