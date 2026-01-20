package com.tarcinapp.entitypersistencegateway.oas.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service to extract route metadata (tags, controller names, etc.) from Spring Cloud Gateway configuration.
 * 
 * <p>This service bridges the gap between Spring Cloud Gateway's route configuration
 * and the OAS transformation engine, allowing dynamic filtering based on route metadata.</p>
 * 
 * <h2>Usage:</h2>
 * <pre>
 * RouteMetadata metadata = routeMetadataService.getRouteMetadata("findEntities");
 * List&lt;String&gt; tags = metadata.getTags();
 * </pre>
 */
@Service
@Slf4j
public class RouteMetadataService {
    
    private final GatewayProperties gatewayProperties;
    
    public RouteMetadataService(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }
    
    /**
     * Gets metadata for a specific route by ID.
     * 
     * @param routeId The route ID (e.g., "findEntities", "createEntity")
     * @return RouteMetadata containing tags, controller name, etc., or null if not found
     */
    public RouteMetadata getRouteMetadata(String routeId) {
        return gatewayProperties.getRoutes().stream()
            .filter(route -> route.getId().equals(routeId))
            .findFirst()
            .map(this::extractMetadata)
            .orElse(null);
    }
    
    /**
     * Gets all route metadata indexed by route ID.
     * 
     * @return Map of route ID to RouteMetadata
     */
    public Map<String, RouteMetadata> getAllRouteMetadata() {
        Map<String, RouteMetadata> result = new HashMap<>();
        
        for (RouteDefinition route : gatewayProperties.getRoutes()) {
            RouteMetadata metadata = extractMetadata(route);
            result.put(route.getId(), metadata);
        }
        
        log.debug("Loaded metadata for {} routes", result.size());
        return result;
    }
    
    /**
     * Extracts metadata from a route definition.
     */
    private RouteMetadata extractMetadata(RouteDefinition route) {
        Map<String, Object> metadata = route.getMetadata();
        
        if (metadata == null) {
            return RouteMetadata.builder()
                .routeId(route.getId())
                .tags(Collections.emptyList())
                .build();
        }
        
        // Extract tags
        List<String> tags = new ArrayList<>();
        Object tagsObj = metadata.get("tags");
        if (tagsObj instanceof List) {
            for (Object tag : (List<?>) tagsObj) {
                if (tag != null) {
                    tags.add(tag.toString());
                }
            }
        }
        
        // Extract controller name
        String controllerName = null;
        Object controllerObj = metadata.get("controllerName");
        if (controllerObj != null) {
            controllerName = controllerObj.toString();
        }
        
        // Extract record type
        String recordType = null;
        Object recordTypeObj = metadata.get("recordType");
        if (recordTypeObj != null) {
            recordType = recordTypeObj.toString();
        }
        
        return RouteMetadata.builder()
            .routeId(route.getId())
            .tags(tags)
            .controllerName(controllerName)
            .recordType(recordType)
            .rawMetadata(metadata)
            .build();
    }
    
    /**
     * DTO containing route metadata.
     */
    @lombok.Data
    @lombok.Builder
    public static class RouteMetadata {
        private String routeId;
        private List<String> tags;
        private String controllerName;
        private String recordType;
        private Map<String, Object> rawMetadata;
        
        /**
         * Checks if this route has a specific tag.
         */
        public boolean hasTag(String tag) {
            return tags != null && tags.contains(tag);
        }
        
        /**
         * Checks if this route has any of the given tags.
         */
        public boolean hasAnyTag(Collection<String> tagsToCheck) {
            if (tags == null || tags.isEmpty() || tagsToCheck == null || tagsToCheck.isEmpty()) {
                return false;
            }
            for (String tag : tagsToCheck) {
                if (tags.contains(tag)) {
                    return true;
                }
            }
            return false;
        }
    }
}
