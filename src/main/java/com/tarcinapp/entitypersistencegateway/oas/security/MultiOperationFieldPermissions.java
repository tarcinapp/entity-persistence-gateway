package com.tarcinapp.entitypersistencegateway.oas.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds field permissions for ALL operations (find, create, update).
 * 
 * <p>OPA returns DIFFERENT forbidden fields based on the operation type.
 * For OAS generation, we need to query OPA separately for each operation
 * and use the appropriate permissions when pruning schemas:</p>
 * 
 * <ul>
 *   <li><strong>find:</strong> Used for GET endpoints (response schemas)</li>
 *   <li><strong>create:</strong> Used for POST endpoints (request schemas)</li>
 *   <li><strong>update:</strong> Used for PATCH/PUT endpoints (request schemas)</li>
 * </ul>
 * 
 * <h2>OPA Query Strategy:</h2>
 * <p>Three separate OPA queries are made with different operation types:</p>
 * <pre>
 * Query 1 (find):   { httpMethod: "GET",   requestPath: "/entities", operation: "find" }
 * Query 2 (create): { httpMethod: "POST",  requestPath: "/entities", operation: "create" }
 * Query 3 (update): { httpMethod: "PATCH", requestPath: "/entities", operation: "update" }
 * </pre>
 * 
 * @see OasFieldPermissionService#fetchMultiOperationPermissions
 */
@Data
@Slf4j
public class MultiOperationFieldPermissions {
    
    /**
     * Operation types supported by the gateway.
     */
    public enum Operation {
        FIND("find", "GET"),
        CREATE("create", "POST"),
        UPDATE("update", "PATCH");
        
        private final String operationName;
        private final String httpMethod;
        
        Operation(String operationName, String httpMethod) {
            this.operationName = operationName;
            this.httpMethod = httpMethod;
        }
        
        public String getOperationName() {
            return operationName;
        }
        
        public String getHttpMethod() {
            return httpMethod;
        }
    }
    
    /**
     * Permissions indexed by operation type.
     */
    private Map<Operation, FieldPermissionContext> permissionsByOperation = new HashMap<>();
    
    /**
     * Indicates full visibility for all operations.
     */
    private boolean fullVisibility = false;
    
    /**
     * Creates a full visibility context (no restrictions for any operation).
     */
    public static MultiOperationFieldPermissions fullVisibility() {
        MultiOperationFieldPermissions permissions = new MultiOperationFieldPermissions();
        permissions.setFullVisibility(true);
        // Also set full visibility for each operation
        for (Operation op : Operation.values()) {
            permissions.permissionsByOperation.put(op, FieldPermissionContext.fullVisibility());
        }
        return permissions;
    }
    
    /**
     * Sets the permissions for a specific operation.
     */
    public void setPermissionsForOperation(Operation operation, FieldPermissionContext permissions) {
        this.permissionsByOperation.put(operation, permissions);
        log.debug("Set permissions for operation {}: fullVisibility={}, rules={}",
            operation.getOperationName(),
            permissions.isFullVisibility(),
            permissions.getRules().size());
    }
    
    /**
     * Gets the permissions for a specific operation.
     * 
     * @param operation The operation type
     * @return The permissions for that operation, or full visibility if not set
     */
    public FieldPermissionContext getPermissionsForOperation(Operation operation) {
        if (fullVisibility) {
            return FieldPermissionContext.fullVisibility();
        }
        return permissionsByOperation.getOrDefault(operation, FieldPermissionContext.fullVisibility());
    }
    
    /**
     * Gets permissions for find (GET) operations.
     */
    public FieldPermissionContext getFindPermissions() {
        return getPermissionsForOperation(Operation.FIND);
    }
    
    /**
     * Gets permissions for create (POST) operations.
     */
    public FieldPermissionContext getCreatePermissions() {
        return getPermissionsForOperation(Operation.CREATE);
    }
    
    /**
     * Gets permissions for update (PATCH/PUT) operations.
     */
    public FieldPermissionContext getUpdatePermissions() {
        return getPermissionsForOperation(Operation.UPDATE);
    }
    
    /**
     * Computes a fingerprint combining all operation permissions.
     * Used for cache key generation.
     */
    public String computeFingerprint() {
        if (fullVisibility) {
            return "full-visibility-all-ops";
        }
        
        StringBuilder sb = new StringBuilder();
        for (Operation op : Operation.values()) {
            FieldPermissionContext ctx = permissionsByOperation.get(op);
            if (ctx != null) {
                sb.append(op.getOperationName())
                  .append(":")
                  .append(ctx.computeFingerprint())
                  .append(";");
            }
        }
        
        // Hash the combined string
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
}
