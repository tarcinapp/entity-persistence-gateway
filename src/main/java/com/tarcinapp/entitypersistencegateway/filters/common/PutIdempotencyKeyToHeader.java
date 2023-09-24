package com.tarcinapp.entitypersistencegateway.filters.common;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class PutIdempotencyKeyToHeader extends AbstractGatewayFilterFactory<PutIdempotencyKeyToHeader.Config> {

    public PutIdempotencyKeyToHeader() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            Object idempotencyKey = exchange.getAttribute("IdempotencyKey");

            if (idempotencyKey != null) {
                ServerHttpRequest request = exchange.getRequest()
                        .mutate()
                        .header("IdempotencyKey", idempotencyKey.toString())
                        .build();

                return chain.filter(exchange.mutate().request(request).build());
            }

            return chain.filter(exchange);
        };
    }

    public static class Config {
        // No configuration properties needed for this filter
    }
}
