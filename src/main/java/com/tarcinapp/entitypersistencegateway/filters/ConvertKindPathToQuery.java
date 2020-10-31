package com.tarcinapp.entitypersistencegateway.filters;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewayContext;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

@Component
public class ConvertKindPathToQuery
        extends AbstractGatewayFilterFactory<ConvertKindPathToQuery.Config> {

    private final static Pattern KIND_QUERY_PATTERN = Pattern.compile("filter\\[where\\]\\[kind\\].*");
    private Logger logger = LogManager.getLogger(ConvertKindPathToQuery.class);

    public ConvertKindPathToQuery() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            logger.debug("ConvertKindPathToQuery filter is started.");

            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindPath = uriVariables.get("kindPath");

            // remove any kind of query variables about kind field
            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: " + uri);

            List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            query
                .removeIf((nvp) -> {
                    Matcher matcher = KIND_QUERY_PATTERN.matcher(nvp.getName());

                    return matcher.matches();
                });

            ServerWebExchange modifiedExchange = exchange.mutate()
                .request(originalRequest -> {

                    logger.debug("Adding where filter for kind.");

                    query.add(new BasicNameValuePair("filter[where][kind]", kindPath));

                    String newQueryStr = query.stream()
                        .map(v -> v.getName() + "=" +v.getValue())
                        .collect(Collectors.joining( "&" ));

                    URI newUri = UriComponentsBuilder.fromUri(uri)
                        .replaceQuery(newQueryStr)
                        .encode()
                        .build()
                        .toUri();

                    logger.debug("New URI: " + newUri);

                    originalRequest.uri(newUri);
                })
                .build();

            return chain.filter(modifiedExchange);
        };
    }
     
    public static class Config {
        
    }
}