package com.tarcinapp.entitypersistencegateway.filters.common;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Replace operations requires the record's all parameters. If a parameter does
 * not exist in payload, backend removes it from the record.
 * 
 * There are some cases where user is unauthorized to send managed fields:
 * createdBy, lastUpdatedBy, creationDateTime, lastUpdatedDateTime
 * 
 * To not to lose these parameters, this filter taking values from the original
 * record and modifies the request payload.
 * 
 * If user is authorized to send values for any of these fields, user's value is
 * used.
 */
@Component
public class AddManagedFieldsFromOriginalToPayloadInReplace
        extends AbstractGatewayFilterFactory<AddManagedFieldsFromOriginalToPayloadInReplace.Config> {

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

            return this.filter(config, exchange, chain);
        };
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> this
                        .processPayload(config, exchange1, inboundJsonRequestStr));

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    private Mono<String> processPayload(Config config, ServerWebExchange exchange, String inboundJsonRequestStr) {
        GatewaySecurityContext gatewaySecurityContext = this.getGatewaySecurityContext(exchange);

        try {
            AnyRecordBase originalRecord = this.getOriginalRecord(exchange);

            if (originalRecord == null)
                return Mono.just(inboundJsonRequestStr);

            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                    new TypeReference<Map<String, Object>>() {
                    });

            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());
            String creationDateTime = DateTimeFormatter.ISO_INSTANT.format(originalRecord.getCreationDateTime());
            String authSubject = gatewaySecurityContext.getAuthSubject();
            List<ManagedField> fieldsToAdd = this.getFieldsToAdd(config);
            
            /**
             * We used putIfAbsent here because user may be authorized to send custom values
             * for these fields.
             */
            fieldsToAdd.stream().forEach(field -> {

                if (field.equals(ManagedField.CREATED_BY) && authSubject != null)
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), originalRecord.getCreatedBy());

                if (field.equals(ManagedField.LAST_UPDATED_BY) && authSubject != null)
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), authSubject);

                if (field.equals(ManagedField.CREATION_DATE_TIME))
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), creationDateTime);

                if (field.equals(ManagedField.LAST_UPDATED_DATE_TIME))
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), now);
            });

            String outboundJsonRequestStr = objectMapper.writeValueAsString(inboundJsonRequestMap);

            return Mono.just(outboundJsonRequestStr);
        } catch (JsonMappingException e) {
            return Mono.error(e);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        } catch (CloneNotSupportedException e) {
            return Mono.error(e);
        }
    }

    private List<ManagedField> getFieldsToAdd(Config config) {
        List<ManagedField> fieldsToAdd = new ArrayList<ManagedField>();

        // if no include or exclude array specified, this filter adds all managed fields
        if (config.getIncludeFields() == null && config.getIncludeFields() == null)
            fieldsToAdd = Arrays.asList(ManagedField.values());

        // if we have includeFields, excludeFields is ignored
        else if (config.getExcludeFields() == null)
            fieldsToAdd = config.getIncludeFields().stream().map(ManagedField::valueOf)
                    .collect(Collectors.toList());

        // if we reach here we only have exclude fields is configured
        else
            fieldsToAdd = config.getExcludeFields().stream().map(ManagedField::valueOf)
                    .collect(Collectors.toList());
        
        return fieldsToAdd;
    }

    private GatewaySecurityContext getGatewaySecurityContext(ServerWebExchange exchange) {
        return (GatewaySecurityContext) exchange.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);
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
        List<String> includeFields;
        List<String> excludeFields;

        public List<String> getIncludeFields() {
            return this.includeFields;
        }

        public void setIncludeFields(List<String> includeFields) {
            this.includeFields = includeFields;
        }

        public List<String> getExcludeFields() {
            return this.excludeFields;
        }

        public void setExcludeFields(List<String> excludeFields) {
            this.excludeFields = excludeFields;
        }
    }
}
