package com.tarcinapp.entitypersistencegateway.filters.common;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;
import com.tarcinapp.entitypersistencegateway.services.DynamicLocalCacheService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DynamicLocalCacheGatewayFilterFactory extends AbstractGatewayFilterFactory<DynamicLocalCacheGatewayFilterFactory.Config> {

    private final DynamicLocalCacheService cacheService;

    // Regex to safely parse max-age from Cache-Control header
    // Captures max-age value ignoring spaces and other directives
    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age\\s*=\\s*(\\d+)");

    @Autowired
    private Environment environment;

    public DynamicLocalCacheGatewayFilterFactory(DynamicLocalCacheService cacheService) {
        super(Config.class);
        this.cacheService = cacheService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        
        // This filter must run before NettyWriteResponseFilter to capture the response body
        // Order -20 ensures it runs before most other write filters
        return new OrderedGatewayFilter((exchange, chain) -> {
            // Restore MDC from exchange attributes for proper logging
            MdcContextLifterConfiguration.restoreMdcFromExchange(exchange);
            
            ServerHttpRequest request = exchange.getRequest();

            // Early exit checks

            // Only cache GET requests
            if (request.getMethod() != HttpMethod.GET) {
                return chain.filter(exchange);
            }

            // Check if client requested no-store
            List<String> cacheControlValues = request.getHeaders().get(HttpHeaders.CACHE_CONTROL);
            boolean clientSaysNoStore = cacheControlValues != null && cacheControlValues.stream().anyMatch(v -> v.contains("no-store"));
            
            if (clientSaysNoStore) {
                log.debug("Client sent no-store. Bypassing cache.");
                return chain.filter(exchange);
            }

            // Check if client requested no-cache (force refresh)
            boolean clientSaysNoCache = cacheControlValues != null && cacheControlValues.stream().anyMatch(v -> v.contains("no-cache"));

            // Resolve recordType with hierarchical fallback
            String recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "DynamicLocalCache");

            KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

            String kindName = null;

            if (kindAliasConfigAttr == null || !kindAliasConfigAttr.isKindAliasConfigured()) {
                log.debug("No kind alias configuration found in attributes. Controller configuration will be used.");
            } else {
                kindName = kindAliasConfigAttr.getKindName();
            }
   
            // Default values from route arguments
            DataSize size = config.getSize(); 
            Duration ttl = config.getTimeToLive();

            // Resolve kind alias and operation for dynamic overrides
            String operation = resolveOperationFromRoute(exchange);
            
            // Apply dynamic configuration overrides from application.yml if available
            // Priority: Operation Specific > Kind Default > Route Args
            if (kindName != null && recordType != null) {
                String kindBaseKey = "app.local-cache." + recordType + ".kinds." + kindName;
                
                String specificSizeVal = environment.getProperty(kindBaseKey + "." + operation + ".size");
                String specificTtlVal = environment.getProperty(kindBaseKey + "." + operation + ".timeToLive");
                
                // Size override
                if (specificSizeVal != null) {
                    DataSize parsed = parseSize(specificSizeVal);
                    if (parsed != null) size = parsed;
                } else {
                    String defaultSizeVal = environment.getProperty(kindBaseKey + ".default.size");
                    DataSize parsedDefault = parseSize(defaultSizeVal);
                    if (parsedDefault != null) size = parsedDefault;
                }

                // TTL override
                if (specificTtlVal != null) {
                    Duration parsed = parseDuration(specificTtlVal);
                    if (parsed != null) ttl = parsed;
                } else {
                    String defaultTtlVal = environment.getProperty(kindBaseKey + ".default.timeToLive");
                    Duration parsedDefault = parseDuration(defaultTtlVal);
                    if (parsedDefault != null) ttl = parsedDefault;
                }
            }

            // If size is 0 or null, caching is disabled for this route
            if (size == null || size.toBytes() <= 0) {
                return chain.filter(exchange);
            }

            final Duration finalConfigTtl = ttl != null ? ttl : Duration.ofMinutes(5);

            // Cache Key Generation

            // Always include User ID in the cache key if authenticated
            String userId = "public";
            GatewaySecurityContext gc = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
            if (gc != null && gc.getAuthSubject() != null) {
                userId = gc.getAuthSubject();
            }

            // Canonicalize query string to ensure order independence
            // e.g., ?a=1&b=2 should be same as ?b=2&a=1
            String sortedQuery = getSortedQueryString(request.getQueryParams());

            // Construct Cache Key: METHOD : PATH : SORTED_QUERY : USER_ID
            String cacheKey = request.getMethod().name() + ":" + request.getURI().getPath() +
                    (sortedQuery.isEmpty() ? "" : "?" + sortedQuery) +
                    ":" + userId;

            // Read from Cache
            
            if (!clientSaysNoCache) {
                DynamicLocalCacheService.CachedResponse cached = cacheService.get(cacheKey);

                if (cached != null) {
                    HttpHeaders requestHeaders = request.getHeaders();
                    HttpHeaders cachedHeaders = cached.getHeaders();

                    long cachedLastModified = cachedHeaders.getLastModified();
                    long ifUnmodifiedSince = requestHeaders.getIfUnmodifiedSince();

                    // 1. CHECK: If-Unmodified-Since (Precondition Failed)
                    // If the resource has been modified *after* the date specified by client, fail.
                    if (ifUnmodifiedSince != -1 && cachedLastModified != -1) {
                        // Compare seconds (HTTP dates don't have millis)
                        if (cachedLastModified / 1000 > ifUnmodifiedSince / 1000) {
                            log.debug("Local Cache Precondition Failed (412): {}", cacheKey);
                            exchange.getResponse().setStatusCode(HttpStatus.PRECONDITION_FAILED);
                            return exchange.getResponse().setComplete();
                        }
                    }

                    // 2. CHECK: If-None-Match (ETag) & If-Modified-Since (304 Not Modified)
                    String ifNoneMatch = requestHeaders.getFirst(HttpHeaders.IF_NONE_MATCH);
                    long ifModifiedSince = requestHeaders.getIfModifiedSince();
                    
                    boolean etagMatches = false;
                    boolean dateMatches = false;
                    boolean hasEtagCondition = (ifNoneMatch != null);
                    boolean hasDateCondition = (ifModifiedSince != -1);

                    // Check ETag
                    if (hasEtagCondition) {
                        // ETag matching logic (Weak/Strong comparison usually handled by equality here)
                        // Note: A simple string equals is mostly sufficient for strong ETags.
                        etagMatches = ifNoneMatch.equals(cached.getEtag());
                    }

                    // Check Date
                    if (hasDateCondition && cachedLastModified != -1) {
                        // If cached content is older or equal to client's date -> Not Modified
                        dateMatches = (cachedLastModified / 1000 <= ifModifiedSince / 1000);
                    }

                    // Decision Logic for 304
                    // RFC 7232: If both provided, both must match.
                    boolean shouldReturn304 = false;

                    if (hasEtagCondition && hasDateCondition) {
                        shouldReturn304 = etagMatches && dateMatches;
                    } else if (hasEtagCondition) {
                        shouldReturn304 = etagMatches;
                    } else if (hasDateCondition) {
                        shouldReturn304 = dateMatches;
                    }

                    if (shouldReturn304) {
                        log.debug("Local Cache HIT (304): {}", cacheKey);
                        exchange.getResponse().setStatusCode(HttpStatus.NOT_MODIFIED);
                        if (cached.getEtag() != null) exchange.getResponse().getHeaders().setETag(cached.getEtag());
                        if (cachedLastModified != -1) exchange.getResponse().getHeaders().setLastModified(cachedLastModified);
                        exchange.getResponse().getHeaders().setCacheControl("public, max-age=" + finalConfigTtl.getSeconds());
                        exchange.getResponse().getHeaders().add("X-Cache-Status", "HIT");
                        return exchange.getResponse().setComplete();
                    }
                    
                    // Return cached response (200 OK)
                    log.debug("Local Cache HIT (200): {}", cacheKey);
                    return writeResponse(exchange, cached, true, finalConfigTtl);
                }
            } else {
                log.debug("Client sent no-cache. Skipping READ, forcing backend fetch.");
            }

            log.debug("Local Cache MISS: {}", cacheKey);

            // Write to Cache (Capture Backend Response)

            ServerHttpResponse originalResponse = exchange.getResponse();
            ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(originalResponse) {
                
                @Override
                public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                    
                    // Only cache 200 OK responses
                    if (getStatusCode() != HttpStatus.OK) {
                        return super.writeWith(body);
                    }

                    // Join the response body stream
                    return DataBufferUtils.join(Flux.from(body))
                        .flatMap(dataBuffer -> {
                            byte[] content = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(content);
                            DataBufferUtils.release(dataBuffer);

                            // Check if backend forbids caching via headers
                            List<String> backendCacheControl = getHeaders().get(HttpHeaders.CACHE_CONTROL);
                            boolean backendSaysNoCache = backendCacheControl != null && 
                                backendCacheControl.stream().anyMatch(v -> v.contains("no-store") || v.contains("private"));

                            if (backendSaysNoCache) {
                                log.debug("Backend sent private/no-store. Skipping cache write for key: {}", cacheKey);
                                return getDelegate().writeWith(Mono.just(
                                    exchange.getResponse().bufferFactory().wrap(content)));
                            }

                            // Calculate TTL respecting backend's max-age
                            Duration finalTtl = calculateTtl(getHeaders(), finalConfigTtl);
                            
                            // Generate ETag using MD5 (Fixed 32 chars length)
                            String etag = "\"" + DigestUtils.md5DigestAsHex(content) + "\"";
                            
                            // Store in cache
                            cacheService.put(cacheKey, new DynamicLocalCacheService.CachedResponse(
                                getStatusCode().value(),
                                getHeaders(), 
                                content,
                                finalTtl,
                                etag
                            ), finalTtl);

                            // Update Response Headers
                            getHeaders().add("X-Cache-Status", "MISS");
                            getHeaders().setETag(etag);
                            getHeaders().setCacheControl("public, max-age=" + finalTtl.getSeconds());

                            return getDelegate().writeWith(Mono.just(
                                exchange.getResponse().bufferFactory().wrap(content)));
                        });
                }
            };

            return chain.filter(exchange.mutate().response(responseDecorator).build());

        }, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 20);
    }

    // Helper Methods
    private String resolveOperationFromRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return (route != null) ? route.getId() : "unknown";
    }

    private DataSize parseSize(String val) {
        if (val == null) return null;
        try { return DataSize.parse(val); } catch (Exception e) { return null; }
    }

    private Duration parseDuration(String val) {
        if (val == null) return null;
        try { return DurationStyle.detectAndParse(val); } catch (Exception e) { return null; }
    }

    private Mono<Void> writeResponse(ServerWebExchange exchange, DynamicLocalCacheService.CachedResponse cached, boolean hit, Duration ttl) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(cached.getStatusCode()));
        response.getHeaders().putAll(cached.getHeaders());
        
        response.getHeaders().add("X-Cache-Status", hit ? "HIT" : "MISS");
        if(cached.getEtag() != null) {
            response.getHeaders().setETag(cached.getEtag());
        }
        response.getHeaders().setCacheControl("public, max-age=" + ttl.getSeconds());

        DataBuffer buffer = response.bufferFactory().wrap(cached.getBody());
        return response.writeWith(Mono.just(buffer));
    }

    // Safely extract max-age using Regex
    private Duration calculateTtl(HttpHeaders headers, Duration configTtl) {
        String cacheControl = headers.getFirst(HttpHeaders.CACHE_CONTROL);
        
        if (cacheControl != null) {
            Matcher matcher = MAX_AGE_PATTERN.matcher(cacheControl.toLowerCase()); 
            
            if (matcher.find()) {
                try {
                    String val = matcher.group(1); 
                    long seconds = Long.parseLong(val);
                    Duration backendTtl = Duration.ofSeconds(seconds);
                    
                    // Gateway protects itself: Use min(backendTtl, configTtl)
                    if (backendTtl.compareTo(configTtl) < 0) {
                        return backendTtl;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse max-age from header: {}", cacheControl);
                }
            }
        }
        return configTtl;
    }

    // Sort query parameters to ensure cache hit regardless of parameter order
    private String getSortedQueryString(MultiValueMap<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        return queryParams.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream()
                        .sorted()
                        .map(value -> entry.getKey() + "=" + value))
                .collect(Collectors.joining("&"));
    }

    @Data
    public static class Config {
        private DataSize size; 
        private Duration timeToLive;
        private String recordType;
    }
}