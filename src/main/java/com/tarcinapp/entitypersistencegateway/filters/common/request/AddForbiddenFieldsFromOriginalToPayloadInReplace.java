package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This filter is used in replaceById (PUT) operations.
 * It merges "Forbidden Fields" (which the user cannot see or update) from the original record
 * back into the new payload. This prevents "Blind Updates" from accidentally deleting
 * sensitive fields that the user had no access to.
 *
 * It uses the eagerly loaded 'ForbiddenFieldsLibrary' from Exchange Attributes.
 */
@Component
@Slf4j
public class AddForbiddenFieldsFromOriginalToPayloadInReplace
        extends AbstractGatewayFilterFactory<AddForbiddenFieldsFromOriginalToPayloadInReplace.Config> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public AddForbiddenFieldsFromOriginalToPayloadInReplace(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            
            // 1. Retrieve the Forbidden Fields Library (Loaded by FetchForbiddenFields filter)
            ForbiddenFieldsLibrary library = exchange.getAttribute(FetchForbiddenFieldsGatewayFilterFactory.GATEWAY_CONTEXT_FORBIDDEN_FIELDS);

            if (library == null || library.getRules() == null || library.getRules().isEmpty()) {
                log.trace("No forbidden field rules found in context. Skipping blind update merge.");
                return chain.filter(exchange);
            }

            // 2. Retrieve Original Record from PolicyData (Populated by AuthenticateRequest -> PolicyDataBuilder)
            PolicyData policyData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
            
            if (policyData == null || policyData.getOriginalRecord() == null) {
                log.trace("No original record found in PolicyData. Skipping blind update merge.");
                return chain.filter(exchange);
            }

            Map<String, Object> originalRecord;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) policyData.getOriginalRecord();
                originalRecord = casted;
            } catch (ClassCastException e) {
                log.warn("Original record is not a Map. Skipping blind update merge.");
                return chain.filter(exchange);
            }

            // 3. Identify Context (_recordType, _kind)
            String recordType = (String) originalRecord.get("_recordType");
            String kind = (String) originalRecord.get("_kind");
            
            if (recordType == null) {
                 return chain.filter(exchange);
            }

            // 4. Resolve Forbidden Fields for this record
            String ruleKey = normalizeRecordType(recordType);
            List<String> forbiddenFields = library.resolveForbiddenFields(ruleKey, kind);

            if (forbiddenFields == null || forbiddenFields.isEmpty()) {
                log.trace("No forbidden fields defined for this record type. Skipping.");
                return chain.filter(exchange);
            }

            log.debug("Merging forbidden fields {} from original record into payload for blind update protection.", forbiddenFields);

            // 5. Execute Merge Logic
            return mergeForbiddenFields(exchange, chain, originalRecord, forbiddenFields);
        };
    }

    /**
     * rewrites the request body by taking values from the original record for the forbidden fields.
     */
    private Mono<Void> mergeForbiddenFields(ServerWebExchange exchange, GatewayFilterChain chain,
                                            Map<String, Object> originalRecord, List<String> forbiddenFields) {
        
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, payloadStr) -> {
                    try {
                        // Deserialize Payload
                        Map<String, Object> payloadRecord = objectMapper.readValue(payloadStr, MAP_TYPE_REFERENCE);

                        // Copy values from Original Record -> Payload Record
                        for (String fieldPath : forbiddenFields) {
                             Object originalValue = getNestedValue(originalRecord, fieldPath);
                             
                             // Only restore if the value existed in the original record
                             // (If it was null or missing originally, we don't need to force it, 
                             // though typically we restore whatever state it was in)
                             if (originalValue != null) {
                                 setNestedValue(payloadRecord, fieldPath, originalValue);
                             }
                        }

                        // Serialize back to String
                        return Mono.just(objectMapper.writeValueAsString(payloadRecord));

                    } catch (JsonProcessingException e) {
                        log.error("JSON Error in blind update filter: {}", e.getMessage());
                        return Mono.error(new RuntimeException("JSON processing error in blind update filter"));
                    } catch (Exception e) {
                        log.error("Unexpected error in blind update filter", e);
                        return Mono.error(e);
                    }
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain)
                .onErrorResume(e -> {
                    log.error("Failed to merge forbidden fields", e);
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    return response.setComplete();
                });
    }

    // --- Helpers for Nested Access (e.g. "metadata.privateKey") ---

    private Object getNestedValue(Map<String, Object> map, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object val = current.get(parts[i]);
            if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nextMap = (Map<String, Object>) val;
                current = nextMap;
            } else {
                return null; // Path doesn't exist or is broken
            }
        }
        return current.get(parts[parts.length - 1]);
    }

    private void setNestedValue(Map<String, Object> map, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            
            // Create intermediate maps if they don't exist
            current.computeIfAbsent(part, k -> new HashMap<String, Object>());
            
            Object val = current.get(part);
            if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nextMap = (Map<String, Object>) val;
                current = nextMap;
            } else {
                // Conflict: Path implies a map, but found a primitive/list.
                // We cannot safely overwrite without potentially breaking schema.
                // Log and skip.
                log.warn("Cannot set nested value for path '{}'. Field '{}' is not a Map.", path, part);
                return; 
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    private String normalizeRecordType(String recordType) {
        if (recordType == null) return null;
        switch (recordType) {
            case "entity": return "entities";
            case "list": return "lists";
            case "relation": return "relations";
            case "entityReaction": return "entityReactions";
            case "listReaction": return "listReactions";
            default: return recordType;
        }
    }

    public static class Config {

    }
}