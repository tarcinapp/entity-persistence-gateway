package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.AliasConfig;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.AliasContext;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.ThroughConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ThroughKindAliasResolverGatewayFilterFactory resolves the {throughAlias} path variable
 * in through-kind-alias routes. It looks up the through alias in the parent alias's
 * ThroughConfig (reactions/entities/lists) and overwrites KindAliasConfigAttr with the
 * through kind's metadata.
 *
 * <p>This filter follows the same pattern as {@link HierarchyKindAliasResolverGatewayFilterFactory}:
 * it runs after {@link KindResolutionGatewayFilterFactory} and overwrites the kindName
 * with the "deeper" (through) kind.</p>
 *
 * <h2>Resolution Strategy</h2>
 * <ol>
 *   <li>Retrieve KindAliasConfigAttr from exchange (set by KindResolution)</li>
 *   <li>Extract throughAlias from URI template variables</li>
 *   <li>Determine which through list to search based on route metadata recordType</li>
 *   <li>Search the matching through list for the alias</li>
 *   <li>On match: overwrite KindAliasConfigAttr, inject _kind query for GET requests</li>
 *   <li>If not found: return 404</li>
 * </ol>
 *
 * <h2>Key Difference from HierarchyKindAliasResolver</h2>
 * <p>Path rewrite is NOT handled by this filter — it stays at route level via RewritePath,
 * because through routes have a static backend path (unlike hierarchy routes where the
 * target segment depends on resolution).</p>
 *
 * <h2>Configuration</h2>
 * <pre>
 * filters:
 * - name: ThroughKindAliasResolver
 *   args:
 *     throughSegment: reactions    # which through list to search: reactions, entities, or lists
 * </pre>
 *
 * @see KindResolutionGatewayFilterFactory
 * @see HierarchyKindAliasResolverGatewayFilterFactory
 */
@Component
@Slf4j
public class ThroughKindAliasResolverGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ThroughKindAliasResolverGatewayFilterFactory.Config> {

    private static final Pattern KIND_QUERY_PATTERN = Pattern.compile("filter\\[where\\]\\[_kind\\].*");

    @Autowired(required = false)
    private OpenApiProperties openApiProperties;

    public ThroughKindAliasResolverGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            MdcContextLifterConfiguration.restoreMdcFromExchange(exchange);

            // 1. Retrieve KindAliasConfigAttr set by KindResolution filter
            KindAliasConfigAttr rootAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

            if (rootAttr == null || !rootAttr.isKindAliasConfigured()) {
                log.error("ThroughKindAliasResolver: KindAliasConfigAttr not found or not configured. " +
                        "Ensure KindResolution filter runs before this filter.");
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Kind alias configuration not available"));
            }

            // 2. Extract throughAlias from URI template variables
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String throughAlias = uriVariables.get("throughAlias");

            if (throughAlias == null || throughAlias.isBlank()) {
                log.debug("ThroughKindAliasResolver: No throughAlias in path. Passing through.");
                return chain.filter(exchange);
            }

            log.debug("ThroughKindAliasResolver: Resolving throughAlias='{}' for root kind='{}', throughSegment='{}'",
                    throughAlias, rootAttr.getKindName(), config.getThroughSegment());

            // 3. Lookup the root alias configuration
            if (openApiProperties == null) {
                log.warn("ThroughKindAliasResolver: OpenApiProperties not available. Cannot resolve alias.");
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Through alias '" + throughAlias + "' could not be resolved"));
            }

            String lookupController = rootAttr.getBaseControllerName();
            if (lookupController == null || lookupController.isBlank()) {
                lookupController = rootAttr.getControllerName();
            }

            String rootAlias = rootAttr.getKindAlias();
            AliasContext rootContext = openApiProperties.getAliasContext(lookupController, rootAlias);

            if (rootContext == null || rootContext.getAliasConfig() == null) {
                log.warn("ThroughKindAliasResolver: Root alias '{}' not found in controller '{}'",
                        rootAlias, lookupController);
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Through alias '" + throughAlias + "' could not be resolved"));
            }

            AliasConfig rootAliasConfig = rootContext.getAliasConfig();

            // 4. Get the through config and search the appropriate list
            ThroughConfig throughConfig = rootAliasConfig.getThrough();
            if (throughConfig == null) {
                log.warn("ThroughKindAliasResolver: No through config defined for alias '{}'", rootAlias);
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Through alias '" + throughAlias + "' not found for resource '" + rootAlias + "'"));
            }

            String throughSegment = config.getThroughSegment();
            List<AliasConfig> throughList = getThroughList(throughConfig, throughSegment);

            if (throughList == null || throughList.isEmpty()) {
                log.warn("ThroughKindAliasResolver: No through.{} entries for alias '{}'",
                        throughSegment, rootAlias);
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Through alias '" + throughAlias + "' not found for resource '" + rootAlias + "'"));
            }

            // 5. Search for the throughAlias in the list
            Optional<AliasConfig> match = throughList.stream()
                    .filter(ac -> ac != null && throughAlias.equals(ac.getAlias()))
                    .findFirst();

            if (match.isEmpty()) {
                log.warn("ThroughKindAliasResolver: Alias '{}' not found in through.{} of root alias '{}'",
                        throughAlias, throughSegment, rootAlias);
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Through alias '" + throughAlias + "' not found for resource '" + rootAlias + "'"));
            }

            AliasConfig targetAliasConfig = match.get();
            String targetKind = targetAliasConfig.getKind();

            log.debug("ThroughKindAliasResolver: Resolved throughAlias='{}' to kind='{}'",
                    throughAlias, targetKind);

            // 6. Update KindAliasConfigAttr for downstream filters
            KindAliasConfigAttr updatedAttr = new KindAliasConfigAttr();
            updatedAttr.setKindAliasConfigured(true);
            updatedAttr.setKindAlias(throughAlias);
            updatedAttr.setKindName(targetKind);
            updatedAttr.setControllerName(rootAttr.getControllerName());
            updatedAttr.setBaseControllerName(rootAttr.getBaseControllerName());
            updatedAttr.setRecordType(rootAttr.getRecordType());

            // Fix originalResourceUrl: KindResolution constructed it using the through
            // record's recordType (e.g. /entity-reactions/<parentId>), but the parent
            // is actually the root entity/list. Correct it to /<parentPath>/<recordId>.
            String recordId = uriVariables.get("recordId");
            if (recordId != null) {
                // lookupController is "entities" or "lists" — same as the technical path
                updatedAttr.setOriginalResourceUrl("/" + lookupController + "/" + recordId);
            } else {
                updatedAttr.setOriginalResourceUrl(rootAttr.getOriginalResourceUrl());
            }

            // Mark as through request
            updatedAttr.setThroughRequest(true);

            // Build through schema key
            String throughSchemaKey = "through:" + lookupController + ":"
                    + rootAttr.getKindName() + ":" + throughAlias;
            updatedAttr.setThroughSchemaKey(throughSchemaKey);

            // Build through route schema key
            String baseRouteId = determineBaseRouteId(exchange, throughSegment, lookupController);
            if (baseRouteId != null) {
                String throughRouteSchemaKey = "through-route:" + lookupController + ":"
                        + rootAttr.getKindName() + ":" + throughAlias + ":" + baseRouteId;
                updatedAttr.setThroughRouteSchemaKey(throughRouteSchemaKey);
            }

            // Compute effective validation enabled
            Boolean effectiveValidationEnabled = targetAliasConfig.getValidationEnabled();
            if (effectiveValidationEnabled == null) {
                effectiveValidationEnabled = true;
            }
            updatedAttr.setEffectiveValidationEnabled(effectiveValidationEnabled);

            exchange.getAttributes().put(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, updatedAttr);

            // 7. For GET requests, inject _kind query filter
            HttpMethod httpMethod = exchange.getRequest().getMethod();
            if (HttpMethod.GET.equals(httpMethod) && targetKind != null) {
                URI originalUri = exchange.getRequest().getURI();

                MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();
                exchange.getRequest().getQueryParams().forEach((name, values) -> {
                    Matcher matcher = KIND_QUERY_PATTERN.matcher(name);
                    if (!matcher.matches()) {
                        newQueryParams.addAll(name, values);
                    }
                });
                newQueryParams.add("filter[where][_kind]", targetKind);

                URI newUri = UriComponentsBuilder.fromUri(originalUri)
                        .replaceQueryParams(newQueryParams)
                        .encode(StandardCharsets.UTF_8)
                        .build()
                        .toUri();

                log.debug("ThroughKindAliasResolver: Injected _kind='{}' into query", targetKind);

                ServerWebExchange modifiedExchange = exchange.mutate()
                        .request(exchange.getRequest().mutate().uri(newUri).build())
                        .build();

                // Preserve the updated attribute in the new exchange
                modifiedExchange.getAttributes().put(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, updatedAttr);

                return chain.filter(modifiedExchange);
            }

            return chain.filter(exchange);
        };
    }

    private List<AliasConfig> getThroughList(ThroughConfig throughConfig, String throughSegment) {
        if (throughSegment == null) return null;
        switch (throughSegment) {
            case "reactions":
                return throughConfig.getReactions();
            case "entities":
                return throughConfig.getEntities();
            case "lists":
                return throughConfig.getLists();
            default:
                log.warn("ThroughKindAliasResolver: Unknown throughSegment '{}'", throughSegment);
                return null;
        }
    }

    private String determineBaseRouteId(ServerWebExchange exchange, String throughSegment, String baseController) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) return null;
        // Extract the base route ID from the current route ID by removing "ByKindAlias" suffix
        String routeId = route.getId();
        if (routeId != null && routeId.endsWith("ByKindAlias")) {
            return routeId.substring(0, routeId.length() - "ByKindAlias".length());
        }
        return routeId;
    }

    @Data
    public static class Config {
        /**
         * Which through list to search: "reactions", "entities", or "lists".
         */
        private String throughSegment;
    }
}
