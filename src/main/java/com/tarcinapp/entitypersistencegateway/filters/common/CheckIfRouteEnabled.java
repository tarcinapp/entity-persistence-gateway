package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * This filter reads app.routes.disabled configuration.
 * If the current route is within this list, then this filter returns 405 Method Not Allowed error.
 * Otherwise, it does not do anything.
 */
@Component
public class CheckIfRouteEnabled
    extends AbstractGatewayFilterFactory<CheckIfRouteEnabled.Config>  {

    private Logger logger = LogManager.getLogger(CheckIfRouteEnabled.class);
    
    @Value("${app.routes.disabled}")
    private String disabled;

    public CheckIfRouteEnabled() {
        super(CheckIfRouteEnabled.Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        
        return (exchange, chain) -> {
            logger.debug("CheckIfRouteEnabled filter is started");
            Route route = (Route)exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route.getId();
            List<String> disabledList = Arrays.stream(disabled.split("\\s*,\\s*"))
                    .map(String::trim)
                    .collect(Collectors.toList());

            if (disabledList.contains(routeId)) {
                logger.warn("Route " + routeId + " is disabled. Returning 405 Method Not Allowed");

                exchange.getResponse().setStatusCode(HttpStatus.METHOD_NOT_ALLOWED); // Method Not Allowed
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        };
    }

    public static class Config {

    }    
}
