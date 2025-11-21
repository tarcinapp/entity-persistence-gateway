package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig.KindAliasPathSingleConfig;

@Component
public class ConvertKindAliasToQueryForFindEntities
        extends AbstractGatewayFilterFactory<ConvertKindAliasToQueryForFindEntities.Config> {

    @Autowired
    private KindAliasPathsConfig kindAliasPathsConfig;
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

            logger.debug("Caller requested kind alias '" + kindAlias + "'. Checking if " + kindAlias
                    + " is configured as an entity kind.");

            KindAliasPathSingleConfig foundKindAliasPathConfig = kindAliasPathsConfig.getKindAliasPaths().stream()
                    .filter(entityKind -> Optional.ofNullable(entityKind.getAlias())
                            .equals(Optional.ofNullable(kindAlias)))
                    .findFirst()
                    .orElse(null);

            if (foundKindAliasPathConfig == null) {
                logger.debug("There is no kind alias configuration found for path /" + kindAlias);
                logger.debug("Exiting ConvertKindAliasToQuery filter with 404.");

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return response.setComplete();
            }

            logger.debug("/" + kindAlias + " is configured to entity kind: '" + foundKindAliasPathConfig.getName() + "'.");

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

                        logger.debug("Adding where filter for _kind.");

                        query.add(new BasicNameValuePair("filter[where][_kind]", foundKindAliasPathConfig.getName()));

                        String newQueryStr = query.stream()
                                .map(v -> v.getName() + "=" + v.getValue())
                                .collect(Collectors.joining("&"));

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