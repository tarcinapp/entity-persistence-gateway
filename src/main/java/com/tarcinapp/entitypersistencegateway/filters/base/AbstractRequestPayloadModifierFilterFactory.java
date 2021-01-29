package com.tarcinapp.entitypersistencegateway.filters.base;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

public abstract class AbstractRequestPayloadModifierFilterFactory<C, I, O> extends AbstractGatewayFilterFactory<C> {

    private Class<I> inClass;
    private Class<O> outClass;

    private Logger logger = LogManager.getLogger(AbstractRequestPayloadModifierFilterFactory.class);

    public AbstractRequestPayloadModifierFilterFactory(Class<C> configClass, Class<I> inClass, Class<O> outClass) {
        super(configClass);
    }

    public abstract Mono<O> modifyRequestPayload(C config, ServerWebExchange exchange, I payload);
    
    @Override
    public GatewayFilter apply(C config) {

        return (exchange, chain) -> {

            return this.filter(config, exchange, chain).onErrorResume(e -> {
                logger.error("An error occured while executing the base class for request payload modification.", e);

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

                return response.setComplete();
            });
        };
    }

    private Mono<Void> filter(C config, ServerWebExchange exchange, GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(inClass, outClass, (ex, payload) -> this
                        .modifyRequestPayload(config, ex, payload));

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }
}
