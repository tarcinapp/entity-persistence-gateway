package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.services.OriginalRecordFetcher;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Basic policy data builder for routes that do not have request payloads.
 * Handles GET, DELETE requests. For requests targeting a specific record (recordId present),
 * fetches the original record for authorization decisions.
 */
@Slf4j
@Component("basicPolicyDataBuilderWithoutPayload")
public class BasicPolicyDataBuilderWithoutPayload extends AbstractPolicyDataBuilder {

    @Autowired
    private OriginalRecordFetcher originalRecordFetcher;

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);
        
        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        log.debug("Building policy data without payload for " + request.getMethod() + " " + request.getPath());

        // If targeting a specific record, fetch the original record for policy evaluation
        if (recordId != null) {
            log.debug("Fetching original record for policy evaluation: " + recordId);
            return originalRecordFetcher.fetchAndAttach(policyData, exchange, recordId)
                    .then(chain.filter(exchange));
        }

        // No record ID, just continue with the filter chain
        return chain.filter(exchange);
    }
}
