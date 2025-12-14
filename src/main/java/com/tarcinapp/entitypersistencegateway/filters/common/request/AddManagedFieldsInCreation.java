package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            
            /*
             * We used putIfAbsent here because user may be authorized to send custom values
             * for these fields.
             */
            for (ManagedField field : fieldsToAdd) {
                
                if (field == ManagedField.CREATION_DATE_TIME || field == ManagedField.LAST_UPDATED_DATE_TIME) {
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

        } catch (Exception e) {
            log.error("Error while adding managed fields to payload", e);
            return Mono.error(e);
        }
    }

    private List<ManagedField> getFieldsToAdd(Config config) {

        if (config.getIncludeFields() == null && config.getExcludeFields() == null) {
            return Arrays.asList(ManagedField.values());
        }

        if (config.getIncludeFields() != null) {
            return config.getIncludeFields().stream()
                .map(ManagedField::valueOf)
                .collect(Collectors.toList());
        }

        List<String> excludeFieldNames = config.getExcludeFields();
        return Arrays.stream(ManagedField.values())
            .filter(field -> !excludeFieldNames.contains(field.name()))
            .collect(Collectors.toList());
    }

    private GatewaySecurityContext getGatewaySecurityContext(ServerWebExchange exchange) {
        return exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
    }

    @Data
    public static class Config {
        List<String> includeFields;
        List<String> excludeFields;
    }
}