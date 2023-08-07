package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import reactor.core.publisher.Mono;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Component
public class DropFieldsFromResponseForFindEntities extends
        AbstractPolicyAwareResponsePayloadModifierFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsFromResponseForFindEntities.PolicyResponse, String, String> {

    private Logger logger = LogManager.getLogger(DropFieldsFromResponseForFindEntities.class);

    public DropFieldsFromResponseForFindEntities() {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyRequestPayload(PolicyEvaluatingFilterConfig config, ServerWebExchange exchange,
            PolicyResponse policyResult, String payload) {

        if (policyResult.getFields().size() == 0) {
            logger.debug("There is no field going to be hidden from the response.");
            return Mono.just(payload);
        }

        logger.debug("Following fields going to be hidden by the response drop filter: " + policyResult.getFields());

        ObjectMapper objectMapper = new ObjectMapper();

        // We need to register JavaTimeModule explicitly to be able to
        // serialize/deserialize java.time.* classes
        objectMapper.registerModule(new JavaTimeModule());

        try {
            List<Map<String, Object>> payloadMap = objectMapper.readValue(payload,
                    new TypeReference<List<Map<String, Object>>>() {
                    });

            policyResult.fields.forEach(f -> {
                payloadMap.forEach(m -> {
                    m.remove(f);
                });

                logger.debug("Field '" + f + "' is dropped from all the items in the response.");
            });

            String modifiedPayload = objectMapper.writeValueAsString(payloadMap);

            logger.debug("Modified payload: " + modifiedPayload);

            return Mono.just(modifiedPayload);
        } catch (JsonMappingException e) {
            return Mono.error(e);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    static class PolicyResponse {
        @JsonProperty(value = "which_fields_forbidden_for_finding")
        ArrayList<String> fields;

        public ArrayList<String> getFields() {
            return this.fields;
        }

        public void setFields(ArrayList<String> fields) {
            this.fields = fields;
        }
    }
}
