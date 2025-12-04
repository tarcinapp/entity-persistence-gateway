package com.tarcinapp.entitypersistencegateway.filters.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.services.JwtAuthenticationService;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizeRequest extends AbstractGatewayFilterFactory<AuthorizeRequest.Config> {

    @Autowired
    private JwtAuthenticationService jwtAuthenticationService;

    @Autowired
    IAuthorizationClient authorizationClient;

    private final ObjectMapper objectMapper;

    public AuthorizeRequest(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            log.debug("Authorization filter is started. Policy name: {}", config.getPolicyName());

            // If auth provider is not configured, the firewall is open; allow the request
            if (!jwtAuthenticationService.isConfigured()) {
                log.warn("Authentication provider is not configured. This request won't be authorized.");
                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain);
        };
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        PolicyData policyInquiryData;

        try {
            policyInquiryData = getPolicyInquriyData(exchange);
        } catch (CloneNotSupportedException e) {
            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Policy data cloning failed"));
        }

        policyInquiryData.setPolicyName(config.getPolicyName());

        return this.executePolicy(policyInquiryData)
            .flatMap(authorized -> {

                if (Boolean.TRUE.equals(authorized)) {
                    log.debug("PEP authorized the request.");
                    return chain.filter(exchange);
                }
                                    
                log.debug("PEP denied the request. Returning 403 Forbidden.");
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied by Policy"));
            })
            .onErrorResume(e -> {
                // If the error is already a ResponseStatusException (e.g., 504 Timeout, 403 Forbidden)
                // forward it to the client as is.
                if (e instanceof ResponseStatusException) {
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(((ResponseStatusException) e).getStatusCode());
                    return response.setComplete();
                }

                // For other unexpected errors, return 500 Internal Server Error
                log.error("Unexpected authorization error: {}", e.getMessage(), e);
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return response.setComplete();
            });
    }

    private Mono<Boolean> executePolicy(PolicyData policyData) {

        if (log.isDebugEnabled()) {
            
            try {
                String policyDataStr = objectMapper.writeValueAsString(policyData);
                log.trace("Policy data prepared: {}", policyDataStr);
            } catch (JsonProcessingException e) {
                log.debug("Unable to serialize policy data to JSON string.", e);
            }
        }

        log.debug("Sending policy data to the PEP.");

        return authorizationClient.executePolicy(policyData)
            .map(result -> result.isAllow());
    }

    private PolicyData getPolicyInquriyData(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
        if (policyInquiryData == null) {
            // Should not happen if AuthenticateRequest runs first, but defensive coding :)
            throw new CloneNotSupportedException("Policy data not found in attributes");
        }
        return (PolicyData) policyInquiryData.clone();
    }

    @Data
    public static class Config {
        private String policyName;
    }
}