package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Gateway filter that acquires a distributed write lock for update operations on specific records.
 * Prevents concurrent modifications to the same record by using Redis-based distributed locking.
 * The lock is automatically released after the request completes.
 */
@Component
@Slf4j
public class AcquireLockForUpdate extends AbstractGatewayFilterFactory<AcquireLockForUpdate.Config> {
    
    @Autowired
    RedissonReactiveClient redissonReactiveClient;

    @Value("${app.shortcode:tarcinapp}")
    private String appShortcode;

    public AcquireLockForUpdate() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String recordId = uriVariables.get("recordId");

            if (recordId == null || recordId.isEmpty()) {
                log.warn("No recordId found in URI variables, skipping lock acquisition");
                return chain.filter(exchange);
            }

            // Constructing lock key
            String lockKey = appShortcode + ":lock-on-record-update:" + recordId;

            // Generate a virtual thread ID for safe lock ownership in reactive flow
            final long virtualThreadId = ThreadLocalRandom.current().nextLong();

            // Convert Duration to Milliseconds (or use defaults)
            long waitMillis = config.getWaitTime() != null ? config.getWaitTime().toMillis() : 3000;
            long leaseMillis = config.getLeaseTime() != null ? config.getLeaseTime().toMillis() : 30000;

            final RLockReactive lock = redissonReactiveClient.getLock(lockKey);

            log.debug("Attempting to acquire update lock for record: {}", recordId);

            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS, virtualThreadId)
                .flatMap(acquired -> {
                    if (!acquired) {
                        log.warn("Record locked by another process: {}", recordId);
                        return Mono.error(new ResponseStatusException(HttpStatus.LOCKED,
                            "Resource is currently locked by another request: " + recordId));
                    }

                    log.debug("Update lock acquired for record: {}", recordId);
                    
                    // Continue the filter chain
                    return chain.filter(exchange);
                })
                .doFinally(signalType -> {
                    // Unlock ONLY if we acquired the lock (using virtualThreadId).
                    // We do not use forceUnlock() to preserve data integrity.
                    lock.unlock(virtualThreadId)
                        .doOnError(e -> log.warn("Error unlocking record {}: {}", recordId, e.getMessage()))
                        .subscribe();
                });
        };
    }

    @Data
    public static class Config {
        private Duration waitTime;  // Supports '3s', '500ms' in YAML
        private Duration leaseTime; // Supports '30s' in YAML
    }
}