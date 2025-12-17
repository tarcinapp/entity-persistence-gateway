package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.dto.ManagedField;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AddManagedFieldsInCreation extends AbstractRequestPayloadModifierFilterFactory<AddManagedFieldsInCreation.Config, String, String> {

    private final ObjectMapper objectMapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    public AddManagedFieldsInCreation(ObjectMapper objectMapper) {
        super(Config.class, String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String payload) {
        
        // Defensive check for empty payload
        if (payload == null || payload.isBlank()) {
            payload = "{}";
        }

        GatewaySecurityContext gatewaySecurityContext = this.getGatewaySecurityContext(exchange);
        
        // Auth subject might be null (anonymous requests)
        String authSubject = (gatewaySecurityContext != null) ? gatewaySecurityContext.getAuthSubject() : null;

        try {
            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(payload, MAP_TYPE_REF);
            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());
            List<ManagedField> fieldsToAdd = this.getFieldsToAdd(config);
            
            for (ManagedField field : fieldsToAdd) {
                
                if (field == ManagedField.CREATED_DATE_TIME || field == ManagedField.LAST_UPDATED_DATE_TIME) {
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), now);
                }

                if (authSubject != null) {
                    if (field == ManagedField.OWNER_USERS) {
                        inboundJsonRequestMap.putIfAbsent(ManagedField.OWNER_USERS.getFieldName(),
                                new String[] { authSubject });
                    }

                    if (field == ManagedField.CREATED_BY || field == ManagedField.LAST_UPDATED_BY) {
                        inboundJsonRequestMap.putIfAbsent(field.getFieldName(), authSubject);
                    }
                }
            }

            return Mono.just(objectMapper.writeValueAsString(inboundJsonRequestMap));

        } catch (JsonProcessingException e) {
            log.error("JSON processing failed during creation payload modification", e);
            return Mono.error(e);
        }
    }

    private List<ManagedField> getFieldsToAdd(Config config) {
        List<ManagedField> allFields = new ArrayList<>(Arrays.asList(ManagedField.values()));

        if (config.getIncludeFields() == null && config.getExcludeFields() == null) {
            return allFields;
        }

        if (config.getIncludeFields() != null) {
            return config.getIncludeFields().stream()
                    .map(ManagedField::valueOf)
                    .collect(Collectors.toList());
        }

        if (config.getExcludeFields() != null) {
            List<ManagedField> excluded = config.getExcludeFields().stream()
                    .map(ManagedField::valueOf)
                    .collect(Collectors.toList());
            allFields.removeAll(excluded);
            return allFields;
        }

        return allFields;
    }

    private GatewaySecurityContext getGatewaySecurityContext(ServerWebExchange exchange) {
        return exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
    }

    @Data
    public static class Config {
        private List<String> includeFields;
        private List<String> excludeFields;
    }
}