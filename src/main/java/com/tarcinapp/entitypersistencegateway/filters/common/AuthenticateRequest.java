package com.tarcinapp.entitypersistencegateway.filters.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.services.JwtAuthenticationService;
import com.tarcinapp.entitypersistencegateway.services.SecurityContextBuilder;
import com.tarcinapp.entitypersistencegateway.services.policydata.PolicyDataBuilderRegistry;

import io.jsonwebtoken.Claims;
import reactor.core.publisher.Mono;

/**
 * Refactored authentication filter that delegates responsibilities to specialized services.
 * 
 * This filter:
 * 1. Authenticates requests using JWT tokens (via JwtAuthenticationService)
 * 2. Builds security context (via SecurityContextBuilder)
 * 3. Prepares policy data (via PolicyDataBuilder implementations)
 * 
 * The filter has been decomposed to follow Single Responsibility Principle,
 * making it easier to test, maintain, and extend with route-specific logic.
 */
@Component
public class AuthenticateRequest extends AbstractGatewayFilterFactory<AuthenticateRequest.Config> {

    @Autowired
    private JwtAuthenticationService jwtAuthenticationService;

    @Autowired
    private SecurityContextBuilder securityContextBuilder;

    @Autowired
    private PolicyDataBuilderRegistry policyDataBuilderRegistry;

    @Value("${app.shortcode:#{tarcinapp}}")
    private String appShortcode;

    private static final String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";
    private static final Logger logger = LogManager.getLogger(AuthenticateRequest.class);

    public AuthenticateRequest() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            logger.debug("Authentication filter started");

            // Initialize security context and policy data
            initializeContexts(exchange);

            // Check if JWT authentication is configured
            if (!jwtAuthenticationService.isConfigured()) {
                logger.warn("RS256 key not configured. Requests won't be authenticated!");
                return chain.filter(exchange);
            }

            return performAuthentication(exchange, chain);
        };
    }

    /**
     * Initializes security context and policy data in exchange attributes
     */
    private void initializeContexts(ServerWebExchange exchange) {
        securityContextBuilder.initializeSecurityContext(exchange);

        PolicyData policyData = new PolicyData();
        policyData.setAppShortcode(this.appShortcode);
        exchange.getAttributes().put(POLICY_INQUIRY_DATA_ATTR, policyData);
    }

    /**
     * Performs JWT authentication and continues the filter chain
     */
    private Mono<Void> performAuthentication(ServerWebExchange exchange, GatewayFilterChain chain) {
        logger.debug("RS256 public key configured. Authenticating request...");

        return jwtAuthenticationService.authenticate(exchange)
                .flatMap(claims -> onAuthenticationSuccess(claims, exchange, chain))
                .onErrorResume(e -> handleAuthenticationError(e, exchange));
    }

    /**
     * Called after successful authentication
     */
    private Mono<Void> onAuthenticationSuccess(Claims claims, ServerWebExchange exchange, 
                                                 GatewayFilterChain chain) {
        logger.debug("Authentication successful for user: " + claims.getSubject());

        // Build security context from JWT claims
        securityContextBuilder.buildFromClaims(claims, exchange);

        // Prepare policy data using the appropriate builder
        PolicyData policyData = exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);
        var policyDataBuilder = policyDataBuilderRegistry.selectBuilder(exchange);

        return policyDataBuilder.buildPolicyData(policyData, exchange, chain);
    }

    /**
     * Handles authentication errors
     */
    private Mono<Void> handleAuthenticationError(Throwable e, ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();

        if (e instanceof WebClientResponseException) {
            WebClientResponseException clientException = (WebClientResponseException) e;
            if (clientException.getStatusCode() == HttpStatus.NOT_FOUND) {
                response.setStatusCode(HttpStatus.NOT_FOUND);
            } else {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
            }
        } else if (e instanceof ResponseStatusException) {
            response.setStatusCode(((ResponseStatusException) e).getStatusCode());
        } else {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            logger.error("Authentication failed", e);
        }

        return response.setComplete();
    }

    public static class Config {
        // Configuration properties can be added here if needed
    }
}
