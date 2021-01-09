package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * This filter checks if we have an extracted predicate variable called
 * 'recordId' and finds the record specified with that id.
 * 
 * Original record is put into gatewaycontext.
 * 
 * The original record may then used in policies and manipulation filters.
 */
@Component
public class FindOriginalRecord extends AbstractGatewayFilterFactory<FindOriginalRecord.Config> {

    @Autowired
    IBackendClientBase backendBaseClient;

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    private Logger logger = LogManager.getLogger(FindOriginalRecord.class);

    public FindOriginalRecord() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        // implement in seperate method in order to reduce nesting
        return (exchange, chain) -> this.filter(config, exchange, chain);
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().toString();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        // if recordId not found, there's nothing to do
        if(recordId == null)
            return chain.filter(exchange);
        
        if(!path.endsWith(recordId))
            return chain.filter(exchange);

        logger.debug("Request is targeting a single record. FindOriginalRecord filter is going to find the original record from backend.");
        
        // we will put the original record in gateway context.
        GatewaySecurityContext gc = (GatewaySecurityContext)exchange.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);

        Mono<AnyRecordBase> originalRecord = this.backendBaseClient.get(path.toString(), AnyRecordBase.class);

        if(logger.getLevel() == Level.DEBUG) {
            // subscribe for debugging purposes
            originalRecord
                .doOnSuccess((record) -> {
                    logger.debug("Original record is found successfully.");

                    ObjectMapper mapper = new ObjectMapper();
                    mapper.registerModule(new JavaTimeModule());

                    try {
                        String recordStr = mapper.writeValueAsString(record);
                        logger.debug("Original record is: {}", recordStr);
                    } catch (JsonProcessingException e) {
                        logger.debug("Unable to serialize original record to JSON string.");
                    }
                });
        }

        // subscribe for error logging
        originalRecord
            .doOnError(e -> {
                logger.error("An error occured while querying the original data.", e);
                // TODO: I dont know what to do but logging
            });

        return chain.filter(exchange);
    }

    public static class Config {

    }
}
