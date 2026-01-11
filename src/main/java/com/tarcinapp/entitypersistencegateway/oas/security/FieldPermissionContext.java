package com.tarcinapp.entitypersistencegateway.oas.security;

import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Context object containing field-level permissions resolved from OPA.
 * 
 * <p>This structure mirrors {@link com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary}
 * but is optimized for schema pruning operations during OAS transformation.</p>
 * 
 * <h2>Structure:</h2>
 * <pre>
 * {
 *   "entities": {
 *     "default": ["_idempotencyKey", "_version"],
 *     "kinds": {
 *       "book": ["secretField"],
 *       "user": ["password", "ssn"]
 *     }
 *   },
 *   "lists": { ... },
 *   "relations": { ... }
 * }
 * </pre>
 */
@Data
public class FieldPermissionContext {
    
    /**
     * Rules grouped by record type (entities, lists, relations, entityReactions, listReactions).
     */
    private Map<String, RecordTypeRules> rules = new HashMap<>();
    
    /**
     * Indicates whether this context represents full visibility (no pruning needed).
     * Used as an optimization to skip schema traversal.
     */
    private boolean fullVisibility = false;
    
    /**
     * Creates a context representing full visibility (no field restrictions).
     * Used when OPA is unavailable or for anonymous spec generation.
     * 
     * @return FieldPermissionContext with fullVisibility=true
     */
    public static FieldPermissionContext fullVisibility() {
        FieldPermissionContext context = new FieldPermissionContext();
        context.setFullVisibility(true);
        return context;
    }
    
    /**
     * Creates a context from the existing ForbiddenFieldsLibrary structure.
     * Provides compatibility with the gateway's existing field permission system.
     * 
     * @param library The ForbiddenFieldsLibrary from OPA
     * @return Converted FieldPermissionContext
     */
    public static FieldPermissionContext fromForbiddenFieldsLibrary(
            com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary library) {
        
        FieldPermissionContext context = new FieldPermissionContext();
        
        if (library == null || library.getRules() == null || library.getRules().isEmpty()) {
            context.setFullVisibility(true);
            return context;
        }
        
        library.getRules().forEach((recordType, typeRules) -> {
            RecordTypeRules converted = new RecordTypeRules();
            converted.setDefaultFields(typeRules.getDefaultFields() != null 
                ? new ArrayList<>(typeRules.getDefaultFields()) 
                : new ArrayList<>());
            converted.setKinds(typeRules.getKinds() != null 
                ? new HashMap<>(typeRules.getKinds()) 
                : new HashMap<>());
            context.getRules().put(recordType, converted);
        });
        
        return context;
    }
    
    /**
     * Gets all forbidden fields for a specific record type and kind.
     * Merges default fields with kind-specific fields.
     * 
     * @param recordType The record type (e.g., "entities", "lists")
     * @param kind The specific kind (e.g., "book") or null for defaults only
     * @return Set of forbidden field names
     */
    public Set<String> getForbiddenFields(String recordType, String kind) {
        if (fullVisibility) {
            return Collections.emptySet();
        }
        
        RecordTypeRules typeRules = rules.get(recordType);
        if (typeRules == null) {
            return Collections.emptySet();
        }
        
        List<String> defaults = typeRules.getDefaultFields();
        if (defaults == null) {
            defaults = Collections.emptyList();
        }
        
        List<String> kindSpecific = Collections.emptyList();
        if (kind != null && typeRules.getKinds() != null) {
            kindSpecific = typeRules.getKinds().getOrDefault(kind, Collections.emptyList());
        }
        
        return Stream.concat(defaults.stream(), kindSpecific.stream())
            .collect(Collectors.toSet());
    }
    
    /**
     * Gets all forbidden fields for a record type across ALL kinds.
     * Useful for pruning base schemas that aren't kind-specific.
     * 
     * @param recordType The record type
     * @return Set of all forbidden fields (defaults + all kind-specific combined)
     */
    public Set<String> getAllForbiddenFields(String recordType) {
        if (fullVisibility) {
            return Collections.emptySet();
        }
        
        RecordTypeRules typeRules = rules.get(recordType);
        if (typeRules == null) {
            return Collections.emptySet();
        }
        
        Set<String> allFields = new HashSet<>();
        
        if (typeRules.getDefaultFields() != null) {
            allFields.addAll(typeRules.getDefaultFields());
        }
        
        if (typeRules.getKinds() != null) {
            typeRules.getKinds().values().forEach(kindFields -> {
                if (kindFields != null) {
                    allFields.addAll(kindFields);
                }
            });
        }
        
        return allFields;
    }
    
    /**
     * Generates a deterministic fingerprint of this permission context.
     * Used as part of the cache key for role-based OAS caching.
     * 
     * @return SHA-256 hash string representing this permission set
     */
    public String computeFingerprint() {
        if (fullVisibility) {
            return "full-visibility";
        }
        
        // Build deterministic string representation
        StringBuilder sb = new StringBuilder();
        
        rules.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                sb.append(entry.getKey()).append(":");
                RecordTypeRules typeRules = entry.getValue();
                
                // Add sorted default fields
                if (typeRules.getDefaultFields() != null) {
                    typeRules.getDefaultFields().stream()
                        .sorted()
                        .forEach(f -> sb.append("d:").append(f).append(","));
                }
                
                // Add sorted kind-specific fields
                if (typeRules.getKinds() != null) {
                    typeRules.getKinds().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(kindEntry -> {
                            sb.append("k:").append(kindEntry.getKey()).append("=");
                            if (kindEntry.getValue() != null) {
                                kindEntry.getValue().stream()
                                    .sorted()
                                    .forEach(f -> sb.append(f).append(","));
                            }
                        });
                }
                
                sb.append(";");
            });
        
        // Return hash of the deterministic string
        return sha256Hex(sb.toString());
    }
    
    /**
     * Computes SHA-256 hash and returns as hex string.
     */
    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Inner class representing rules for a single record type.
     */
    @Data
    public static class RecordTypeRules {
        /**
         * Fields forbidden for ALL kinds under this record type.
         */
        private List<String> defaultFields = new ArrayList<>();
        
        /**
         * Kind-specific forbidden fields.
         * Key: kind name, Value: list of forbidden fields
         */
        private Map<String, List<String>> kinds = new HashMap<>();
    }
}
