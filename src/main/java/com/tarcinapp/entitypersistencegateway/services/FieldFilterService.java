package com.tarcinapp.entitypersistencegateway.services;

import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service responsible for high-performance, context-aware field filtering.
 * It acts as a "Surgeon", modifying the response payload based on OPA rules.
 */
@Service
@Slf4j
public class FieldFilterService {

    /**
     * Entry point for filtering response payload.
     * Supports Audit for Polymorphic Lookups.
     *
     * @param payload Response body (Map or List)
     * @param library Forbidden Fields Library
     * @param targetPaths Fields to drill down into (includes/lookups)
     * @param lookupConstraints Map of "Lookup Property" -> "Fields used to filter it"
     * @return Filtered (sanitized) payload
     */
    public Object filterPayload(Object payload, ForbiddenFieldsLibrary library, List<String> targetPaths, Map<String, Set<String>> lookupConstraints) {
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
                cleanTargetsInMap(mapPayload, library, targetPaths, lookupConstraints);
            } else if (payload instanceof List) {
                List<?> listPayload = (List<?>) payload;
                for (Object item : listPayload) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mapItem = (Map<String, Object>) item;
                        cleanTargetsInMap(mapItem, library, targetPaths, lookupConstraints);
                    }
                }
            }
        }

        return payload;
    }

    // Overload for backward compatibility / tests without constraints
    public Object filterPayload(Object payload, ForbiddenFieldsLibrary library, List<String> targetPaths) {
        return filterPayload(payload, library, targetPaths, Collections.emptyMap());
    }

    /**
     * Strips the {@code _kind} field from all records in the payload, including any nested
     * included or looked-up resources identified by {@code targetPaths}.
     * <p>Called on kind-alias routes where {@code _kind} is implied by the URL path segment
     * and must not appear in the response received by the client.</p>
     *
     * @param payload     Response body (Map or List)
     * @param targetPaths Nested relation/lookup target keys to also strip {@code _kind} from
     */
    public void stripKindField(Object payload, List<String> targetPaths) {
        removeKindFromDataStructure(payload);
        if (targetPaths == null || targetPaths.isEmpty()) return;
        if (payload instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) payload;
            for (String target : targetPaths) {
                if (map.containsKey(target)) {
                    removeKindFromDataStructure(map.get(target));
                }
            }
        } else if (payload instanceof List) {
            for (Object item : (List<?>) payload) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    for (String target : targetPaths) {
                        if (mapItem.containsKey(target)) {
                            removeKindFromDataStructure(mapItem.get(target));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeKindFromDataStructure(Object data) {
        if (data instanceof Map) {
            ((Map<String, Object>) data).remove("_kind");
        } else if (data instanceof List) {
            for (Object item : (List<?>) data) {
                if (item instanceof Map) {
                    ((Map<String, Object>) item).remove("_kind");
                }
            }
        }
    }

    private void cleanTargetsInMap(Map<String, Object> map, ForbiddenFieldsLibrary library, List<String> targetPaths, Map<String, Set<String>> lookupConstraints) {
        for (String target : targetPaths) {
            if (map.containsKey(target)) {
                Object targetData = map.get(target);

                // --- SECURITY AUDIT FOR POLYMORPHIC LOOKUPS ---
                // If this target is a lookup that was filtered by specific fields,
                // we must ensure those fields are NOT forbidden for the specific record(s) returned.
                if (lookupConstraints != null && lookupConstraints.containsKey(target)) {
                    Set<String> usedFilterFields = lookupConstraints.get(target);
                    
                    // Perform audit
                    boolean isSafe = auditLookupData(targetData, library, usedFilterFields);
                    
                    if (!isSafe) {
                         if (log.isDebugEnabled()) {
                             log.debug("Security Audit Failed: Lookup target '{}' was filtered by forbidden fields. Nuking content to prevent inference.", target);
                         }
                         // Violation detected: Remove the entire lookup result to prevent side-channel leaks.
                         map.remove(target);
                         continue; // Skip cleaning since it's gone
                    }
                }

                // --- CLEAN DATA ---
                // If audit passed (or no audit needed), proceed to clean visual fields
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

    /**
     * Audits the data to ensure no forbidden fields were used in the filter query.
     * Returns TRUE if safe, FALSE if violation detected.
     */
    private boolean auditLookupData(Object data, ForbiddenFieldsLibrary library, Set<String> usedFilterFields) {
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapRecord = (Map<String, Object>) data;
            return auditLookupRecord(mapRecord, library, usedFilterFields);
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            for (Object item : list) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    // If ANY record in the list violates the rule, the whole list is unsafe.
                    if (!auditLookupRecord(mapItem, library, usedFilterFields)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return true; // Primitives are safe
    }

    /**
     * checks a single record against used filter fields.
     */
    private boolean auditLookupRecord(Map<String, Object> record, ForbiddenFieldsLibrary library, Set<String> usedFilterFields) {
        String recordType = (String) record.get("_recordType");
        String kind = (String) record.get("_kind");

        if (recordType == null) return true; // Cannot determine rules, assume safe

        String ruleKey = normalizeRecordType(recordType);
        List<String> forbiddenFields = library.resolveForbiddenFields(ruleKey, kind);
        
        if (forbiddenFields == null || forbiddenFields.isEmpty()) return true;

        // Check intersection: Did we filter by a field that is forbidden for this record?
        for (String usedField : usedFilterFields) {
            if (forbiddenFields.contains(usedField)) {
                if (log.isDebugEnabled()) {
                     log.debug("Audit Violation: Record type '{}/{}' has forbidden field '{}' which was used in lookup filter.", ruleKey, kind, usedField);
                }
                return false;
            }
        }
        return true;
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