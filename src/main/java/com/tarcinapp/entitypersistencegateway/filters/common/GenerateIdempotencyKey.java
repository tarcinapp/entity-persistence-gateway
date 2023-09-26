package com.tarcinapp.entitypersistencegateway.filters.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactivestreams.Publisher;
import org.redisson.api.RLockReactive;
import org.redisson.api.RReadWriteLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class GenerateIdempotencyKey
        extends AbstractGatewayFilterFactory<GenerateIdempotencyKey.Config> {

    private Logger logger = LogManager.getLogger(GenerateIdempotencyKey.class);

    @Autowired
    private Environment environment;

    @Autowired
    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilterFactory;

    @Autowired
    RedissonReactiveClient redissonReactiveClient;

    public GenerateIdempotencyKey() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            logger.debug("GenerateIdempotencyKey filter is started.");

            String keyFieldsProperty = "app.idempotency." + config.getRecord() + ".keyfields";
            String keyFieldsStr = environment.getProperty(keyFieldsProperty);

            if (keyFieldsStr == null) {
                return chain.filter(exchange);
            }

            List<String> fields = Arrays.asList(keyFieldsStr.split(","));

            logger.debug("Idempotency is configured for " + config.getRecord()
                    + ". Following fields are going to be used:" + fields.toString());

            return modifyRequestBodyFilterFactory
                    .apply(
                            new ModifyRequestBodyGatewayFilterFactory.Config()
                                    .setRewriteFunction(String.class, String.class, (ex, payload) -> {

                                        try {

                                            String idempotencyKey = calculateIdempotencyKey(fields, ex, payload);
                                            logger.debug("Idempotency key is calculated as: " + idempotencyKey);

                                            // add calculated idempotency key to the header
                                            ex
                                                    .getAttributes()
                                                    .put("IdempotencyKey", idempotencyKey);

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
                                            return this.lockRecord(config, keyFieldsStr, idempotencyKey)
                                                    .then(Mono.just(payload));
                                        } catch (JsonProcessingException e) {
                                            logger.error(
                                                    "An error occured while parsing the request payload for idempotency.",
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

    private Mono<Void> lockRecord(Config config, String recordKind, String idempotencyKey) {

        final long currentThreadId = Thread.currentThread().getId();
        final RReadWriteLockReactive lock = redissonReactiveClient
                .getReadWriteLock("lock-on-" + config.getRecord() + "-creation-" + idempotencyKey);
        final RLockReactive writeLock = lock.writeLock();

        return writeLock.isLocked()
                .flatMap(locked -> {

                    if (locked) {
                        throw new ResponseStatusException(HttpStatus.LOCKED,
                                "Resource already locked. Idempotency key: " + idempotencyKey);
                    }

                    logger.debug("Lock acquired for the " + config.getRecord() + " creation with idempotency key: "
                            + idempotencyKey);

                    return writeLock
                            .tryLock(3, 30, TimeUnit.SECONDS, currentThreadId)
                            .flatMap(lockAcquired -> {

                                if (!lockAcquired) {
                                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                            "Failed to acquire write lock for the " + config.getRecord()
                                                    + " creation with idempotency key: " + idempotencyKey);
                                }

                                logger.debug("Lock acquired for the resource " + recordKind
                                        + " creation with idempotency key: " + idempotencyKey);

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

    private String calculateIdempotencyKey(List<String> config, ServerWebExchange ex, String payload)
            throws JsonMappingException, JsonProcessingException {
        List<String> values = parseKeyFieldsFromRequestBody(payload, config);
        String idempotencyKey = generateIdempotencyKey(values);

        return idempotencyKey;
    }

    private List<String> parseKeyFieldsFromRequestBody(String payload, List<String> fields)
            throws JsonMappingException, JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(payload);

        return fields.stream()
                .map(field -> jsonNode.at(field))
                .filter(selectedNode -> !selectedNode.isMissingNode())
                .map(selectedNode -> selectedNode.getNodeType() == JsonNodeType.STRING ? selectedNode.asText()
                        : selectedNode.toString())
                .collect(Collectors.toList());
    }

    private String generateIdempotencyKey(List<String> values) {

        // Use a hash function (e.g., SHA-256) to hash the keyFields to create an
        // idempotency key.
        // This example uses SHA-256 for simplicity; you can choose a suitable hashing
        // algorithm.
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join(",", values).getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating idempotency key", e);
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

        private String record;

        public String getRecord() {
            return record;
        }

        public void setRecord(String record) {
            this.record = record;
        }

    }
}
