package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Backend loopback is allowing client to query data using stringified JSON.
 * Like: GET /api/activities/findOne?filter={"where":{"id":1234}}
 * 
 * This makes queries uncontrolled and makes hard to authorize client based on query parameters.
 * This filter, completely solves this issue with preventing clients to use json filters.
 */
@Component
public class PreventStringifiedJsonFilter extends AbstractGatewayFilterFactory<PreventStringifiedJsonFilter.Config> {
    
    private Logger logger = LogManager.getLogger(PreventStringifiedJsonFilter.class);

    public PreventStringifiedJsonFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        
        return (exchange, chain) -> {

            logger.debug("PreventStringifiedJsonFilter filter is started.");
            
            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: " + uri);

            List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            Optional<NameValuePair> filterQuery = query.stream()
                .filter(pair -> "filter".equals(pair.getName()))
                .findFirst();

                if (filterQuery.isPresent()) {
                    String filterValue = filterQuery.get().getValue();
                    
                    if (filterValue.startsWith("{")) {
                        logger.warn("Client tried to query with stringified JSON. Returning UNAUTHORIZED");
                        
                        // Return an unauthorized response
                        ServerHttpResponse response = exchange.getResponse();
                        response.setStatusCode(HttpStatus.UNAUTHORIZED);
                        return response.setComplete();
                    }
                }
    
                return chain.filter(exchange);
        };
    }

    public static class Config {


    }

}
