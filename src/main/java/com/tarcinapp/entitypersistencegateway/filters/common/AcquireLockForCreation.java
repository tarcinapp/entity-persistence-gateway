package com.tarcinapp.entitypersistencegateway.filters.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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
            // If the client sends an Idempotency Key, we skip reading the body (saving RAM/CPU).
            String idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_HEADER);

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                log.debug("Idempotency header found: {}", idempotencyKey);
                String lockKey = appShortcode + ":lock:creation:header:" + idempotencyKey;
                
                // Acquire lock without reading body and proceed
                return lockAndProceed(lockKey, config)
                        .then(chain.filter(exchange));
            }

            // 2. STRATEGY: Payload Hash (SLOW PATH)
            // No header implies we must read and hash the body.
            return modifyRequestBodyFilterFactory
                .apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setRewriteFunction(String.class, String.class, (ex, payload) -> {
                        
                        String hash = calculatePayloadHash(payload);
                        String lockKey = appShortcode + ":lock:creation:hash:" + hash;

                        // Acquire lock, if successful, return original payload to continue chain
                        return lockAndProceed(lockKey, config)
                                .thenReturn(payload);
                    }))
                .filter(exchange, chain);
        };
    }

    /**
     * Shared Locking Logic
     * Uses RLock (Mutex) and Virtual Thread ID for reactive safety.
     */
    private Mono<Void> lockAndProceed(String lockKey, Config config) {
        
        final RLockReactive lock = redissonReactiveClient.getLock(lockKey);
        
        // In reactive streams, thread ID is not stable. 
        // We generate a virtual ID to act as the lock owner for this specific request.
        final long virtualThreadId = ThreadLocalRandom.current().nextLong();

        // Convert Duration to Milliseconds (or use defaults)
        long waitMillis = config.getWaitTime() != null ? config.getWaitTime().toMillis() : 3000;
        long leaseMillis = config.getLeaseTime() != null ? config.getLeaseTime().toMillis() : 30000;

        return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS, virtualThreadId)
            .flatMap(acquired -> {
                if (!acquired) {
                    return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, 
                        "Resource is currently being processed. Duplicate request detected."));
                }
                
                log.debug("Lock acquired: {}", lockKey);
                return Mono.empty();
            })
            // Ensure lock is released whether the chain succeeds or fails
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
        private Duration waitTime;  // Supports '3s', '500ms' in YAML
        private Duration leaseTime; // Supports '30s' in YAML
    }
}