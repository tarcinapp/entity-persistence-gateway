package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
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
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Replace (PUT) operations require the record's all parameters.
 * This filter handles Managed Fields (_createdBy, _lastUpdatedBy, _ownerUsers etc.) logic using a base class.
 *
 * 1. _createdBy, _createdDateTime:
 * - Strategy: Preserve Original if missing.
 * - If user sent a value (even if different), we ACCEPT it. Because as route has reached to this point, we assume
 *  user is authorized to do so.
 *
 * 2. _ownerUsers:
 * - Strategy: Preserve Original if missing.
 * - If set, we rely on Policy to validate the content of the list.
 *
 * 3. _lastUpdatedBy & _lastUpdatedDateTime:
 * - Strategy: Smart Overwrite.
 * - If missing OR matches original (Pass-through from non-admin), set to 'Me'/'Now'.
 * - If different (Admin override), accept user's value.
 */
@Component
@Slf4j
public class AddManagedFieldsFromOriginalToPayloadInReplace
        extends AbstractRequestPayloadModifierFilterFactory<AddManagedFieldsFromOriginalToPayloadInReplace.Config, String, String> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public AddManagedFieldsFromOriginalToPayloadInReplace(ObjectMapper objectMapper) {
        super(Config.class, String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String inboundJsonRequestStr) {
        GatewaySecurityContext gatewaySecurityContext = this.getGatewaySecurityContext(exchange);

        try {
            Map<String, Object> originalRecord = this.getOriginalRecord(exchange);

            if (originalRecord == null) {
                log.debug("Original record is null, returning payload as is.");
                return Mono.just(inboundJsonRequestStr);
            }

            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr, MAP_TYPE_REFERENCE);

            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());
            // Safe string conversion to avoid ClassCastException
            String createdDateTime = String.valueOf(originalRecord.get("_createdDateTime"));
            String createdBy = String.valueOf(originalRecord.get("_createdBy"));
            String authSubject = gatewaySecurityContext.getAuthSubject();

            List<ManagedField> fieldsToAdd = this.getFieldsToAdd(config);

            fieldsToAdd.forEach(field -> {
                String fieldName = field.getFieldName();

                // 1. CREATED_BY & CREATED_DATE_TIME
                // Strategy: Preserve Original if missing.
                if (field.equals(ManagedField.CREATED_BY)) {
                    inboundJsonRequestMap.putIfAbsent(fieldName, createdBy);
                }
                else if (field.equals(ManagedField.CREATED_DATE_TIME)) {
                    inboundJsonRequestMap.putIfAbsent(fieldName, createdDateTime);
                }

                // 2. OWNER_USERS
                // Strategy: Preserve Original if missing.
                else if (field.equals(ManagedField.OWNER_USERS)) {
                    inboundJsonRequestMap.putIfAbsent(fieldName, originalRecord.get(fieldName));
                }

                // 3. LAST_UPDATED_BY
                // Strategy: Update to Current User if missing OR matches original.
                else if (field.equals(ManagedField.LAST_UPDATED_BY) && authSubject != null) {
                    Object originalValue = originalRecord.get(fieldName);
                    Object incomingValue = inboundJsonRequestMap.get(fieldName);

                    // If missing OR (present AND matches original), we overwrite with current user
                    // This handles the case where non-admins send original value to pass policy check
                    if (!inboundJsonRequestMap.containsKey(fieldName) || Objects.equals(originalValue, incomingValue)) {
                        inboundJsonRequestMap.put(fieldName, authSubject);
                    }
                    // Else: User sent a specific DIFFERENT value, we trust they are Admin.
                }

                // 4. LAST_UPDATED_DATE_TIME
                // Strategy: Update to Now if missing OR matches original.
                else if (field.equals(ManagedField.LAST_UPDATED_DATE_TIME)) {
                    Object originalValue = originalRecord.get(fieldName);
                    Object incomingValue = inboundJsonRequestMap.get(fieldName);

                    // If missing OR (present AND matches original), we overwrite with NOW
                    if (!inboundJsonRequestMap.containsKey(fieldName) || Objects.equals(originalValue, incomingValue)) {
                        inboundJsonRequestMap.put(fieldName, now);
                    }
                    // Else: User sent a specific DIFFERENT value, we trust they are Admin.
                }
            });

            return Mono.just(objectMapper.writeValueAsString(inboundJsonRequestMap));

        } catch (JsonProcessingException e) {
            log.error("JSON processing failed", e);
            return Mono.error(e);
        } catch (CloneNotSupportedException e) {
            log.error("Cloning policy data failed", e);
            return Mono.error(e);
        }
    }

    private List<ManagedField> getFieldsToAdd(Config config) {
        List<ManagedField> allFields = new ArrayList<>(Arrays.asList(ManagedField.values()));

        // Case 1: No config -> Add All
        if (config.getIncludeFields() == null && config.getExcludeFields() == null) {
            return allFields;
        }

        // Case 2: Explicit Include -> Only add these
        if (config.getIncludeFields() != null) {
            return config.getIncludeFields().stream()
                    .map(ManagedField::valueOf)
                    .collect(Collectors.toList());
        }

        // Case 3: Explicit Exclude -> Add All MINUS these
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
        return (GatewaySecurityContext) exchange.getAttributes().get(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOriginalRecord(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
        if (policyInquiryData == null) return null;
        return (Map<String, Object>) policyInquiryData.getOriginalRecord();
    }

    @Data
    public static class Config {
        private List<String> includeFields;
        private List<String> excludeFields;
    }
}