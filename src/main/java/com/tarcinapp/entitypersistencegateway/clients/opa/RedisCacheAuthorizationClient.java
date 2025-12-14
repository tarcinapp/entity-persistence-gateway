package com.tarcinapp.entitypersistencegateway.clients.opa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.auth.PolicyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Decorator around the real OPA client that adds Redis caching.
 */
@Component
@Primary
@Slf4j
public class RedisCacheAuthorizationClient implements IAuthorizationClient {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final IAuthorizationClient delegate;

    public RedisCacheAuthorizationClient(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("opaClient") IAuthorizationClient delegate
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.delegate = delegate;
    }

    @Override
    public Mono<PolicyResult> executePolicy(PolicyData data) {
        log.debug("Starting executePolicy for policy: {}", data.getPolicyName());
        String key = buildCacheKey(data.getPolicyName(), data.getEncodedJwt());
        Duration ttl = calculateTtl(data.getEncodedJwt());

        if (ttl.isZero() || ttl.isNegative()) {
            return delegate.executePolicy(data);
        }

        log.debug("Checking Redis for query with key: {}", key);
        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> {
                    log.debug("Found cached result for key: {}", key);
                    return deserialize(json, PolicyResult.class)
                            .map(Mono::just)
                            .orElseGet(Mono::empty);
                })
                .switchIfEmpty(delegate.executePolicy(data)
                        .doOnNext(result -> log.debug("No cached result found for key: {}, fetched from delegate", key))
                        .flatMap(result -> serialize(result)
                                .map(json -> {
                                    log.debug("Preparing key and value to put in cache for key: {}", key);
                                    return redisTemplate.opsForValue()
                                            .set(key, json, ttl)
                                            .onErrorResume(e -> {
                                                log.warn("Redis write failed for key {}: {}", key, e.toString());
                                                return Mono.just(false);
                                            })
                                            .doOnNext(success -> log.debug("Successfully cached result for key: {} with ttl: {}", key, ttl))
                                            .thenReturn(result);
                                })
                                .orElseGet(() -> Mono.just(result))
                        )
                )
                .onErrorResume(e -> {
                    log.warn("Redis read failed for key {}: {}", key, e.toString());
                    return delegate.executePolicy(data);
                });
    }

    @Override
    public <T> Mono<T> executePolicy(PolicyData data, Class<T> type) {
        log.debug("Starting executePolicy for policy: {} with type: {}", data.getPolicyName(), type.getSimpleName());
        String key = buildCacheKey(data.getPolicyName(), data.getEncodedJwt());
        Duration ttl = calculateTtl(data.getEncodedJwt());

        if (ttl.isZero() || ttl.isNegative()) {
            return delegate.executePolicy(data, type);
        }

        log.debug("Checking Redis for query with key: {}", key);
        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> {
                    log.debug("Found cached result for key: {}", key);
                    return deserialize(json, type)
                            .map(Mono::just)
                            .orElseGet(Mono::empty);
                })
                .switchIfEmpty(delegate.executePolicy(data, type)
                        .doOnNext(result -> log.debug("No cached result found for key: {}, fetched from delegate", key))
                        .flatMap(result -> serialize(result)
                                .map(json -> {
                                    log.debug("Preparing key and value to put in cache for key: {}", key);
                                    return redisTemplate.opsForValue()
                                            .set(key, json, ttl)
                                            .onErrorResume(e -> {
                                                log.warn("Redis write failed for key {}: {}", key, e.toString());
                                                return Mono.just(false);
                                            })
                                            .doOnNext(success -> log.debug("Successfully cached result for key: {} with ttl: {}", key, ttl))
                                            .thenReturn(result);
                                })
                                .orElseGet(() -> Mono.just(result))
                        )
                )
                .onErrorResume(e -> {
                    log.warn("Redis read failed for key {}: {}", key, e.toString());
                    return delegate.executePolicy(data, type);
                });
    }

    private String buildCacheKey(String policyName, String encodedJwt) {
        String hash = sha256Hex(encodedJwt == null ? "" : encodedJwt);
        return "auth:policy:" + (policyName == null ? "" : policyName) + ":" + hash;
    }

    private Duration calculateTtl(String encodedJwt) {
        try {
            if (encodedJwt == null) return Duration.ZERO;
            String[] parts = encodedJwt.split("\\.");
            if (parts.length < 2) return Duration.ZERO;
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            Object expObj = payload.get("exp");
            if (expObj == null) return Duration.ZERO;
            long expSeconds;
            if (expObj instanceof Number) {
                expSeconds = ((Number) expObj).longValue();
            } else {
                expSeconds = Long.parseLong(expObj.toString());
            }
            long nowSeconds = Instant.now().getEpochSecond();
            long delta = expSeconds - nowSeconds;
            if (delta <= 0) return Duration.ZERO;
            return Duration.ofSeconds(delta);
        } catch (Exception e) {
            log.warn("Failed to parse JWT exp for TTL: {}", e.toString());
            return Duration.ZERO;
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 algorithm not available: {}", e.toString());
            return Integer.toHexString(input.hashCode());
        }
    }

    private <T> java.util.Optional<String> serialize(T obj) {
        try {
            return java.util.Optional.of(objectMapper.writeValueAsString(obj));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize cache value: {}", e.toString());
            return java.util.Optional.empty();
        }
    }

    private <T> java.util.Optional<T> deserialize(String json, Class<T> type) {
        try {
            return java.util.Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Failed to deserialize cache value for {}: {}", type.getSimpleName(), e.toString());
            return java.util.Optional.empty();
        }
    }
}
