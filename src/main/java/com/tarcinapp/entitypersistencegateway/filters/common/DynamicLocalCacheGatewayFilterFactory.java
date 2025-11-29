package com.tarcinapp.entitypersistencegateway.filters.common;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig.KindAliasPathSingleConfig;
import com.tarcinapp.entitypersistencegateway.services.DynamicLocalCacheService;
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
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class DynamicLocalCacheGatewayFilterFactory extends AbstractGatewayFilterFactory<DynamicLocalCacheGatewayFilterFactory.Config> {

    private final DynamicLocalCacheService cacheService;
    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    @Autowired
    private Environment environment;

    @Autowired(required = false)
    private KindAliasPathsConfig kindAliasPathsConfig;

    public DynamicLocalCacheGatewayFilterFactory(DynamicLocalCacheService cacheService) {
        super(Config.class);
        this.cacheService = cacheService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        
        // WRITE_RESPONSE_FILTER'dan hemen önce çalışmalı (-1)
        return new OrderedGatewayFilter((exchange, chain) -> {
            
            ServerHttpRequest request = exchange.getRequest();

            // --- 0. ERKEN ÇIKIŞ KONTROLLERİ ---

            // Sadece GET isteklerini cache'le
            if (request.getMethod() != HttpMethod.GET) {
                return chain.filter(exchange);
            }

            // Client "no-store" diyorsa (Sakın kaydetme)
            List<String> cacheControlValues = request.getHeaders().get(HttpHeaders.CACHE_CONTROL);
            boolean clientSaysNoStore = cacheControlValues != null && cacheControlValues.stream().anyMatch(v -> v.contains("no-store"));
            
            if (clientSaysNoStore) {
                log.debug("Client sent no-store. Bypassing cache.");
                return chain.filter(exchange);
            }

            // Client "no-cache" diyorsa (Git backend'den tazele)
            boolean clientSaysNoCache = cacheControlValues != null && cacheControlValues.stream().anyMatch(v -> v.contains("no-cache"));

            // --- 1. CONFIG RESOLUTION (ORİJİNAL MANTIK + YENİ ÖZELLİKLER) ---
            
            String recordType = config.getRecordType();
            if (recordType == null || recordType.isEmpty()) {
                log.warn("DynamicLocalCache filter requires 'recordType' arg.");
                // recordType yoksa devam edebiliriz ama dinamik kind override çalışmaz.
            }
            
            // Route Args'dan gelen varsayılan değerler
            DataSize size = config.getSize(); 
            Duration ttl = config.getTimeToLive();

            // Kind Alias Resolution
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindAlias = uriVariables.get("kindAlias");
            String kindName = resolveKindName(kindAlias, recordType);
            String operation = resolveOperationFromRoute(exchange);
            
            // Dynamic configuration selection.
            // Priority:
            // 1. Kind + Operation Specific (books.findAll.size)
            // 2. Kind Default (books.default.size)
            // 3. Route Args Default (comes from YAML)
            if (kindName != null && recordType != null) {
                String kindBaseKey = "app.local-cache." + recordType + ".kinds." + kindName;
                
                // 1. Operation Specific Overrides
                String specificSizeVal = environment.getProperty(kindBaseKey + "." + operation + ".size");
                String specificTtlVal = environment.getProperty(kindBaseKey + "." + operation + ".timeToLive");
                
                // Size Resolution
                if (specificSizeVal != null) {
                    DataSize parsed = parseSize(specificSizeVal);
                    if (parsed != null) size = parsed;
                } else {
                    // 2. Kind Default Size
                    String defaultSizeVal = environment.getProperty(kindBaseKey + ".default.size");
                    DataSize parsedDefault = parseSize(defaultSizeVal);
                    if (parsedDefault != null) size = parsedDefault;
                }

                // TTL Resolution
                if (specificTtlVal != null) {
                    Duration parsed = parseDuration(specificTtlVal);
                    if (parsed != null) ttl = parsed;
                } else {
                    // 2. Kind Default TTL
                    String defaultTtlVal = environment.getProperty(kindBaseKey + ".default.timeToLive");
                    Duration parsedDefault = parseDuration(defaultTtlVal);
                    if (parsedDefault != null) ttl = parsedDefault;
                }
            }

            // 0B veya null ise Cache DISABLED
            if (size == null || size.toBytes() <= 0) {
                return chain.filter(exchange);
            }

            final Duration finalConfigTtl = ttl != null ? ttl : Duration.ofMinutes(5);

            // --- 2. CACHE KEY GENERATION ---

            // GÜVENLİK: User ID'yi anahtara ekle.
            String userId = "public";
            GatewaySecurityContext gc = exchange.getAttribute(GATEWAY_SECURITY_CONTEXT_ATTR);
            if (gc != null && gc.getAuthSubject() != null) {
                userId = gc.getAuthSubject();
            }

            // Key: METHOD : PATH : QUERY : USER_ID
            String cacheKey = request.getMethod().name() + ":" + request.getURI().getPath() +
                    (request.getURI().getQuery() != null ? "?" + request.getURI().getQuery() : "") +
                    ":" + userId;

            // --- 3. READ CACHE (Client no-cache demediyse) ---
            
            if (!clientSaysNoCache) {
                DynamicLocalCacheService.CachedResponse cached = cacheService.get(cacheKey);
                String ifNoneMatch = request.getHeaders().getFirst(HttpHeaders.IF_NONE_MATCH);

                if (cached != null) {
                    
                    // ETag Check (304 Not Modified)
                    if (ifNoneMatch != null && ifNoneMatch.equals(cached.getEtag())) {
                        log.debug("Local Cache HIT (304): {}", cacheKey);
                        exchange.getResponse().setStatusCode(HttpStatus.NOT_MODIFIED);
                        exchange.getResponse().getHeaders().setETag(cached.getEtag());
                        exchange.getResponse().getHeaders().setCacheControl("public, max-age=" + finalConfigTtl.getSeconds());
                        exchange.getResponse().getHeaders().add("X-Cache-Status", "HIT");
                        return exchange.getResponse().setComplete();
                    }
                    
                    // Tam Veri Dönüşü (200 OK)
                    log.debug("Local Cache HIT (200): {}", cacheKey);
                    return writeResponse(exchange, cached, true, finalConfigTtl);
                }
            } else {
                log.debug("Client sent no-cache. Skipping READ, forcing backend fetch.");
            }

            log.debug("Local Cache MISS: {}", cacheKey);

            // Write cache (Capture Backend Response and Cache)
            ServerHttpResponse originalResponse = exchange.getResponse();
            ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(originalResponse) {
                
                @Override
                public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                    
                    // Only cache 200 OK responses.
                    if (getStatusCode() != HttpStatus.OK) {
                        return super.writeWith(body);
                    }

                    // Join body stream
                    return DataBufferUtils.join(Flux.from(body))
                        .flatMap(dataBuffer -> {
                            byte[] content = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(content);
                            DataBufferUtils.release(dataBuffer); // Prevent memory leak

                            List<String> backendCacheControl = getHeaders().get(HttpHeaders.CACHE_CONTROL);
                            boolean backendSaysNoCache = backendCacheControl != null && 
                                backendCacheControl.stream().anyMatch(v -> v.contains("no-store") || v.contains("private"));

                            // If backend says no-store or private, do not cache
                            if (backendSaysNoCache) {
                                log.debug("Backend sent private/no-store. Skipping cache write for key: {}", cacheKey);
                                // Return data directly, do not write to cache.
                                return getDelegate().writeWith(Mono.just(
                                    exchange.getResponse().bufferFactory().wrap(content)));
                            }

                            // Consider Cache-Control from backend if present, otherwise use config TTL
                            Duration finalTtl = calculateTtl(getHeaders(), finalConfigTtl);
                            
                            // Calculate ETag (MD5)
                            String etag = "\"" + DigestUtils.md5DigestAsHex(content) + "\"";
                            
                            // Write to cache
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
                            // Inform client how long to cache this response
                            getHeaders().setCacheControl("public, max-age=" + finalTtl.getSeconds());

                            return getDelegate().writeWith(Mono.just(
                                exchange.getResponse().bufferFactory().wrap(content)));
                        });
                }
            };

            return chain.filter(exchange.mutate().response(responseDecorator).build());

        }, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 20);
    }

    // --- HELPER METHODS ---
    private String resolveKindName(String kindAlias, String recordType) {
        if (kindAlias != null && kindAliasPathsConfig != null && recordType != null) {
            return kindAliasPathsConfig.getKindAliasPaths().stream()
                    .filter(k -> k.getAlias() != null && k.getAlias().equals(kindAlias) 
                            && k.getRecordType() != null && k.getRecordType().equals(recordType))
                    .map(KindAliasPathSingleConfig::getName)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

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
        
        // Standard headers to indicate cache status and control
        response.getHeaders().add("X-Cache-Status", hit ? "HIT" : "MISS");
        if(cached.getEtag() != null) {
            response.getHeaders().setETag(cached.getEtag());
        }
        // Inform client how long to cache this response
        response.getHeaders().setCacheControl("public, max-age=" + ttl.getSeconds());

        DataBuffer buffer = response.bufferFactory().wrap(cached.getBody());
        return response.writeWith(Mono.just(buffer));
    }

    private Duration calculateTtl(HttpHeaders headers, Duration configTtl) {
        // Is there a 'Cache-Control: max-age=...' from the backend?
        String cacheControl = headers.getFirst(HttpHeaders.CACHE_CONTROL);

        if (cacheControl != null && cacheControl.contains("max-age=")) {

            try {
                String val = cacheControl.substring(cacheControl.indexOf("max-age=") + 8).split(",")[0];
                long seconds = Long.parseLong(val);
                Duration backendTtl = Duration.ofSeconds(seconds);
                
                // Use backend TTL if it is shorter than config TTL
                if (backendTtl.compareTo(configTtl) < 0) return backendTtl;
            } catch (Exception e) {
                // Use config TTL if parsing fails
            }
        }
        return configTtl;
    }

    public static class Config {
        private DataSize size; 
        private Duration timeToLive;
        private String recordType;

        public DataSize getSize() { return size; }
        public void setSize(DataSize size) { this.size = size; }
        public Duration getTimeToLive() { return timeToLive; }
        public void setTimeToLive(Duration timeToLive) { this.timeToLive = timeToLive; }
        public String getRecordType() { return recordType; }
        public void setRecordType(String recordType) { this.recordType = recordType; }
    }
}