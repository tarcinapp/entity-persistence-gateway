package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.tarcinapp.entitypersistencegateway.config.TogglesProperties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * This filter reads app.routes.disabled configuration.
 * If the current route is within this list, then this filter returns 405 Method Not Allowed error.
 * Otherwise, it does not do anything.
 */
@Component
@Slf4j
public class CheckIfRouteEnabled
    extends AbstractGatewayFilterFactory<CheckIfRouteEnabled.Config>  {    
    @Autowired
    private TogglesProperties toggles;

    public CheckIfRouteEnabled() {
        super(CheckIfRouteEnabled.Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        
        return (exchange, chain) -> {
            log.debug("CheckIfRouteEnabled filter is started");
            Route route = (Route)exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : null;

            List<String> routesOn = normalizeList(toggles.getRoutes().getOn());
            List<String> routesOff = normalizeList(toggles.getRoutes().getOff());
            List<String> controllersOn = normalizeList(toggles.getControllers().getOn());
            List<String> controllersOff = normalizeList(toggles.getControllers().getOff());
            List<String> tagsOn = normalizeList(toggles.getTags().getOn());
            List<String> tagsOff = normalizeList(toggles.getTags().getOff());

            String controllerName = config.getControllerName();
            boolean controllerDecidesDisabled = false;

            // Controller-level priority
            if (controllerName != null && !controllerName.trim().isEmpty()) {
                controllerName = controllerName.trim();
                if (!controllersOn.isEmpty()) {
                    // only controllers listed in controllersOn are enabled
                    controllerDecidesDisabled = !controllersOn.contains(controllerName);
                } else if (!controllersOff.isEmpty()) {
                    // controllers listed in controllersOff are disabled
                    controllerDecidesDisabled = controllersOff.contains(controllerName);
                } else {
                    controllerDecidesDisabled = false; // no controller rules
                }

                if (controllerDecidesDisabled) {
                    log.warn("Controller '" + controllerName + "' is disabled by controller toggles. Returning 404 Not Found for route " + routeId);
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                }
            }

            // Tag-level evaluation (only applies if controller didn't disable)
            boolean tagDecidesDisabled = false;
            
            if (!tagsOn.isEmpty() || !tagsOff.isEmpty()) {
                List<String> routeTags = extractTagsFromRoute(route);
                
                log.debug("CheckIfRouteEnabled evaluating tags for route: " + routeId + ", routeTags: " + routeTags + ", tagsOff: " + tagsOff + ", tagsOn: " + tagsOn);
                
                if (routeTags != null && !routeTags.isEmpty()) {
                    if (!tagsOn.isEmpty()) {
                        // only routes with tags listed in tagsOn are enabled
                        tagDecidesDisabled = routeTags.stream().noneMatch(tagsOn::contains);
                    } else if (!tagsOff.isEmpty()) {
                        // routes with tags listed in tagsOff are disabled
                        tagDecidesDisabled = routeTags.stream().anyMatch(tagsOff::contains);
                        if (tagDecidesDisabled) {
                            log.warn("Route " + routeId + " is disabled by tag toggles (has tags in tagsOff). Returning 404 Not Found");
                        }
                    }
                } else if (!tagsOn.isEmpty()) {
                    // route has no tags but tagsOn is set, so it's disabled
                    tagDecidesDisabled = true;
                    log.debug("Route " + routeId + " has no tags but tagsOn is configured: " + tagsOn);
                }
            }

            if (tagDecidesDisabled) {
                log.warn("Route " + routeId + " is disabled by tag toggles. Returning 404 Not Found");
                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                return exchange.getResponse().setComplete();
            }

            // Route-level evaluation (only applies if controller and tags didn't disable)
            boolean routeDisabled = false;

            if (routeId != null) {
                
                if (!routesOn.isEmpty()) {
                    routeDisabled = !routesOn.contains(routeId);
                } else if (!routesOff.isEmpty()) {
                    routeDisabled = routesOff.contains(routeId);
                } else {
                    routeDisabled = false;
                }
            }

            if (routeDisabled) {
                log.warn("Route " + routeId + " is disabled by route toggles. Returning 404 Not Found");
                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        };
    }

    private List<String> normalizeList(List<String> items) {
        return items == null ? java.util.Collections.emptyList()
                : items.stream().filter(s -> s != null).map(String::trim).collect(Collectors.toList());
    }

    private List<String> extractTagsFromRoute(Route route) {
        if (route == null || route.getMetadata() == null) {
            return null;
        }
        
        Object tagsObj = route.getMetadata().get("tags");
        if (tagsObj == null) {
            return null;
        }
        
        // Handle List directly
        if (tagsObj instanceof List<?>) {
            List<?> list = (List<?>) tagsObj;
            List<String> stringList = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    stringList.add(item.toString());
                }
            }
            return stringList;
        }
        
        // Handle Map (LinkedHashMap from YAML with numeric indices)
        if (tagsObj instanceof java.util.Map<?, ?>) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) tagsObj;
            List<String> stringList = new java.util.ArrayList<>();
            
            // Sort by numeric keys to preserve order
            map.entrySet().stream()
                .sorted((a, b) -> {
                    try {
                        int keyA = Integer.parseInt(a.getKey().toString());
                        int keyB = Integer.parseInt(b.getKey().toString());
                        return Integer.compare(keyA, keyB);
                    } catch (NumberFormatException e) {
                        return a.getKey().toString().compareTo(b.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    if (entry.getValue() != null) {
                        stringList.add(entry.getValue().toString());
                    }
                });
            
            return stringList.isEmpty() ? null : stringList;
        }
        
        log.debug("Tags object is not a List or Map. Type: " + tagsObj.getClass().getName() + ", Value: " + tagsObj);
        return null;
    }

    @Data
    public static class Config {
        private String controllerName;
    }    
}
