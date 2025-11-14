package com.tarcinapp.entitypersistencegateway.services.policydata;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.services.OriginalRecordFetcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;
import java.util.Map;

/**
 * Policy data builder for reaction creation endpoints.
 * Fetches the target resource (entity or list) and injects its managed fields into _relationMetadata.
 */
@Component("policyDataBuilderForReactionCreation")
public class PolicyDataBuilderForReactionCreation implements PolicyDataBuilder {
    @Autowired
    private OriginalRecordFetcher originalRecordFetcher;

    @Autowired
    private PayloadExtractor payloadExtractor;

    private static final Logger logger = LogManager.getLogger(PolicyDataBuilderForReactionCreation.class);

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        GatewaySecurityContext securityContext = exchange.getAttribute("GatewaySecurityContext");
        policyData.setHttpMethod(request.getMethod());
        policyData.setEncodedJwt(securityContext != null ? securityContext.getEncodedJwt() : null);
        policyData.setQueryParams(request.getQueryParams());
        policyData.setRequestPath(request.getPath());

        // Step 1: Extract and attach payload
        return payloadExtractor.extractAndAttachPayload(policyData, exchange, chain)
            .then(Mono.defer(() -> {
                AnyRecordBase payload = policyData.getRequestPayload();
                if (payload == null) {
                    logger.warn("No payload found in request for reaction creation");
                    return chain.filter(exchange);
                }
                // Step 2: Determine target type and id
                String targetId = null;
                String targetType = null;
                Map<String, Object> properties = payload.getCustomFields();
                if (properties != null && properties.containsKey("_entityId")) {
                    targetId = String.valueOf(properties.get("_entityId"));
                    targetType = "entities";
                } else if (properties != null && properties.containsKey("_listId")) {
                    targetId = String.valueOf(properties.get("_listId"));
                    targetType = "lists";
                }
                if (targetId == null || targetType == null) {
                    logger.warn("Reaction payload missing _entityId or _listId");
                    return chain.filter(exchange);
                }
                // Step 3: Fetch the target resource and inject its managed fields into _relationMetadata
                return originalRecordFetcher.fetchByTypeAndId(targetType, targetId)
                    .flatMap(targetResource -> {
                        if (targetResource != null && payload.getCustomFields() != null) {
                            payload.getCustomFields().put("_relationMetadata", targetResource);
                        }
                        return chain.filter(exchange);
                    });
            }));
    }
}
