package com.tarcinapp.entitypersistencegateway.filters.common;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;
import com.tarcinapp.entitypersistencegateway.dto.ManagedField;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Replace operations requires the record's all parameters.
 * If a parameter does not exist in payload, backend removes it from the record.
 * 
 * There are some cases where user is unauthorized to send managed fields:
 * createdBy, lastUpdatedBy, creationDateTime, lastUpdatedDateTime
 * 
 * To not to lose these parameters, this filter taking values from the original record
 * and modifies the request payload.
 * 
 * If user is authorized to send values for any of these fields, user's value is used.
 */
@Component
public class AddManagedFieldsFromOriginalToPayloadInReplace extends AbstractGatewayFilterFactory<AddManagedFieldsFromOriginalToPayloadInReplace.Config> {

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    Logger logger = LogManager.getLogger(AddManagedFieldsFromOriginalToPayloadInReplace.class);

    public AddManagedFieldsFromOriginalToPayloadInReplace() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("AddManagedFieldsInReplace filter is started");

            return this.filter(exchange, chain);
        };
    }

    private Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                    GatewaySecurityContext gc = (GatewaySecurityContext) exchange1.getAttributes()
                            .get(GATEWAY_SECURITY_CONTEXT_ATTR);

                    try {
                        AnyRecordBase originalRecord = this.getOriginalRecord(exchange);

                        if (originalRecord == null)
                            return Mono.just(inboundJsonRequestStr);

                        ObjectMapper objectMapper = new ObjectMapper();
                        Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                                new TypeReference<Map<String, Object>>() {
                                });

                        String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());
                        String creationDateTime = DateTimeFormatter.ISO_INSTANT
                                .format(originalRecord.getCreationDateTime());

                        /**
                         * We used putIfAbsent here because user may be authorized to send custom values
                         * for these fields.
                         */
                        inboundJsonRequestMap.putIfAbsent(ManagedField.CREATED_BY.getFieldName(), originalRecord.getCreatedBy());
                        inboundJsonRequestMap.putIfAbsent(ManagedField.LAST_UPDATED_BY.getFieldName(), gc.getAuthSubject());
                        inboundJsonRequestMap.putIfAbsent(ManagedField.CREATION_DATE_TIME.getFieldName(), creationDateTime);
                        inboundJsonRequestMap.putIfAbsent(ManagedField.LAST_UPDATED_DATE_TIME.getFieldName(), now);

                        String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

                        return Mono.just(outboundJsonRequestStr);
                    } catch (JsonMappingException e) {
                        return Mono.error(e);
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    } catch (CloneNotSupportedException e) {
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

    public static class Config {

    }
}
