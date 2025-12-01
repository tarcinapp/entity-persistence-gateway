package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.services.OriginalRecordFetcher;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Basic policy data builder for routes that have request payloads and also
 * require fetching the original record (e.g., updateById, replaceById).
 */
@Slf4j
@Component("basicPolicyDataBuilderWithPayloadAndOriginal")
public class BasicPolicyDataBuilderWithPayloadAndOriginal implements PolicyDataBuilder {

    @Autowired
    private OriginalRecordFetcher originalRecordFetcher;

    @Autowired
    private PayloadExtractor payloadExtractor;

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        // Get security context
        GatewaySecurityContext securityContext = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

        // Populate basic policy data
        policyData.setHttpMethod(request.getMethod());
        policyData.setEncodedJwt(securityContext != null ? securityContext.getEncodedJwt() : null);
        policyData.setQueryParams(request.getQueryParams());
        policyData.setRequestPath(request.getPath());

        log.debug("Building policy data with payload and original for " + request.getMethod() + " " + request.getPath());

        // Fetch original record first, then extract payload
        return originalRecordFetcher.fetchAndAttach(policyData, exchange, recordId)
                .then(payloadExtractor.extractAndAttachPayload(policyData, exchange, chain));
    }
}
