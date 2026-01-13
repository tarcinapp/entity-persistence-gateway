package com.tarcinapp.entitypersistencegateway.oas.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.helpers.TokenParserRegistry;
import com.tarcinapp.entitypersistencegateway.oas.cache.OasCacheKeyBuilder;
import com.tarcinapp.entitypersistencegateway.oas.cache.OasCacheService;
import com.tarcinapp.entitypersistencegateway.oas.client.BackendOasClient;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import com.tarcinapp.entitypersistencegateway.oas.security.OasFieldPermissionService;
import com.tarcinapp.entitypersistencegateway.oas.transformation.OasSchemaPruner;
import com.tarcinapp.entitypersistencegateway.oas.transformation.OasTransformationEngine;
import com.tarcinapp.entitypersistencegateway.oas.transformation.OasTransformationEngine.RequestContext;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;

/**
 * Handler for dynamic OpenAPI specification requests.
 * 
 * <p>This handler orchestrates the entire OAS generation pipeline:</p>
 * <ol>
 *   <li>Extract and validate authentication (optional based on config)</li>
 *   <li>Build cache key from user's role fingerprint</li>
 *   <li>Check cache for existing personalized spec</li>
 *   <li>On cache miss: fetch raw OAS, transform, query OPA, prune, cache</li>
 *   <li>Return JSON or YAML based on Accept header or endpoint</li>
 * </ol>
 * 
 * <h2>Non-Blocking Guarantee:</h2>
 * <p>The entire pipeline is reactive. No blocking calls are made.</p>
 * 
 * <h2>Isolation:</h2>
 * <p>This handler runs on a completely separate path from gateway routes.
 * It does not interfere with normal API traffic.</p>
 */
@Component
@Slf4j
public class DynamicOasHandler {
    
    private static final MediaType MEDIA_TYPE_YAML = MediaType.valueOf("application/x-yaml");
    
    private final OasOrchestratorProperties properties;
    private final BackendOasClient backendOasClient;
    private final OasTransformationEngine transformationEngine;
    private final OasFieldPermissionService fieldPermissionService;
    private final OasSchemaPruner schemaPruner;
    private final OasCacheService cacheService;
    private final OasCacheKeyBuilder cacheKeyBuilder;
    private final TokenParserRegistry tokenParserRegistry;
    
    private final ObjectMapper genericMapper;
    
    public DynamicOasHandler(
            OasOrchestratorProperties properties,
            BackendOasClient backendOasClient,
            OasTransformationEngine transformationEngine,
            OasFieldPermissionService fieldPermissionService,
            OasSchemaPruner schemaPruner,
            OasCacheService cacheService,
            OasCacheKeyBuilder cacheKeyBuilder,
            TokenParserRegistry tokenParserRegistry,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.backendOasClient = backendOasClient;
        this.transformationEngine = transformationEngine;
        this.fieldPermissionService = fieldPermissionService;
        this.schemaPruner = schemaPruner;
        this.cacheService = cacheService;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.tokenParserRegistry = tokenParserRegistry;
        
        // Use generic mapper for JWT parsing, NOT for OpenAPI serialization
        // OpenAPI serialization uses io.swagger.v3.core.util.Json/Yaml.pretty() static methods
        this.genericMapper = objectMapper;
    }
    
    /**
     * Handles requests for OpenAPI specification in JSON format.
     * 
     * @param request The incoming server request
     * @return ServerResponse containing the OAS JSON
     */
    public Mono<ServerResponse> handleJsonRequest(ServerRequest request) {
        return handleOasRequest(request, OutputFormat.JSON);
    }
    
    /**
     * Handles requests for OpenAPI specification in YAML format.
     * 
     * @param request The incoming server request
     * @return ServerResponse containing the OAS YAML
     */
    public Mono<ServerResponse> handleYamlRequest(ServerRequest request) {
        return handleOasRequest(request, OutputFormat.YAML);
    }
    
    /**
     * Handles cache invalidation requests (admin endpoint).
     * 
     * @param request The incoming server request
     * @return ServerResponse confirming invalidation
     */
    public Mono<ServerResponse> handleCacheInvalidation(ServerRequest request) {
        String cacheKey = request.queryParam("key").orElse(null);
        
        Mono<Void> invalidation = cacheKey != null
            ? cacheService.invalidate(cacheKey)
            : cacheService.invalidateAll();
        
        return invalidation
            .then(ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"cache_invalidated\"}"));
    }
    
    /**
     * Handles requests for cache statistics (admin endpoint).
     * 
     * @param request The incoming server request
     * @return ServerResponse containing cache stats
     */
    public Mono<ServerResponse> handleCacheStats(ServerRequest request) {
        OasCacheService.CacheStats stats = cacheService.getStats();
        
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(stats);
    }
    
    /**
     * Core OAS request handler.
     */
    private Mono<ServerResponse> handleOasRequest(ServerRequest request, OutputFormat format) {
        if (!properties.isEnabled()) {
            return ServerResponse.notFound().build();
        }
        
        log.debug("Handling OAS request: {} format={}", request.path(), format);
        
        // Extract request context for dynamic server URL generation
        RequestContext requestContext = extractRequestContext(request);
        
        // 1. Extract security context (non-blocking)
        return extractSecurityContext(request)
            .flatMap(securityContext -> {
                // 2. Build cache key from security context
                String cacheKey = cacheKeyBuilder.buildCacheKey(securityContext);
                String formatSuffix = format == OutputFormat.JSON ? ":json" : ":yaml";
                String fullCacheKey = cacheKey + formatSuffix;
                
                // 3. Get from cache or compute
                return cacheService.getOrCompute(
                    fullCacheKey,
                    computePersonalizedOas(securityContext, requestContext, format)
                );
            })
            .flatMap(oasContent -> buildResponse(oasContent, format))
            .onErrorResume(this::handleError);
    }
    
    /**
     * Extracts request context (protocol, host, port) for dynamic server URL generation.
     */
    private RequestContext extractRequestContext(ServerRequest request) {
        try {
            var uri = request.uri();
            return RequestContext.builder()
                .scheme(uri.getScheme() != null ? uri.getScheme() : "http")
                .host(uri.getHost() != null ? uri.getHost() : "localhost")
                .port(uri.getPort())
                .contextPath(null) // Gateway typically runs at root
                .build();
        } catch (Exception e) {
            log.warn("Failed to extract request context: {}", e.getMessage());
            return RequestContext.builder()
                .scheme("http")
                .host("localhost")
                .port(8081)
                .build();
        }
    }
    
    /**
     * Extracts the security context from the request.
     * Authentication is REQUIRED when TokenParserRegistry has configured providers.
     * This aligns with the existing AuthenticateRequest filter logic.
     */
    private Mono<GatewaySecurityContext> extractSecurityContext(ServerRequest request) {
        String authHeader = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        
        // Use existing auth configuration - if providers are configured, auth is required
        boolean authRequired = tokenParserRegistry.isConfigured();
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (authRequired) {
                return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required for API documentation"
                ));
            }
            log.debug("No auth header, proceeding as anonymous (auth not configured)");
            return Mono.just(new GatewaySecurityContext());
        }
        
        String token = authHeader.substring(7);
        
        return validateToken(token)
            .map(claims -> {
                GatewaySecurityContext context = new GatewaySecurityContext();
                context.setEncodedJwt(token);
                context.setAuthSubject(claims.getSubject());
                
                // Extract roles from claims
                @SuppressWarnings("unchecked")
                List<String> roles = claims.get("roles", List.class);
                if (roles != null) {
                    context.setRoles(new java.util.ArrayList<>(roles));
                }
                
                // Extract groups from claims (if present)
                @SuppressWarnings("unchecked")
                List<String> groups = claims.get("groups", List.class);
                if (groups != null) {
                    context.setGroups(new java.util.ArrayList<>(groups));
                }
                
                return context;
            })
            .onErrorResume(e -> {
                log.warn("JWT authentication failed: {}", e.getMessage());
                if (authRequired) {
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid authentication token"
                    ));
                }
                return Mono.just(new GatewaySecurityContext());
            });
    }
    
    /**
     * Validates the JWT token using the registered token parsers.
     */
    private Mono<Claims> validateToken(String jwt) {
        return Mono.fromCallable(() -> {
            // Extract issuer from token without validation
            String issuer = extractIssuerWithoutValidation(jwt);
            
            if (issuer == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token does not contain issuer claim");
            }
            
            var parser = tokenParserRegistry.getParser(issuer);
            if (parser == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown token issuer: " + issuer);
            }
            
            return parser.parseClaimsJws(jwt).getBody();
        });
    }
    
    /**
     * Extracts the issuer from a JWT without validating the signature.
     */
    private String extractIssuerWithoutValidation(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            var node = genericMapper.readTree(payload);
            
            if (node.has("iss")) {
                return node.get("iss").asText();
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to extract issuer from token: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Computes the personalized OAS for a user.
     * This is the full transformation pipeline.
     * 
     * <p>The pipeline follows the EXACT same authorization pattern as gateway routes:</p>
     * <ol>
     *   <li>Fetch raw OAS from backend</li>
     *   <li>Transform OAS (add paths, schemas, server info)</li>
     *   <li>Query OPA for forbidden fields per operation (find, create, update)</li>
     *   <li>Prune schemas based on operation-specific field permissions</li>
     *   <li>Serialize to JSON/YAML</li>
     * </ol>
     * 
     * <p>OPA is queried 3 times with different operation types:</p>
     * <ul>
     *   <li>find:   GET /entities, operation="find"</li>
     *   <li>create: POST /entities, operation="create"</li>
     *   <li>update: PATCH /entities, operation="update"</li>
     * </ul>
     */
    private Mono<String> computePersonalizedOas(GatewaySecurityContext securityContext, RequestContext requestContext, OutputFormat format) {
        log.debug("Computing personalized OAS for user: {}",
            securityContext.getAuthSubject() != null ? securityContext.getAuthSubject() : "anonymous");
        
        // Use a base path for OPA queries (e.g., /entities)
        // The actual path doesn't affect the forbidden fields rules - they're based on recordType
        String basePath = "/entities";
        
        // Pipeline: Fetch → Transform (with request context) → Get Multi-Operation Permissions → Prune → Serialize
        return backendOasClient.fetchRawOas()
            .map(rawOas -> {
                log.debug("Transforming raw OAS with {} paths",
                    rawOas.getPaths() != null ? rawOas.getPaths().size() : 0);
                return transformationEngine.transform(rawOas, requestContext);
            })
            .flatMap(transformedOas -> 
                // Fetch forbidden fields for ALL operations (find, create, update)
                // OPA returns DIFFERENT rules for each operation!
                // PolicyData contains: appShortcode, encodedJwt, operation, httpMethod, requestPath
                // NO requestPayload - forbidden fields query must NOT contain payload
                fieldPermissionService.fetchMultiOperationPermissions(securityContext, basePath)
                    .map(permissions -> {
                        log.debug("Pruning OAS based on multi-operation field permissions");
                        return schemaPruner.pruneWithMultiOperationPermissions(transformedOas, permissions);
                    })
            )
            .map(prunedOas -> serializeOas(prunedOas, format))
            .doOnSuccess(spec -> log.debug("Generated personalized OAS: {} bytes", spec.length()));
    }
    
    /**
     * Serializes the OpenAPI object to JSON or YAML.
     * Uses Swagger's native serializers which properly handle internal fields.
     */
    private String serializeOas(OpenAPI openApi, OutputFormat format) {
        try {
            if (format == OutputFormat.YAML) {
                // Use Swagger's native YAML serializer - handles internal fields properly
                return io.swagger.v3.core.util.Yaml.pretty(openApi);
            } else {
                // Use Swagger's native JSON serializer - handles internal fields properly
                return io.swagger.v3.core.util.Json.pretty(openApi);
            }
        } catch (Exception e) {
            log.error("Failed to serialize OpenAPI: {}", e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to serialize OpenAPI specification"
            );
        }
    }
    
    /**
     * Builds the HTTP response with appropriate content type.
     */
    private Mono<ServerResponse> buildResponse(String content, OutputFormat format) {
        MediaType contentType = format == OutputFormat.JSON
            ? MediaType.APPLICATION_JSON
            : MEDIA_TYPE_YAML;
        
        return ServerResponse.ok()
            .contentType(contentType)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + properties.getCache().getTtl().toSeconds())
            .bodyValue(content);
    }
    
    /**
     * Handles errors and converts them to appropriate HTTP responses.
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("OAS generation failed: {}", error.getMessage());
        
        if (error instanceof ResponseStatusException) {
            ResponseStatusException rse = (ResponseStatusException) error;
            return ServerResponse.status(rse.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ErrorResponse(
                    rse.getStatusCode().value(),
                    rse.getReason()
                ));
        }
        
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new ErrorResponse(
                500,
                "Failed to generate API documentation"
            ));
    }
    
    /**
     * Output format enum.
     */
    private enum OutputFormat {
        JSON, YAML
    }
    
    /**
     * Error response DTO.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ErrorResponse {
        private int status;
        private String message;
    }
}
