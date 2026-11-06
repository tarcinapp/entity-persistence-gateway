package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Handles _lastUpdatedBy and _lastUpdatedDateTime injection for PATCH (updateById) operations.
 *
 * Strategy for each field:
 *
 *   A. Caller does NOT have the right to change the field
 *      (_lastUpdatedBy is in forbidden_update_list for their role — editor / member without field-level role):
 *      → Always overwrite with the authenticated caller's identity / current timestamp.
 *        OPA already ensured they could only have sent the original value or nothing at all;
 *        the gateway must still stamp the real caller identity regardless.
 *
 *   B. Caller DOES have the right to change the field
 *      (admin, or editor/member holding a field-level role that pruned the field from forbidden_update_list):
 *      → putIfAbsent: only inject when the field is absent from the payload.
 *        If the caller expressed any value (including the original), honour it — they are
 *        authorised to set whatever they choose.
 *
 * This filter must be placed AFTER AuthorizeRequest (and FetchForbiddenFields) in the filter
 * chain so that:
 *   1. The ForbiddenFieldsLibrary is already populated in exchange attributes.
 *   2. The OPA operation-level allow/deny decision has already been made.
 */
@Component
@Slf4j
public class AddManagedFieldsInPatch
        extends AbstractRequestPayloadModifierFilterFactory<AddManagedFieldsInPatch.Config, String, String> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private static final String FIELD_LAST_UPDATED_BY = "_lastUpdatedBy";
    private static final String FIELD_LAST_UPDATED_DATE_TIME = "_lastUpdatedDateTime";

    private final ObjectMapper objectMapper;

    public AddManagedFieldsInPatch(ObjectMapper objectMapper) {
        super(Config.class, String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String inboundJsonRequestStr) {

        GatewaySecurityContext securityContext = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

        if (securityContext == null || securityContext.getAuthSubject() == null) {
            log.debug("No authenticated subject found; skipping managed field injection for PATCH.");
            return Mono.just(inboundJsonRequestStr);
        }

        Map<String, Object> originalRecord = getOriginalRecord(exchange);

        if (originalRecord == null) {
            log.debug("Original record not found in exchange; skipping managed field injection for PATCH.");
            return Mono.just(inboundJsonRequestStr);
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(inboundJsonRequestStr, MAP_TYPE_REF);

            String authSubject = securityContext.getAuthSubject();
            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());

            // Resolve record type from route metadata (already in plural form, e.g. "entities",
            // "relations"). Kind is not in route metadata so it comes from the original record.
            String recordType = RecordTypeResolver.resolve(null, exchange, "AddManagedFieldsInPatch");
            String kind = (String) originalRecord.get("_kind");

            boolean lastUpdatedByForbidden = isFieldForbiddenForUpdate(exchange, recordType, kind, FIELD_LAST_UPDATED_BY);
            boolean lastUpdatedDateTimeForbidden = isFieldForbiddenForUpdate(exchange, recordType, kind, FIELD_LAST_UPDATED_DATE_TIME);

            applyField(payload, FIELD_LAST_UPDATED_BY, authSubject, lastUpdatedByForbidden);
            applyField(payload, FIELD_LAST_UPDATED_DATE_TIME, now, lastUpdatedDateTimeForbidden);

            return Mono.just(objectMapper.writeValueAsString(payload));

        } catch (JsonProcessingException e) {
            log.error("JSON processing failed during PATCH managed field injection", e);
            return Mono.error(e);
        }
    }

    /**
     * Applies a single managed field to the payload using the correct strategy:
     *
     * - forbidden == true  (caller cannot control the field):
     *     Always overwrite. Caller may have echoed the original value to pass OPA;
     *     the gateway must still stamp the real caller identity.
     *
     * - forbidden == false (caller is authorised to set the field — admin / field-level role):
     *     putIfAbsent. Honour any value the caller expressed; only inject when absent.
     */
    private void applyField(Map<String, Object> payload, String fieldName, Object injectValue, boolean forbidden) {
        if (forbidden) {
            log.trace("Field {} is forbidden for this caller — overwriting with injected value.", fieldName);
            payload.put(fieldName, injectValue);
        } else {
            log.trace("Field {} is not forbidden for this caller — injecting only if absent.", fieldName);
            payload.putIfAbsent(fieldName, injectValue);
        }
    }

    /**
     * Returns true when the given field appears in the forbidden-for-update list
     * that OPA computed for this caller, record type, and kind.
     *
     * FetchForbiddenFields runs earlier in the chain and stores the result in
     * exchange attributes under GATEWAY_CONTEXT_FORBIDDEN_FIELDS.
     * If the library is absent (e.g. auth is disabled), we conservatively treat
     * the field as NOT forbidden so injection falls back to putIfAbsent.
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOriginalRecord(ServerWebExchange exchange) {
        PolicyData policyData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
        if (policyData == null) return null;
        return (Map<String, Object>) policyData.getOriginalRecord();
    }

    @Data
    public static class Config {
        // No configuration needed — this filter always processes _lastUpdatedBy
        // and _lastUpdatedDateTime and has no optional behaviour to toggle.
    }
}
