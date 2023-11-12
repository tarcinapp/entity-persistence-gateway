package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.config.SavedQueryConfig;

/**
 * Gateway application can allow or prevent clients to send loopback specific
 * query parameters.
 * This behavior controlled by the configuration: app.allowLoopbackQueryNotation
 * In addition, gateway application can help creating loopback query parameters
 * easier by mapping the given
 * well-known query parameters to loopback specific equivalents. Using this
 * approach, user can hide the underlying technology.
 * This is what this filter does.
 * Mapped query parameters:
 * ?s=foo: This query parameter stands for searching in the names of the
 * entities in the loopback application.
 * Mapped as ?filter[where][name][regexp]=.*foo.*
 * 
 * As of today, preventing loopback specific filters and going with only the
 * parameters handled by this filter reduces
 * the total querying capability. For instance, client's can order records using
 * multiple fields:
 * ?filter[order][0]=name&filter[order][1]
 * If application is configured to not to allow loopback specific queries, then
 * they will be able to order using single field only.
 * ?order=name
 */
@Component
public class ConvertToLoopbackQuery extends AbstractGatewayFilterFactory<ConvertToLoopbackQuery.Config> {

    private Logger logger = LogManager.getLogger(ConvertToLoopbackQuery.class);

    // loopback query parameters
    private static final List<String> filterPrefixes = Arrays.asList("filter[where]", "filter[fields]",
            "filter[include]", "filter[limit]", "filter[order]", "filter[skip]");

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    @Value("${app.allowLoopbackQueryNotation:true}")
    private boolean allowLoopbackQueryNotation;

    @Autowired
    private SavedQueryConfig savedQueries;

    public ConvertToLoopbackQuery() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("ConvertToLoopbackQuery filter is started");

            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: " + uri);

            List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            // this variable is defined to pass to the SPEL of saved queries.
            Map<String, String> queryMap = query.stream()
                    .collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));

            List<NameValuePair> newQuery = query.stream()
                    .flatMap(nvp -> {

                        String name = nvp.getName();
                        String value = nvp.getValue();

                        // check if client sent a loopback specific query.
                        if (filterPrefixes.stream().anyMatch(name::startsWith)) {
                            logger.debug("Client sent loopback specific query parameters.");

                            if (this.allowLoopbackQueryNotation) {
                                // return the query as it is.
                                logger.debug("Application is configured to allow loopback specific query parameters.");

                                return Stream.of(nvp);
                            } else {
                                // do not move loopback specific queries to the new list
                                logger.debug(
                                        "Application is configured to prevent loopback specific query parameters.");

                                return Stream.empty();
                            }
                        }

                        // if client asked for a search operation
                        if ("s".equals(name) || "search".equals(name)) {
                            return this.createSearchQuery(nvp);
                        }

                        // if client is asked for a saved query
                        if ("q".equals(name) || "query".equals(name)) {

                            String savedQuery = savedQueries.getQueries().get(value);

                            if (savedQuery == null) {
                                logger.warn("Client requested a saved query: " + savedQuery
                                        + ". But there is no such query defined in application config.");
                                return Stream.empty();
                            }

                            GatewaySecurityContext gatewaySecurityContext = exchange
                                    .getAttribute(GATEWAY_SECURITY_CONTEXT_ATTR);

                            // Create a StandardEvaluationContext
                            StandardEvaluationContext context = new StandardEvaluationContext();

                            // set variables to the spel evaluation context here

                            // set gateway security context if we have in hand
                            if(gatewaySecurityContext == null)
                                context.setVariable("userId", gatewaySecurityContext.getAuthSubject());

                            // make existing query variables accessible by SPEL saved queries
                            context.setVariable("query", queryMap);

                            ExpressionParser parser = new SpelExpressionParser();
                            Expression expression = parser.parseExpression(savedQuery);
                            String resolvedQuery = (String) expression.getValue(context);
                            List<NameValuePair> nameValuePairs = URLEncodedUtils.parse(resolvedQuery,
                                    Charset.forName("UTF-8"));

                            return nameValuePairs.stream();
                        }

                        if ("fields".equals(name)) {
                            return this.createFieldsQuery(nvp);
                        }

                        if ("limit".equals(name)) {
                            NameValuePair newNvp = new BasicNameValuePair("filter[limit]", value);
                            return Stream.of(newNvp);
                        }

                        if ("skip".equals(name)) {
                            NameValuePair newNvp = new BasicNameValuePair("filter[skip]", value);
                            return Stream.of(newNvp);
                        }

                        if ("order".equals(name)) {
                            NameValuePair newNvp = new BasicNameValuePair("filter[order]", value);
                            return Stream.of(newNvp);
                        }

                        return Stream.of(nvp);
                    })
                    .collect(Collectors.toList());

            // as we built new query string, now we can go ahead and change the query from
            // the original request
            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(originalRequest -> {

                        String newQueryStr = newQuery.stream()
                                .map(v -> v.getName() + "=" + v.getValue())
                                .collect(Collectors.joining("&"));

                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                .replaceQuery(newQueryStr)
                                .encode()
                                .build()
                                .toUri();

                        logger.debug("New URI " + newUri);

                        originalRequest
                                .uri(newUri);
                    })
                    .build();

            return chain.filter(modifiedExchange);
        };
    }

    private Stream<NameValuePair> createFieldsQuery(NameValuePair nvp) {
        String value = nvp.getValue();

        return Arrays.stream(value.split(","))
                .map(fieldName -> {
                    String newKey = "filter[fields]";
                    return new BasicNameValuePair(newKey, fieldName);
                });
    }

    private Stream<NameValuePair> createSearchQuery(NameValuePair nvp) {
        String value = nvp.getValue();
        NameValuePair newNvp = new BasicNameValuePair("filter[where][name][regexp]", ".*" + value + ".*");

        return Stream.of(newNvp);
    }

    public static class Config {

    }
}
