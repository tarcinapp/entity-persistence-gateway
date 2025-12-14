package com.tarcinapp.entitypersistencegateway.services.policydata;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Basic policy data builder for routes that do not have request payloads
 * and do not require fetching the original record (e.g., findAll, count).
 */
@Slf4j
@Component("basicPolicyDataBuilderWithoutPayloadNoOriginal")
public class BasicPolicyDataBuilderWithoutPayloadNoOriginal extends AbstractPolicyDataBuilder {

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);
        
        ServerHttpRequest request = exchange.getRequest();

        log.debug("Building policy data without payload and without original for " + request.getMethod() + " " + request.getPath());

        // Simply continue the chain
        return chain.filter(exchange);
    }
}
