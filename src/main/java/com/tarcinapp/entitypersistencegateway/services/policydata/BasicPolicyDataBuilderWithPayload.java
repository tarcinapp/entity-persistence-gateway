package com.tarcinapp.entitypersistencegateway.services.policydata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import reactor.core.publisher.Mono;

/**
 * Basic policy data builder for routes that have request payloads.
 * Handles POST, PUT, PATCH requests by extracting and attaching the payload
 * to the policy data for authorization decisions.
 */
@Component("basicPolicyDataBuilderWithPayload")
public class BasicPolicyDataBuilderWithPayload implements PolicyDataBuilder {

    @Autowired
    private PayloadExtractor payloadExtractor;

    private static final Logger logger = LogManager.getLogger(BasicPolicyDataBuilderWithPayload.class);

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

        logger.debug("Building policy data with payload for " + request.getMethod() + " " + request.getPath());

        // Extract payload and attach to policy data
        return payloadExtractor.extractAndAttachPayload(policyData, exchange, chain);
    }
}
