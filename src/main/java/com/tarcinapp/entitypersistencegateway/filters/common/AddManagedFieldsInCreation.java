package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.dto.ManagedField;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AddManagedFieldsInCreation extends AbstractGatewayFilterFactory<AddManagedFieldsInCreation.Config> {

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    @Autowired(required = false)
    Key key;

    Logger logger = LogManager.getLogger(AddManagedFieldsInCreation.class);

    public AddManagedFieldsInCreation() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("AddManagedFieldsInCreation filter is started");

            if (key == null) {
                logger.warn(
                        "RS256 key is not configured. Ownership information won't be added to the request payload! Please configure a valid RS256 public key to enable record ownership.");

                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain);
        };
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> this
                        .processPayload(config, exchange1, inboundJsonRequestStr));

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    private Mono<String> processPayload(Config config, ServerWebExchange exchange, String inboundJsonRequestStr) {
        GatewaySecurityContext gatewaySecurityContext = this.getGatewaySecurityContext(exchange);

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                    new TypeReference<Map<String, Object>>() {
                    });

            String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());
            String authSubject = gatewaySecurityContext.getAuthSubject();
            List<ManagedField> fieldsToAdd = this.getFieldsToAdd(config);
            /**
             * We used putIfAbsent here because user may be authorized to send custom values
             * for these fields.
             */
            fieldsToAdd.stream().forEach(field -> {

                if (field.equals(ManagedField.CREATION_DATE_TIME))
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), now);

                if (field.equals(ManagedField.LAST_UPDATED_DATE_TIME))
                    inboundJsonRequestMap.putIfAbsent(field.getFieldName(), now);

                if (authSubject != null) {

                    if (field.equals(ManagedField.OWNER_USERS))
                        inboundJsonRequestMap.putIfAbsent(ManagedField.OWNER_USERS.getFieldName(),
                                new String[] { authSubject });

                    if (field.equals(ManagedField.CREATED_BY))
                        inboundJsonRequestMap.putIfAbsent(field.getFieldName(), authSubject);

                    if (field.equals(ManagedField.LAST_UPDATED_BY))
                        inboundJsonRequestMap.putIfAbsent(field.getFieldName(), authSubject);
                }
            });

            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

            logger.debug("Request payload is modified with adding the owner user. New payload: ",
                    outboundJsonRequestStr);

            return Mono.just(outboundJsonRequestStr);
        } catch (JsonMappingException e) {
            return Mono.error(e);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private List<ManagedField> getFieldsToAdd(Config config) {
        List<ManagedField> fieldsToAdd = new ArrayList<ManagedField>();

        // if no include or exclude array specified, this filter adds all managed fields
        if (config.getIncludeFields() == null && config.getIncludeFields() == null)
            fieldsToAdd = Arrays.asList(ManagedField.values());

        // if we have includeFields, excludeFields is ignored
        else if (config.getExcludeFields() == null)
            fieldsToAdd = config.getIncludeFields().stream().map(ManagedField::valueOf).collect(Collectors.toList());

        // if we reach here we only have exclude fields is configured
        else
            fieldsToAdd = config.getExcludeFields().stream().map(ManagedField::valueOf).collect(Collectors.toList());

        return fieldsToAdd;
    }

    private GatewaySecurityContext getGatewaySecurityContext(ServerWebExchange exchange) {
        return (GatewaySecurityContext) exchange.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);
    }

    public static class Config {
        List<String> includeFields;
        List<String> excludeFields;

        public List<String> getIncludeFields() {
            return this.includeFields;
        }

        public void setIncludeFields(List<String> includeFields) {
            this.includeFields = includeFields;
        }

        public List<String> getExcludeFields() {
            return this.excludeFields;
        }

        public void setExcludeFields(List<String> excludeFields) {
            this.excludeFields = excludeFields;
        }
    }
}