package com.tarcinapp.entitypersistencegateway.oas.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Caching service for personalized OpenAPI specifications.
 * 
 * <p>Implements a two-level caching strategy:</p>
 * <ul>
 *   <li><b>L1 (Caffeine)</b>: In-memory cache for ultra-fast local access</li>
 *   <li><b>L2 (Redis)</b>: Distributed cache for cross-instance sharing</li>
 * </ul>
 * 
 * <h2>Thundering Herd Prevention:</h2>
 * <p>Uses a concurrent map of in-flight computations to ensure that only one
 * thread/request computes the OAS for a given cache key. Other concurrent
 * requests subscribe to the same computation Mono.</p>
 * 
 * <h2>Cache Flow:</h2>
 * <pre>
 * Request → L1 Check → (hit) → Return
 *                   → (miss) → L2 Check → (hit) → Populate L1 → Return
 *                                      → (miss) → Compute → Populate L1 & L2 → Return
 * </pre>
 */
@Service
@Slf4j
public class OasCacheService {
    
    private final ReactiveStringRedisTemplate redisTemplate;
    private final OasOrchestratorProperties properties;
    
    // L1: In-memory cache
    private final Cache<String, String> localCache;
    
    // Thundering herd prevention: track in-flight computations
    private final ConcurrentHashMap<String, Mono<String>> inflightComputations = new ConcurrentHashMap<>();
    
    public OasCacheService(
            ReactiveStringRedisTemplate redisTemplate,
            OasOrchestratorProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        
        // Initialize L1 cache with configured size and TTL
        this.localCache = Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxLocalCacheSize())
            .expireAfterWrite(properties.getCache().getTtl().toMinutes(), TimeUnit.MINUTES)
            .recordStats()
            .build();
        
        log.info("OasCacheService initialized with L1 size={}, TTL={}",
            properties.getCache().getMaxLocalCacheSize(),
            properties.getCache().getTtl());
    }
    
    /**
     * Gets an OAS spec from cache, or computes and caches it if not present.
     * 
     * <p>This method handles:</p>
     * <ul>
     *   <li>L1 (local) cache lookup</li>
     *   <li>L2 (Redis) cache lookup on L1 miss</li>
     *   <li>Computation with thundering herd prevention on L2 miss</li>
     *   <li>Cache population on successful computation</li>
     * </ul>
     * 
     * @param cacheKey The cache key (from OasCacheKeyBuilder)
     * @param computeSpec Mono that computes the OAS spec if not cached
     * @return Mono containing the cached or computed OAS JSON string
     */
    public Mono<String> getOrCompute(String cacheKey, Mono<String> computeSpec) {
        if (!properties.getCache().isEnabled()) {
            log.debug("Caching disabled, computing fresh OAS");
            return computeSpec;
        }
        
        // L1: Check local cache first
        String localCached = localCache.getIfPresent(cacheKey);
        if (localCached != null) {
            log.debug("L1 cache hit for key: {}", truncateKey(cacheKey));
            return Mono.just(localCached);
        }
        
        // L2: Check Redis
        return redisTemplate.opsForValue().get(cacheKey)
            .doOnNext(v -> log.debug("L2 cache hit for key: {}", truncateKey(cacheKey)))
            .switchIfEmpty(Mono.defer(() -> computeWithThunderingHerdProtection(cacheKey, computeSpec)))
            .doOnNext(spec -> {
                // Populate L1 on any successful retrieval
                localCache.put(cacheKey, spec);
            });
    }
    
    /**
     * Computes the OAS with protection against thundering herd.
     * 
     * <p>If multiple requests arrive for the same cache key simultaneously,
     * only one will actually compute the spec. Others will subscribe to
     * the same computation Mono.</p>
     */
    private Mono<String> computeWithThunderingHerdProtection(String cacheKey, Mono<String> computeSpec) {
        return inflightComputations.computeIfAbsent(cacheKey, k -> {
            log.debug("L2 cache miss, computing OAS for key: {}", truncateKey(cacheKey));
            
            return computeSpec
                .flatMap(spec -> storeInRedis(cacheKey, spec).thenReturn(spec))
                .doOnNext(spec -> log.debug("Computed and cached OAS for key: {} ({} bytes)",
                    truncateKey(cacheKey), spec.length()))
                .doFinally(signal -> {
                    // Remove from inflight map when done (success or error)
                    inflightComputations.remove(cacheKey);
                })
                .cache(); // Share the result with concurrent subscribers
        });
    }
    
    /**
     * Stores the computed OAS in Redis.
     */
    private Mono<Boolean> storeInRedis(String cacheKey, String spec) {
        Duration ttl = properties.getCache().getTtl();
        
        return redisTemplate.opsForValue()
            .set(cacheKey, spec, ttl)
            .doOnSuccess(success -> {
                if (Boolean.TRUE.equals(success)) {
                    log.debug("Stored OAS in Redis with TTL: {}", ttl);
                }
            })
            .onErrorResume(e -> {
                log.warn("Failed to store OAS in Redis: {}", e.getMessage());
                return Mono.just(false);
            });
    }
    
    /**
     * Invalidates a specific cache entry.
     * 
     * @param cacheKey The cache key to invalidate
     * @return Mono completing when invalidation is done
     */
    public Mono<Void> invalidate(String cacheKey) {
        log.info("Invalidating cache for key: {}", truncateKey(cacheKey));
        
        // Invalidate L1
        localCache.invalidate(cacheKey);
        
        // Invalidate L2
        return redisTemplate.delete(cacheKey)
            .doOnSuccess(count -> log.debug("Deleted {} Redis entries", count))
            .then();
    }
    
    /**
     * Invalidates all OAS caches.
     * 
     * @return Mono completing when invalidation is done
     */
    public Mono<Void> invalidateAll() {
        log.info("Invalidating all OAS caches");
        
        // Invalidate L1
        localCache.invalidateAll();
        
        // Invalidate L2 (all keys with our prefix)
        String pattern = properties.getCache().getKeyPrefix() + "*";
        
        return redisTemplate.keys(pattern)
            .flatMap(redisTemplate::delete)
            .then()
            .doOnSuccess(v -> log.info("All OAS caches invalidated"));
    }
    
    /**
     * Gets cache statistics for monitoring.
     * 
     * @return Cache statistics summary
     */
    public CacheStats getStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats caffeineStats = localCache.stats();
        
        CacheStats stats = new CacheStats();
        stats.setL1HitCount(caffeineStats.hitCount());
        stats.setL1MissCount(caffeineStats.missCount());
        stats.setL1HitRate(caffeineStats.hitRate());
        stats.setL1Size(localCache.estimatedSize());
        stats.setInflightComputations(inflightComputations.size());
        
        return stats;
    }
    
    /**
     * Truncates a cache key for logging (avoids logging full hashes).
     */
    private String truncateKey(String key) {
        if (key == null || key.length() <= 25) {
            return key;
        }
        return key.substring(0, 25) + "...";
    }
    
    /**
     * Cache statistics DTO.
     */
    @lombok.Data
    public static class CacheStats {
        private long l1HitCount;
        private long l1MissCount;
        private double l1HitRate;
        private long l1Size;
        private int inflightComputations;
    }
}
