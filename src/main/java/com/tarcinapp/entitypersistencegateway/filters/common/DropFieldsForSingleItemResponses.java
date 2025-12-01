package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DropFieldsForSingleItemResponses extends
        AbstractPolicyAwareResponsePayloadModifierFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsForSingleItemResponses.PolicyResponse, String, String> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public DropFieldsForSingleItemResponses(ObjectMapper objectMapper) {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class, String.class, String.class, objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyResponsePayload(PolicyEvaluatingFilterConfig config, ServerWebExchange exchange,
            PolicyResponse pr, String payload) {
                
        if (pr.getFields().size() == 0) {
            log.debug("There is no field going to be hidden from the response.");
            return Mono.just(payload);
        }

        log.debug("Following fields going to be hidden by the response drop filter: " + pr.getFields());
        
        try {
            Map<String, Object> payloadMap = objectMapper.readValue(payload, MAP_TYPE_REFERENCE);

            pr.getFields().forEach(f -> {
                payloadMap.remove(f);

                log.debug("Field '" + f + "' is dropped from the response.");
            });

            String modifiedPayload = objectMapper.writeValueAsString(payloadMap);

            return Mono.just(modifiedPayload);
        } catch (JsonMappingException e) {
            return Mono.error(e);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /**
     * This POJO is used to map PDP response of inquiry of forbidden fields.
     */
    static class PolicyResponse {
        @JsonProperty(value="which_fields_forbidden_for_finding")
        ArrayList<String> fields;

        public ArrayList<String> getFields() {
            return this.fields;
        }

        public void setFields(ArrayList<String> fields) {
            this.fields = fields;
        }
    }
}