package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig.KindAliasPathSingleConfig;

@Component
public class ConvertKindAliasToQueryForFindEntities
        extends AbstractGatewayFilterFactory<ConvertKindAliasToQueryForFindEntities.Config> {

    @Autowired
    private KindAliasPathsConfig kindAliasPathsConfig;
    
    // Pattern Matcher for MultiValueMap keys
    private final static Pattern KIND_QUERY_PATTERN = Pattern.compile("filter\\[where\\]\\[_kind\\].*");
    private Logger logger = LogManager.getLogger(ConvertKindAliasToQueryForFindEntities.class);

    public ConvertKindAliasToQueryForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            logger.debug("ConvertKindAliasToQuery filter is started.");

            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindAlias = uriVariables.get("kindAlias");

            logger.debug("Caller requested kind alias '{}'. Checking if {} is configured as an entity kind.", kindAlias, kindAlias);

            KindAliasPathSingleConfig foundKindAliasPathConfig = kindAliasPathsConfig.getKindAliasPaths().stream()
                    .filter(entityKind -> Optional.ofNullable(entityKind.getAlias())
                            .equals(Optional.ofNullable(kindAlias)))
                    .findFirst()
                    .orElse(null);

            if (foundKindAliasPathConfig == null) {
                logger.debug("There is no kind alias configuration found for path /{}", kindAlias);
                logger.debug("Exiting ConvertKindAliasToQuery filter with 404.");

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return response.setComplete();
            }

            logger.debug("/{}} is configured to entity kind: '{}'.", kindAlias, foundKindAliasPathConfig.getName());

            
            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: {}", uri);

            // 1. Get original query parameters
            MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
            MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();

            // 2. Copy all query parameters except those related to _kind
            originalQueryParams.forEach((name, values) -> {
                Matcher matcher = KIND_QUERY_PATTERN.matcher(name);

                // If parameter does not match KIND_QUERY_PATTERN (i.e., not a _kind filter), copy it.
                if (!matcher.matches()) {
                    newQueryParams.addAll(name, values);
                }
            });

            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(originalRequest -> {

                        logger.debug("Adding where filter for _kind.");

                        // 3. Add new _kind filter
                        newQueryParams.add("filter[where][_kind]", foundKindAliasPathConfig.getName());

                        // --- URI Rebuild: Replace query parameters with MultiValueMap ---
                        
                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                .replaceQueryParams(newQueryParams)
                                .encode(StandardCharsets.UTF_8)
                                .build()
                                .toUri();

                        // Log decoded URI for easier reading
                        try {
                            // For logging, encode MultiValueMap to get query string
                            String newQueryStr = UriComponentsBuilder.newInstance().queryParams(newQueryParams).build().encode().getQuery();
                            
                            String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                            if (newQueryStr != null && !newQueryStr.isEmpty()) {
                                // Use Charset.name() with URLDecoder
                                String decodedQuery = java.net.URLDecoder.decode(newQueryStr, StandardCharsets.UTF_8.name());
                                decodedUri += "?" + decodedQuery;
                            }
                            logger.debug("New URI (decoded): {}", decodedUri);
                        } catch (Exception e) {
                            logger.debug("New URI {} (failed to decode: {})", newUri, e.getMessage());
                        }

                        originalRequest.uri(newUri);
                    })
                    .build();

            return chain.filter(modifiedExchange);
        };
    }

    public static class Config {

    }
}