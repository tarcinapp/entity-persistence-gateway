package com.tarcinapp.entitypersistencegateway.oas.security;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Service for querying OPA to obtain field-level visibility permissions.
 * 
 * <p>This service uses the SAME mechanism as {@link com.tarcinapp.entitypersistencegateway.filters.common.request.FetchForbiddenFieldsGatewayFilterFactory}
 * to fetch forbidden fields, but augments the PolicyData with schema metadata (x-record-type mappings)
 * from the transformed OpenAPI spec.</p>
 * 
 * <h2>Mechanism:</h2>
 * <ol>
 *   <li>Build PolicyData from GatewaySecurityContext (JWT, claims)</li>
 *   <li>Extract schema metadata from OpenAPI spec (schema name → x-record-type)</li>
 *   <li>Set extracted metadata as PolicyData.requestPayload</li>
 *   <li>Set policy name from configuration (typically /policies/gateway/forbidden_fields/policy/result)</li>
 *   <li>Call authorizationClient.executePolicy(PolicyData, ForbiddenFieldsLibrary.class)</li>
 *   <li>Convert ForbiddenFieldsLibrary to FieldPermissionContext</li>
 * </ol>
 * 
 * <h2>PolicyData Structure Sent to OPA:</h2>
 * <pre>
 * {
 *   "policyName": "/policies/gateway/forbidden_fields/policy/result",
 *   "encodedJwt": "eyJ...",
 *   "requestPayload": {
 *     "Book": "entities",
 *     "Author": "entities",
 *     "BookAuthor": "relations"
 *   }
 * }
 * </pre>
 * 
 * <p><strong>Note:</strong> The requestPayload contains schema metadata (x-record-type mappings)
 * so OPA policies can make decisions based on which schemas exist in the API.</p>
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
     * <p>Queries OPA with the user's identity context AND the list of schemas
     * in the OpenAPI spec to determine which fields should be hidden from the
     * generated OpenAPI specification.</p>
     * 
     * @param securityContext The authenticated user's security context (may be null for anonymous)
     * @param openApi The transformed OpenAPI spec with schemas
     * @return Mono containing the field permission context
     */
    public Mono<FieldPermissionContext> fetchFieldPermissions(GatewaySecurityContext securityContext, OpenAPI openApi) {
        // Anonymous users get a special anonymous permission context
        if (securityContext == null || securityContext.getEncodedJwt() == null) {
            log.debug("No security context available, fetching anonymous field permissions");
            return fetchAnonymousPermissions(openApi);
        }
        
        PolicyData policyData = buildPolicyData(securityContext, openApi);
        
        log.debug("Fetching field permissions for user: {} with {} roles and {} schemas",
            securityContext.getAuthSubject(),
            securityContext.getRoles() != null ? securityContext.getRoles().size() : 0,
            openApi.getComponents() != null && openApi.getComponents().getSchemas() != null
                ? openApi.getComponents().getSchemas().size() : 0
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
     * This method queries OPA with empty claims but the full schema list to get
     * the public-only field set.</p>
     * 
     * @param openApi The transformed OpenAPI spec with schemas
     * @return Mono containing the anonymous permission context
     */
    private Mono<FieldPermissionContext> fetchAnonymousPermissions(OpenAPI openApi) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        // Include schemas even for anonymous users
        policyData.setRequestPayload(extractSchemaMetadata(openApi));
        
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
     * Extracts schema metadata (record types) from the OpenAPI spec.
     * 
     * <p>This method extracts the x-record-type vendor extension from each schema
     * and returns a map of schema names to their record types. This metadata is
     * passed to OPA via PolicyData.requestPayload so OPA can make field visibility
     * decisions based on which schemas exist and their types.</p>
     * 
     * <p>Example output:</p>
     * <pre>
     * {
     *   "Book": "entities",
     *   "Author": "entities",
     *   "BookAuthor": "relations"
     * }
     * </pre>
     * 
     * @param openApi The transformed OpenAPI spec with x-record-type extensions
     * @return Map of schema names to their record types (empty if no schemas or extensions)
     */
    private Map<String, Object> extractSchemaMetadata(OpenAPI openApi) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        
        if (openApi == null || openApi.getComponents() == null || 
            openApi.getComponents().getSchemas() == null) {
            return metadata;
        }
        
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String schemaName = entry.getKey();
            Schema<?> schema = entry.getValue();
            
            if (schema != null && schema.getExtensions() != null) {
                Object recordType = schema.getExtensions().get("x-record-type");
                if (recordType != null) {
                    metadata.put(schemaName, recordType);
                    log.trace("Schema '{}' has record type: {}", schemaName, recordType);
                }
            }
        }
        
        log.debug("Extracted schema metadata for {} schemas", metadata.size());
        return metadata;
    }
    
    /**
     * Builds the PolicyData object for OPA query.
     * 
     * <p>This follows the SAME pattern as {@link com.tarcinapp.entitypersistencegateway.filters.common.request.FetchForbiddenFieldsGatewayFilterFactory}
     * but adds schema metadata via requestPayload:</p>
     * <ul>
     *   <li><strong>policyName:</strong> Policy path from configuration (e.g., /policies/gateway/forbidden_fields/policy/result)</li>
     *   <li><strong>encodedJwt:</strong> User's JWT token for authentication/authorization context</li>
     *   <li><strong>requestPayload:</strong> Schema metadata (schema name → x-record-type) extracted from OpenAPI spec</li>
     * </ul>
     * 
     * <p>OPA receives this PolicyData and returns ForbiddenFieldsLibrary with field restrictions.</p>
     * 
     * @param securityContext The user's security context with JWT
     * @param openApi The transformed OpenAPI spec with x-record-type extensions
     * @return PolicyData configured for field visibility query
     */
    private PolicyData buildPolicyData(GatewaySecurityContext securityContext, OpenAPI openApi) {
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(properties.getOpa().getFieldPolicy());
        policyData.setEncodedJwt(securityContext.getEncodedJwt());
        
        // Extract schema metadata (x-record-type mappings) and set as requestPayload
        // This allows OPA to make decisions based on which schemas exist in the API
        Map<String, Object> schemaMetadata = extractSchemaMetadata(openApi);
        policyData.setRequestPayload(schemaMetadata);
        
        log.trace("Built PolicyData with policy='{}', jwt={}, schemas={}",
            properties.getOpa().getFieldPolicy(),
            securityContext.getEncodedJwt() != null ? "present" : "null",
            schemaMetadata.size());
        
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
