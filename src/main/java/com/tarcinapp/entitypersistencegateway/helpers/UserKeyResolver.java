package com.tarcinapp.entitypersistencegateway.helpers;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import reactor.core.publisher.Mono;

@Component
public class UserKeyResolver implements KeyResolver {

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        GatewaySecurityContext gc = (GatewaySecurityContext)exchange.getAttribute(GATEWAY_SECURITY_CONTEXT_ATTR);

        String key = gc.getAuthSubject();

        if(key==null) {
            key = exchange.getRequest().getRemoteAddress().getHostName();
        }

        return Mono.just(key);
    }
}
