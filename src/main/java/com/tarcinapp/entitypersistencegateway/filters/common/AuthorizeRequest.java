package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AuthorizeRequest extends AbstractGatewayFilterFactory<AuthorizeRequest.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    @Autowired(required = false)
    private Key key;
    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private final ObjectMapper objectMapper;

    public AuthorizeRequest(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {

        // implement in seperate method in order to reduce nesting
        return (exchange, chain) -> {

            log.debug("Authorization filter is started. Policy name: " + config.getPolicyName());

            if (this.key == null) {
                log.warn("RS256 key is not configured. This request won't be authorized.");
                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain)
                .onErrorResume(e -> {
                    log.error("Authorization filter error: " + e.getMessage(), e);
        
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
        
                    return response.setComplete();
            });
        };
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        PolicyData policyInquiryData;

        try {
            policyInquiryData = getPolicyInquriyData(exchange);
        } catch (CloneNotSupportedException e1) {
            return Mono.error(new Exception("An error occured while trying to clone the policy data"));
        }

        policyInquiryData.setPolicyName(config.getPolicyName());

        return this.executePolicy(policyInquiryData)
            .flatMap(result -> {

                if(result.equals(true)) {
                    log.debug("PEP authorized our request.");
                    return chain.filter(exchange);
                }
                                    
                log.debug("PEP did't authorized the request. Throwing unauthorized exception.");

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.UNAUTHORIZED);

                return response.setComplete();
            }).onErrorResume(e -> {
                log.error("Error during policy execution: " + e.getMessage(), e);
    
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
    
                return response.setComplete();
            });
    }

    /**
     * An in-class wrapper on AuthorizationClient's execute policy.
     * The only functionality we add here by wrapping is DEBUG logging.
     * @param policyData
     * @return
     */
    private Mono<Boolean> executePolicy(PolicyData policyData) {

        if (log.isDebugEnabled()) {
            log.debug("Policy data is prepared.");

            try {
                String policyDataStr = objectMapper.writeValueAsString(policyData);
                log.debug("Policy data: {}", policyDataStr);
            } catch (JsonProcessingException e) {
                log.debug("Unable to serialize policy data to JSON string.");
            }
        }

        log.debug("Sending policy data to the PEP.");

        return authorizationClient.executePolicy(policyData)
            .flatMap(result -> {

                if (result.isAllow()) {
                    return Mono.just(Boolean.TRUE);
                }

                return Mono.just(Boolean.FALSE);
        });
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * 
     * @param exchange
     * @return
     * @throws CloneNotSupportedException
     */
    private PolicyData getPolicyInquriyData(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);
        return (PolicyData) policyInquiryData.clone();
    }

    public static class Config {

        String policyName;

        public String getPolicyName() {
            return this.policyName;
        }

        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }

    }
}
