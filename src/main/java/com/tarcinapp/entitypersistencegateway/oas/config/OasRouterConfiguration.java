package com.tarcinapp.entitypersistencegateway.oas.config;

import com.tarcinapp.entitypersistencegateway.oas.handler.DynamicOasHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for the Dynamic OAS Orchestrator.
 * 
 * <p>This configuration exposes the OAS endpoints as a RouterFunction,
 * which operates completely independently of the Spring Cloud Gateway
 * route definitions. This guarantees zero interference with normal
 * API traffic routing.</p>
 * 
 * <h2>Endpoint Isolation:</h2>
 * <p>RouterFunctions are processed BEFORE gateway route predicates.
 * When a request matches /openapi.json or /openapi.yaml, it is handled
 * directly by the DynamicOasHandler and never touches the gateway
 * routing infrastructure.</p>
 * 
 * <h2>Available Endpoints:</h2>
 * <ul>
 *   <li>{@code GET /openapi.json} - Returns personalized OAS in JSON format</li>
 *   <li>{@code GET /openapi.yaml} - Returns personalized OAS in YAML format</li>
 *   <li>{@code POST /admin/oas/cache/invalidate} - Invalidates OAS cache (admin only)</li>
 *   <li>{@code GET /admin/oas/cache/stats} - Returns cache statistics (admin only)</li>
 * </ul>
 * 
 * <h2>Conditional Activation:</h2>
 * <p>The entire OAS orchestrator can be disabled via configuration:
 * {@code oas-orchestrator.enabled=false}</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.oas.orchestrator.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class OasRouterConfiguration {
    
    private final OasOrchestratorProperties properties;

    public OasRouterConfiguration(OasOrchestratorProperties properties) {
        this.properties = properties;
        log.info("Dynamic OAS Orchestrator is ENABLED");
        log.info("  JSON endpoint: {}", properties.getEndpoints().getJson());
        log.info("  YAML endpoint: {}", properties.getEndpoints().getYaml());
    }
    
    /**
     * Creates the router function for public OAS endpoints.
     * 
     * <p>These routes handle the main API documentation requests
     * and return personalized, virtualized OpenAPI specifications.</p>
     * 
     * @param handler The DynamicOasHandler component
     * @return RouterFunction for OAS endpoints
     */
    @Bean
    @Order(-1) // Process before gateway routes (which have default order 0)
    public RouterFunction<ServerResponse> oasRouterFunction(DynamicOasHandler handler) {
        String jsonPath = properties.getEndpoints().getJson();
        String yamlPath = properties.getEndpoints().getYaml();
        
        log.debug("Configuring OAS routes: json={}, yaml={}", jsonPath, yamlPath);
        
        return RouterFunctions
            // JSON endpoint
            .route(
                RequestPredicates.GET(jsonPath)
                    .and(RequestPredicates.accept(MediaType.APPLICATION_JSON, MediaType.ALL)),
                handler::handleJsonRequest
            )
            // YAML endpoint
            .andRoute(
                RequestPredicates.GET(yamlPath)
                    .and(RequestPredicates.accept(MediaType.ALL)),
                handler::handleYamlRequest
            )
            // Accept header negotiation on a common path (optional)
            .andRoute(
                RequestPredicates.GET("/openapi")
                    .and(RequestPredicates.accept(MediaType.valueOf("application/x-yaml"))),
                handler::handleYamlRequest
            )
            .andRoute(
                RequestPredicates.GET("/openapi")
                    .and(RequestPredicates.accept(MediaType.APPLICATION_JSON)),
                handler::handleJsonRequest
            );
    }
    
    /**
     * Creates the router function for administrative OAS endpoints.
     * 
     * <p>These routes handle cache management and are protected
     * by additional authorization (should be configured via Spring Security).</p>
     * 
     * @param handler The DynamicOasHandler component
     * @return RouterFunction for admin OAS endpoints
     */
    @Bean
    @Order(-1) // Process before gateway routes
    public RouterFunction<ServerResponse> oasAdminRouterFunction(DynamicOasHandler handler) {
        return RouterFunctions
            // Cache invalidation endpoint
            .route(
                RequestPredicates.POST("/admin/oas/cache/invalidate"),
                handler::handleCacheInvalidation
            )
            // Cache statistics endpoint
            .andRoute(
                RequestPredicates.GET("/admin/oas/cache/stats"),
                handler::handleCacheStats
            );
    }

    /**
     * Logs configuration details on startup.
     */
    @Bean
    public OasOrchestratorStartupLogger oasOrchestratorStartupLogger() {
        return new OasOrchestratorStartupLogger(properties);
    }
    
    /**
     * Startup logger for OAS Orchestrator configuration.
     */
    @Slf4j
    static class OasOrchestratorStartupLogger {
        
        OasOrchestratorStartupLogger(OasOrchestratorProperties properties) {
            if (log.isInfoEnabled()) {
                log.info("=== Dynamic OAS Orchestrator Configuration ===");
                log.info("Backend OAS Path: {}", properties.getBackend().getSpecPath());
                log.info("Cache TTL: {}", properties.getCache().getTtl());
                log.info("L1 Cache Size: {}", properties.getCache().getMaxLocalCacheSize());
                log.info("OPA Fail-Closed: {}", properties.getOpa().isFailClosed());
                log.info("OPA Policy Path: {}", properties.getOpa().getFieldPolicy());
                // Auth requirement follows app.auth.providers via TokenParserRegistry.isConfigured()
                log.info("===============================================");
            }
        }
    }
}
