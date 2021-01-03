package com.tarcinapp.entitypersistencegateway.auth;

import reactor.core.publisher.Mono;

public interface IAuthorizationClient {
    Mono<PolicyResult> executePolicy(PolicyData data);
    <T> Mono<T> executePolicy(PolicyData data,  Class<T> type);
}
