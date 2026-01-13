package com.tarcinapp.entitypersistencegateway.oas.security;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service for querying OPA to obtain field-level visibility permissions.
 * 
 * <p>This service queries OPA separately for EACH operation type (find, create, update)
 * because OPA returns DIFFERENT forbidden fields based on the operation.</p>
 * 
 * <h2>Multi-Operation Query Strategy:</h2>
 * <p>For OAS generation, we must query OPA 3 times with different inputs:</p>
 * <pre>
 * Query 1 (find):   { httpMethod: "GET",   requestPath: "/entities", operation: "find" }
 * Query 2 (create): { httpMethod: "POST",  requestPath: "/entities", operation: "create" }
 * Query 3 (update): { httpMethod: "PATCH", requestPath: "/entities", operation: "update" }
 * </pre>
 * 
 * <h2>CRITICAL RULES:</h2>
 * <ul>
 *   <li><strong>NO requestPayload:</strong> Forbidden fields query must NOT contain request payload</li>
 *   <li><strong>httpMethod is MANDATORY:</strong> Must match the operation (GET/POST/PATCH)</li>
 *   <li><strong>requestPath is MANDATORY:</strong> Use base path for record type (e.g., /entities)</li>
 *   <li><strong>appShortcode from config:</strong> Retrieved from app.shortcode, NEVER hardcoded</li>
 * </ul>
 * 
 * @see com.tarcinapp.entitypersistencegateway.filters.common.request.FetchForbiddenFieldsGatewayFilterFactory
 */
@Service
@Slf4j
public class OasFieldPermissionService {
    
    private final IAuthorizationClient authorizationClient;
    private final OasOrchestratorProperties properties;
    
    /**
     * App shortcode from configuration. NEVER hardcoded.
     * Used for OPA policy evaluation context.
     */
    @Value("${app.shortcode}")
    private String appShortcode;
    
    public OasFieldPermissionService(
            IAuthorizationClient authorizationClient,
            OasOrchestratorProperties properties) {
        this.authorizationClient = authorizationClient;
        this.properties = properties;
    }
    
    /**
     * Fetches field permissions for ALL operations (find, create, update).
     * 
     * <p>OPA returns DIFFERENT forbidden fields for each operation type.
     * This method queries OPA 3 times in parallel with different inputs:</p>
     * <ul>
     *   <li>find:   GET /entities, operation="find"</li>
     *   <li>create: POST /entities, operation="create"</li>
     *   <li>update: PATCH /entities, operation="update"</li>
     * </ul>
     * 
     * @param securityContext The authenticated user's security context (may be null for anonymous)
     * @param basePath The base path for the record type (e.g., /entities)
     * @return Mono containing permissions for all operations
     */
    public Mono<MultiOperationFieldPermissions> fetchMultiOperationPermissions(
            GatewaySecurityContext securityContext, String basePath) {
        
        if (securityContext == null || securityContext.getEncodedJwt() == null) {
            log.debug("No security context available, fetching anonymous multi-operation permissions");
            return fetchAnonymousMultiOperationPermissions(basePath);
        }
        
        log.debug("Fetching multi-operation field permissions for user: {} with {} roles",
            securityContext.getAuthSubject(),
            securityContext.getRoles() != null ? securityContext.getRoles().size() : 0);
        
        // Query OPA in parallel for all 3 operations
        Mono<FieldPermissionContext> findPermissions = fetchPermissionsForOperation(
            securityContext, basePath, Operation.FIND);
        Mono<FieldPermissionContext> createPermissions = fetchPermissionsForOperation(
            securityContext, basePath, Operation.CREATE);
        Mono<FieldPermissionContext> updatePermissions = fetchPermissionsForOperation(
            securityContext, basePath, Operation.UPDATE);
        
        // Combine all three results
        return Mono.zip(findPermissions, createPermissions, updatePermissions)
            .map(tuple -> {
                MultiOperationFieldPermissions multiPerms = new MultiOperationFieldPermissions();
                multiPerms.setPermissionsForOperation(Operation.FIND, tuple.getT1());
                multiPerms.setPermissionsForOperation(Operation.CREATE, tuple.getT2());
                multiPerms.setPermissionsForOperation(Operation.UPDATE, tuple.getT3());
                
                log.info("Fetched multi-operation permissions: find={} rules, create={} rules, update={} rules",
                    tuple.getT1().getRules().size(),
                    tuple.getT2().getRules().size(),
                    tuple.getT3().getRules().size());
                
                return multiPerms;
            })
            .onErrorResume(e -> {
                log.warn("Failed to fetch multi-operation permissions: {}. Using full visibility.", e.getMessage());
                return Mono.just(MultiOperationFieldPermissions.fullVisibility());
            });
    }
    
    /**
     * Fetches permissions for a specific operation.
     */
    private Mono<FieldPermissionContext> fetchPermissionsForOperation(
            GatewaySecurityContext securityContext, String basePath, Operation operation) {
        
        PolicyData policyData = buildPolicyDataForOperation(securityContext, basePath, operation);
        
        log.debug("Querying OPA for operation '{}': httpMethod={}, requestPath={}", 
            operation.getOperationName(), operation.getHttpMethod(), basePath);
        
        return authorizationClient.executePolicy(policyData, ForbiddenFieldsLibrary.class)
            .timeout(properties.getOpa().getTimeout())
            .map(FieldPermissionContext::fromForbiddenFieldsLibrary)
            .doOnNext(ctx -> {
                log.debug("OPA response for operation '{}': fullVisibility={}, rules={}",
                    operation.getOperationName(), ctx.isFullVisibility(), ctx.getRules().size());
            })
            .onErrorResume(e -> {
                log.warn("OPA query failed for operation '{}': {}. Using full visibility.",
                    operation.getOperationName(), e.getMessage());
                return Mono.just(FieldPermissionContext.fullVisibility());
            });
    }
    
    /**
     * Builds PolicyData for a specific operation type.
     * 
     * <p>Each operation has its own httpMethod and operation name:</p>
     * <ul>
     *   <li>find:   GET, "find"</li>
     *   <li>create: POST, "create"</li>
     *   <li>update: PATCH, "update"</li>
     * </ul>
     * 
     * <p>CRITICAL: NO requestPayload - forbidden fields query must NOT contain payload.</p>
     */
    private PolicyData buildPolicyDataForOperation(
            GatewaySecurityContext securityContext, String basePath, Operation operation) {
        
        PolicyData policyData = new PolicyData();
        
        // Policy name from configuration
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        
        // MANDATORY: appShortcode from configuration (NEVER hardcoded)
        policyData.setAppShortcode(appShortcode);
        
        // MANDATORY: httpMethod matching the operation
        policyData.setHttpMethod(HttpMethod.valueOf(operation.getHttpMethod()));
        
        // MANDATORY: requestPath (base path for the record type)
        policyData.setRequestPath(RequestPath.parse(basePath, ""));
        
        // User's JWT token
        if (securityContext != null) {
            policyData.setEncodedJwt(securityContext.getEncodedJwt());
        }
        
        // Operation type: find, create, or update
        policyData.setOperation(operation.getOperationName());
        
        // CRITICAL: NO requestPayload - forbidden fields query must NOT contain payload
        
        log.trace("Built PolicyData for {}: appShortcode='{}', httpMethod='{}', requestPath='{}', operation='{}'",
            operation, appShortcode, operation.getHttpMethod(), basePath, operation.getOperationName());
        
        return policyData;
    }
    
    /**
     * Fetches multi-operation permissions for anonymous users.
     */
    private Mono<MultiOperationFieldPermissions> fetchAnonymousMultiOperationPermissions(String basePath) {
        log.debug("Fetching anonymous multi-operation permissions for path: {}", basePath);
        
        Mono<FieldPermissionContext> findPermissions = fetchAnonymousPermissionsForOperation(basePath, Operation.FIND);
        Mono<FieldPermissionContext> createPermissions = fetchAnonymousPermissionsForOperation(basePath, Operation.CREATE);
        Mono<FieldPermissionContext> updatePermissions = fetchAnonymousPermissionsForOperation(basePath, Operation.UPDATE);
        
        return Mono.zip(findPermissions, createPermissions, updatePermissions)
            .map(tuple -> {
                MultiOperationFieldPermissions multiPerms = new MultiOperationFieldPermissions();
                multiPerms.setPermissionsForOperation(Operation.FIND, tuple.getT1());
                multiPerms.setPermissionsForOperation(Operation.CREATE, tuple.getT2());
                multiPerms.setPermissionsForOperation(Operation.UPDATE, tuple.getT3());
                return multiPerms;
            })
            .onErrorResume(e -> {
                log.warn("Failed to fetch anonymous multi-operation permissions: {}. Using full visibility.", 
                    e.getMessage());
                return Mono.just(MultiOperationFieldPermissions.fullVisibility());
            });
    }
    
    /**
     * Fetches anonymous permissions for a specific operation.
     */
    private Mono<FieldPermissionContext> fetchAnonymousPermissionsForOperation(String basePath, Operation operation) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        policyData.setAppShortcode(appShortcode);
        policyData.setHttpMethod(HttpMethod.valueOf(operation.getHttpMethod()));
        policyData.setRequestPath(RequestPath.parse(basePath, ""));
        policyData.setOperation(operation.getOperationName());
        // NO encodedJwt for anonymous users
        // NO requestPayload
        
        return authorizationClient.executePolicy(policyData, ForbiddenFieldsLibrary.class)
            .timeout(properties.getOpa().getTimeout())
            .map(FieldPermissionContext::fromForbiddenFieldsLibrary)
            .onErrorResume(e -> {
                log.warn("Anonymous OPA query failed for operation '{}': {}. Using full visibility.",
                    operation.getOperationName(), e.getMessage());
                return Mono.just(FieldPermissionContext.fullVisibility());
            });
    }
    
    // ===================== LEGACY SINGLE-OPERATION METHOD (kept for backward compatibility) =====================
    
    /**
     * Fetches field-level permissions for OAS schema pruning (single operation).
     * 
     * @deprecated Use {@link #fetchMultiOperationPermissions} instead for proper per-operation permissions.
     * @param securityContext The authenticated user's security context (may be null for anonymous)
     * @param requestPath The request path being accessed (e.g., /openapi.json)
     * @return Mono containing the field permission context
     */
    @Deprecated
    public Mono<FieldPermissionContext> fetchFieldPermissions(GatewaySecurityContext securityContext, String requestPath) {
        // Anonymous users get a special anonymous permission context
        if (securityContext == null || securityContext.getEncodedJwt() == null) {
            log.debug("No security context available, fetching anonymous field permissions");
            return fetchAnonymousPermissions(requestPath);
        }
        
        PolicyData policyData = buildPolicyData(securityContext, requestPath);
        
        log.debug("Fetching field permissions for user: {} with {} roles",
            securityContext.getAuthSubject(),
            securityContext.getRoles() != null ? securityContext.getRoles().size() : 0
        );
        
        return authorizationClient.executePolicy(policyData, ForbiddenFieldsLibrary.class)
            .timeout(properties.getOpa().getTimeout())
            .map(FieldPermissionContext::fromForbiddenFieldsLibrary)
            .doOnNext(ctx -> {
                if (ctx.isFullVisibility()) {
                    log.debug("OPA returned full visibility for user: {}", securityContext.getAuthSubject());
                } else {
                    log.debug("OPA returned field restrictions for {} record types",
                        ctx.getRules().size());
                }
            })
            .onErrorResume(e -> handleOpaError(e, securityContext));
    }
    
    /**
     * Fetches permissions for anonymous (unauthenticated) users.
     */
    @Deprecated
    private Mono<FieldPermissionContext> fetchAnonymousPermissions(String requestPath) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        policyData.setAppShortcode(appShortcode);
        policyData.setHttpMethod(HttpMethod.GET);
        policyData.setRequestPath(RequestPath.parse(requestPath, ""));
        policyData.setOperation("find");
        
        log.debug("Fetching anonymous field permissions for path: {}", requestPath);
        
        return authorizationClient.executePolicy(policyData, ForbiddenFieldsLibrary.class)
            .timeout(properties.getOpa().getTimeout())
            .map(FieldPermissionContext::fromForbiddenFieldsLibrary)
            .onErrorResume(e -> {
                log.warn("Failed to fetch anonymous field permissions: {}. Defaulting to full visibility.",
                    e.getMessage());
                return Mono.just(FieldPermissionContext.fullVisibility());
            });
    }
    
    @Deprecated
    private PolicyData buildPolicyData(GatewaySecurityContext securityContext, String requestPath) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        policyData.setAppShortcode(appShortcode);
        policyData.setHttpMethod(HttpMethod.GET);
        policyData.setRequestPath(RequestPath.parse(requestPath, ""));
        policyData.setEncodedJwt(securityContext.getEncodedJwt());
        policyData.setOperation("find");
        return policyData;
    }
    
    private Mono<FieldPermissionContext> handleOpaError(Throwable error, GatewaySecurityContext securityContext) {
        String userInfo = securityContext != null ? securityContext.getAuthSubject() : "anonymous";
        
        if (properties.getOpa().isFailClosed()) {
            log.error("OPA field permission query failed for user '{}' (fail-closed mode): {}",
                userInfo, error.getMessage());
            return Mono.error(new OpaFieldPermissionException(
                "Unable to determine field permissions. Please try again later.",
                error
            ));
        }
        
        log.warn("OPA field permission query failed for user '{}' (fail-open mode): {}. " +
                "Returning full visibility for documentation.",
            userInfo, error.getMessage());
        
        return Mono.just(FieldPermissionContext.fullVisibility());
    }
    
    /**
     * Exception thrown when OPA field permission query fails in fail-closed mode.
     */
    public static class OpaFieldPermissionException extends RuntimeException {
        public OpaFieldPermissionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
