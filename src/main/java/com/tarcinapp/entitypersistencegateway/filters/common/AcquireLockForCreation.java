package com.tarcinapp.entitypersistencegateway.filters.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AcquireLockForCreation extends AbstractGatewayFilterFactory<AcquireLockForCreation.Config> {

    @Autowired
    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilterFactory;

    @Autowired
    RedissonReactiveClient redissonReactiveClient;

    @Value("${app.shortcode:#{tarcinapp}}")
    private String appShortcode;

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    public AcquireLockForCreation() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            // 1. STRATEGY: Header Check (FAST PATH)
            // If the client provides an Idempotency Key, we avoid the cost of reading the body.
            String idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_HEADER);

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                log.debug("Idempotency header found: {}", idempotencyKey);
                String lockKey = appShortcode + ":lock:creation:header:" + idempotencyKey;

                // Acquire the lock directly without reading the body and continue the chain
                return lockAndProceed(lockKey, config, Mono.empty())
                        .then(chain.filter(exchange));
            }

            // 2. STRATEGY: Payload Hash (SLOW PATH)
            // If the header does not exist, we must read and hash the body.
            return modifyRequestBodyFilterFactory
                .apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setRewriteFunction(String.class, String.class, (ex, payload) -> {

                        String hash = calculatePayloadHash(payload);
                        String lockKey = appShortcode + ":lock:creation:hash:" + hash;

                        // Acquire the lock; if successful, return the payload unchanged
                        return lockAndProceed(lockKey, config, Mono.just(payload))
                                .thenReturn(payload);
                    }))
                .filter(exchange, chain);
        };
    }

    /**
     * Common Lock Acquisition Logic
     * Uses RLock (mutex) and manages the risk of thread identity in reactive flows.
     */
    private Mono<Void> lockAndProceed(String lockKey, Config config, Mono<?> trigger) {

        final RLockReactive lock = redissonReactiveClient.getLock(lockKey);

        // Since threads may switch in reactive pipelines, we generate a virtual Thread ID.
        // Redisson will treat this ID as the “owner” of the lock.
        final long virtualThreadId = ThreadLocalRandom.current().nextLong();

        long waitTime = config.getWaitTime() != null ? config.getWaitTime() : 3000;
        long leaseTime = config.getLeaseTime() != null ? config.getLeaseTime() : 30000;

        return lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS, virtualThreadId)
            .flatMap(acquired -> {
                if (!acquired) {
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Resource is currently being processed. Duplicate request detected."
                    ));
                }

                log.debug("Lock acquired: {}", lockKey);
                return Mono.empty();
            })
            // When the chain completes (response sent or error raised), release the lock
            .doFinally(signal -> {
                lock.unlock(virtualThreadId)
                    .doOnError(e -> log.warn("Error unlocking key {}: {}", lockKey, e.getMessage()))
                    .subscribe();
            })
            .then();
    }

    private String calculatePayloadHash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    @Data
    public static class Config {
        private Long waitTime;  // Milliseconds
        private Long leaseTime; // Milliseconds
    }
}
