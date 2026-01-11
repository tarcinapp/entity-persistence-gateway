package com.tarcinapp.entitypersistencegateway.oas.security;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service for querying OPA to obtain field-level visibility permissions.
 * 
 * <p>This service queries OPA with the user's identity context (JWT claims)
 * to determine which fields should be hidden from the OpenAPI specification.
 * Unlike request-time authorization, this query does NOT include request payloads
 * or query parameters since they don't exist during spec generation.</p>
 * 
 * <h2>OPA Policy Contract:</h2>
 * <pre>
 * package policies.oas.field_visibility
 * 
 * # Input structure:
 * # {
 * #   "encodedJwt": "eyJ...",
 * #   "roles": ["admin", "user"],
 * #   "groups": ["engineering"]
 * # }
 * 
 * # Output structure (same as ForbiddenFieldsLibrary):
 * # {
 * #   "entities": {
 * #     "default": ["_idempotencyKey"],
 * #     "kinds": { "user": ["password"] }
 * #   }
 * # }
 * </pre>
 * 
 * <h2>Failure Handling:</h2>
 * <p>When OPA is unavailable or returns errors, this service defaults to
 * "full visibility" (fail-open) for documentation purposes. This is intentional
 * since blocking documentation access is generally worse than showing extra fields
 * that would be blocked at request time anyway.</p>
 */
@Service
@Slf4j
public class OasFieldPermissionService {
    
    private final IAuthorizationClient authorizationClient;
    private final OasOrchestratorProperties properties;
    
    public OasFieldPermissionService(
            IAuthorizationClient authorizationClient,
            OasOrchestratorProperties properties) {
        this.authorizationClient = authorizationClient;
        this.properties = properties;
    }
    
    /**
     * Fetches field-level permissions for OAS schema pruning.
     * 
     * <p>Queries OPA with the user's identity context to determine which fields
     * should be hidden from the generated OpenAPI specification.</p>
     * 
     * @param securityContext The authenticated user's security context (may be null for anonymous)
     * @return Mono containing the field permission context
     */
    public Mono<FieldPermissionContext> fetchFieldPermissions(GatewaySecurityContext securityContext) {
        // Anonymous users get a special anonymous permission context
        if (securityContext == null || securityContext.getEncodedJwt() == null) {
            log.debug("No security context available, fetching anonymous field permissions");
            return fetchAnonymousPermissions();
        }
        
        PolicyData policyData = buildPolicyData(securityContext);
        
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
     * 
     * <p>Anonymous users may have different visibility rules than authenticated users.
     * This method queries OPA with empty claims to get the public-only field set.</p>
     * 
     * @return Mono containing the anonymous permission context
     */
    private Mono<FieldPermissionContext> fetchAnonymousPermissions() {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        // Empty JWT and null roles/groups indicate anonymous user
        
        return authorizationClient.executePolicy(policyData, ForbiddenFieldsLibrary.class)
            .timeout(properties.getOpa().getTimeout())
            .map(FieldPermissionContext::fromForbiddenFieldsLibrary)
            .onErrorResume(e -> {
                log.warn("Failed to fetch anonymous field permissions: {}. Defaulting to full visibility.",
                    e.getMessage());
                return Mono.just(FieldPermissionContext.fullVisibility());
            });
    }
    
    /**
     * Builds the PolicyData object for OPA query.
     * 
     * <p>Note: Unlike request-time authorization, we do NOT include:
     * <ul>
     *   <li>Request payload (doesn't exist)</li>
     *   <li>Query parameters (doesn't exist)</li>
     *   <li>Original record (not applicable)</li>
     * </ul>
     * This is intentional - we're querying for field visibility based solely
     * on user identity, not request-specific data.</p>
     * 
     * @param securityContext The user's security context
     * @return PolicyData configured for field visibility query
     */
    private PolicyData buildPolicyData(GatewaySecurityContext securityContext) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        policyData.setEncodedJwt(securityContext.getEncodedJwt());
        
        // Note: We deliberately do not set operation, httpMethod, requestPath, etc.
        // These are request-time concepts that don't apply to spec generation.
        
        return policyData;
    }
    
    /**
     * Handles OPA errors based on configuration.
     * 
     * <p>Default behavior (failClosed=false): Return full visibility on error.
     * This is appropriate for documentation since:</p>
     * <ul>
     *   <li>Users can see the full API structure</li>
     *   <li>Actual field filtering still happens at request time</li>
     *   <li>Documentation availability is prioritized over field hiding</li>
     * </ul>
     * 
     * <p>Strict mode (failClosed=true): Propagate the error and return 503.
     * Use this in high-security environments where field visibility in docs
     * is as sensitive as actual data access.</p>
     * 
     * @param error The error from OPA
     * @param securityContext The user's security context
     * @return Mono with fallback context or error
     */
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
