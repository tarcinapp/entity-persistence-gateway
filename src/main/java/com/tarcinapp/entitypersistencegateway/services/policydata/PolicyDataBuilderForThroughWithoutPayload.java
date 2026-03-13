package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
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
 * Policy data builder for through-routes without a request payload (GET/DELETE).
 * 
 * These routes follow the pattern:
 *   GET/DELETE /{controller}/{recordId}/{throughSegment}
 *   e.g. GET /entities/{entityId}/lists
 *        GET /entities/{entityId}/reactions
 *        DELETE /lists/{listId}/reactions
 * 
 * The policy requires the parent entity (identified by recordId) in originalRecord.
 * This builder fetches the parent entity and places it in originalRecord.
 */
@Slf4j
@Component("policyDataBuilderForThroughWithoutPayload")
public class PolicyDataBuilderForThroughWithoutPayload extends AbstractPolicyDataBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    @Autowired
    private IBackendClientBase backendBaseClient;

    private final ObjectMapper objectMapper;

    public PolicyDataBuilderForThroughWithoutPayload(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);

        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        log.debug("Building policy data for through route without payload: {} {}", request.getMethod(), request.getPath());

        String parentResourceUrl = resolveParentResourceUrl(exchange, recordId);

        log.debug("Fetching parent resource from: {}", parentResourceUrl);

        return backendBaseClient.get(parentResourceUrl, Object.class)
                .flatMap(parentRecord -> {
                    Map<String, Object> parentMap;
                    if (parentRecord instanceof Map) {
                        parentMap = (Map<String, Object>) parentRecord;
                    } else {
                        parentMap = objectMapper.convertValue(parentRecord, MAP_TYPE_REFERENCE);
                    }
                    policyData.setOriginalRecord(parentMap);
                    log.debug("Built policy data with parent in originalRecord for through route");
                    return chain.filter(exchange);
                });
    }

    private String resolveParentResourceUrl(ServerWebExchange exchange, String recordId) {
        KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute("KindAliasConfigAttr");

        if (kindAliasConfigAttr != null && kindAliasConfigAttr.isKindAliasConfigured()) {
            String resolvedUrl = kindAliasConfigAttr.getOriginalResourceUrl();
            log.debug("Using kind alias mapped URL for parent: {}", resolvedUrl);
            return resolvedUrl;
        }

        // For generic through routes, extract parent URL by truncating after recordId.
        // Handles: /entities/{id}/lists, /entities/{id}/reactions,
        //          /lists/{id}/entities, /lists/{id}/reactions
        String path = exchange.getRequest().getPath().toString();
        if (recordId != null) {
            int recordIdIndex = path.indexOf("/" + recordId);
            if (recordIdIndex >= 0) {
                return path.substring(0, recordIdIndex + 1 + recordId.length());
            }
        }

        // Fallback
        return path.replaceAll("/" + recordId + "/.*", "/" + recordId);
    }
}
