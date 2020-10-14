package com.tarcinapp.entitypersistencegateway.keyresolvers;

import com.tarcinapp.entitypersistencegateway.GatewayContext;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class UserKeyResolver implements KeyResolver {

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        GatewayContext gc = (GatewayContext)exchange.getAttribute(GATEWAY_CONTEXT_ATTR);

        String key = gc.getAuthSubject();

        if(key==null) {
            key = exchange.getRequest().getRemoteAddress().getHostName();
        }

        return Mono.just(key);
    }
}