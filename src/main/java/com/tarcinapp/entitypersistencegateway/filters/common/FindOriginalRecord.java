package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.Map;

import com.tarcinapp.entitypersistencegateway.GatewayContext;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
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
public class FindOriginalRecord extends AbstractGatewayFilterFactory<FindOriginalRecord.Config> {

    @Autowired
    IBackendClientBase backendBaseClient;

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

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
        
        // we will put the original record in gateway context.
        GatewayContext gc = (GatewayContext)exchange.getAttributes().get(GATEWAY_CONTEXT_ATTR);

        Mono<AnyRecordBase> originalRecord = this.backendBaseClient.get(path.toString(), AnyRecordBase.class);
        gc.setOriginalRecord(originalRecord);

        return chain.filter(exchange);
    }

    public static class Config {

    }
}
