package com.tarcinapp.entitypersistencegateway.oas.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.support.RouterFunctionMapping;

import java.util.List;

/**
 * Configures CORS on RouterFunctionMapping for OAS endpoints.
 *
 * globalcors covers RoutePredicateHandlerMapping (gateway routes) and
 * SimpleUrlHandlerMapping (actuator) but not RouterFunctionMapping, which
 * handles /openapi.json, /openapi.yaml and /admin/oas/**. Without this,
 * OPTIONS preflights to those paths return 403.
 *
 * This is a separate class from OasRouterConfiguration to avoid a circular
 * dependency: OasRouterConfiguration produces RouterFunction beans →
 * RouterFunctionMapping depends on those beans → injecting RouterFunctionMapping
 * back into OasRouterConfiguration closes the cycle. Placing the injection here
 * (a class that produces no RouterFunction beans) breaks the cycle.
 */
@Configuration
@ConditionalOnProperty(name = "app.oas.orchestrator.enabled", havingValue = "true", matchIfMissing = false)
public class OasCorsConfiguration {

    @Value("${app.inbound.cors.allowedOrigins}")
    private List<String> allowedOrigins;

    @Value("${app.inbound.cors.allowedMethods}")
    private List<String> allowedMethods;

    @Value("${app.inbound.cors.allowedHeaders}")
    private List<String> allowedHeaders;

    @Value("${app.inbound.cors.exposedHeaders}")
    private List<String> exposedHeaders;

    @Value("${app.inbound.cors.allowCredentials}")
    private boolean allowCredentials;

    @Value("${app.inbound.cors.maxAge}")
    private long maxAge;

    @Autowired
    public void configureRouterFunctionMappingCors(RouterFunctionMapping routerFunctionMapping) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(allowedMethods);
        config.setAllowedHeaders(allowedHeaders);
        config.setExposedHeaders(exposedHeaders);
        config.setAllowCredentials(allowCredentials);
        config.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        routerFunctionMapping.setCorsConfigurationSource(source);
    }
}
