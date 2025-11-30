package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ConvertKindAliasToQueryForFindEntities
        extends AbstractGatewayFilterFactory<ConvertKindAliasToQueryForFindEntities.Config> {

    // Pattern Matcher for MultiValueMap keys
    private final static Pattern KIND_QUERY_PATTERN = Pattern.compile("filter\\[where\\]\\[_kind\\].*");

    public ConvertKindAliasToQueryForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            log.debug("ConvertKindAliasToQuery filter is started.");

            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindAlias = uriVariables.get("kindAlias");

            log.debug("Caller requested kind alias '{}'. Checking if {} is configured as an entity kind.", kindAlias,
                    kindAlias);

            KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

            // Defensive check: If attribute is missing or kind is not configured, skip logic.
            if (kindAliasConfigAttr == null || !kindAliasConfigAttr.isKindAliasConfigured()) {
                log.debug("No kind alias configuration found in attributes. Skipping payload modification.");
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kind configuration not found for the provided alias"));
            }

            String kindName = kindAliasConfigAttr.getKindName();

            URI uri = exchange.getRequest().getURI();
            log.debug("Original URI: {}", uri);

            // 1. Get original query parameters
            MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
            MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();

            // 2. Copy all query parameters except those related to _kind
            originalQueryParams.forEach((name, values) -> {
                Matcher matcher = KIND_QUERY_PATTERN.matcher(name);

                // If parameter does not match KIND_QUERY_PATTERN (i.e., not a _kind filter),
                // copy it.
                if (!matcher.matches()) {
                    newQueryParams.addAll(name, values);
                }
            });

            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(originalRequest -> {

                        log.debug("Adding where filter for _kind.");

                        // 3. Add new _kind filter
                        newQueryParams.add("filter[where][_kind]", kindName);

                        // --- URI Rebuild: Replace query parameters with MultiValueMap ---

                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                .replaceQueryParams(newQueryParams)
                                .encode(StandardCharsets.UTF_8)
                                .build()
                                .toUri();

                        // Log decoded URI for easier reading
                        try {
                            // For logging, encode MultiValueMap to get query string
                            String newQueryStr = UriComponentsBuilder.newInstance().queryParams(newQueryParams).build()
                                    .encode().getQuery();

                            String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                            if (newQueryStr != null && !newQueryStr.isEmpty()) {
                                // Use Charset.name() with URLDecoder
                                String decodedQuery = java.net.URLDecoder.decode(newQueryStr,
                                        StandardCharsets.UTF_8.name());
                                decodedUri += "?" + decodedQuery;
                            }
                            log.debug("New URI (decoded): {}", decodedUri);
                        } catch (Exception e) {
                            log.debug("New URI {} (failed to decode: {})", newUri, e.getMessage());
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