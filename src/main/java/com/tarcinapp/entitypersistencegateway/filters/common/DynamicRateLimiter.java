package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Dynamic rate limiter filter that resolves kind-specific rate limits at
 * runtime.
 * 
 * 1. Extracts the kindAlias from the URI path variable at request time
 * 2. Looks up
 * app.rate-limits.kinds.{kindName}.{operation}.replenishRate/burstCapacity from
 * Environment
 * 3. Falls back to the values specified in the application-routes.yml if no
 * kind-specific rate limit is configured
 * 
 * Example:
 * - Request to /api/v1/entities/books (POST)
 * - Filter extracts kindAlias="books"
 * - Looks for app.rate-limits.kinds.book.create.replenishRate and burstCapacity
 * - If found, uses those; otherwise uses group or default
 */
@Component
@Slf4j
public class DynamicRateLimiter extends AbstractGatewayFilterFactory<DynamicRateLimiter.Config> {

    @Autowired
    private Environment environment;

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    public DynamicRateLimiter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            String recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "DynamicRateLimiter");

            KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

            // Defensive check: If attribute is missing or kind is not configured, skip
            // logic.
            if (kindAliasConfigAttr == null || !kindAliasConfigAttr.isKindAliasConfigured()) {
                log.debug("No kind alias configuration found in attributes. Skipping payload modification.");
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kind configuration not found for the provided alias"));
            }

            String kindName = kindAliasConfigAttr.getKindName();
            String operation = resolveOperationFromRoute(exchange);

            int replenishRate = -1;
            int burstCapacity = -1;

            // 1) Try kind+operation (dynamic config from
            // app.rate-limits.kinds.{kind}.{operation})
            if (kindName != null && operation != null) {
                String replenishKey = "app.rate-limits." + recordType + ".kinds." + kindName + "." + operation
                        + ".replenishRate";
                String burstKey = "app.rate-limits." + recordType + ".kinds." + kindName + "." + operation
                        + ".burstCapacity";
                String replenishVal = environment.getProperty(replenishKey);
                String burstVal = environment.getProperty(burstKey);

                if (replenishVal != null) {
                    try {
                        replenishRate = Integer.parseInt(replenishVal);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (burstVal != null) {
                    try {
                        burstCapacity = Integer.parseInt(burstVal);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 1b) If operation-specific not found, try kind-level default:
            // app.rate-limits.kinds.{kindName}.default.*
            if (kindName != null && (replenishRate < 0 || burstCapacity < 0)) {
                String defReplenishKey = "app.rate-limits." + recordType + ".kinds." + kindName
                        + ".default.replenishRate";
                String defBurstKey = "app.rate-limits." + recordType + ".kinds." + kindName + ".default.burstCapacity";
                String defReplenishVal = environment.getProperty(defReplenishKey);
                String defBurstVal = environment.getProperty(defBurstKey);

                if (replenishRate < 0 && defReplenishVal != null) {

                    try {
                        replenishRate = Integer.parseInt(defReplenishVal);
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (burstCapacity < 0 && defBurstVal != null) {

                    try {
                        burstCapacity = Integer.parseInt(defBurstVal);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 2) Fallback to route args (these are set in application-routes.yml)
            if (replenishRate < 0) {
                replenishRate = config.getReplenishRate();
            }

            if (burstCapacity < 0) {
                burstCapacity = config.getBurstCapacity();
            }

            // 3) final fallback to global defaults if still null
            if (replenishRate < 0) {
                replenishRate = 10;
            }

            if (burstCapacity < 0) {
                burstCapacity = 20;
            }

            String key = resolveKey(exchange, kindName, operation);

            // Build a routeId to register the dynamic config into the RedisRateLimiter
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String dynamicRouteId = (route != null ? route.getId() : "dynamic-rate-limiter") + ":"
                    + (kindName != null ? kindName : "unknown") + ":" + (operation != null ? operation : "unknown");

            // Register per-request config into RedisRateLimiter's config map so isAllowed
            // uses these values
            // (RedisRateLimiter exposes a config map; add/override the entry for our
            // dynamicRouteId)

            try {
                RedisRateLimiter.Config rlConfig = new RedisRateLimiter.Config();
                rlConfig.setReplenishRate(replenishRate);
                rlConfig.setBurstCapacity(burstCapacity);
                redisRateLimiter.getConfig().put(dynamicRouteId, rlConfig);
            } catch (Exception e) {
                log.warn(
                        "Failed to register dynamic rate limiter config; proceeding with available redisRateLimiter defaults",
                        e);
            }

            log.debug("DynamicRateLimiter: key=" + key + ", replenishRate=" + replenishRate + ", burstCapacity="
                    + burstCapacity + ", routeId=" + dynamicRouteId);

            return redisRateLimiter.isAllowed(dynamicRouteId, key)
                    .flatMap(response -> {

                        if (response.isAllowed()) {
                            return chain.filter(exchange);
                        } else {
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            return exchange.getResponse().setComplete();
                        }
                    });
        };
    }

    /**
     * Uses the route id as the operation name for rate limit lookup.
     */
    private String resolveOperationFromRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return (route != null) ? route.getId() : null;
    }

    private String resolveKey(ServerWebExchange exchange, String kindName, String operation) {
        GatewaySecurityContext gc = (GatewaySecurityContext) exchange
                .getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

        // normalize kind/op
        String kind = kindName != null ? kindName : "unknown";
        String op = operation != null ? operation : "unknown";

        // try subject first (if available)
        String subject = null;

        if (gc != null) {
            try {
                subject = gc.getAuthSubject();
            } catch (Exception ignored) {
            }
        }

        if (subject != null && !subject.isEmpty()) {
            return subject + ":" + kind + ":" + op;
        }

        // fallback to IP (safe null checks)
        String ip = "unknown";

        if (exchange != null && exchange.getRequest() != null) {

            if (exchange.getRequest().getRemoteAddress() != null
                    && exchange.getRequest().getRemoteAddress().getAddress() != null) {
                String hostAddr = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();

                if (hostAddr != null && !hostAddr.isEmpty()) {
                    ip = hostAddr;
                }
            }
        }

        return kind + ":" + op + ":" + ip;
    }

    @Data
    public static class Config {
        private int replenishRate = 10; // fallback default
        private int burstCapacity = 20; // fallback default
        private String recordType;
    }
}
