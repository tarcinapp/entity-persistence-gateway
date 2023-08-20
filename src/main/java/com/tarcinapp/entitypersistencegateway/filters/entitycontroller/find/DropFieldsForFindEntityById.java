package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class DropFieldsForFindEntityById extends
        AbstractPolicyAwareResponsePayloadModifierFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsForFindEntityById.PolicyResponse, String, String> {

    private Logger logger = LogManager.getLogger(DropFieldsForFindEntityById.class);

    public DropFieldsForFindEntityById() {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyResponsePayload(PolicyEvaluatingFilterConfig config, ServerWebExchange exchange,
            PolicyResponse pr, String payload) {

        if (pr.getFields().size() == 0) {
            logger.debug("There is no field going to be hidden from the response.");
            return Mono.just(payload);
        }

        logger.debug("Following fields going to be hidden by the response drop filter: " + pr.getFields());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        try {
            Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });

            pr.getFields().forEach(f -> {
                payloadMap.remove(f);

                logger.debug("Field '" + f + "' is dropped from the response.");
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