package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.services.JwtAuthenticationService;

import org.springframework.beans.factory.annotation.Autowired;
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
import lombok.extern.slf4j.Slf4j;

/**
 * This filter is meant to be used in replaceById operations. replaceById
 * operations, as name suggests, replaces the target object with the object
 * given in payload.
 * 
 * However, there are some cases where user is not authorized to see certain
 * fields (e.g. validFromDateTime, validUntilDateTime, visibility). We call
 * those fields as forbiddenFields.
 * 
 * In such cases, users cannot change the value of those fields. Their update
 * attempts will be rejected with '401 - Unauthorized' error code.
 * 
 * Nevertheless, if a user is authorized to update a forbidden field (with
 * required roles), then he will be able to change the value for that field
 * according to the policy.
 * 
 * To make replaceById operations available for those users, this filter adds
 * values for the forbidden fields from the original record.
 * 
 * Note: The payload that the user provides must still be subjected to the
 * authorization logic. Please apply this filter after the authorization filter.
 */
@Component
@Slf4j
public class AddForbiddenFieldsFromOriginalToPayloadInReplace
        extends AbstractGatewayFilterFactory<AddForbiddenFieldsFromOriginalToPayloadInReplace.Config> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    @Autowired
    IAuthorizationClient authorizationClient;

    @Autowired
    private JwtAuthenticationService jwtAuthenticationService;

    private final ObjectMapper objectMapper;

    public AddForbiddenFieldsFromOriginalToPayloadInReplace(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            log.debug("AddForbiddenFieldsFromOriginalToPayloadInReplace filter is started. Policy name: "
                    + config.getPolicyName());


            // Check if JWT authentication is configured
            if (!jwtAuthenticationService.isConfigured()) {
                log.warn("RS256 key is not configured. We can't query for forbidden fields. ");
                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain)
                .onErrorResume(e -> {
                    log.error("Error in AddForbiddenFieldsFromOriginalToPayloadInReplace filter", e);

                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

                    return response.setComplete();
                });
        };
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        PolicyData policyInquiryData;

        try {
            policyInquiryData = getPolicyInquriyData(exchange);
        } catch (CloneNotSupportedException e) {
            return Mono.error(e);
        }

        policyInquiryData.setPolicyName(config.getPolicyName());

        return this.authorizationClient.executePolicy(policyInquiryData, PolicyResponse.class).flatMap(pr -> {

            if (pr.fields.size() > 0)
                return this.takeFieldsFromTheOriginalRecord(pr.fields, exchange, chain);

            log.debug("No field found as forbidden. Exiting from filter.");
            return chain.filter(exchange);
        });

    }

    private Mono<Void> takeFieldsFromTheOriginalRecord(ArrayList<String> fields, ServerWebExchange exchange,
            GatewayFilterChain chain) {

        Map<String, Object> originalRecord;

        try {
            originalRecord = this.getOriginalRecord(exchange);
        } catch (CloneNotSupportedException e) {
            return Mono.error(e);
        }

        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, payloadStr) -> {

                    try {
                        Map<String, Object> payloadRecord = objectMapper.readValue(payloadStr, MAP_TYPE_REFERENCE);

                        // Copy forbidden fields from original record to payload
                        fields.stream()
                            .forEach(field -> {
                                Object propertyValue = originalRecord.get(field);
                                payloadRecord.put(field, propertyValue);
                            });
                        
                        String outboundJsonRequestStr = objectMapper.writeValueAsString(payloadRecord);

                        return Mono.just(outboundJsonRequestStr);
                    } catch (JsonMappingException e) {
                        return Mono.error(e);
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
            });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    /**
     * A shorthand method for accessing the original record from the policy data.
     * 
     * @param exchange
     * @return
     * @throws CloneNotSupportedException
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getOriginalRecord(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
        return (Map<String, Object>) policyInquiryData.getOriginalRecord();
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * 
     * @param exchange
     * @return
     * @throws CloneNotSupportedException
     */
    private PolicyData getPolicyInquriyData(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
        return (PolicyData) policyInquiryData.clone();
    }

    /**
     * This POJO is used to map PDP response of inquiry of forbidden fields.
     */
    private static class PolicyResponse {
        
        @JsonProperty(value="which_fields_forbidden_for_update")
        ArrayList<String> fields;

        public ArrayList<String> getFields() {
            return this.fields;
        }

        public void setFields(ArrayList<String> fields) {
            this.fields = fields;
        }
    }

    public static class Config {
        String policyName;

        public String getPolicyName() {
            return this.policyName;
        }

        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }
    }

}
