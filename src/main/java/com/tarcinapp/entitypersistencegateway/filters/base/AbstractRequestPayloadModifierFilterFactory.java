package com.tarcinapp.entitypersistencegateway.filters.base;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public abstract class AbstractRequestPayloadModifierFilterFactory<C, I, O> extends AbstractGatewayFilterFactory<C> {

    private Class<I> inClass;
    private Class<O> outClass;

    public AbstractRequestPayloadModifierFilterFactory(Class<C> configClass, Class<I> inClass, Class<O> outClass) {
        super(configClass);

        this.inClass = inClass;
        this.outClass = outClass;
    }

    public abstract Mono<O> modifyRequestPayload(C config, ServerWebExchange exchange, I payload);
    
    @Override
    public GatewayFilter apply(C config) {

        return (exchange, chain) -> {
            return this.filter(config, exchange, chain);
        };
    }

    private Mono<Void> filter(C config, ServerWebExchange exchange, GatewayFilterChain chain) {

        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
            .setContentType(MediaType.APPLICATION_JSON_VALUE)
            .setRewriteFunction(inClass, outClass, (ex, payload) -> {
                return modifyRequestPayload(config, ex, payload)
                    .onErrorResume(e -> {
                        // Catches ONLY payload modification errors - not downstream chain errors
                        log.error("An error occurred while executing request payload modification.", e);
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            "Request payload modification failed", e));
                    });
            });

        // chain.filter is called here - its errors will propagate upstream, not caught by this filter
        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }
}
