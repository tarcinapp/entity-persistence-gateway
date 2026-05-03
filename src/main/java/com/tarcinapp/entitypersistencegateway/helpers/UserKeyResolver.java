package com.tarcinapp.entitypersistencegateway.helpers;

import java.net.InetSocketAddress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import reactor.core.publisher.Mono;

@Component
public class UserKeyResolver implements KeyResolver {

    @Value("${app.inbound.trustedProxyCount:0}")
    private int trustedProxyCount;

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
            key = op + ":" + resolveClientIp(exchange);
        }

        return Mono.just(key);
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        try {
            InetSocketAddress addr;
            if (trustedProxyCount > 0) {
                addr = XForwardedRemoteAddressResolver
                        .maxTrustedIndex(trustedProxyCount)
                        .resolve(exchange);
            } else {
                addr = exchange.getRequest().getRemoteAddress();
            }
            if (addr != null && addr.getAddress() != null) {
                return addr.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    /**
     * Uses the route id as the operation name for rate limit lookup.
     */
    private String resolveOperationFromRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return (route != null) ? route.getId() : null;
    }
}
