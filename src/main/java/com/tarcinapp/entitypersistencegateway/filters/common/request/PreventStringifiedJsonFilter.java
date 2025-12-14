package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.net.URI;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import lombok.extern.slf4j.Slf4j;

/**
 *  * Backend is allowing client to query data using stringified JSON.
 *  * Like: GET /api/activities/findOne?filter={"where":{"id":1234}}
 *  *  * This makes queries uncontrolled and makes hard to authorize client
 * based on query parameters.
 *  * This filter, completely solves this issue with preventing clients to use
 * json filters.
 *  
 */
@Component
@Slf4j
public class PreventStringifiedJsonFilter extends AbstractGatewayFilterFactory<PreventStringifiedJsonFilter.Config> {
    public PreventStringifiedJsonFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            log.debug("PreventStringifiedJsonFilter filter is started.");

            URI uri = exchange.getRequest().getURI();
            log.debug("Original URI: {}", uri);

            
            MultiValueMap<String, String> queryParams = exchange.getRequest().getQueryParams();

            
            String filterValue = queryParams.getFirst("filter");

            if (filterValue != null) {
                if (filterValue.startsWith("{")) {
                    log.warn("Client tried to query with stringified JSON. Returning UNAUTHORIZED");

                    // Return an unauthorized response
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return response.setComplete();
                }
            }

            return chain.filter(exchange);
        };
    }

    public static class Config {

    }

}