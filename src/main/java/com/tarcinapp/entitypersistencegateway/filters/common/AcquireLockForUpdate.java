package com.tarcinapp.entitypersistencegateway.filters.common;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.redisson.api.RLockReactive;
import org.redisson.api.RReadWriteLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * Gateway filter that acquires a distributed write lock for update operations on specific records.
 * Prevents concurrent modifications to the same record by using Redis-based distributed locking.
 * The lock is automatically released after the request completes.
 */
@Component
public class AcquireLockForUpdate extends AbstractGatewayFilterFactory<AcquireLockForUpdate.Config> {

    private Logger logger = LogManager.getLogger(AcquireLockForUpdate.class);

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
                logger.warn("No recordId found in URI variables, skipping lock acquisition");
                return chain.filter(exchange);
            }

            logger.debug("Acquiring write lock for record: " + recordId);

            Duration wait = config.getWaitTime() != null ? config.getWaitTime() : Duration.ofSeconds(3);
            Duration lease = config.getLeaseTime() != null ? config.getLeaseTime() : Duration.ofSeconds(30);

            return acquireWriteLock(recordId, wait, lease)
                    .then(chain.filter(exchange))
                    .doFinally(signalType -> {
                        releaseWriteLock(recordId).subscribe();
                    });
        };
    }

    private Mono<Void> acquireWriteLock(String recordId, Duration waitTime, Duration leaseTime) {
        final RReadWriteLockReactive lock = redissonReactiveClient
                .getReadWriteLock(appShortcode + ":lock-on-record-update:" + recordId);
        final RLockReactive writeLock = lock.writeLock();

        return writeLock.isLocked()
                .flatMap(locked -> {
                    if (locked) {
                        logger.warn("Record already locked: " + recordId);
                        throw new ResponseStatusException(HttpStatus.LOCKED,
                                "Resource is currently locked by another request: " + recordId);
                    }

                    return writeLock.tryLock(waitTime.getSeconds(), leaseTime.getSeconds(), TimeUnit.SECONDS)
                            .flatMap(lockAcquired -> {
                                if (!lockAcquired) {
                                    logger.error("Failed to acquire write lock for record: " + recordId);
                                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                            "Failed to acquire write lock for record: " + recordId);
                                }

                                logger.debug("Write lock acquired for record: " + recordId);
                                return Mono.<Void>empty();
                            });
                })
                .onErrorResume(throwable -> {
                    return releaseWriteLock(recordId)
                            .then(Mono.error(throwable));
                });
    }

    private Mono<Void> releaseWriteLock(String recordId) {
        final RReadWriteLockReactive lock = redissonReactiveClient
                .getReadWriteLock(appShortcode + ":lock-on-record-update:" + recordId);
        final RLockReactive writeLock = lock.writeLock();

        return writeLock.forceUnlock()
                .doOnSuccess(v -> logger.debug("Write lock released for record: " + recordId))
                .doOnError(e -> logger.error("Error releasing write lock for record: " + recordId, e))
                .then()
                .onErrorResume(e -> Mono.empty()); // Ignore unlock errors
    }

    public static class Config {
        private Duration waitTime;
        private Duration leaseTime;

        public Duration getWaitTime() {
            return waitTime;
        }

        public void setWaitTime(Duration waitTime) {
            this.waitTime = waitTime;
        }

        public Duration getLeaseTime() {
            return leaseTime;
        }

        public void setLeaseTime(Duration leaseTime) {
            this.leaseTime = leaseTime;
        }
    }
}
