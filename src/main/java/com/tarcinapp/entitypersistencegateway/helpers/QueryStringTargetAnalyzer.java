package com.tarcinapp.entitypersistencegateway.helpers;

import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility component responsible for analyzing the HTTP Query String.
 *
 * Capabilities:
 * 1. Identifies "Target Fields" (Includes/Lookups) for surgical response filtering.
 * 2. Identifies "Filtering Fields" for general request validation.
 * 3. Audits "Lookup Constraints" to detect if a polymorphic lookup was filtered by a specific field.
 * Supports nested lookups of arbitrary depth via Scope Matching.
 */
@Component
public class QueryStringTargetAnalyzer {

    // Regex to extract field names from where clauses
    private static final Pattern WHERE_CLAUSE_FIELD_PATTERN = Pattern.compile("\\[where\\]\\[([^\\]]+)\\]");

    // LoopBack Operators and Keywords to ignore during field extraction
    private static final Set<String> IGNORED_SEGMENTS = Set.of(
            "eq", "gt", "gte", "lt", "lte", "between", "inq", "nin", "neq",
            "like", "nlike", "ilike", "nilike", "regexp", "near",
            "and", "or",
            "filter", "where", "include", "scope", "fields", "limit", "skip", "order", "lookup"
    );

    // Regex to extract content within brackets
    private static final Pattern BRACKET_CONTENT_PATTERN = Pattern.compile("\\[([^\\[\\]]+)\\]");

    /**
     * Extracts a list of property names that are expected to contain relational objects/arrays.
     * Supports nested includes/lookups.
     */
    public List<String> resolveTargetFields(MultiValueMap<String, String> queryParams) {
        Set<String> targets = new HashSet<>();

        if (queryParams == null || queryParams.isEmpty()) {
            return new ArrayList<>();
        }

        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();

            if (values == null || values.isEmpty()) continue;

            // filter[include]... or filter[lookup]...
            if (isIncludeKey(key) || isLookupKey(key)) {
                targets.addAll(values);
            }
        }

        return new ArrayList<>(targets);
    }

    /**
     * Builds a map of Lookup Properties and the Fields used to filter them.
     * Used for "Polymorphic Lookup Audit" in the response phase.
     *
     * Logic:
     * 1. Find property definitions: filter[lookup][0][prop] = "_parents"
     * -> Scope: "filter[lookup][0]", Target: "_parents"
     * 2. Find where clauses: filter[lookup][0][scope][where][salary] = 5000
     * -> Scope: "filter[lookup][0]", Field: "salary"
     * 3. Match Scope to link Target -> Fields.
     *
     * @return Map<TargetPropertyName, Set<UsedFields>>
     * Example: { "_parents": ["salary", "bonus"], "_roles": ["level"] }
     */
    public Map<String, Set<String>> resolveLookupConstraints(MultiValueMap<String, String> queryParams) {
        Map<String, Set<String>> lookupConstraints = new HashMap<>();
        
        if (queryParams == null || queryParams.isEmpty()) {
            return lookupConstraints;
        }

        // Temporary maps to link ScopeID -> PropertyName and ScopeID -> Fields
        Map<String, String> scopeToProperty = new HashMap<>();
        Map<String, Set<String>> scopeToFields = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();

            if (values == null || values.isEmpty()) continue;

            // 1. Identify Lookup Definitions
            // Key ends with [prop] AND starts with filter[lookup]
            if (key.startsWith("filter[lookup]") && key.endsWith("[prop]")) {
                // Extract Scope ID: "filter[lookup][0][prop]" -> "filter[lookup][0]"
                // Or nested: "filter[lookup][0][scope][lookup][1][prop]" -> "filter[lookup][0][scope][lookup][1]"
                String scopeId = key.substring(0, key.lastIndexOf("[prop]"));
                scopeToProperty.put(scopeId, values.get(0));
            }

            // 2. Identify Lookup Where Clauses
            // Key contains [scope][where] AND starts with filter[lookup]
            if (key.startsWith("filter[lookup]") && key.contains("[scope][where]")) {
                // Split to find Scope ID
                // "filter[lookup][0][scope][where][salary]"
                int splitIndex = key.lastIndexOf("[scope][where]");
                if (splitIndex != -1) {
                    String scopeId = key.substring(0, splitIndex);
                    
                    // Extract Field Name from the suffix
                    // "[scope][where][salary][gt]" -> extract "salary"
                    String suffix = key.substring(splitIndex); // [scope][where][salary][gt]
                    String fieldName = extractFieldNameFromSuffix(suffix);

                    if (fieldName != null) {
                        scopeToFields.computeIfAbsent(scopeId, k -> new HashSet<>()).add(fieldName);
                    }
                }
            }
        }

        // 3. Match Scopes and Build Result
        for (Map.Entry<String, Set<String>> fieldEntry : scopeToFields.entrySet()) {
            String scopeId = fieldEntry.getKey();
            Set<String> fields = fieldEntry.getValue();

            String propertyName = scopeToProperty.get(scopeId);
            
            // Only add if we found a corresponding property definition for this scope
            if (propertyName != null) {
                lookupConstraints.computeIfAbsent(propertyName, k -> new HashSet<>()).addAll(fields);
            }
        }

        return lookupConstraints;
    }

    /**
     * Extracts a list of field names that are being used in 'where' clauses.
     */
    public List<String> resolveFilteringFields(MultiValueMap<String, String> queryParams) {
        Set<String> fields = new HashSet<>();

        if (queryParams == null || queryParams.isEmpty()) {
            return new ArrayList<>();
        }

        for (String key : queryParams.keySet()) {
            Matcher matcher = WHERE_CLAUSE_FIELD_PATTERN.matcher(key);
            while (matcher.find()) {
                fields.add(matcher.group(1));
            }
        }

        return new ArrayList<>(fields);
    }

    // --- Private Helpers ---

    private boolean isIncludeKey(String key) {
        if (key.startsWith("filter[include]")) {
            if (key.endsWith("[relation]")) return true;
            if (key.equals("filter[include]")) return true;
        }
        return false;
    }

    private boolean isLookupKey(String key) {
        return key.startsWith("filter[lookup]") && key.endsWith("[prop]");
    }

    /**
     * Extracts the actual field name from the tail of a query key.
     * Suffix example: "[scope][where][and][0][salary][gt]" -> returns "salary"
     */
    private String extractFieldNameFromSuffix(String suffix) {
        List<String> segments = new ArrayList<>();
        Matcher m = BRACKET_CONTENT_PATTERN.matcher(suffix);
        while (m.find()) {
            segments.add(m.group(1));
        }

        if (segments.isEmpty()) return null;

        // Iterate backwards. Find first segment that is NOT a keyword/operator.
        for (int i = segments.size() - 1; i >= 0; i--) {
            String segment = segments.get(i);
            
            // Skip numeric indices
            if (segment.matches("\\d+")) continue;
            
            // Skip ignored keywords/operators
            if (IGNORED_SEGMENTS.contains(segment)) continue;

            return segment;
        }
        return null;
    }
}