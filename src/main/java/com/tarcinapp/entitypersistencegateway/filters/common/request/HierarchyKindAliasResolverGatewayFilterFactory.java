package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.AliasConfig;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.AliasContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
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
 * HierarchyKindAliasResolverGatewayFilterFactory resolves domain-driven URL segments
 * (e.g., /folders/1/sub-folders) to technical paths (/folders/1/children) with proper
 * kind filtering.
 *
 * <p>This filter supports user-friendly hierarchical navigation where the URL segment
 * after the record ID represents a business domain concept (alias) rather than the
 * technical accessor (children/parents).</p>
 *
 * <h2>Resolution Strategy</h2>
 * <ol>
 *   <li>Retrieve the root resource's {@link KindAliasConfigAttr} from exchange attributes
 *       (populated by {@link KindResolutionGatewayFilterFactory})</li>
 *   <li>Extract the {@code hierarchyAlias} path variable from the URI template</li>
 *   <li>Search the OAS configuration:
 *     <ul>
 *       <li>First in the root alias's {@code children} list</li>
 *       <li>Then in the root alias's {@code parents} list if not found</li>
 *     </ul>
 *   </li>
 *   <li>Handle special cases:
 *     <ul>
 *       <li>{@code "children"} or {@code "parents"} literals → passthrough with root's kind</li>
 *       <li>Unresolved alias → 404 Not Found</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>Request Transformations</h2>
 * <ul>
 *   <li><b>Path Rewrite:</b> Replace {@code {hierarchyAlias}} with resolved path
 *       (children or parents)</li>
 *   <li><b>Query Injection:</b> Add {@code _kind={TargetKind}} to filter results</li>
 *   <li><b>Attribute Update:</b> Update {@link KindAliasConfigAttr} with target resource
 *       metadata for downstream filters</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <pre>
 * filters:
 * - name: HierarchyKindAliasResolver
 *   args:
 *     childrenAccessorSegment: children
 *     parentsAccessorSegment: parents
 * </pre>
 *
 * @see KindResolutionGatewayFilterFactory
 * @see OpenApiProperties
 */
@Component
@Slf4j
public class HierarchyKindAliasResolverGatewayFilterFactory
        extends AbstractGatewayFilterFactory<HierarchyKindAliasResolverGatewayFilterFactory.Config> {

    // Pattern to match existing _kind query parameters for removal before injection
    private static final Pattern KIND_QUERY_PATTERN = Pattern.compile("filter\\[where\\]\\[_kind\\].*");

    // Technical path segment constants for static fallback
    private static final String CHILDREN_LITERAL = "children";
    private static final String PARENTS_LITERAL = "parents";

    @Autowired(required = false)
    private OpenApiProperties openApiProperties;

    public HierarchyKindAliasResolverGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Restore MDC from exchange attributes for proper logging context
            MdcContextLifterConfiguration.restoreMdcFromExchange(exchange);

            // 1. Retrieve KindAliasConfigAttr set by KindResolution filter
            KindAliasConfigAttr rootAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);
            
            if (rootAttr == null || !rootAttr.isKindAliasConfigured()) {
                log.error("HierarchyKindAliasResolver: KindAliasConfigAttr not found or not configured. " +
                        "Ensure KindResolution filter runs before this filter.");
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Kind alias configuration not available"));
            }

            // 2. Extract hierarchyAlias from URI template variables
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String hierarchyAlias = uriVariables.get("hierarchyAlias");

            if (hierarchyAlias == null || hierarchyAlias.isBlank()) {
                log.debug("HierarchyKindAliasResolver: No hierarchyAlias in path. Passing through.");
                return chain.filter(exchange);
            }

            log.debug("HierarchyKindAliasResolver: Resolving hierarchyAlias='{}' for root kind='{}'",
                    hierarchyAlias, rootAttr.getKindName());

            // 3. Determine the controller context for OAS lookup
            String lookupController = rootAttr.getBaseControllerName();
            if (lookupController == null || lookupController.isBlank()) {
                lookupController = rootAttr.getControllerName();
            }

            // 4. Static fallback: If hierarchyAlias is exactly "children" or "parents"
            //    treat it as a technical accessor using the root's default kind
            if (CHILDREN_LITERAL.equals(hierarchyAlias) || PARENTS_LITERAL.equals(hierarchyAlias)) {
                log.debug("HierarchyKindAliasResolver: Static fallback for technical accessor '{}'", 
                        hierarchyAlias);
                // Rewrite path to technical outbound path and pass through without kind injection
                return handleStaticFallback(exchange, chain, rootAttr, hierarchyAlias);
            }

            // 5. Lookup the root alias configuration in OpenApiProperties
            if (openApiProperties == null) {
                log.warn("HierarchyKindAliasResolver: OpenApiProperties not available. Cannot resolve alias.");
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hierarchy alias '" + hierarchyAlias + "' could not be resolved"));
            }

            String rootAlias = rootAttr.getKindAlias();
            AliasContext rootContext = openApiProperties.getAliasContext(lookupController, rootAlias);

            if (rootContext == null || rootContext.getAliasConfig() == null) {
                log.warn("HierarchyKindAliasResolver: Root alias '{}' not found in controller '{}'",
                        rootAlias, lookupController);
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hierarchy alias '" + hierarchyAlias + "' could not be resolved"));
            }

            AliasConfig rootAliasConfig = rootContext.getAliasConfig();

            // 6. Search in children list
            Optional<AliasConfig> childMatch = findAliasInList(rootAliasConfig.getChildren(), hierarchyAlias);
            if (childMatch.isPresent()) {
                log.debug("HierarchyKindAliasResolver: Found '{}' in children list with kind='{}'",
                        hierarchyAlias, childMatch.get().getKind());
                return handleResolvedAlias(exchange, chain, rootAttr, childMatch.get(),
                        config.getChildrenAccessorSegment(), config);
            }

            // 7. Search in parents list
            Optional<AliasConfig> parentMatch = findAliasInList(rootAliasConfig.getParents(), hierarchyAlias);
            if (parentMatch.isPresent()) {
                log.debug("HierarchyKindAliasResolver: Found '{}' in parents list with kind='{}'",
                        hierarchyAlias, parentMatch.get().getKind());
                return handleResolvedAlias(exchange, chain, rootAttr, parentMatch.get(),
                        config.getParentsAccessorSegment(), config);
            }

            // 8. Alias not found - return 404
            log.warn("HierarchyKindAliasResolver: Alias '{}' not found in children or parents of root alias '{}'",
                    hierarchyAlias, rootAlias);
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Hierarchy alias '" + hierarchyAlias + "' not found for resource '" + rootAlias + "'"));
        };
    }

    /**
     * Searches for an alias in a list of AliasConfig entries.
     *
     * @param aliasList     The list of AliasConfig to search
     * @param targetAlias   The alias string to find
     * @return Optional containing the matching AliasConfig, or empty if not found
     */
    private Optional<AliasConfig> findAliasInList(List<AliasConfig> aliasList, String targetAlias) {
        if (aliasList == null || aliasList.isEmpty()) {
            return Optional.empty();
        }
        return aliasList.stream()
                .filter(ac -> ac != null && targetAlias.equals(ac.getAlias()))
                .findFirst();
    }

    /**
     * Handles the case where hierarchyAlias is exactly "children" or "parents".
     * No path transformation is needed, but we may optionally inject the root's kind
     * as a default filter.
     */
        private Mono<Void> handleStaticFallback(ServerWebExchange exchange,
                             org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                             KindAliasConfigAttr rootAttr,
                             String technicalAccessor) {
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        String technicalRecordPath = resolveTechnicalPath(rootAttr.getRecordType());
        String newPath = "/" + technicalRecordPath + "/" + recordId + "/" + technicalAccessor;

        URI originalUri = exchange.getRequest().getURI();
        URI newUri = UriComponentsBuilder.fromUri(originalUri)
            .replacePath(newPath)
            .encode(StandardCharsets.UTF_8)
            .build()
            .toUri();

        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
            .uri(newUri)
            .build();

        ServerWebExchange modifiedExchange = exchange.mutate()
            .request(modifiedRequest)
            .build();

        log.debug("HierarchyKindAliasResolver: Static fallback path rewrite to '{}'", newPath);

        return chain.filter(modifiedExchange);
    }

    /**
     * Handles a successfully resolved hierarchy alias by:
     * 1. Rewriting the path to use the technical accessor segment
     * 2. Injecting the resolved kind into query parameters
     * 3. Updating KindAliasConfigAttr for downstream filters with validation context
     */
    private Mono<Void> handleResolvedAlias(ServerWebExchange exchange,
                                            org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                            KindAliasConfigAttr rootAttr,
                                            AliasConfig targetAliasConfig,
                                            String technicalAccessorSegment,
                                            Config config) {
        
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");
        String targetKind = targetAliasConfig.getKind();
        String targetAlias = targetAliasConfig.getAlias();

        URI originalUri = exchange.getRequest().getURI();
        
        // Get HTTP method for determining route ID
        org.springframework.http.HttpMethod httpMethod = exchange.getRequest().getMethod();

        // 1. Path Rewrite: Build technical outbound path removing kindAlias and inbound base
        //    Pattern: /api/v1/entities/{alias}/{uuid}/{hierarchyAlias} -> /entities/{uuid}/{children|parents}
        String technicalRecordPath = resolveTechnicalPath(rootAttr.getRecordType());
        String newPath = "/" + technicalRecordPath + "/" + recordId + "/" + technicalAccessorSegment;

        log.debug("HierarchyKindAliasResolver: Path rewrite to '{}' (recordType='{}')", newPath, technicalRecordPath);

        // 2. Query Injection: Add _kind filter, removing any existing _kind parameters
        MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
        MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();

        // Copy all query params except _kind filters
        originalQueryParams.forEach((name, values) -> {
            Matcher matcher = KIND_QUERY_PATTERN.matcher(name);
            if (!matcher.matches()) {
                newQueryParams.addAll(name, values);
            }
        });

        // Inject the resolved kind
        newQueryParams.add("filter[where][_kind]", targetKind);

        log.debug("HierarchyKindAliasResolver: Injected _kind='{}' into query", targetKind);

        // 3. Build the new URI
        URI newUri = UriComponentsBuilder.fromUri(originalUri)
                .replacePath(newPath)
                .replaceQueryParams(newQueryParams)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        // 4. Update KindAliasConfigAttr for downstream filters
        KindAliasConfigAttr updatedAttr = new KindAliasConfigAttr();
        updatedAttr.setKindAliasConfigured(true);
        updatedAttr.setKindAlias(targetAlias);
        updatedAttr.setKindName(targetKind);
        // Preserve controller context from root
        updatedAttr.setControllerName(rootAttr.getControllerName());
        updatedAttr.setBaseControllerName(rootAttr.getBaseControllerName());
        updatedAttr.setRecordType(rootAttr.getRecordType());
        // Update original resource URL to reflect the hierarchical access pattern
        if (rootAttr.getOriginalResourceUrl() != null) {
            updatedAttr.setOriginalResourceUrl(rootAttr.getOriginalResourceUrl() + "/" + technicalAccessorSegment);
        }

        // 5. Set validation context fields for hierarchy request
        updatedAttr.setHierarchyRequest(true);
        
        // Build hierarchy schema key if target has inline schema defined
        // Format: "hierarchy:{controller}:{rootKind}:{targetAlias}"
        String hierarchySchemaKey = null;
        if (targetAliasConfig.getSchema() != null && !targetAliasConfig.getSchema().isBlank()) {
            String lookupController = rootAttr.getBaseControllerName();
            if (lookupController == null || lookupController.isBlank()) {
                lookupController = rootAttr.getControllerName();
            }
            hierarchySchemaKey = "hierarchy:" + lookupController + ":" 
                               + rootAttr.getKindName() + ":" + targetAlias;
            log.debug("HierarchyKindAliasResolver: Built hierarchySchemaKey='{}'", hierarchySchemaKey);
        }
        updatedAttr.setHierarchySchemaKey(hierarchySchemaKey);
        
        // Build hierarchy route schema key if target has route-level schema defined
        // Format: "hierarchy-route:{controller}:{rootKind}:{targetAlias}:{routeId}"
        String hierarchyRouteSchemaKey = null;
        String baseRouteId = determineBaseRouteId(httpMethod, technicalAccessorSegment, rootAttr.getRecordType());
        if (baseRouteId != null && targetAliasConfig.getRoutes() != null) {
            var routeConfig = targetAliasConfig.getRoutes().get(baseRouteId);
            if (routeConfig != null && routeConfig.getSchema() != null && !routeConfig.getSchema().isBlank()) {
                String lookupController = rootAttr.getBaseControllerName();
                if (lookupController == null || lookupController.isBlank()) {
                    lookupController = rootAttr.getControllerName();
                }
                hierarchyRouteSchemaKey = "hierarchy-route:" + lookupController + ":" 
                                   + rootAttr.getKindName() + ":" + targetAlias + ":" + baseRouteId;
                log.debug("HierarchyKindAliasResolver: Built hierarchyRouteSchemaKey='{}'", hierarchyRouteSchemaKey);
            }
        }
        updatedAttr.setHierarchyRouteSchemaKey(hierarchyRouteSchemaKey);
        
        // Compute effective validation enabled from target alias config
        Boolean effectiveValidationEnabled = targetAliasConfig.getValidationEnabled();
        if (effectiveValidationEnabled == null) {
            effectiveValidationEnabled = true; // Default enabled
        }
        updatedAttr.setEffectiveValidationEnabled(effectiveValidationEnabled);

        exchange.getAttributes().put(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, updatedAttr);

        log.debug("HierarchyKindAliasResolver: Updated KindAliasConfigAttr - alias='{}', kind='{}', " +
                  "hierarchyRequest=true, hierarchySchemaKey='{}', validationEnabled={}",
                updatedAttr.getKindAlias(), updatedAttr.getKindName(), 
                hierarchySchemaKey, effectiveValidationEnabled);

        // 6. Create modified exchange with new request
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .uri(newUri)
                .build();

        ServerWebExchange modifiedExchange = exchange.mutate()
                .request(modifiedRequest)
                .build();

        return chain.filter(modifiedExchange);
    }

    /**
     * Configuration class for HierarchyKindAliasResolver filter.
     */
    @Data
    public static class Config {
        /**
         * The technical path segment used for children accessor.
         * Default: "children"
         */
        private String childrenAccessorSegment = CHILDREN_LITERAL;

        /**
         * The technical path segment used for parents accessor.
         * Default: "parents"
         */
        private String parentsAccessorSegment = PARENTS_LITERAL;
    }

    /**
     * Maps the logical recordType (e.g., 'entityReactions') to the technical URL segment
     * (e.g., 'entity-reactions'). Defaults to the recordType value when no mapping needed.
     */
    private String resolveTechnicalPath(String recordType) {
        if (recordType == null) return null;
        switch (recordType) {
            case "entityReactions":
                return "entity-reactions";
            case "listReactions":
                return "list-reactions";
            default:
                return recordType;
        }
    }
    
    /**
     * Determines the base route ID for hierarchy operations based on HTTP method,
     * accessor type (children/parents), and record type.
     * 
     * Route IDs for hierarchy operations:
     * - POST children → createEntityChild, createListChild, createChildEntityReaction, createChildListReaction
     * - GET children → findEntityChildren, findListChildren, findChildrenEntityReactionsByReactionId, findChildrenListReactionsByReactionId
     * - GET parents → findEntityParents, findListParents, findParentsByEntityReactionId, findParentsByListReactionId
     */
    private String determineBaseRouteId(org.springframework.http.HttpMethod httpMethod, 
                                        String technicalAccessorSegment, 
                                        String recordType) {
        if (httpMethod == null || technicalAccessorSegment == null || recordType == null) {
            return null;
        }
        
        boolean isChildren = CHILDREN_LITERAL.equals(technicalAccessorSegment);
        boolean isParents = PARENTS_LITERAL.equals(technicalAccessorSegment);
        boolean isPost = org.springframework.http.HttpMethod.POST.equals(httpMethod);
        boolean isGet = org.springframework.http.HttpMethod.GET.equals(httpMethod);
        
        // Determine the base route ID based on record type and operation
        switch (recordType) {
            case "entities":
                if (isPost && isChildren) return "createEntityChild";
                if (isGet && isChildren) return "findEntityChildren";
                if (isGet && isParents) return "findEntityParents";
                break;
            case "lists":
                if (isPost && isChildren) return "createListChild";
                if (isGet && isChildren) return "findListChildren";
                if (isGet && isParents) return "findListParents";
                break;
            case "entityReactions":
                if (isPost && isChildren) return "createChildEntityReaction";
                if (isGet && isChildren) return "findChildrenEntityReactionsByReactionId";
                if (isGet && isParents) return "findParentsByEntityReactionId";
                break;
            case "listReactions":
                if (isPost && isChildren) return "createChildListReaction";
                if (isGet && isChildren) return "findChildrenListReactionsByReactionId";
                if (isGet && isParents) return "findParentsByListReactionId";
                break;
        }
        
        return null;
    }
}
