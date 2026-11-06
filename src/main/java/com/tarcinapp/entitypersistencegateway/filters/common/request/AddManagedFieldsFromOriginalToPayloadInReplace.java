package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.dto.ManagedField;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;

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

/**
 * Replace (PUT) operations require all record parameters to be present.
 * This filter handles Managed Fields (_createdBy, _lastUpdatedBy, _ownerUsers etc.) for PUT.
 *
 * 1. _createdBy, _createdDateTime:
 *    Strategy: Preserve Original if missing (putIfAbsent).
 *    If caller sent any value, accept it — OPA already authorised it.
 *
 * 2. _ownerUsers:
 *    Strategy: Preserve Original if missing (putIfAbsent).
 *    If set, OPA validates the content.
 *
 * 3. _lastUpdatedBy & _lastUpdatedDateTime:
 *    Strategy: Forbidden-field-aware injection.
 *
 *    A. Field is forbidden for this caller (editor / member without field-level role):
 *       Always overwrite with authSubject / now.
 *       OPA forced them to send the original value or nothing; the gateway must still
 *       stamp the real caller identity.
 *
 *    B. Field is NOT forbidden (admin, or caller holding a field-level role):
 *       putIfAbsent — only inject when absent.
 *       If the caller expressed any value (including the stored one), honour it.
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

            // Resolve record type from route metadata (already in plural form, e.g. "entities").
            // Kind comes from the original record since route metadata does not carry it.
            String recordType = RecordTypeResolver.resolve(null, exchange, "AddManagedFieldsFromOriginalToPayloadInReplace");
            String kind = (String) originalRecord.get("_kind");
            boolean lastUpdatedByForbidden = isFieldForbiddenForUpdate(exchange, recordType, kind, ManagedField.LAST_UPDATED_BY.getFieldName());
            boolean lastUpdatedDateTimeForbidden = isFieldForbiddenForUpdate(exchange, recordType, kind, ManagedField.LAST_UPDATED_DATE_TIME.getFieldName());

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
                // Strategy: Forbidden-field-aware injection.
                else if (field.equals(ManagedField.LAST_UPDATED_BY) && authSubject != null) {
                    applyAuditField(inboundJsonRequestMap, fieldName, authSubject, lastUpdatedByForbidden);
                }

                // 4. LAST_UPDATED_DATE_TIME
                // Strategy: Forbidden-field-aware injection.
                else if (field.equals(ManagedField.LAST_UPDATED_DATE_TIME)) {
                    applyAuditField(inboundJsonRequestMap, fieldName, now, lastUpdatedDateTimeForbidden);
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

    /**
     * Applies an audit field using the correct strategy:
     *
     * - forbidden == true:  always overwrite — caller cannot control this field,
     *                        so the gateway must always stamp the correct value.
     * - forbidden == false: putIfAbsent — caller is authorised to set any value;
     *                        honour whatever they expressed, only inject when absent.
     */
    private void applyAuditField(Map<String, Object> payload, String fieldName, Object injectValue, boolean forbidden) {
        if (forbidden) {
            log.trace("Field {} is forbidden for update — overwriting with injected value.", fieldName);
            payload.put(fieldName, injectValue);
        } else {
            log.trace("Field {} is not forbidden for update — injecting only if absent.", fieldName);
            payload.putIfAbsent(fieldName, injectValue);
        }
    }

    /**
     * Returns true when the given field appears in the forbidden-for-update list
     * that OPA computed for this caller, record type, and kind.
     * Falls back to false (putIfAbsent) when the library is not available.
     */
    private boolean isFieldForbiddenForUpdate(ServerWebExchange exchange, String recordType, String kind, String fieldName) {
        ForbiddenFieldsLibrary library = exchange.getAttribute(FetchForbiddenFieldsGatewayFilterFactory.GATEWAY_CONTEXT_FORBIDDEN_FIELDS);

        if (library == null || recordType == null) {
            log.debug("ForbiddenFieldsLibrary or recordType not available; treating {} as not forbidden.", fieldName);
            return false;
        }

        List<String> forbidden = library.resolveForbiddenFields(recordType, kind);
        return forbidden != null && forbidden.contains(fieldName);
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