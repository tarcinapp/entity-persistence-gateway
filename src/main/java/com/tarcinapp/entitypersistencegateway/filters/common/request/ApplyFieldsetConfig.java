package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.net.URI;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.FieldsetDefinition;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;
import com.tarcinapp.entitypersistencegateway.services.FieldsetService;

import reactor.core.publisher.Mono;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 *  * Gateway filter that applies fieldset configurations to response payloads.
 *  *  * This filter supports:
 *  * - Query parameter based fieldset selection: ?fieldset=managed
 *  * - Default fieldsets per resource type
 *  * - Global and resource-specific fieldset definitions
 *  * - JSON path based field filtering
 *  * - Show mode (whitelist) and hide mode (blacklist)
 *  
 */
@Component
@Slf4j
public class ApplyFieldsetConfig
        extends AbstractResponsePayloadModifierFilterFactory<ApplyFieldsetConfig.Config, String, String> {    private static final String FIELDSET_QUERY_PARAM = "fieldset";
    private static final String FIELDSETS_TOGGLE_PARAM = "fieldsets"; // accepts false|0|no|off to disable

    @Autowired
    private FieldSetsConfiguration fieldSetsConfig;

    @Autowired
    private FieldsetService fieldsetService;

    public ApplyFieldsetConfig() {
        super(Config.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyResponsePayload(Config config, ServerWebExchange exchange, String payload) {

        // Check if fieldsets are configured at all
        if (fieldSetsConfig == null) {
            log.debug("No fieldset configuration found, returning original payload");
            return Mono.just(payload);
        }

        // Resolve recordType with hierarchical fallback
        String resourceType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "ApplyFieldsetConfig");

        // Parse query parameters
        URI uri = exchange.getRequest().getURI();
        log.debug("Processing request URI: {}", uri);

        
        MultiValueMap<String, String> queryParams = exchange.getRequest().getQueryParams();

        // If caller explicitly disables fieldsets, skip applying any defaults or
        // requested sets
        boolean fieldsetsDisabled = queryParams.containsKey(FIELDSETS_TOGGLE_PARAM) &&
                queryParams.get(FIELDSETS_TOGGLE_PARAM).stream()
                        .map(String::toLowerCase)
                        .anyMatch(v -> v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"));

        if (fieldsetsDisabled) {
            log.debug("Fieldsets explicitly disabled via query ({}=false). Returning original payload.",
                    FIELDSETS_TOGGLE_PARAM);
            return Mono.just(payload);
        }

        // Check if user specified a fieldset in the query (overrides default)
        String requestedFieldset = queryParams.getFirst(FIELDSET_QUERY_PARAM);

        // Resolve the fieldset to apply
        FieldsetDefinition fieldsetDefinition = fieldsetService.resolveFieldset(
                fieldSetsConfig,
                resourceType,
                requestedFieldset);

        if (fieldsetDefinition == null) {
            if (requestedFieldset != null) {
                log.warn("Requested fieldset '{}' not found for resource type '{}', returning original payload",
                        requestedFieldset, resourceType);
            } else {
                log.debug("No fieldset to apply for resource type '{}', returning original payload", resourceType);
            }
            return Mono.just(payload);
        }

        // Apply the fieldset
        try {
            String modifiedPayload = fieldsetService.applyFieldset(payload, fieldsetDefinition);

            log.debug("Successfully applied fieldset for resource type '{}' with mode '{}'",
                    resourceType, fieldsetDefinition.getMode());
            log.trace("Modified payload: {}", modifiedPayload);

            return Mono.just(modifiedPayload);

        } catch (JsonProcessingException e) {
            log.error("Failed to apply fieldset due to JSON processing error", e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to apply fieldset configuration",
                    e);
        }
    }

    /**
     * Configuration for the ApplyFieldsetConfig filter
     */
    @Data
    public static class Config {
        /**
         * The resource type for this route (entities, lists, relations, reactions)
         */
        private String recordType;
    }
}