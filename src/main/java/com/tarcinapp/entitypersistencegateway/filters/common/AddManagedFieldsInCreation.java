package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
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
import com.tarcinapp.entitypersistencegateway.dto.ManagedField;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AddManagedFieldsInCreation extends AbstractRequestPayloadModifierFilterFactory<AddManagedFieldsInCreation.Config, String, String> {

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    @Autowired(required = false)
    Key key;

    Logger logger = LogManager.getLogger(AddManagedFieldsInCreation.class);

    public AddManagedFieldsInCreation() {
        super(Config.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String payload) {
        GatewaySecurityContext gatewaySecurityContext = this.getGatewaySecurityContext(exchange);

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(payload,
                    new TypeReference<Map<String, Object>>() {
                    });

            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());
            String authSubject = gatewaySecurityContext.getAuthSubject();
            List<ManagedField> fieldsToAdd = this.getFieldsToAdd(config);
            /**
             * We used putIfAbsent here because user may be authorized to send custom values
             * for these fields.
             */
            fieldsToAdd.stream().forEach(field -> {

                if (field.equals(ManagedField.CREATION_DATE_TIME))
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), now);

                if (field.equals(ManagedField.LAST_UPDATED_DATE_TIME))
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), now);

                if (authSubject != null) {

                    if (field.equals(ManagedField.OWNER_USERS))
                        inboundJsonRequestMap.putIfAbsent(ManagedField.OWNER_USERS.getFieldName(),
                                new String[] { authSubject });

                    if (field.equals(ManagedField.CREATED_BY))
                        inboundJsonRequestMap.putIfAbsent(field.getFieldName(), authSubject);

                    if (field.equals(ManagedField.LAST_UPDATED_BY))
                        inboundJsonRequestMap.putIfAbsent(field.getFieldName(), authSubject);
                }
            });

            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

            return Mono.just(outboundJsonRequestStr);
        } catch (JsonMappingException e) {
            return Mono.error(e);
        } catch (JsonProcessingException e) {
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
            fieldsToAdd = config.getIncludeFields().stream().map(ManagedField::valueOf).collect(Collectors.toList());

        // if we reach here we only have exclude fields is configured
        else
            fieldsToAdd = config.getExcludeFields().stream().map(ManagedField::valueOf).collect(Collectors.toList());

        return fieldsToAdd;
    }

    private GatewaySecurityContext getGatewaySecurityContext(ServerWebExchange exchange) {
        return (GatewaySecurityContext) exchange.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);
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