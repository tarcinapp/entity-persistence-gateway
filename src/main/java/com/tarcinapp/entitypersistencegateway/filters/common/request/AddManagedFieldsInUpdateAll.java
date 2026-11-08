package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
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
 * Handles _lastUpdatedBy and _lastUpdatedDateTime injection for bulk PATCH (updateAll) operations.
 *
 * Unlike AddManagedFieldsInPatch there is no single original record in the exchange — the
 * operation matches N records by query. Therefore kind is always resolved as null, which is
 * safe: no kind-specific update rules exist for these two fields in data.rego.
 *
 * Strategy (identical logic to AddManagedFieldsInPatch, minus the kind-aware lookup):
 *
 *   - Field is forbidden for this caller (editor / member):
 *       Force-overwrite with caller identity / current timestamp.
 *       In practice OPA already blocks editors/members from including these fields
 *       in an updateAll payload at all (no "same value" escape hatch exists for bulk),
 *       so this branch is unreachable — but is kept for correctness and symmetry.
 *
 *   - Field is NOT forbidden (admin, or field-level role):
 *       putIfAbsent — honour whatever value the admin explicitly sent.
 *
 * Must be placed AFTER AuthorizeRequest (and FetchForbiddenFields) in the filter chain.
 */
@Component
@Slf4j
public class AddManagedFieldsInUpdateAll
        extends AbstractRequestPayloadModifierFilterFactory<AddManagedFieldsInUpdateAll.Config, String, String> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private static final String FIELD_LAST_UPDATED_BY = "_lastUpdatedBy";
    private static final String FIELD_LAST_UPDATED_DATE_TIME = "_lastUpdatedDateTime";

    private final ObjectMapper objectMapper;

    public AddManagedFieldsInUpdateAll(ObjectMapper objectMapper) {
        super(Config.class, String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String inboundJsonRequestStr) {

        GatewaySecurityContext securityContext = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

        if (securityContext == null || securityContext.getAuthSubject() == null) {
            log.debug("No authenticated subject found; skipping managed field injection for updateAll.");
            return Mono.just(inboundJsonRequestStr);
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(inboundJsonRequestStr, MAP_TYPE_REF);

            String authSubject = securityContext.getAuthSubject();
            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());

            // kind is null for bulk operations — no single record to derive it from.
            // resolveForbiddenFields handles null kind gracefully (skips kind-specific merge).
            String recordType = RecordTypeResolver.resolve(null, exchange, "AddManagedFieldsInUpdateAll");

            boolean lastUpdatedByForbidden = isFieldForbiddenForUpdate(exchange, recordType, FIELD_LAST_UPDATED_BY);
            boolean lastUpdatedDateTimeForbidden = isFieldForbiddenForUpdate(exchange, recordType, FIELD_LAST_UPDATED_DATE_TIME);

            applyField(payload, FIELD_LAST_UPDATED_BY, authSubject, lastUpdatedByForbidden);
            applyField(payload, FIELD_LAST_UPDATED_DATE_TIME, now, lastUpdatedDateTimeForbidden);

            return Mono.just(objectMapper.writeValueAsString(payload));

        } catch (JsonProcessingException e) {
            log.error("JSON processing failed during updateAll managed field injection", e);
            return Mono.error(e);
        }
    }

    private void applyField(Map<String, Object> payload, String fieldName, Object injectValue, boolean forbidden) {
        if (forbidden) {
            log.trace("Field {} is forbidden for this caller — overwriting with injected value.", fieldName);
            payload.put(fieldName, injectValue);
        } else {
            log.trace("Field {} is not forbidden for this caller — injecting only if absent.", fieldName);
            payload.putIfAbsent(fieldName, injectValue);
        }
    }

    private boolean isFieldForbiddenForUpdate(ServerWebExchange exchange, String recordType, String fieldName) {
        ForbiddenFieldsLibrary library = exchange.getAttribute(FetchForbiddenFieldsGatewayFilterFactory.GATEWAY_CONTEXT_FORBIDDEN_FIELDS);

        if (library == null || recordType == null) {
            log.debug("ForbiddenFieldsLibrary or recordType not available; treating {} as not forbidden.", fieldName);
            return false;
        }

        // kind = null: use record-type-level defaults only (correct for bulk operations)
        List<String> forbidden = library.resolveForbiddenFields(recordType, null);
        return forbidden != null && forbidden.contains(fieldName);
    }

    @Data
    public static class Config {
    }
}
