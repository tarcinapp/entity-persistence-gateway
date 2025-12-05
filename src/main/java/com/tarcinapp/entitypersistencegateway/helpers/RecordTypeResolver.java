package com.tarcinapp.entitypersistencegateway.helpers;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for resolving recordType with hierarchical fallback strategy.
 * 
 * Resolution order:
 * 1. Filter argument (highest priority) - explicitly defined in filter config
 * 2. Route metadata (fallback) - defined once at route level
 * 3. Error - throws exception if neither is found
 * 
 * This eliminates redundant recordType declarations across multiple filters
 * in the same route.
 */
@Slf4j
public class RecordTypeResolver {

    /**
     * Resolves recordType from filter config or route metadata.
     * 
     * @param filterRecordType recordType from filter's config (may be null)
     * @param exchange the current server web exchange
     * @return the resolved recordType
     * @throws IllegalArgumentException if recordType cannot be resolved
     */
    public static String resolve(String filterRecordType, ServerWebExchange exchange) {
        // Step 1: Check filter argument (highest priority)
        if (filterRecordType != null && !filterRecordType.isEmpty()) {
            log.debug("RecordType resolved from filter argument: {}", filterRecordType);
            return filterRecordType;
        }

        // Step 2: Fallback to route metadata
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && route.getMetadata() != null) {
            Object metaValue = route.getMetadata().get("recordType");
            if (metaValue != null) {
                String recordType = metaValue.toString();
                log.debug("RecordType resolved from route metadata for route {}: {}", route.getId(), recordType);
                return recordType;
            }
        }

        // Step 3: Validation - throw exception if not found
        String routeId = route != null ? route.getId() : "unknown";
        log.error("RecordType is missing in both filter config and route metadata for route: {}", routeId);
        throw new IllegalArgumentException(
            "recordType is missing in both Filter Config and Route Metadata for route: " + routeId
        );
    }

    /**
     * Resolves recordType from filter config or route metadata, with a custom filter name for logging.
     * 
     * @param filterRecordType recordType from filter's config (may be null)
     * @param exchange the current server web exchange
     * @param filterName name of the filter requesting resolution (for better error messages)
     * @return the resolved recordType
     * @throws IllegalArgumentException if recordType cannot be resolved
     */
    public static String resolve(String filterRecordType, ServerWebExchange exchange, String filterName) {
        try {
            return resolve(filterRecordType, exchange);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Filter '" + filterName + "' requires recordType but it's missing in both " +
                "Filter Config and Route Metadata", e
            );
        }
    }
}
