package com.tarcinapp.entitypersistencegateway.services;

import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service responsible for high-performance, context-aware field filtering.
 * It acts as a "Surgeon", modifying the response payload based on OPA rules.
 */
@Service
@Slf4j
public class FieldFilterService {

    public Object filterPayload(Object payload, ForbiddenFieldsLibrary library, List<String> targetPaths) {
        if (payload == null) return null;
        if (library == null) return payload;

        // DEBUG: High-level confirmation only. Prints once per request.
        if (log.isDebugEnabled()) {
            log.debug("FieldFilterService: Filtering payload. Available rules for types: {}", library.getRules().keySet());
        }

        // 1. Clean the ROOT object(s)
        cleanDataStructure(payload, library);

        // 2. Surgical Strike: Clean Relational Targets
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

    private void cleanTargetsInMap(Map<String, Object> map, ForbiddenFieldsLibrary library, List<String> targetPaths) {
        for (String target : targetPaths) {
            if (map.containsKey(target)) {
                Object targetData = map.get(target);
                cleanDataStructure(targetData, library);
            }
        }
    }

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

    private void cleanSingleRecord(Map<String, Object> record, ForbiddenFieldsLibrary library) {
        // 1. Identify Context
        String recordType = (String) record.get("_recordType");
        String kind = (String) record.get("_kind");

        if (recordType == null) {
            return;
        }

        // Normalize record type (singular -> plural) to match OPA keys
        String ruleKey = normalizeRecordType(recordType);

        // TRACE: Per-record inspection is too noisy for DEBUG
        if (log.isTraceEnabled()) {
             log.trace("Inspecting record. _recordType: '{}' (normalized to '{}'), _kind: '{}'", recordType, ruleKey, kind);
        }

        // 2. Resolve Rules using the normalized key
        List<String> forbiddenFields = library.resolveForbiddenFields(ruleKey, kind);

        if (forbiddenFields == null || forbiddenFields.isEmpty()) {
            if (log.isTraceEnabled()) {
                log.trace("No forbidden fields found for ruleKey: '{}', kind: '{}'", ruleKey, kind);
            }
            return;
        }
        
        if (log.isTraceEnabled()) {
             log.trace("Applying forbidden fields for {}/{}: {}", ruleKey, kind, forbiddenFields);
        }

        // 3. Path Resolution & Deletion
        for (String path : forbiddenFields) {
            navigateAndDelete(record, path);
        }
    }

    private void navigateAndDelete(Map<String, Object> currentObject, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> targetNode = currentObject;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object nextNode = targetNode.get(part);

            if (nextNode instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nextMap = (Map<String, Object>) nextNode;
                targetNode = nextMap;
            } else if (nextNode instanceof List) {
                String remainingPath = getRemainingPath(parts, i + 1);
                List<?> list = (List<?>) nextNode;
                
                for (Object item : list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mapItem = (Map<String, Object>) item;
                        navigateAndDelete(mapItem, remainingPath);
                    }
                }
                return;
            } else {
                return;
            }
        }

        String keyToDelete = parts[parts.length - 1];
        if (targetNode.containsKey(keyToDelete)) {
            targetNode.remove(keyToDelete);
            // TRACE: Confirm deletion (Detailed action)
            if (log.isTraceEnabled()) {
                log.trace("DELETED field '{}' from record.", path);
            }
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

    private String normalizeRecordType(String recordType) {
        if (recordType == null) return null;
        switch (recordType) {
            case "entity": return "entities";
            case "list": return "lists";
            case "relation": return "relations";
            case "entityReaction": return "entityReactions";
            case "listReaction": return "listReactions";
            default: return recordType;
        }
    }
}