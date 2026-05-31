package com.tarcinapp.entitypersistencegateway.oas.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration properties for the Dynamic OAS Orchestrator.
 * 
 * <p>This component generates personalized, virtualized OpenAPI specifications
 * by transforming the backend's technical OAS based on domain aliases and
 * user-specific field permissions.</p>
 * 
 * <h2>Configuration Example:</h2>
 * <pre>
 * app:
 *   oas:
 *     orchestrator:
 *       enabled: true
 *       endpoints:
 *         json: /openapi.json
 *         yaml: /openapi.yaml
 *       cache:
 *         enabled: true
 *         ttl: PT15M
 *         raw-oas-ttl: PT5M
 *       backend:
 *         spec-path: /explorer/openapi.json
 *         connect-timeout: 3000
 *         read-timeout: 5000
 *       opa:
 *         field-policy: /policies/oas/field_visibility/policy/result
 *         timeout: PT500MS
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "app.oas.orchestrator")
@Data
public class OasOrchestratorProperties {
    
    /**
     * Master switch to enable/disable the Dynamic OAS Orchestrator.
     * When disabled, OAS endpoints return 404.
     */
    private boolean enabled = true;
    
    /**
     * Endpoint configuration for OAS serving.
     */
    private EndpointConfig endpoints = new EndpointConfig();
    
    /**
     * Caching configuration for transformed OAS specs.
     */
    private CacheConfig cache = new CacheConfig();
    
    /**
     * Backend service configuration for fetching raw OAS.
     */
    private BackendConfig backend = new BackendConfig();
    
    /**
     * OPA integration configuration for field-level permissions.
     */
    private OpaConfig opa = new OpaConfig();
    
    /**
     * Transformation behavior configuration.
     */
    private TransformationConfig transformation = new TransformationConfig();
    
    @Data
    public static class EndpointConfig {
        /**
         * Path to serve JSON-formatted OpenAPI spec.
         * Relative to the gateway's base URI.
         */
        private String json = "/openapi.json";
        
        /**
         * Path to serve YAML-formatted OpenAPI spec.
         * Relative to the gateway's base URI.
         */
        private String yaml = "/openapi.yaml";
        
        // NOTE: Authentication requirement is NOT configurable here.
        // It follows the same logic as all gateway routes:
        // If app.auth.providers is configured, JWT is required.
        // See TokenParserRegistry.isConfigured()
    }
    
    @Data
    public static class CacheConfig {
        /**
         * Enable/disable caching of transformed OAS specs.
         * Strongly recommended for production.
         */
        private boolean enabled = true;
        
        /**
         * Time-to-live for cached personalized OAS specs.
         * Each unique role combination gets its own cached spec.
         */
        private Duration ttl = Duration.ofMinutes(15);
        
        /**
         * Time-to-live for the raw backend OAS cache.
         * This is shared across all users since it's role-independent.
         */
        private Duration rawOasTtl = Duration.ofMinutes(5);
        
        /**
         * Maximum number of role-based specs to cache in-memory (L2 Caffeine).
         * Prevents memory exhaustion from diverse role combinations.
         */
        private int maxLocalCacheSize = 100;
        
        /**
         * Redis key prefix for OAS caching.
         */
        private String keyPrefix = "oas:v1:";
    }
    
    @Data
    public static class BackendConfig {
        /**
         * Path to the backend's OpenAPI spec endpoint.
         * Appended to the routing-target base URL.
         */
        private String specPath = "/explorer/openapi.json";
        
        /**
         * Connection timeout for backend OAS fetch.
         */
        private int connectTimeoutMs = 3000;
        
        /**
         * Read timeout for backend OAS fetch.
         */
        private int readTimeoutMs = 5000;
        
        /**
         * Write timeout for backend OAS fetch.
         */
        private int writeTimeoutMs = 3000;
        
        /**
         * Overall response timeout for backend OAS fetch.
         */
        private Duration responseTimeout = Duration.ofSeconds(5);

        /**
         * Maximum number of bytes to buffer when reading the backend OAS response body.
         * The default Spring WebClient limit is 256KB (262144 bytes).
         * Large OpenAPI specs require this to be increased.
         */
        private int maxBufferSizeBytes = 10 * 1024 * 1024; // 10MB

        /**
         * Startup retry configuration for fetching backend OAS.
         */
        private RetryConfig retry = new RetryConfig();
    }
    
    @Data
    public static class RetryConfig {
        /**
         * Maximum number of retry attempts.
         */
        private int maxAttempts = 5;
        
        /**
         * Initial interval between retries.
         */
        private long initialIntervalMs = 2000;
        
        /**
         * Multiplier for exponential backoff (not used in simple retry, but good for future).
         */
        private double multiplier = 1.5;
        
        /**
         * Maximum interval between retries.
         */
        private long maxIntervalMs = 10000;
    }
    
    @Data
    public static class OpaConfig {
        /**
         * OPA policy path for field-level visibility decisions.
         * This policy receives JWT claims and schema metadata, then returns forbidden fields per record type.
         * 
         * <p><strong>Default:</strong> Uses the same policy as FetchForbiddenFieldsGatewayFilterFactory</p>
         * <p><strong>Override:</strong> Set app.oas.orchestrator.opa.field-policy in application-oas-orchestrator.yml</p>
         * 
         * <p>Policy Contract (Same as FetchForbiddenFieldsGatewayFilterFactory):</p>
         * <pre>
         * Input PolicyData: {
         *   "policyName": "/policies/gateway/forbidden_fields/policy/result",
         *   "encodedJwt": "eyJ...",
         *   "requestPayload": {
         *     "Book": "entities",
         *     "Author": "entities"
         *   }
         * }
         * 
         * Output ForbiddenFieldsLibrary: {
         *   "entities": {
         *     "default": ["_idempotencyKey"],
         *     "kinds": { "book": ["secretField"] }
         *   }
         * }
         * </pre>
         */
        private String fieldPolicy = "/policies/gateway/forbidden_fields/policy/result";
        
        /**
         * Timeout for OPA field permission queries.
         * On timeout, the orchestrator falls back to full visibility (fail-open for docs).
         */
        private Duration timeout = Duration.ofMillis(500);
        
        /**
         * Whether to fail-closed when OPA is unreachable.
         * When false (default), OPA failures result in full-visibility specs.
         * When true, OPA failures return 503 Service Unavailable.
         */
        private boolean failClosed = false;
    }
    
    @Data
    public static class TransformationConfig {
        /**
         * Whether to include generic (non-aliased) endpoints in the spec.
         * When false, only aliased resources appear in the generated OAS.
         */
        private boolean includeGenericEndpoints = false;
        
        /**
         * Whether to simplify verbose backend schema names.
         * E.g., "GenericEntityExcluding__idempotencyKey..." → "Book"
         */
        private boolean simplifySchemaNames = true;
    }
}
