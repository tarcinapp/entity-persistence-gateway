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
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
 * and embeds its managed fields into _relationMetadata within the policy data for authorization.
 * This builder does NOT modify the request payload - it only builds policy data.
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

        // Extract payload, fetch target resource, build policy data (without modifying payload)
        return extractPayloadAndBuildPolicyData(policyData, exchange, chain);
    }

    /**
     * Extracts the request payload, fetches the target resource (entity/list),
     * and builds policy data with _relationMetadata for authorization.
     * The request payload is passed through unchanged.
     */
    private Mono<Void> extractPayloadAndBuildPolicyData(PolicyData policyData, ServerWebExchange exchange,
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

                        // Fetch the target resource and build policy data
                        return fetchTargetResourceAndBuildPolicyData(policyData, payloadJSON, finalResourceType, finalTargetResourceId)
                            .thenReturn(inboundJsonRequestStr); // Return original payload unchanged
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse JSON payload", e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Invalid JSON in request body");
                    }
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    /**
     * Fetches the target resource and builds policy data with _relationMetadata
     */
    private Mono<Void> fetchTargetResourceAndBuildPolicyData(PolicyData policyData,
                                                              Map<String, Object> payloadJSON,
                                                              String resourceType,
                                                              String targetResourceId) {
        String targetResourcePath = "/" + resourceType + "/" + targetResourceId;
        log.debug("Fetching target resource: " + targetResourcePath);

        return backendBaseClient.get(targetResourcePath, AnyRecordBase.class)
            .doOnNext(targetResource -> {
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

                // Build policy data with request payload and relation metadata
                AnyRecordBase recordBase = prepareRecordBaseFromPayload(payloadJSON);
                recordBase.set_relationMetadata(relationMetadata);
                policyData.setRequestPayload(recordBase);
                
                log.debug("Built policy data with _relationMetadata for reaction");
            })
            .onErrorMap(
                WebClientResponseException.NotFound.class,
                e -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Target resource not found: " + targetResourcePath, e))
            .onErrorMap(e -> {
                if (e instanceof ResponseStatusException) {
                    return e;
                }
                log.error("Failed to fetch target resource: " + targetResourcePath, e);
                return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not fetch target resource for reaction: " + targetResourceId, e);
            })
            .then();
    }

    /**
     * Prepares a record base object from the request payload.
     * Extracts managed fields for policy evaluation.
     */
    @SuppressWarnings("unchecked")
    private AnyRecordBase prepareRecordBaseFromPayload(Map<String, Object> payloadJSON) {
        AnyRecordBase recordBase = new AnyRecordBase();

        try {
            // Extract managed fields from payload
            recordBase.set_id((String) payloadJSON.get("_id"));
            recordBase.set_kind((String) payloadJSON.get("_kind"));
            recordBase.set_visibility((String) payloadJSON.get("_visibility"));
            recordBase.set_ownerUsers((java.util.List<String>) payloadJSON.get("_ownerUsers"));
            recordBase.set_ownerGroups((java.util.List<String>) payloadJSON.get("_ownerGroups"));

            return recordBase;
        } catch (ClassCastException e) {
            log.error("Invalid field type in request payload", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid field type in request payload", e);
        }
    }
}
