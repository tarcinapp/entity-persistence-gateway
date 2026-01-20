package com.tarcinapp.entitypersistencegateway.filters.common.request;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
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
            // ERROR HANDLING SCOPE:
            // This onErrorResume ONLY catches errors occurring during the Policy Execution (e.g. OPA down, Network error).
            // It does NOT catch errors from downstream filters (chain.filter) because they happen in the flatMap below.
            .onErrorResume(e -> {
                log.error("Authorization check failed (PEP error): {}", e.getMessage(), e);
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Authorization System Failure"));
            })
            .flatMap(authorized -> {
                if (Boolean.TRUE.equals(authorized)) {
                    log.debug("PEP authorized the request.");
                    // Forward to the next filter in the chain.
                    // Errors occurring here or downstream will bubble up to the Global Exception Handler.
                    return chain.filter(exchange);
                }
                
                log.debug("PEP denied the request. Returning 403 Forbidden.");
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied by Policy"));
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
            throw new CloneNotSupportedException("Policy data not found in attributes");
        }
        return (PolicyData) policyInquiryData.clone();
    }

    @Data
    public static class Config {
        private String policyName;
    }
}