package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * This filter is used to place the kind name extracted from the path into the request payload 
 * for create (POST) and replace (PUT) entity requests.
 * * Logic:
 * 1. Retrieves the resolved kind configuration from Exchange Attributes (set by KindResolution filter).
 * 2. Checks if a kind alias is actually configured.
 * 3. If configured, injects "_kind": "kindName" into the JSON payload.
 * 4. Skips this logic for PATCH requests (partial updates).
 */
@Component
@Slf4j
public class PlaceKindNameIntoPayload
        extends
        AbstractRequestPayloadModifierFilterFactory<PlaceKindNameIntoPayload.Config, String, String> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    // Inject the shared ObjectMapper via constructor (Performance optimization)
    public PlaceKindNameIntoPayload(ObjectMapper objectMapper) {
        super(Config.class, String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String payload) {

        log.debug("PlaceKindNameInRequestForEntityManagement filter started.");

        KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

        // Defensive check: If attribute is missing or kind is not configured, skip logic.
        if (kindAliasConfigAttr == null || !kindAliasConfigAttr.isKindAliasConfigured()) {
            log.debug("No kind alias configuration found in attributes. Skipping payload modification.");
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Kind configuration not found for the provided alias"));
        }

        String kindName = kindAliasConfigAttr.getKindName();

        /*
         * Kind alias routes: _kind is derived from the URL path segment, not from the body.
         * Strip any client-supplied _kind first (incorrect value or not needed).
         * For POST and PUT: re-inject the correct kind name resolved from the path alias.
         * For PATCH: strip only — partial updates must not set or change _kind.
         */
        try {
            // Check for empty payload to avoid parsing errors
            if (payload == null || payload.isBlank()) {
                return Mono.just(payload);
            }

            Map<String, Object> payloadMap = objectMapper.readValue(payload, MAP_TYPE_REFERENCE);

            // Remove any _kind the client may have supplied
            payloadMap.remove("_kind");

            if (exchange.getRequest().getMethod() == HttpMethod.PATCH) {
                log.debug("PATCH operation: stripped any client-supplied _kind. Not injecting kind name.");
                return Mono.just(objectMapper.writeValueAsString(payloadMap));
            }

            // POST / PUT: inject the authoritative kind name resolved from the path alias
            payloadMap.put("_kind", kindName);
            log.debug("Kind name '{}' injected into request payload.", kindName);

            return Mono.just(objectMapper.writeValueAsString(payloadMap));
        } catch (Exception e) {
            log.error("Error while modifying request payload for kind injection", e);
            return Mono.error(e);
        }
    }

    public static class Config {
    }
}