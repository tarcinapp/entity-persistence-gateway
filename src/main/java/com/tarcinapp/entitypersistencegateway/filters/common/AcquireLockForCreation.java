package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.redisson.api.RLockReactive;
import org.redisson.api.RReadWriteLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import reactor.core.publisher.Mono;

@Component
public class AcquireLockForCreation
        extends AbstractGatewayFilterFactory<AcquireLockForCreation.Config> {

    private Logger logger = LogManager.getLogger(AcquireLockForCreation.class);

    @Autowired
    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilterFactory;

    @Autowired
    RedissonReactiveClient redissonReactiveClient;

    public AcquireLockForCreation() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            logger.debug("AcquireLockForCreation filter is started.");

            return modifyRequestBodyFilterFactory
                    .apply(
                            new ModifyRequestBodyGatewayFilterFactory.Config()
                                    .setRewriteFunction(String.class, String.class, (ex, payload) -> {

                                        try {

                                            String payloadHash = calculatePayloadhHash(ex, payload);
                                            logger.debug("Payload hash is calculated as: " + payloadHash);

                                            /*
                                             * if (true) {
                                             * JsonProcessingException jsonProcessingException = new
                                             * JsonProcessingException(
                                             * "Simulated JSON processing exception") {
                                             * };
                                             * 
                                             * throw jsonProcessingException;
                                             * }
                                             */

                                            // Pass the request as it is to the filter chain
                                            return this.lockRecord(payloadHash)
                                                    .then(Mono.just(payload));
                                        } catch (JsonProcessingException e) {
                                            logger.error(
                                                    "An error occured while parsing the request payload for hash calculation.",
                                                    e);

                                            throw new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST, "Invalid JSON body", e);
                                        }
                                    }))
                    .filter(exchange, chain)
                    .onErrorResume(e -> {

                        ServerHttpResponse response = exchange.getResponse();

                        if (e instanceof ResponseStatusException) {
                            response.setStatusCode(((ResponseStatusException) e).getStatus());
                        } else {
                            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                            logger.error(e);
                        }

                        return response.setComplete();
                    });
        };
    }

    private Mono<Void> lockRecord(String payloadHash) {

        final long currentThreadId = Thread.currentThread().getId();
        final RReadWriteLockReactive lock = redissonReactiveClient
                .getReadWriteLock("lock-on-record-creation-" + payloadHash);
        final RLockReactive writeLock = lock.writeLock();

        return writeLock.isLocked()
                .flatMap(locked -> {

                    if (locked) {
                        throw new ResponseStatusException(HttpStatus.LOCKED,
                                "Resource already locked. payload hash: " + payloadHash);
                    }

                    logger.debug("Lock acquired for the record creation with payload hash: "
                            + payloadHash);

                    return writeLock
                            .tryLock(3, 30, TimeUnit.SECONDS, currentThreadId)
                            .flatMap(lockAcquired -> {

                                if (!lockAcquired) {
                                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                            "Failed to acquire write lock for the record creation with payload hash: "
                                                    + payloadHash);
                                }

                                logger.debug("Lock acquired for the record creation with payload hash: " + payloadHash);

                                return Mono.<Void>empty();
                            })
                            .onErrorResume(throwable -> {

                                return writeLock.unlock(currentThreadId)
                                        .then(Mono.error(throwable));
                            })
                            .doFinally(signalType -> {
                                writeLock.unlock(currentThreadId).subscribe();
                            });
                });
    }

    private String calculatePayloadhHash(ServerWebExchange ex, String payload)
            throws JsonMappingException, JsonProcessingException {

        // Use a hash function (e.g., SHA-256) to hash the keyFields to create an
        // payload hash.
        // This example uses SHA-256 for simplicity; you can choose a suitable hashing
        // algorithm.
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error calculating hash from the payload", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    public static class Config {

    }
}
