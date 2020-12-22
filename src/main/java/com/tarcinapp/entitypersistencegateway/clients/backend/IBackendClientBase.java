package com.tarcinapp.entitypersistencegateway.clients.backend;

import reactor.core.publisher.Mono;

public interface IBackendClientBase {
    <T> Mono<T> get(String path,  Class<T> type);
}