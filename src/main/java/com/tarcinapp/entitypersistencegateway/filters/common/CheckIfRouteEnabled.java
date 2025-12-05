package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

            // Route-level evaluation (only applies if controller didn't disable)
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

    @Data
    public static class Config {
        private String controllerName;
    }    
}
