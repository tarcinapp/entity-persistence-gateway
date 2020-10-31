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
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig.EntityKindsSingleConfig;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;
import org.springframework.http.HttpStatus;

@Component
public class ConvertKindPathToQuery
        extends AbstractGatewayFilterFactory<ConvertKindPathToQuery.Config> {


    @Autowired
    private EntityKindsConfig entityKindsConfig;
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

            logger.debug("Caller requested kindPath '" + kindPath + "'. Checking if " + kindPath + " is configured as an entity kind.");

            EntityKindsSingleConfig foundEntityKindConfig = entityKindsConfig.getEntityKinds().stream()
                .filter(entityKind -> entityKind.getPathMap().equals(kindPath))
                .findFirst()
                .orElse(null);

            if(foundEntityKindConfig==null) {
                logger.debug("There is no kindPath configuration found for path /"+kindPath);
                logger.debug("Exiting ConvertKindPathToQuery filter with 404.");

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return response.setComplete();
            }

            logger.debug("/"+kindPath + " is configured to entity kind: '" + foundEntityKindConfig.getName() + "'.");

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

                    query.add(new BasicNameValuePair("filter[where][kind]", foundEntityKindConfig.getName()));

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