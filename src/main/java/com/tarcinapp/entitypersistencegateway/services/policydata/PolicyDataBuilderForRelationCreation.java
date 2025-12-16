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
 * Policy data builder for relation creation endpoint.
 * Fetches both the entity (_entityId) and list (_listId) from the payload in parallel,
 * and maps them to _toMetadata and _fromMetadata in the policy data for authorization.
 * This builder does NOT modify the request payload - it only builds policy data.
 */
@Slf4j
@Component("policyDataBuilderForRelationCreation")
public class PolicyDataBuilderForRelationCreation extends AbstractPolicyDataBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    @Autowired
    private IBackendClientBase backendBaseClient;

    private final ObjectMapper objectMapper;

    public PolicyDataBuilderForRelationCreation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain) {
        populateCommonData(policyData, exchange);
        
        ServerHttpRequest request = exchange.getRequest();

        log.debug("Building policy data for relation creation: " + request.getMethod() + " " + request.getPath());

        // Extract payload, fetch both entity and list, build policy data (without modifying payload)
        return extractPayloadAndBuildPolicyData(policyData, exchange, chain);
    }

    /**
     * Extracts the request payload, fetches both entity and list in parallel,
     * and builds policy data with _toMetadata and _fromMetadata for authorization.
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

                        // Extract entity and list IDs from payload
                        String entityId = (String) payloadJSON.get("_entityId");
                        String listId = (String) payloadJSON.get("_listId");

                        if (entityId == null || listId == null) {
                            log.error("Relation payload missing _entityId or _listId");
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Relation must specify both _entityId and _listId");
                        }

                        // Fetch both resources in parallel and build policy data
                        return fetchRelatedResourcesAndBuildPolicyData(policyData, payloadJSON, entityId, listId)
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
     * Fetches both entity and list in parallel and builds policy data with 
     * _toMetadata and _fromMetadata for authorization.
     */
    private Mono<Void> fetchRelatedResourcesAndBuildPolicyData(PolicyData policyData,
                                                                Map<String, Object> payloadJSON,
                                                                String entityId,
                                                                String listId) {
        String entityPath = "/entities/" + entityId;
        String listPath = "/lists/" + listId;
        
        log.debug("Fetching entity and list in parallel: {} and {}", entityPath, listPath);

        // Fetch both resources in parallel
        Mono<AnyRecordBase> entityMono = backendBaseClient.get(entityPath, AnyRecordBase.class)
            .onErrorMap(e -> {
                log.warn("Failed to fetch entity for relation: " + entityId, e);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Could not fetch entity for relation: " + entityId, e);
            });

        Mono<AnyRecordBase> listMono = backendBaseClient.get(listPath, AnyRecordBase.class)
            .onErrorMap(e -> {
                log.warn("Failed to fetch list for relation: " + listId, e);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Could not fetch list for relation: " + listId, e);
            });

        // Wait for both to complete in parallel
        return Mono.zip(entityMono, listMono)
            .doOnNext(tuple -> {
                AnyRecordBase entity = tuple.getT1();
                AnyRecordBase list = tuple.getT2();

                // Create _toMetadata with managed fields from entity
                Map<String, Object> toMetadata = createMetadataMap(entity);
                
                // Create _fromMetadata with managed fields from list
                Map<String, Object> fromMetadata = createMetadataMap(list);

                // Build policy data with request payload and metadata
                AnyRecordBase recordBase = prepareRecordBaseFromPayload(payloadJSON);
                recordBase.getCustomFields().put("_toMetadata", toMetadata);
                recordBase.getCustomFields().put("_fromMetadata", fromMetadata);
                policyData.setRequestPayload(recordBase);
                
                log.debug("Built policy data with _toMetadata and _fromMetadata for relation");
            })
            .then();
    }

    /**
     * Creates a metadata map from a resource with managed fields
     */
    private Map<String, Object> createMetadataMap(AnyRecordBase resource) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_id", resource.get_id());
        metadata.put("_visibility", resource.get_visibility());
        metadata.put("_ownerUsers", resource.get_ownerUsers());
        metadata.put("_ownerGroups", resource.get_ownerGroups());
        metadata.put("_viewerUsers", resource.get_viewerUsers());
        metadata.put("_viewerGroups", resource.get_viewerGroups());
        
        // Always include validity dates, even if null
        if (resource.get_validFromDateTime() != null) {
            // Format as ISO-8601 without timezone brackets: 2025-10-03T08:32:23.558Z
            String formattedDate = resource.get_validFromDateTime()
                .withZoneSameInstant(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
            metadata.put("_validFromDateTime", formattedDate);
        } else {
            metadata.put("_validFromDateTime", null);
        }
        
        if (resource.get_validUntilDateTime() != null) {
            // Format as ISO-8601 without timezone brackets: 2025-10-03T08:32:23.558Z
            String formattedDate = resource.get_validUntilDateTime()
                .withZoneSameInstant(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
            metadata.put("_validUntilDateTime", formattedDate);
        } else {
            metadata.put("_validUntilDateTime", null);
        }

        return metadata;
    }

    /**
     * Prepares a record base object from the request payload.
     * Preserves all incoming payload data for policy evaluation.
     */
    @SuppressWarnings("unchecked")
    private AnyRecordBase prepareRecordBaseFromPayload(Map<String, Object> payloadJSON) {
        AnyRecordBase recordBase = new AnyRecordBase();

        try {
            // Extract standard managed fields if present for the dedicated fields
            recordBase.set_id((String) payloadJSON.get("_id"));
            recordBase.set_kind((String) payloadJSON.get("_kind"));
            recordBase.set_visibility((String) payloadJSON.get("_visibility"));
            recordBase.set_ownerUsers((java.util.List<String>) payloadJSON.get("_ownerUsers"));
            recordBase.set_ownerGroups((java.util.List<String>) payloadJSON.get("_ownerGroups"));

            // Clone ALL incoming payload data to custom fields
            // (excluding metadata fields which will be added from fetched resources)
            for (Map.Entry<String, Object> entry : payloadJSON.entrySet()) {
                recordBase.getCustomFields().put(entry.getKey(), entry.getValue());
            }

            return recordBase;
        } catch (ClassCastException e) {
            log.error("Invalid field type in request payload", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid field type in request payload", e);
        }
    }
}
