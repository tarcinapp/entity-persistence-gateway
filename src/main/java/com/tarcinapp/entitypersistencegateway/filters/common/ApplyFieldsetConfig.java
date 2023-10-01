package com.tarcinapp.entitypersistencegateway.filters.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.FieldsetProperties;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractResponsePayloadModifierFilterFactory;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@Component
public class ApplyFieldsetConfig
        extends AbstractResponsePayloadModifierFilterFactory<ApplyFieldsetConfig.Config, String, String> {

    private Logger logger = LogManager.getLogger();

    @Autowired
    FieldSetsConfiguration entityKindsConfig;

    @Value("${app.defaultFieldset:#{null}}")
    private String defaultFieldset;

    public ApplyFieldsetConfig() {
        super(Config.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyResponsePayload(Config config, ServerWebExchange exchange, String oldPayload) {
        Map<String, FieldsetProperties> fieldSets = this.entityKindsConfig.getFieldsets();

        /*
         * If there is no fieldset configured, there is nothing that this filter can do.
         */
        if (fieldSets == null)
            return Mono.just(oldPayload);

        URI uri = exchange.getRequest().getURI();
        logger.debug("Original URI: " + uri);

        List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

        if (this.defaultFieldset != null) {
            logger.debug("A default fieldset is configured.");

            FieldsetProperties fieldsetProperties = fieldSets.get(this.defaultFieldset);

            if (fieldsetProperties == null) {
                logger.warn("Default fieldset is configured as " + this.defaultFieldset
                        + ". However, this fieldset is not defined. Skipping applying any fieldset.");

                return Mono.just(oldPayload);
            }

            try {
                String newPayload = this.applyFieldset(oldPayload, fieldsetProperties);

                return Mono.just(newPayload);
            } catch (JsonProcessingException e) {
                logger.error(e);

                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Invalid JSON response", e);
            }
        }

        if (query.size() == 0)
            return Mono.just(oldPayload);        

        final String[] newPayloadHolder = { oldPayload };

        query.stream()
                .takeWhile(nvp -> "fieldset".equals(nvp.getName()))
                .findFirst()
                .ifPresent(nvp -> {
                    String fieldSetName = nvp.getValue();
                    FieldsetProperties fieldsetProperties = fieldSets.get(fieldSetName);

                    if (fieldsetProperties != null) {
                        logger.debug("Client asked " + fieldSetName
                                + " field set and this is configured in application config.");

                        try {
                            newPayloadHolder[0] = this.applyFieldset(oldPayload, fieldsetProperties);
                        } catch (JsonProcessingException e) {
                            logger.error(e);

                            throw new ResponseStatusException(
                                    HttpStatus.INTERNAL_SERVER_ERROR, "Invalid JSON response", e);
                        }
                    }
                });

        return Mono.just(newPayloadHolder[0]);
    }

    private String applyFieldset(String oldPayload, FieldsetProperties fieldsetProperties)
            throws JsonMappingException, JsonProcessingException {

        // show list has presedence over hide list
        if (fieldsetProperties.getShowList() != null && fieldsetProperties.getShowList().size() > 0) {
            return dropIf(oldPayload, s -> !fieldsetProperties.getShowList().contains(s.getKey()));
        }

        if (fieldsetProperties.getHideList() != null && fieldsetProperties.getHideList().size() > 0) {
            return dropIf(oldPayload, s -> fieldsetProperties.getHideList().contains(s.getKey()));
        }

        logger.debug("Fieldset configuration is applied.");
        logger.trace("New payload is: " + oldPayload);

        return oldPayload;
    }

    private String dropIf(String payload, Predicate<Entry<String, Object>> predicate)
            throws JsonMappingException, JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        // We need to register JavaTimeModule explicitly to be able to
        // serialize/deserialize java.time.* classes
        objectMapper.registerModule(new JavaTimeModule());

        // read the payload
        JsonNode jsonNode = objectMapper.readTree(payload);
        String modifiedPayload = "";

        if (jsonNode.isArray()) {
            // If it's an array
            List<Map<String, Object>> payloadMap = objectMapper.readValue(payload,
                    new TypeReference<List<Map<String, Object>>>() {
                    });

            payloadMap.stream()

                    // on each item of the response array
                    .forEach(item -> {
                        item.entrySet().removeIf(predicate);
                    });

            modifiedPayload = objectMapper.writeValueAsString(payloadMap);
        } else if (jsonNode.isObject()) {
            // If it's an object
            Map<String, Object> payloadMap = objectMapper.readValue(payload,
                    new TypeReference<Map<String, Object>>() {
                    });

            payloadMap.entrySet()
                    .removeIf(predicate);

            modifiedPayload = objectMapper.writeValueAsString(payloadMap);
        } else {
            // Handle other cases as needed
            throw new IllegalArgumentException("Unsupported JSON structure");
        }

        return modifiedPayload;
    }

    public static class Config {

    }
}
