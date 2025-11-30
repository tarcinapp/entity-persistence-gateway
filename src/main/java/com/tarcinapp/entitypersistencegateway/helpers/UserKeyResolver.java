package com.tarcinapp.entitypersistencegateway.helpers;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import reactor.core.publisher.Mono;

@Component
public class UserKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        GatewaySecurityContext gc = (GatewaySecurityContext)exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
        
        // safe subject extraction
        String subject = null;
        
        if (gc != null) {
            try { subject = gc.getAuthSubject(); } catch (Exception ignored) {}
        }

        String operation = resolveOperationFromRoute(exchange);
        String op = operation != null ? operation : "unknown";

        String key;

        // prefer subject: subject:operation
        if (subject != null && !subject.isEmpty()) {
            key = subject + ":" + op;
        } else {
            // fallback to IP: operation:ip
            String ip = "unknown";

            if (exchange != null && exchange.getRequest() != null &&
                exchange.getRequest().getRemoteAddress() != null &&
                exchange.getRequest().getRemoteAddress().getAddress() != null) {
                String hostAddr = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();

                if (hostAddr != null && !hostAddr.isEmpty()) {
                    ip = hostAddr;
                }
            }
            key = op + ":" + ip;
        }

        return Mono.just(key);
    }

    /**
     * Uses the route id as the operation name for rate limit lookup.
     */
    private String resolveOperationFromRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return (route != null) ? route.getId() : null;
    }
}
