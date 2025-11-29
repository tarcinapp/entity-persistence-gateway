package com.tarcinapp.entitypersistencegateway.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

/**
 * Service responsible for managing in-memory caching of HTTP responses at the Gateway level.
 *
 * This service is primarily designated for use by the DynamicLocalCacheGatewayFilterFactory filter.
 * It stores backend responses (status code, headers, body) to reduce load on downstream services
 * and improve response times for repetitive read operations.
 *
 * Core Mechanism:
 *
 * - Storage Engine: Utilizes Caffeine, a high-performance Java caching library.
 *
 * - Smart Memory Management:
 * - Dynamic Sizing: Automatically calculates the maximum cache weight based on the JVM's max heap size
 * (defaulting to 25% of the heap) to prevent OutOfMemoryErrors in different deployment environments.
 * - Weigher: Estimates the memory footprint of each entry by summing the response body length and a fixed
 * overhead for metadata (headers, object references), ensuring the limit reflects actual RAM usage rather than just entry count.
 *
 * - Per-Entry Expiration: Unlike standard caches with a global TTL, this service implements a custom Expiry policy
 * that allows each cached response to have its own Time-To-Live (TTL). This TTL is derived from route-specific configurations
 * or backend Cache-Control headers.
 *
 * Security & Optimization:
 *
 * To optimize memory usage and ensure security, specific headers are strictly ignored during caching:
 * - Security: Authorization (often large JWTs), Set-Cookie, Cookie, Proxy-Authenticate.
 * - Proxy Metadata: X-Forwarded-For, X-Real-IP, etc.
 *
 * Storing large tokens like JWTs would waste significant memory and pose security risks if served to different users.
 *
 * Configuration Properties:
 * - app.local-cache.global-total-size-mb: (Optional) Overrides dynamic calculation with a fixed limit in MB.
 * - app.local-cache.heap-usage-percentage: (Default: 0.25) Fraction of the max heap to use for caching (e.g., 0.25 for 25%).
 * - app.local-cache.stats-enabled: (Default: false) Enables collection of cache statistics (hits, misses, evictions).
 */

@Service
@Slf4j
public class DynamicLocalCacheService {

    private final Cache<String, CachedResponse> cache;
    
    // Fixed estimate for header and Java Object overhead (Bytes) - CPU friendly approach
    private static final int ESTIMATED_METADATA_OVERHEAD = 200; 

    public DynamicLocalCacheService(
            // Use fixed MB limit if present
            @org.springframework.beans.factory.annotation.Value("${app.local-cache.global-total-size-mb:#{null}}") Integer fixedCacheSizeMb,
            // Otherwise, set as a percentage of heap (default 25%)
            @org.springframework.beans.factory.annotation.Value("${app.local-cache.heap-usage-percentage:0.25}") float heapPercentage,
            // Statistics collection setting (default off)
            @org.springframework.beans.factory.annotation.Value("${app.local-cache.stats-enabled:false}") boolean statsEnabled
    ) {
        long maxWeightBytes;

        if (fixedCacheSizeMb != null) {
            maxWeightBytes = (long) fixedCacheSizeMb * 1024 * 1024;
            log.info("DynamicLocalCache initialized with FIXED limit: {} MB", fixedCacheSizeMb);
        } else {
            long maxHeapBytes = Runtime.getRuntime().maxMemory();
            if (maxHeapBytes == Long.MAX_VALUE) {
                maxHeapBytes = 256 * 1024 * 1024;
                log.warn("JVM Max Memory could not be determined. Fallback to 256MB base for calculation.");
            }
            
            maxWeightBytes = (long) (maxHeapBytes * heapPercentage);
            
            long maxHeapMb = maxHeapBytes / (1024 * 1024);
            long cacheMb = maxWeightBytes / (1024 * 1024);
            
            log.info("DynamicLocalCache initialized DYNAMICALLY using {}% of Heap.", heapPercentage * 100);
            log.info("JVM Max Heap: ~{} MB, Calculated Cache Limit: ~{} MB", maxHeapMb, cacheMb);
        }

        // Weigher lambda uses String and CachedResponse types
        Caffeine<String, CachedResponse> builder = Caffeine.newBuilder()
                .maximumWeight(maxWeightBytes)
                .weigher((String key, CachedResponse value) -> {
                    // Body size + estimated Java Object/Header overhead
                    return value.getBody().length + ESTIMATED_METADATA_OVERHEAD;
                })
                .expireAfter(new CustomExpiry());

        if (statsEnabled) {
            builder.recordStats();
            log.info("DynamicLocalCache statistics recording ENABLED.");
        }

        this.cache = builder.build();
    }

    public CachedResponse get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, CachedResponse response, Duration routeTtl) {
        // New data always means fresh TTL
        cache.put(key, response);
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    /**
     * Returns cache performance statistics.
     * If stats-enabled: false, returns empty values.
     */
    public CacheStats getStats() {
        return cache.stats();
    }

    // --- INNER CLASSES ---

    private static class CustomExpiry implements Expiry<String, CachedResponse> {
        @Override
        public long expireAfterCreate(String key, CachedResponse value, long currentTime) {
            return value.getTtl().toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, CachedResponse value, long currentTime, long currentDuration) {
            return value.getTtl().toNanos();
        }

        @Override
        public long expireAfterRead(String key, CachedResponse value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    @Value
    public static class CachedResponse {
        
        // Headers that should not be cached, pose security risks, or take up unnecessary space
        private static final Set<String> IGNORED_HEADERS = Set.of(
            HttpHeaders.AUTHORIZATION.toLowerCase(),
            HttpHeaders.SET_COOKIE.toLowerCase(),
            HttpHeaders.COOKIE.toLowerCase(),
            HttpHeaders.PROXY_AUTHENTICATE.toLowerCase(),
            "x-forwarded-for",
            "x-forwarded-proto",
            "x-real-ip"
        );

        int statusCode;
        HttpHeaders headers;
        byte[] body;
        Duration ttl;
        String etag;

        public CachedResponse(int statusCode, HttpHeaders headers, byte[] body, Duration ttl, String etag) {
            this.statusCode = statusCode;
            
            // Header cleanup and safe copying
            HttpHeaders copyHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach((key, values) -> {
                    if (key != null && !IGNORED_HEADERS.contains(key.toLowerCase())) {
                        copyHeaders.addAll(key, values);
                    }
                });
            }
            
            this.headers = HttpHeaders.readOnlyHttpHeaders(copyHeaders); 
            
            // DEFENSIVE COPY: copying the mutable byte array to prevent external modifications
            this.body = Arrays.copyOf(body, body.length);
            
            this.ttl = ttl;
            this.etag = etag;
        }
    }
}