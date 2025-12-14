package com.tarcinapp.entitypersistencegateway.services.policydata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Basic policy data builder for routes that have request payloads.
 * Handles POST, PUT, PATCH requests by extracting and attaching the payload
 * to the policy data for authorization decisions.
 */
@Slf4j
@Component("basicPolicyDataBuilderWithPayload")
public class BasicPolicyDataBuilderWithPayload extends AbstractPolicyDataBuilder {

    @Autowired
    private PayloadExtractor payloadExtractor;

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);
        
        ServerHttpRequest request = exchange.getRequest();

        log.debug("Building policy data with payload for " + request.getMethod() + " " + request.getPath());

        // Extract payload and attach to policy data
        return payloadExtractor.extractAndAttachPayload(policyData, exchange, chain);
    }
}
