package com.tarcinapp.entitypersistencegateway.clients.backend;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public interface IBackendClientBase {
    <T> Mono<T> get(String path, Class<T> type);
    <T> Mono<T> get(String path, Class<T> type, ServerWebExchange exchange);
}