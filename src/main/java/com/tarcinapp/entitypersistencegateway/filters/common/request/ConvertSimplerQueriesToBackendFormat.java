package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.config.SavedQueryConfig;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway application can allow or prevent clients to send backend specific
 * query parameters.
 * This behavior controlled by the configuration: app.allowBackendQueryNotation
 * In addition, gateway application can help creating backend query parameters
 * easier by mapping the given
 * well-known query parameters to backend specific equivalents. Using this
 * approach, user can hide the underlying technology.
 * This is what this filter does.
 * Mapped query parameters:
 * ?s=foo: This query parameter stands for searching in the names of the
 * entities in the backend application.
 * Mapped as ?filter[where][name][regexp]=.*foo.*
 *  * As of today, preventing backend specific filters and going with only the
 * parameters handled by this filter reduces
 * the total querying capability. For instance, client's can order records using
 * multiple fields:
 * ?filter[order][0]=name&filter[order][1]
 * If application is configured to not to allow backend specific queries, then
 * they will be able to order using single field only.
 * ?order=name
 */
@Component
@Slf4j
public class ConvertSimplerQueriesToBackendFormat extends AbstractGatewayFilterFactory<ConvertSimplerQueriesToBackendFormat.Config> {
    // backend query parameters — filter[*] family (find routes) and where[*] family (updateAll/count/deleteAll routes)
    private static final List<String> filterPrefixes = Arrays.asList("filter[where]", "filter[fields]",
            "filter[include]", "filter[lookup]", "filter[limit]", "filter[order]", "filter[skip]",
            "where[", "entityWhere[", "listWhere[");

    @Value("${app.allowBackendQueryNotation:true}")
    private boolean allowBackendQueryNotation;

    @Autowired
    private SavedQueryConfig savedQueries;
    
    // SpEL Parser is created only once
    private final ExpressionParser spelParser = new SpelExpressionParser();

    public ConvertSimplerQueriesToBackendFormat() {
        super(Config.class);
    }


    @Data
    private static class QueryParam {
        public final String name;
        public final String value;

        public QueryParam(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }


    /**
     * Replaces the logic of URLEncodedUtils.parse and NameValuePair iteration.
     * Takes a MultiValueMap and returns a flat stream of QueryParam objects.
     */
    private Stream<QueryParam> flattenQueryParams(MultiValueMap<String, String> multiMap) {
        return multiMap.entrySet().stream()
            .flatMap(entry -> entry.getValue().stream()
                .map(value -> new QueryParam(entry.getKey(), value)));
    }


    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            log.debug("ConvertSimplerQueriesToBackendFormat filter is started");

            URI uri = exchange.getRequest().getURI();
            log.debug("Original URI: {}", uri);

            
            MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
            
            
            List<QueryParam> flatQueryParams = flattenQueryParams(originalQueryParams).collect(Collectors.toList());

            // this variable is defined to pass to the SPEL of saved queries.
            // Note: Using filtering to handle null values since Collectors.toMap doesn't accept nulls
            Map<String, String> queryMap = flatQueryParams.stream()
                    .filter(qp -> qp.getValue() != null)
                    .collect(Collectors.toMap(QueryParam::getName, QueryParam::getValue));

            
            List<QueryParam> newQuery = flatQueryParams.stream()
                    .flatMap(qp -> {

                        String name = qp.getName();
                        String value = qp.getValue();

                        // check if client sent a backend specific query.
                        if (filterPrefixes.stream().anyMatch(name::startsWith)) {
                            log.debug("Client sent backend specific query parameters.");

                            if (this.allowBackendQueryNotation) {
                                // return the query as it is.
                                log.debug("Application is configured to allow backend specific query parameters.");

                                return Stream.of(qp);
                            } else {
                                // do not move backend specific queries to the new list
                                log.debug(
                                        "Application is configured to prevent backend specific query parameters.");

                                return Stream.empty();
                            }
                        }

                        // if client asked for a search operation
                        if ("s".equals(name) || "search".equals(name)) {
                            return this.createSearchQuery(value, config.isUseWhereNotation());
                        }

                        // if client is asked for a saved query
                        if ("q".equals(name) || "query".equals(name)) {

                            String savedQuery = savedQueries.getQueries().get(value);

                            if (savedQuery == null) {
                                log.warn("Client requested a saved query: {}"
                                        + ". But there is no such query defined in application config.", value);
                                return Stream.empty();
                            }

                            GatewaySecurityContext gatewaySecurityContext = exchange
                                    .getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

                            // Create a StandardEvaluationContext
                            StandardEvaluationContext context = new StandardEvaluationContext();

                            // set variables to the spel evaluation context here

                            // set gateway security context if we have in hand
                            if(gatewaySecurityContext != null)
                                context.setVariable("userId", gatewaySecurityContext.getAuthSubject());

                            // make existing query variables accessible by SPEL saved queries
                            context.setVariable("query", queryMap);

                            // ExpressionParser parser = new SpelExpressionParser();
                            Expression expression = spelParser.parseExpression(savedQuery);
                            String resolvedQuery = (String) expression.getValue(context);
                            
                            
                            MultiValueMap<String, String> parsedParams = UriComponentsBuilder.fromUriString("?" + resolvedQuery).build().getQueryParams();
                            
                            
                            return flattenQueryParams(parsedParams);
                        }

                        if ("fields".equals(name)) {
                            if (config.isUseWhereNotation()) {
                                log.debug("fields param has no equivalent in where[*] notation, dropping.");
                                return Stream.empty();
                            }
                            return this.createFieldsQuery(value);
                        }

                        if ("include".equals(name)) {
                            if (config.isUseWhereNotation()) {
                                log.debug("include param has no equivalent in where[*] notation, dropping.");
                                return Stream.empty();
                            }
                            return this.createIncludeQuery(value);
                        }

                        if ("lookup".equals(name)) {
                            if (config.isUseWhereNotation()) {
                                log.debug("lookup param has no equivalent in where[*] notation, dropping.");
                                return Stream.empty();
                            }
                            return this.createLookupQuery(value);
                        }

                        if ("limit".equals(name)) {
                            if (config.isUseWhereNotation()) {
                                log.debug("limit param has no equivalent in where[*] notation, dropping.");
                                return Stream.empty();
                            }
                            QueryParam newQp = new QueryParam("filter[limit]", value);
                            return Stream.of(newQp);
                        }

                        if ("skip".equals(name)) {
                            if (config.isUseWhereNotation()) {
                                log.debug("skip param has no equivalent in where[*] notation, dropping.");
                                return Stream.empty();
                            }
                            QueryParam newQp = new QueryParam("filter[skip]", value);
                            return Stream.of(newQp);
                        }

                        if ("order".equals(name)) {
                            if (config.isUseWhereNotation()) {
                                log.debug("order param has no equivalent in where[*] notation, dropping.");
                                return Stream.empty();
                            }
                            QueryParam newQp = new QueryParam("filter[order]", value);
                            return Stream.of(newQp);
                        }

                        return Stream.of(qp);
                    })
                    .collect(Collectors.toList());

            
            MultiValueMap<String, String> finalQueryParams = new LinkedMultiValueMap<>();
            newQuery.forEach(qp -> finalQueryParams.add(qp.getName(), qp.getValue()));

            // as we built new query string, now we can go ahead and change the query from
            // the original request
            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(originalRequest -> {

                        // URI Rebuild: Now using MultiValueMap. No need for String joining.
                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                .replaceQueryParams(finalQueryParams)
                                .encode(StandardCharsets.UTF_8)
                                .build()
                                .toUri();

                        log.debug("New URI {}", newUri);

                        originalRequest
                                .uri(newUri);
                    })
                    .build();

            return chain.filter(modifiedExchange);
        };
    }

    
    
    private Stream<QueryParam> createFieldsQuery(String value) {
        return Arrays.stream(value.split(","))
                .map(fieldName -> {
                    String newKey = "filter[fields][" + fieldName.trim() + "]";
                    return new QueryParam(newKey, "true");
                });
    }

    private Stream<QueryParam> createIncludeQuery(String value) {
        String[] relations = value.split(",");
        return java.util.stream.IntStream.range(0, relations.length)
                .mapToObj(i -> new QueryParam(
                        "filter[include][" + i + "][relation]",
                        relations[i].trim()));
    }

    private Stream<QueryParam> createLookupQuery(String value) {
        String[] props = value.split(",");
        return java.util.stream.IntStream.range(0, props.length)
                .mapToObj(i -> new QueryParam(
                        "filter[lookup][" + i + "][prop]",
                        props[i].trim()));
    }

    private Stream<QueryParam> createSearchQuery(String value, boolean useWhereNotation) {
        String key = useWhereNotation ? "where[_name][regexp]" : "filter[where][_name][regexp]";
        QueryParam newQp = new QueryParam(key, ".*" + value + ".*");
        return Stream.of(newQp);
    }

    @Data
    public static class Config {
        /**
         * When true, simplified params are translated to the where[*] query family
         * instead of filter[where][*]. Use this for updateAll, count, and deleteAll
         * routes which only accept where[*] notation from the backend.
         * params that have no equivalent in where[*] notation (limit, skip, order,
         * fields) are silently dropped.
         */
        private boolean useWhereNotation = false;
    }
}