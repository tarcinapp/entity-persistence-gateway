package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.FieldsetDefinition;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.services.FieldsetService;

import reactor.core.publisher.Mono;

/**
 * Gateway filter that applies fieldset configurations to response payloads.
 * 
 * This filter supports:
 * - Query parameter based fieldset selection: ?fieldset=managed
 * - Default fieldsets per resource type
 * - Global and resource-specific fieldset definitions
 * - JSON path based field filtering
 * - Show mode (whitelist) and hide mode (blacklist)
 */
@Component
public class ApplyFieldsetConfig
        extends AbstractResponsePayloadModifierFilterFactory<ApplyFieldsetConfig.Config, String, String> {

    private static final Logger logger = LogManager.getLogger(ApplyFieldsetConfig.class);
    private static final String FIELDSET_QUERY_PARAM = "fieldset";
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
            logger.debug("No fieldset configuration found, returning original payload");
            return Mono.just(payload);
        }

        String resourceType = config.getRecordType();
        if (resourceType == null || resourceType.isEmpty()) {
            logger.warn("No resource type specified in filter configuration, cannot apply fieldsets");
            return Mono.just(payload);
        }

        // Parse query parameters
        URI uri = exchange.getRequest().getURI();
        logger.debug("Processing request URI: {}", uri);
        
        List<NameValuePair> queryParams = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));
        
        // If caller explicitly disables fieldsets, skip applying any defaults or requested sets
        boolean fieldsetsDisabled = queryParams.stream()
            .filter(nvp -> FIELDSETS_TOGGLE_PARAM.equals(nvp.getName()))
            .map(NameValuePair::getValue)
            .map(String::toLowerCase)
            .anyMatch(v -> v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"));

        if (fieldsetsDisabled) {
            logger.debug("Fieldsets explicitly disabled via query ({}=false). Returning original payload.", FIELDSETS_TOGGLE_PARAM);
            return Mono.just(payload);
        }

        // Check if user specified a fieldset in the query (overrides default)
        String requestedFieldset = queryParams.stream()
                .filter(nvp -> FIELDSET_QUERY_PARAM.equals(nvp.getName()))
                .map(NameValuePair::getValue)
                .findFirst()
                .orElse(null);

        // Resolve the fieldset to apply
        FieldsetDefinition fieldsetDefinition = fieldsetService.resolveFieldset(
            fieldSetsConfig, 
            resourceType, 
            requestedFieldset
        );

        if (fieldsetDefinition == null) {
            if (requestedFieldset != null) {
                logger.warn("Requested fieldset '{}' not found for resource type '{}', returning original payload", 
                           requestedFieldset, resourceType);
            } else {
                logger.debug("No fieldset to apply for resource type '{}', returning original payload", resourceType);
            }
            return Mono.just(payload);
        }

        // Apply the fieldset
        try {
            String modifiedPayload = fieldsetService.applyFieldset(payload, fieldsetDefinition);
            
            logger.debug("Successfully applied fieldset for resource type '{}' with mode '{}'", 
                        resourceType, fieldsetDefinition.getMode());
            logger.trace("Modified payload: {}", modifiedPayload);
            
            return Mono.just(modifiedPayload);
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to apply fieldset due to JSON processing error", e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Failed to apply fieldset configuration", 
                e
            );
        }
    }

    /**
     * Configuration for the ApplyFieldsetConfig filter
     */
    public static class Config {
        /**
         * The resource type for this route (entities, lists, relations, reactions)
         */
        private String recordType;

        public String getRecordType() {
            return this.recordType;
        }

        public void setRecordType(String recordType) {
            this.recordType = recordType;
        }
    }
}
