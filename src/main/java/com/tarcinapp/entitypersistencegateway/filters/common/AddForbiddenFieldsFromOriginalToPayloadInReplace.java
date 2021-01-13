package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.ArrayList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanWrapperImpl;
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
public class AddForbiddenFieldsFromOriginalToPayloadInReplace
        extends AbstractGatewayFilterFactory<AddForbiddenFieldsFromOriginalToPayloadInReplace.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private Logger logger = LogManager.getLogger(AddForbiddenFieldsFromOriginalToPayloadInReplace.class);

    public AddForbiddenFieldsFromOriginalToPayloadInReplace() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            return this.filter(config, exchange, chain)
                .onErrorResume(e -> {
                    logger.error(e);

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

            logger.debug("No field found as forbidden. Exiting from filter.");
            return chain.filter(exchange);
        });

    }

    private Mono<Void> takeFieldsFromTheOriginalRecord(ArrayList<String> fields, ServerWebExchange exchange,
            GatewayFilterChain chain) {

        AnyRecordBase originalRecord;

        try {
            originalRecord = this.getOriginalRecord(exchange);
        } catch (CloneNotSupportedException e) {
            return Mono.error(e);
        }

        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, payloadStr) -> {

                    try {
                        ObjectMapper objectMapper = new ObjectMapper()
                            .registerModule(new JavaTimeModule());
                        AnyRecordBase payloadRecord = objectMapper.readValue(payloadStr, AnyRecordBase.class);

                        BeanWrapperImpl originalRecordWrapper = new BeanWrapperImpl(originalRecord);
                        BeanWrapperImpl payloadRecordWrapper = new BeanWrapperImpl(payloadRecord);


                        fields.stream()
                            .forEach(field -> {
                                Object propertyValue = originalRecordWrapper.getPropertyValue(field);
                                payloadRecordWrapper.setPropertyValue(field, propertyValue);
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
    private AnyRecordBase getOriginalRecord(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);
        return policyInquiryData.getOriginalRecord();
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * 
     * @param exchange
     * @return
     * @throws CloneNotSupportedException
     */
    private PolicyData getPolicyInquriyData(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);
        return (PolicyData) policyInquiryData.clone();
    }

    /**
     * This POJO is used to map PDP response of inquiry of forbidden fields.
     */
    private static class PolicyResponse {
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
