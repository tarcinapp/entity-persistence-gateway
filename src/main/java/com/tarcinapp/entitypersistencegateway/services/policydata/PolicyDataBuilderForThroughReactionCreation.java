package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Policy data builder for through-reaction creation endpoints
 * (e.g., POST /entities/{entityId}/reactions, POST /lists/{listId}/reactions).
 * 
 * Fetches the parent record (entity or list) from the URL path variable,
 * and embeds it as _relationMetadata within requestPayload for authorization.
 * Sets originalRecord to an empty map.
 */
@Slf4j
@Component("policyDataBuilderForThroughReactionCreation")
public class PolicyDataBuilderForThroughReactionCreation extends AbstractPolicyDataBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    @Autowired
    private IBackendClientBase backendBaseClient;

    private final ObjectMapper objectMapper;

    public PolicyDataBuilderForThroughReactionCreation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);

        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        log.debug("Building policy data for through-reaction creation: {} {}", request.getMethod(), request.getPath());

        // Determine parent resource URL
        String parentResourceUrl = resolveParentResourceUrl(exchange, recordId);

        log.debug("Fetching parent resource from: {}", parentResourceUrl);

        // Fetch parent, then extract payload and combine
        return fetchParentAndBuildPolicyData(policyData, exchange, chain, parentResourceUrl);
    }

    private String resolveParentResourceUrl(ServerWebExchange exchange, String recordId) {
        KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute("KindAliasConfigAttr");

        if (kindAliasConfigAttr != null && kindAliasConfigAttr.isKindAliasConfigured()) {
            String resolvedUrl = kindAliasConfigAttr.getOriginalResourceUrl();
            log.debug("Using kind alias mapped URL for parent: {}", resolvedUrl);
            return resolvedUrl;
        }

        // For generic through routes, extract from request path: /entities/{id}/reactions -> /entities/{id}
        String path = exchange.getRequest().getPath().toString();
        int reactionSegmentIndex = path.indexOf("/reactions");
        if (reactionSegmentIndex > 0) {
            return path.substring(0, reactionSegmentIndex);
        }

        // Fallback: use path up to recordId
        return path.replaceAll("/" + recordId + "/.*", "/" + recordId);
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> fetchParentAndBuildPolicyData(PolicyData policyData, ServerWebExchange exchange,
                                                      GatewayFilterChain chain, String parentResourceUrl) {
        // Fetch parent record as a raw Map to get ALL fields
        return backendBaseClient.get(parentResourceUrl, Object.class)
            .flatMap(parentRecord -> {
                Map<String, Object> parentMap;
                if (parentRecord instanceof Map) {
                    parentMap = (Map<String, Object>) parentRecord;
                } else {
                    parentMap = objectMapper.convertValue(parentRecord, MAP_TYPE_REFERENCE);
                }

                // Now extract payload and combine with parent
                return extractPayloadAndCombine(policyData, exchange, chain, parentMap);
            });
    }

    private Mono<Void> extractPayloadAndCombine(PolicyData policyData, ServerWebExchange exchange,
                                                  GatewayFilterChain chain, Map<String, Object> parentMap) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig =
            new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                    // Build requestPayload with _relationMetadata
                    Map<String, Object> requestPayload = new HashMap<>();
                    requestPayload.put("_relationMetadata", parentMap);

                    policyData.setRequestPayload(requestPayload);
                    policyData.setOriginalRecord(new HashMap<>());

                    log.debug("Built policy data with _relationMetadata for through-reaction creation");

                    // Return original payload unchanged
                    return Mono.just(inboundJsonRequestStr);
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }
}
