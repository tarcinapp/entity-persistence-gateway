package com.tarcinapp.entitypersistencegateway.services.policydata;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Basic policy data builder for routes that do not have request payloads
 * and do not require fetching the original record (e.g., findAll, count).
 */
@Slf4j
@Component("basicPolicyDataBuilderWithoutPayloadNoOriginal")
public class BasicPolicyDataBuilderWithoutPayloadNoOriginal implements PolicyDataBuilder {

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Get security context
        GatewaySecurityContext securityContext = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

        // Populate basic policy data
        policyData.setHttpMethod(request.getMethod());
        policyData.setEncodedJwt(securityContext != null ? securityContext.getEncodedJwt() : null);
        policyData.setQueryParams(request.getQueryParams());
        policyData.setRequestPath(request.getPath());

        log.debug("Building policy data without payload and without original for " + request.getMethod() + " " + request.getPath());

        // Simply continue the chain
        return chain.filter(exchange);
    }
}
