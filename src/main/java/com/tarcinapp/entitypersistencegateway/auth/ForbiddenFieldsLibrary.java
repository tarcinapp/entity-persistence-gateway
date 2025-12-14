package com.tarcinapp.entitypersistencegateway.auth;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents the complete library of forbidden fields returned by OPA.
 * Structure:
 * {
 * "entities": {
 * "default": ["field1"],
 * "kinds": { "book": ["field2"] }
 * },
 * "lists": { ... }
 * }
 */
@Data
public class ForbiddenFieldsLibrary {

    /**
     * Stores rules grouped by record type (e.g., "entities", "relations").
     * The keys are dynamic, so we use a Map.
     */
    private Map<String, RecordTypeRules> rules = new HashMap<>();

    /**
     * Jackson hook to capture dynamic root keys (recordTypes) into the map.
     */
    @JsonAnySetter
    public void addRecordTypeRule(String key, RecordTypeRules value) {
        rules.put(key, value);
    }

    /**
     * Smart Helper: Merges 'default' fields and 'kind-specific' fields 
     * for a given record type and kind.
     * * @param recordType The type of record (e.g., "entities")
     * @param kind The kind of record (e.g., "book")
     * @return A combined list of all forbidden fields for this specific object.
     */
    public List<String> resolveForbiddenFields(String recordType, String kind) {
        RecordTypeRules typeRules = rules.get(recordType);

        // If no rules exist for this record type, return empty list
        if (typeRules == null) {
            return Collections.emptyList();
        }

        // 1. Get Default fields for this Record Type
        List<String> defaults = typeRules.getDefaultFields();
        if (defaults == null) defaults = Collections.emptyList();

        // 2. Get Kind-specific fields
        List<String> kindSpecifics = Collections.emptyList();
        if (kind != null && typeRules.getKinds() != null) {
            kindSpecifics = typeRules.getKinds().get(kind);
            if (kindSpecifics == null) {
                kindSpecifics = Collections.emptyList();
            }
        }

        // 3. Merge both lists
        if (defaults.isEmpty() && kindSpecifics.isEmpty()) {
            return Collections.emptyList();
        }

        return Stream.concat(defaults.stream(), kindSpecifics.stream())
                .collect(Collectors.toList());
    }

    /**
     * Inner DTO representing rules for a single Record Type.
     */
    @Data
    public static class RecordTypeRules {

        /**
         * Fields forbidden for ALL kinds under this record type.
         * JSON field is "default", mapped to "defaultFields".
         */
        @JsonProperty("default")
        private List<String> defaultFields = new ArrayList<>();

        /**
         * Specific rules per kind.
         * Key: kind name (e.g., "book")
         * Value: List of forbidden fields
         */
        private Map<String, List<String>> kinds = new HashMap<>();
    }
}