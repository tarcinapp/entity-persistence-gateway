package com.tarcinapp.entitypersistencegateway.services;

import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service responsible for high-performance, context-aware field filtering.
 * It acts as a "Surgeon", modifying the response payload based on OPA rules.
 * * Logic:
 * 1. Inspects the data (Root or Relational Targets).
 * 2. Reads self-describing fields (_recordType, _kind).
 * 3. Resolves specific forbidden fields from the library.
 * 4. Executes "Path Resolution" to delete fields without full traversal.
 */
@Service
@Slf4j
public class FieldFilterService {

    /**
     * Main entry point for filtering.
     * * @param payload The deserialized JSON body (Map or List).
     * @param library The rules library fetched from OPA (Redis).
     * @param targetPaths List of relational fields to inspect (from Query Analyzer).
     * @return The filtered payload.
     */
    public Object filterPayload(Object payload, ForbiddenFieldsLibrary library, List<String> targetPaths) {
        if (payload == null || library == null) {
            return payload;
        }

        // 1. Clean the ROOT object(s)
        // (The main response itself is a record or list of records)
        cleanDataStructure(payload, library);

        // 2. Surgical Strike: Clean Relational Targets (Includes/Lookups)
        // We only navigate to specific fields requested by the user.
        if (targetPaths != null && !targetPaths.isEmpty()) {
            if (payload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapPayload = (Map<String, Object>) payload;
                cleanTargetsInMap(mapPayload, library, targetPaths);
            } else if (payload instanceof List) {
                List<?> listPayload = (List<?>) payload;
                for (Object item : listPayload) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mapItem = (Map<String, Object>) item;
                        cleanTargetsInMap(mapItem, library, targetPaths);
                    }
                }
            }
        }

        return payload;
    }

    /**
     * Navigates to target fields within a map and cleans them.
     */
    private void cleanTargetsInMap(Map<String, Object> map, ForbiddenFieldsLibrary library, List<String> targetPaths) {
        for (String target : targetPaths) {
            // Check if the target field exists in the payload
            if (map.containsKey(target)) {
                Object targetData = map.get(target);
                
                // Recursively clean this part of the tree
                // The target data (e.g., "author") is expected to be self-describing too.
                cleanDataStructure(targetData, library);
            }
        }
    }

    /**
     * Determines the type of structure (List or Map) and applies cleaning logic.
     */
    private void cleanDataStructure(Object data, ForbiddenFieldsLibrary library) {
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapData = (Map<String, Object>) data;
            cleanSingleRecord(mapData, library);
        } else if (data instanceof List) {
            List<?> listData = (List<?>) data;
            for (Object item : listData) {
                cleanDataStructure(item, library);
            }
        }
    }

    /**
     * Core Logic:
     * 1. Identifies the record context (_recordType, _kind).
     * 2. Fetches rules.
     * 3. Executes deletion.
     */
    private void cleanSingleRecord(Map<String, Object> record, ForbiddenFieldsLibrary library) {
        // 1. Identify Context from Self-Describing Data
        String recordType = (String) record.get("_recordType");
        String kind = (String) record.get("_kind");

        // If data is missing meta-info, we can't apply specific rules.
        // (Depending on requirements, we might want to apply default/global rules here)
        if (recordType == null) {
            return;
        }

        // 2. Resolve Rules
        List<String> forbiddenFields = library.resolveForbiddenFields(recordType, kind);

        if (forbiddenFields == null || forbiddenFields.isEmpty()) {
            return;
        }

        // 3. Path Resolution & Deletion
        for (String path : forbiddenFields) {
            navigateAndDelete(record, path);
        }
    }

    /**
     * Navigates dot-notation paths and deletes the target field.
     * Handles nested Maps and Lists automatically.
     * Example Path: "metadata.config.privateKey" or "authors.name"
     */
    private void navigateAndDelete(Map<String, Object> currentObject, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> targetNode = currentObject;

        // Traverse until the second to last part
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object nextNode = targetNode.get(part);

            if (nextNode instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nextMap = (Map<String, Object>) nextNode;
                targetNode = nextMap;
            } else if (nextNode instanceof List) {
                // Array Traversal: If path goes through an array (e.g. "authors.name"),
                // apply the rest of the path to ALL items in that array.
                String remainingPath = getRemainingPath(parts, i + 1);
                List<?> list = (List<?>) nextNode;
                
                for (Object item : list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mapItem = (Map<String, Object>) item;
                        navigateAndDelete(mapItem, remainingPath);
                    }
                }
                return; // Done for this branch
            } else {
                return; // Path broken (field doesn't exist or is primitive)
            }
        }

        // Delete the final key
        String keyToDelete = parts[parts.length - 1];
        if (targetNode.containsKey(keyToDelete)) {
            targetNode.remove(keyToDelete);
            log.trace("Removed forbidden field: {}", path);
        }
    }

    private String getRemainingPath(String[] parts, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < parts.length; i++) {
            if (i > startIndex) sb.append(".");
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}