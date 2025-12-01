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
 * Policy data builder for hierarchical data creation (e.g., createEntityChild, createListChild).
 * Fetches the parent record and includes it in originalRecord for authorization decisions.
 */
@Slf4j
@Component("policyDataBuilderWithPayloadAndParent")
public class PolicyDataBuilderWithPayloadAndParent implements PolicyDataBuilder {

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

        log.debug("Building policy data with payload and parent for " + request.getMethod() + " " + request.getPath());

        // Fetch parent record first (it will be in originalRecord), then extract payload
        return originalRecordFetcher.fetchAndAttach(policyData, exchange, recordId)
                .then(payloadExtractor.extractAndAttachPayload(policyData, exchange, chain));
    }
}
