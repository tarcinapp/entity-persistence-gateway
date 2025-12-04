package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import reactor.core.publisher.Mono;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DropFieldsForMultiItemResponses extends
        AbstractPolicyAwareResponsePayloadModifierFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsForMultiItemResponses.PolicyResponse, String, String> {

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE_REFERENCE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public DropFieldsForMultiItemResponses(ObjectMapper objectMapper) {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class, String.class, String.class, objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyResponsePayload(PolicyEvaluatingFilterConfig config, ServerWebExchange exchange,
            PolicyResponse policyResult, String payload) {

        if (policyResult.getFields().isEmpty()) {
            log.debug("There is no field going to be hidden from the response.");
            return Mono.just(payload);
        }

        log.debug("Following fields going to be hidden by the response drop filter: " + policyResult.getFields());

        try {
            List<Map<String, Object>> payloadMap = objectMapper.readValue(payload, LIST_MAP_TYPE_REFERENCE);

            policyResult.fields.forEach(f -> {
                payloadMap.forEach(m -> {
                    m.remove(f);
                });

                log.debug("Field '" + f + "' is dropped from all the items in the response.");
            });

            String modifiedPayload = objectMapper.writeValueAsString(payloadMap);

            log.trace("Modified payload: " + modifiedPayload);

            return Mono.just(modifiedPayload);
        } catch (JsonMappingException e) {
            return Mono.error(e);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    @Data
    static class PolicyResponse {
        @JsonProperty(value = "which_fields_forbidden_for_finding")
        ArrayList<String> fields;
    }
}
