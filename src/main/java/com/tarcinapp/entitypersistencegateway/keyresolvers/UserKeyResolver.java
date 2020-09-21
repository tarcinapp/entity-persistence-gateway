package com.tarcinapp.entitypersistencegateway.keyresolvers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class UserKeyResolver implements KeyResolver {

    @Value("${app.requestHeaders.authenticationSubject}")
    private String authSubjectHeader;

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String subject = exchange.getRequest().getHeaders().getFirst(authSubjectHeader);

        return Mono.just(subject);
    }
}
