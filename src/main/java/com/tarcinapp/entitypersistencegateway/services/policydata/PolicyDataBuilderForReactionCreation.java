package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Policy data builder for reaction creation endpoints.
 * Fetches the target resource (entity or list) from _entityId or _listId in the payload,
 * and embeds its managed fields into _relationMetadata within the request payload.
 */
@Slf4j
@Component("policyDataBuilderForReactionCreation")
public class PolicyDataBuilderForReactionCreation extends AbstractPolicyDataBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    @Autowired
    private IBackendClientBase backendBaseClient;

    private final ObjectMapper objectMapper;

    public PolicyDataBuilderForReactionCreation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);
        
        ServerHttpRequest request = exchange.getRequest();

        log.debug("Building policy data for reaction creation: " + request.getMethod() + " " + request.getPath());

        // Extract payload and fetch target resource, then inject _relationMetadata
        return extractPayloadAndInjectRelationMetadata(policyData, exchange, chain);
    }

    /**
     * Extracts the request payload, fetches the target resource (entity/list),
     * and injects its managed fields into _relationMetadata.
     */
    private Mono<Void> extractPayloadAndInjectRelationMetadata(PolicyData policyData, ServerWebExchange exchange,
                                                                 GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = 
            new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                    try {
                        Map<String, Object> payloadJSON = objectMapper.readValue(
                            inboundJsonRequestStr,
                            MAP_TYPE_REFERENCE
                        );

                        // Determine target resource ID from payload
                        String targetResourceId = null;
                        String resourceType = null;
                        
                        if (payloadJSON.containsKey("_entityId")) {
                            targetResourceId = (String) payloadJSON.get("_entityId");
                            resourceType = "entities";
                        } else if (payloadJSON.containsKey("_listId")) {
                            targetResourceId = (String) payloadJSON.get("_listId");
                            resourceType = "lists";
                        }

                        if (targetResourceId == null || resourceType == null) {
                            log.error("Reaction payload missing _entityId or _listId");
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Reaction must specify either _entityId or _listId");
                        }

                        final String finalResourceType = resourceType;
                        final String finalTargetResourceId = targetResourceId;

                        // Fetch the target resource and inject its metadata
                        return fetchTargetResourceAndInjectMetadata(payloadJSON, finalResourceType, finalTargetResourceId)
                            .flatMap(updatedPayload -> {
                                try {
                                    // Set the payload in policy data
                                    AnyRecordBase recordBase = prepareRecordBaseFromPayload(updatedPayload);
                                    policyData.setRequestPayload(recordBase);

                                    // Return the updated JSON string
                                    String updatedJsonStr = objectMapper.writeValueAsString(updatedPayload);
                                    log.debug("Injected _relationMetadata into reaction payload");
                                    return Mono.just(updatedJsonStr);
                                } catch (JsonProcessingException e) {
                                    log.error("Failed to serialize updated payload", e);
                                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to process request payload");
                                }
                            });
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse JSON payload", e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Invalid JSON in request body");
                    }
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    /**
     * Fetches the target resource and injects its managed fields into _relationMetadata
     */
    private Mono<Map<String, Object>> fetchTargetResourceAndInjectMetadata(Map<String, Object> payloadJSON,
                                                                             String resourceType,
                                                                             String targetResourceId) {
        String targetResourcePath = "/" + resourceType + "/" + targetResourceId;
        log.debug("Fetching target resource: " + targetResourcePath);

        return backendBaseClient.get(targetResourcePath, AnyRecordBase.class)
            .map(targetResource -> {
                // Create _relationMetadata with managed fields from target resource
                Map<String, Object> relationMetadata = new HashMap<>();
                relationMetadata.put("_id", targetResource.get_id());
                relationMetadata.put("_visibility", targetResource.get_visibility());
                relationMetadata.put("_ownerUsers", targetResource.get_ownerUsers());
                relationMetadata.put("_ownerGroups", targetResource.get_ownerGroups());
                relationMetadata.put("_viewerUsers", targetResource.get_viewerUsers());
                relationMetadata.put("_viewerGroups", targetResource.get_viewerGroups());
                
                if (targetResource.get_validFromDateTime() != null) {
                    // Format as ISO-8601 without timezone brackets: 2025-10-03T08:32:23.558Z
                    String formattedDate = targetResource.get_validFromDateTime()
                        .withZoneSameInstant(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
                    relationMetadata.put("_validFromDateTime", formattedDate);
                }
                if (targetResource.get_validUntilDateTime() != null) {
                    // Format as ISO-8601 without timezone brackets: 2025-10-03T08:32:23.558Z
                    String formattedDate = targetResource.get_validUntilDateTime()
                        .withZoneSameInstant(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
                    relationMetadata.put("_validUntilDateTime", formattedDate);
                }

                // Inject _relationMetadata into payload
                payloadJSON.put("_relationMetadata", relationMetadata);

                return payloadJSON;
            })
            .onErrorMap(e -> {
                log.error("Failed to fetch target resource: " + targetResourcePath, e);
                return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not fetch target resource for reaction: " + targetResourceId, e);
            });
    }

    /**
     * Prepares a record base object from the request payload.
     * Extracts managed fields including _relationMetadata for policy evaluation.
     */
    private AnyRecordBase prepareRecordBaseFromPayload(Map<String, Object> payloadJSON) {
        AnyRecordBase recordBase = new AnyRecordBase();

        try {
            // Extract managed fields from payload
            recordBase.set_id((String) payloadJSON.get("_id"));
            recordBase.set_kind((String) payloadJSON.get("_kind"));
            recordBase.set_visibility((String) payloadJSON.get("_visibility"));
            recordBase.set_ownerUsers((java.util.List<String>) payloadJSON.get("_ownerUsers"));
            recordBase.set_ownerGroups((java.util.List<String>) payloadJSON.get("_ownerGroups"));

            // Extract _relationMetadata if present
            @SuppressWarnings("unchecked")
            Map<String, Object> relationMetadata = (Map<String, Object>) payloadJSON.get("_relationMetadata");
            if (relationMetadata != null) {
                recordBase.set_relationMetadata(relationMetadata);
            }

            return recordBase;
        } catch (ClassCastException e) {
            log.error("Invalid field type in request payload", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid field type in request payload", e);
        }
    }
}
