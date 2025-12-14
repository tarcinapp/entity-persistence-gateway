package com.tarcinapp.entitypersistencegateway.helpers;

import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility component responsible for analyzing the HTTP Query String.
 * It identifies:
 * 1. "Target Fields" that contain relational data (Includes or Lookups).
 * 2. "Filtering Fields" used in 'where' clauses.
 * * This allows the Gateway to:
 * - Perform "Surgical Filtering" on the response body.
 * - Block queries filtering by forbidden fields.
 */
@Component
public class QueryStringTargetAnalyzer {

    // Regex to extract field names from where clauses
    // Matches: [where][fieldName] or [where][and][0][fieldName] logic is complex, 
    // but typically the field is the last part before an operator or value.
    // Simplifying: We look for [where][<fieldName>] pattern.
    private static final Pattern WHERE_CLAUSE_FIELD_PATTERN = Pattern.compile("\\[where\\]\\[([^\\]]+)\\]");

    /**
     * Extracts a list of property names that are expected to contain relational objects/arrays.
     * Supports nested includes/lookups due to startsWith/endsWith logic.
     * * @param queryParams The query parameters from ServerWebExchange
     * @return A list of field names (e.g., ["reactions", "authorDetails"])
     */
    public List<String> resolveTargetFields(MultiValueMap<String, String> queryParams) {
        Set<String> targets = new HashSet<>();

        if (queryParams == null || queryParams.isEmpty()) {
            return new ArrayList<>();
        }

        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();

            if (values == null || values.isEmpty()) {
                continue;
            }

            // Check for Include: filter[include][...][relation]=propertyName
            // Logic supports nested includes (e.g. filter[include][0][scope][include][0][relation])
            if (isIncludeKey(key)) {
                targets.addAll(values);
            }
            
            // Check for Lookup: filter[lookup][...][prop]=propertyName
            else if (isLookupKey(key)) {
                targets.addAll(values);
            }
        }

        return new ArrayList<>(targets);
    }

    /**
     * Extracts a list of field names that are being used in 'where' clauses.
     * This can be used to block queries on forbidden fields.
     * * Example: filter[where][_idempotencyKey]=123 -> returns ["_idempotencyKey"]
     * Example: filter[lookup][0][scope][where][_yasakliAlan]=val -> returns ["_yasakliAlan"]
     * * @param queryParams The query parameters
     * @return List of field names used in filtering
     */
    public List<String> resolveFilteringFields(MultiValueMap<String, String> queryParams) {
        Set<String> fields = new HashSet<>();

        if (queryParams == null || queryParams.isEmpty()) {
            return new ArrayList<>();
        }

        for (String key : queryParams.keySet()) {
            Matcher matcher = WHERE_CLAUSE_FIELD_PATTERN.matcher(key);
            while (matcher.find()) {
                // Group 1 contains the field name inside [where][...]
                fields.add(matcher.group(1));
            }
        }

        return new ArrayList<>(fields);
    }

    private boolean isIncludeKey(String key) {
        // Matches keys starting with filter[include]
        // This covers simple, indexed array, and nested scopes.
        if (key.startsWith("filter[include]")) {
            // Check if it ends with [relation] indicating the property name value
            if (key.endsWith("[relation]")) {
                return true;
            }
            // Short form: filter[include]=relationName
            if (key.equals("filter[include]")) {
                return true;
            }
        }
        return false;
    }

    private boolean isLookupKey(String key) {
        // Matches keys starting with filter[lookup] and ending with [prop]
        // This covers nested scopes as well.
        return key.startsWith("filter[lookup]") && key.endsWith("[prop]");
    }
}