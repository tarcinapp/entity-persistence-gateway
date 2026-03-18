package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.registry.TypeHintSchemaRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * InjectTypeHintsToQueryFilterFactory automatically appends LoopBack 4 type-hint
 * query parameters (e.g. {@code filter[where][pageCount][type]=number}) so that
 * clients do not have to include them manually.
 *
 * <p>The filter consults {@link TypeHintSchemaRegistry} – populated at startup from
 * the OpenAPI alias schema definitions – to determine whether a query field that
 * appears in a where-clause should receive a {@code number} or {@code boolean}
 * type hint.
 *
 * <p><b>Supported where-clause families</b>
 * <ul>
 *   <li>{@code filter[where][…]} – standard entity / list queries</li>
 *   <li>{@code entityFilter[where][…]} – entity-scoped queries on reaction/through routes</li>
 *   <li>{@code listFilter[where][…]} – list-scoped queries on reaction/through routes</li>
 *   <li>{@code filterThrough[where][…]} – through-relation queries</li>
 *   <li>{@code where[…]} – bulk updateAll / deleteAll / count style queries</li>
 *   <li>{@code entityWhere[…]} – entity-scoped bulk queries</li>
 *   <li>{@code listWhere[…]} – list-scoped bulk queries</li>
 * </ul>
 *
 * <p><b>Type-hint injection rules</b>
 * <ul>
 *   <li>Implicit-eq key ({@code filter[where][price]}):
 *       adds {@code filter[where][price][type]=number}</li>
 *   <li>Explicit-op key ({@code filter[where][price][gt]}):
 *       keeps original key, adds {@code filter[where][price][type]=number} as a new sibling</li>
 *   <li>Array-value op key ({@code filter[where][price][inq][0]}):
 *       keeps all original keys, adds {@code filter[where][price][type]=number} as a new sibling</li>
 *   <li>Dot-notation ({@code filter[where][info.pageCount]}):
 *       the field name is looked up via dot-notation traversal in the registry</li>
 *   <li>Gateway-managed fields (starting with {@code _}) are skipped</li>
 *   <li>Params containing {@code [lookup]} are skipped</li>
 * </ul>
 *
 * <p>The filter is a pure pass-through when no kind alias is configured or when
 * the registry yields no type information for the encountered fields.
 *
 * <p>Filter placement: after {@code PreventQueryByForbiddenFields}, before
 * {@code RemoveRequestHeader=Authorization}.
 */
@Component
@Slf4j
public class InjectTypeHintsToQueryGatewayFilterFactory
        extends AbstractGatewayFilterFactory<InjectTypeHintsToQueryGatewayFilterFactory.Config> {

    @Autowired
    private TypeHintSchemaRegistry typeHintSchemaRegistry;

    // Regex that extracts the content of every [...] segment in a query-param key
    private static final Pattern BRACKET_CONTENT_PATTERN = Pattern.compile("\\[([^\\[\\]]+)\\]");

    // LoopBack comparison operators (must not be mistaken for field names)
    private static final Set<String> COMPARISON_OPERATORS = Set.of(
            "eq", "gt", "gte", "lt", "lte", "between", "inq", "nin", "neq",
            "like", "nlike", "ilike", "nilike", "regexp", "near");

    // Logical operators that nest where conditions
    private static final Set<String> LOGICAL_OPERATORS = Set.of("and", "or");

    // LoopBack / gateway structural keywords that appear in query-param keys
    // but never represent field names
    private static final Set<String> STRUCTURE_KEYWORDS = Set.of(
            "filter", "where", "include", "scope", "fields", "limit", "skip", "order", "lookup",
            "entityFilter", "listFilter", "filterThrough", "entityWhere", "listWhere");

    public InjectTypeHintsToQueryGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            MultiValueMap<String, String> originalParams = exchange.getRequest().getQueryParams();

            if (originalParams.isEmpty()) {
                return chain.filter(exchange);
            }

            KindAliasConfigAttr attr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

            // Type hints are derived from kind-alias schemas; skip when no alias is active
            if (attr == null || !attr.isKindAliasConfigured()) {
                return chain.filter(exchange);
            }

            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = (route != null) ? route.getId() : null;

            URI originalUri = exchange.getRequest().getURI();
            if (log.isDebugEnabled()) {
                try {
                    String decodedOriginalUri = java.net.URLDecoder.decode(originalUri.toString(), StandardCharsets.UTF_8.name());
                    log.debug("InjectTypeHintsToQuery - Original URI (decoded): {}", decodedOriginalUri);
                } catch (Exception e) {
                    log.debug("InjectTypeHintsToQuery - Original URI: {} (failed to decode: {})", originalUri, e.getMessage());
                }
            }

            // Deep-copy to ensure inner lists are mutable
            MultiValueMap<String, String> mutableParams = new LinkedMultiValueMap<>();
            originalParams.forEach((key, values) -> mutableParams.put(key, new ArrayList<>(values)));

            boolean isModified = false;

            // Snapshot keys to avoid ConcurrentModificationException while adding siblings
            List<String> paramKeys = new ArrayList<>(mutableParams.keySet());

            for (String key : paramKeys) {

                if (!isWhereClauseKey(key)) {
                    continue;
                }

                String fieldName = extractFieldName(key);
                if (fieldName == null || fieldName.startsWith("_")) {
                    continue;
                }

                Optional<String> typeHintOpt = typeHintSchemaRegistry.resolveHint(attr, routeId, fieldName);
                if (typeHintOpt.isEmpty()) {
                    continue;
                }

                String typeHint = typeHintOpt.get();

                // Build the sibling key from the original key (before any [eq] rename)
                // so that the [type] hint is placed at the field/operator level, not on
                // the newly added [eq] bracket.
                String siblingKey = buildTypeSiblingKey(key);

                // Convert implicit exact match (no trailing operator) to explicit [eq]
                if (isImplicitEq(key)) {
                    List<String> values = mutableParams.remove(key);
                    String eqKey = key + "[eq]";
                    if (values != null) {
                        mutableParams.put(eqKey, values);
                    }
                    isModified = true;
                    log.debug("Converted implicit eq: {} -> {}", key, eqKey);
                }

                if (!mutableParams.containsKey(siblingKey)) {
                    mutableParams.add(siblingKey, typeHint);
                    isModified = true;
                    log.debug("Injected type hint: {}={}", siblingKey, typeHint);
                }
            }

            if (!isModified) {
                return chain.filter(exchange);
            }

            URI newUri = UriComponentsBuilder.fromUri(originalUri)
                    .replaceQueryParams(mutableParams)
                    .build()
                    .toUri();

            if (log.isDebugEnabled()) {
                try {
                    String decodedQuery = UriComponentsBuilder.newInstance().queryParams(mutableParams).build().encode().getQuery();
                    String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                    if (decodedQuery != null && !decodedQuery.isEmpty()) {
                        decodedUri += "?" + java.net.URLDecoder.decode(decodedQuery, StandardCharsets.UTF_8.name());
                    }
                    log.debug("InjectTypeHintsToQuery - New URI (decoded): {}", decodedUri);
                } catch (Exception e) {
                    log.debug("InjectTypeHintsToQuery - New URI: {} (failed to decode: {})", newUri, e.getMessage());
                }
            }

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate().uri(newUri).build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    // -------------------------------------------------------------------------
    // Key classification
    // -------------------------------------------------------------------------

    /**
     * Returns true when {@code key} targets a where-clause that may benefit from
     * a type hint.
     */
    private boolean isWhereClauseKey(String key) {
        if (key.contains("[lookup]")) {
            return false;
        }

        // Families that use an explicit [where] sub-key
        if (key.startsWith("filter[where][")
                || key.startsWith("entityFilter[where][")
                || key.startsWith("listFilter[where][")
                || key.startsWith("filterThrough[where][")) {
            return true;
        }

        // Families where the key itself starts the where clause
        if (key.startsWith("where[")
                || key.startsWith("entityWhere[")
                || key.startsWith("listWhere[")) {
            return true;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Field-name extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts the logical field name from a LoopBack query-param key by walking
     * the bracket segments from the end and skipping:
     * <ul>
     *   <li>pure numeric segments (array indices)</li>
     *   <li>structure keywords (filter, where, …)</li>
     *   <li>logical operators (and, or)</li>
     *   <li>comparison operators (eq, gt, …)</li>
     * </ul>
     * The first segment that is none of the above is the field name.
     */
    private String extractFieldName(String key) {
        List<String> segments = new ArrayList<>();
        Matcher m = BRACKET_CONTENT_PATTERN.matcher(key);
        while (m.find()) {
            segments.add(m.group(1));
        }

        for (int i = segments.size() - 1; i >= 0; i--) {
            String s = segments.get(i);
            if (s.matches("\\d+")) continue;
            if (STRUCTURE_KEYWORDS.contains(s)) continue;
            if (LOGICAL_OPERATORS.contains(s)) continue;
            if (COMPARISON_OPERATORS.contains(s)) continue;
            return s;
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Implicit-eq detection
    // -------------------------------------------------------------------------

    /**
     * Returns true when the last bracket segment of {@code key} is a field name
     * (i.e. not a comparison operator, logical operator, structure keyword, or
     * numeric array index). Such a key represents an implicit exact-match and must
     * be rewritten to the explicit {@code [eq]} form before type-hint injection.
     */
    private boolean isImplicitEq(String key) {
        List<String> segments = new ArrayList<>();
        Matcher m = BRACKET_CONTENT_PATTERN.matcher(key);
        while (m.find()) {
            segments.add(m.group(1));
        }
        if (segments.isEmpty()) {
            return false;
        }
        String last = segments.get(segments.size() - 1);
        return !last.matches("\\d+")
                && !COMPARISON_OPERATORS.contains(last)
                && !LOGICAL_OPERATORS.contains(last)
                && !STRUCTURE_KEYWORDS.contains(last);
    }

    // -------------------------------------------------------------------------
    // Sibling key construction
    // -------------------------------------------------------------------------

    /**
     * Builds the {@code [type]} sibling key for a given where-clause key.
     *
     * <p>Computes the key for the {@code [type]} sibling. The sibling is always placed at
     * the <em>field level</em> — operator and array-index suffixes are peeled off the key
     * only to locate the field anchor. The original query-param keys are never modified.
     *
     * <ul>
     *   <li>{@code filter[where][price]} → sibling {@code filter[where][price][type]}</li>
     *   <li>{@code filter[where][price][gt]} → sibling {@code filter[where][price][type]}</li>
     *   <li>{@code filter[where][price][inq][0]} → sibling {@code filter[where][price][type]}</li>
     * </ul>
     *
     * Algorithm: strip any trailing purely-numeric or comparison-operator bracket
     * segments, then append {@code [type]}.
     */
    private String buildTypeSiblingKey(String key) {
        String stripped = key;

        while (stripped.endsWith("]")) {
            int lastOpen = stripped.lastIndexOf('[');
            if (lastOpen < 0) break;
            String lastSegment = stripped.substring(lastOpen + 1, stripped.length() - 1);
            if (lastSegment.matches("\\d+") || COMPARISON_OPERATORS.contains(lastSegment)) {
                stripped = stripped.substring(0, lastOpen);
            } else {
                break;
            }
        }

        return stripped + "[type]";
    }

    // -------------------------------------------------------------------------
    // Config (empty – no configuration parameters required)
    // -------------------------------------------------------------------------

    public static class Config {
    }
}
