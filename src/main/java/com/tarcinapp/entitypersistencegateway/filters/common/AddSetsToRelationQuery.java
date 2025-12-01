package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap; // Yeni kullanılan sınıf
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import lombok.extern.slf4j.Slf4j;

/**
 *  * This filter restricts the returned relations based on user permissions.
 *  *  * IMPORTANT: This filter is specifically designed for RELATIONS only.
 *  * For entities and lists, use AddSetsToEntityListOrReactionViaRecordQuery
 * filter instead.
 *  *  * Relations Security Model:
 *  * Relations have fundamentally different security dynamics than
 * entities/lists:
 *  *  * 1. **No Direct Ownership**: Relations themselves don't have ownership.
 *  *    They inherit visibility from the list and entity they connect.
 *  *  * 2. **Dual Visibility**: A relation is visible ONLY if user can see
 * BOTH:
 *  *    - The LIST that owns the relation (source)
 *  *    - The ENTITY that the relation points to (target)
 *  *  * 3. **Backend Set Model**: The backend uses TWO sets for relations:
 *  *    - listSet[audience][userIds/groupIds] - Checks if user can see the list
 *  *    - entitySet[audience][userIds/groupIds] - Checks if user can see the
 * entity
 *  *    Both must pass for the relation to be visible.
 *  *    
 *  *    IMPORTANT: listSet and entitySet are TOP-LEVEL parameters, NOT nested
 * under set[...]
 *  *  * 4. **Query Patterns**: Relations are typically queried by:
 *  *    - List ID: filter[where][_listId]=uuid
 *  *    - Entity reference:
 * filter[where][_entityId]=tapp://localhost/entities/abc123
 *  *    - Relation type: filter[where][_kind]=isPartOf
 *  *  * How this filter works:
 *  * This filter applies THREE types of restrictions to relations:
 *  *  * 1. Relation status filtering via
 * set[or][0][actives]&set[or][1][pendings]:
 *  *    - Shows only active OR pending relations (filters out expired)
 *  *    - User's custom set[...] queries are preserved and combined with this
 * filter
 *  *  * 2. List visibility via listSet[audience]:
 *  *    - Checks if user can see the list that owns the relation
 *  *    - User's custom listSet[...] queries are preserved and combined with
 * audience check
 *  *  * 3. Entity visibility via entitySet[audience]:
 *  *    - Checks if user can see the entity the relation points to  
 *  *    - User's custom entitySet[...] queries are preserved and combined with
 * audience check
 *  *  * The filter constructs (when user has existing queries):
 *  * set[and][0][or][0][actives]&set[and][0][or][1][pendings] - Enforced:
 * Relation status
 *  * set[and][1][...] - User's custom set queries (if any)
 *  * listSet[and][0][audience][userIds/groupIds] - Enforced: List visibility
 * check
 *  * listSet[and][1][...] - User's custom listSet queries (if any)
 *  * entitySet[and][0][audience][userIds/groupIds] - Enforced: Entity
 * visibility check  
 *  * entitySet[and][1][...] - User's custom entitySet queries (if any)
 *  *  * When user has no existing queries for a particular set type:
 *  * set[or][0][actives]&set[or][1][pendings] - Direct relation status filter
 *  * listSet[audience][userIds/groupIds] - Direct list visibility check
 *  * entitySet[audience][userIds/groupIds] - Direct entity visibility check
 *  *  * Note: Unlike entities/lists, relations typically don't use includes or
 * lookups,
 *  * as they ARE the connecting mechanism themselves.
 *  *  
 */
@Component
@Slf4j
public class AddSetsToRelationQuery extends AbstractGatewayFilterFactory<AddSetsToRelationQuery.Config> {
        @Value("${app.shortcode:#{tarcinapp}}")
        private String appShortcode;

        public AddSetsToRelationQuery() {
                super(Config.class);
        }

        @Override
        public GatewayFilter apply(Config config) {

                return (exchange, chain) -> {

                        log.debug("AddSetsToRelationQuery filter is started.");

                        GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes()
                                        .get(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

                        if (gc == null) {
                                log.debug("Security context missing; skipping modifications.");
                                return chain.filter(exchange);
                        }

                        ArrayList<String> roles = gc.getRoles();
                        String userId = gc.getAuthSubject();
                        ArrayList<String> groups = gc.getGroups();

                        if (roles == null) {
                                log.debug("Authentication information not found. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        // Null safety checks for userId and groups
                        if (userId == null || groups == null) {
                                log.warn("User ID or groups not found in security context. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        log.debug("User roles are: {}", roles);

                        /**
                         * If user role is any of the following, we do not need to add sets
                         */
                        Stream<String> prefixedRolesStream = Stream.of(
                                        appShortcode + ".admin",
                                        appShortcode + ".editor",
                                        appShortcode + ".relations.admin",
                                        appShortcode + ".relations.find.admin",
                                        appShortcode + ".relations.editor",
                                        appShortcode + ".relations.find.editor");

                        /**
                         * TODO: Should I ask this to PEP?
                         * This filter only applies when user has lower authority than editor user.
                         */
                        if (prefixedRolesStream.anyMatch(roles::contains)) {

                                log.debug(
                                                "No need to limit response items for these roles. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        URI uri = exchange.getRequest().getURI();
                        log.debug("Original URI: {}", uri);

                        // Extract original query parameters
                        MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
                        MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();

                        /**
                         * Check if caller has any existing sets that need wrapping.
                         * We need to handle three types: set[], listSet[], and entitySet[]
                         */
                        boolean callerHasSet = originalQueryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith("set["));
                        boolean callerHasListSet = originalQueryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith("listSet["));
                        boolean callerHasEntitySet = originalQueryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith("entitySet["));

                        log.debug("Caller has - set: {}, listSet: {}, entitySet: {}",
                                        callerHasSet, callerHasListSet, callerHasEntitySet);

                        /**
                         * Transform user's existing queries by wrapping them under [and][1] only if
                         * they exist.
                         * This prevents unnecessary nesting when user hasn't provided specific set
                         * types.
                         */
                        for (Map.Entry<String, List<String>> entry : originalQueryParams.entrySet()) {
                                String paramName = entry.getKey();
                                List<String> values = entry.getValue();
                                String newName = paramName;

                                // Wrap user's set queries under [and][1] only if they have sets
                                if (callerHasSet && paramName.startsWith("set[")) {
                                        newName = paramName.replaceFirst("^set\\[", "set[and][1][");
                                }
                                // Wrap user's listSet queries under [and][1] only if they have listSet
                                else if (callerHasListSet && paramName.startsWith("listSet[")) {
                                        newName = paramName.replaceFirst("^listSet\\[", "listSet[and][1][");
                                }
                                // Wrap user's entitySet queries under [and][1] only if they have entitySet
                                else if (callerHasEntitySet && paramName.startsWith("entitySet[")) {
                                        newName = paramName.replaceFirst("^entitySet\\[", "entitySet[and][1][");
                                }

                                // Transform nested lookups inside top-level sets (for filter[lookup]s under
                                // set/listSet/entitySet)
                                if (paramName.startsWith("filter[lookup][") && paramName.contains("][set][")
                                                && !paramName.contains("][set][and][")) {
                                        newName = paramName.replaceFirst("\\]\\[set\\]\\[", "][set][and][0][");
                                }
                                // Nested lookups inside lookups
                                if (paramName.matches(
                                                ".*filter\\[lookup\\]\\[\\d+\\]\\[scope\\]\\[lookup\\]\\[\\d+\\].*\\[set\\]\\[.*")
                                                && paramName.contains("][set][")
                                                && !paramName.contains("][set][and][")) {
                                        newName = paramName.replaceFirst("\\]\\[set\\]\\[", "][set][and][0][");
                                }

                                newQueryParams.addAll(newName, values); // MultiValueMap'e ekle
                        }

                        /**
                         * Add relation status filter: set[or][0][actives]&set[or][1][pendings]
                         * This filters relations to only show active or pending ones.
                         */
                        if (callerHasSet) {
                                // User has sets - add our status filter at [and][0]
                                newQueryParams.add("set[and][0][or][0][actives]", "");
                                newQueryParams.add("set[and][0][or][1][pendings]", "");
                        } else {
                                // No user sets - add status filter directly
                                newQueryParams.add("set[or][0][actives]", "");
                                newQueryParams.add("set[or][1][pendings]", "");
                        }

                        log.debug("Added relation status filter (actives OR pendings)");

                        /**
                         * Add list and entity audience sets to restrict relations.
                         * We use separate listSet and entitySet parameters:
                         * - listSet[audience] - Checks if user can see the list (source)
                         * - entitySet[audience] - Checks if user can see the entity (target)
                         */
                        String groupsStr = groups.stream().collect(Collectors.joining(","));

                        // Add listSet[audience] - checks list visibility
                        if (callerHasListSet) {
                                // User has listSet - add our audience check at [and][0]
                                newQueryParams.add("listSet[and][0][audience][userIds]", userId);
                                newQueryParams.add("listSet[and][0][audience][groupIds]", groupsStr);
                        } else {
                                // No user listSet - add audience check directly
                                newQueryParams.add("listSet[audience][userIds]", userId);
                                newQueryParams.add("listSet[audience][groupIds]", groupsStr);
                        }

                        // Add entitySet[audience] - checks entity visibility
                        if (callerHasEntitySet) {
                                // User has entitySet - add our audience check at [and][0]
                                newQueryParams.add("entitySet[and][0][audience][userIds]", userId);
                                newQueryParams.add("entitySet[and][0][audience][groupIds]", groupsStr);
                        } else {
                                // No user entitySet - add audience check directly
                                newQueryParams.add("entitySet[audience][userIds]", userId);
                                newQueryParams.add("entitySet[audience][groupIds]", groupsStr);
                        }

                        log.debug("Added listSet and entitySet audience - userIds: {}, groupIds: {}", userId,
                                        groupsStr);

                        addAudienceSetsToLookups(newQueryParams, userId, groupsStr);

                        // Build new URI
                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                        .replaceQueryParams(newQueryParams)
                                        .encode(StandardCharsets.UTF_8)
                                        .build()
                                        .toUri();

                        // Log decoded URI for easier reading
                        if (log.isDebugEnabled()) {
                                try {
                                        String decodedQuery = UriComponentsBuilder.newInstance()
                                                        .queryParams(newQueryParams).build().encode().getQuery();
                                        String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority()
                                                        + newUri.getPath();
                                        if (decodedQuery != null && !decodedQuery.isEmpty()) {
                                                decodedUri += "?" + java.net.URLDecoder.decode(decodedQuery,
                                                                StandardCharsets.UTF_8.name());
                                        }
                                        log.debug("New URI (decoded): {}", decodedUri);
                                } catch (Exception e) {
                                        log.debug("New URI: {} (failed to decode: {})", newUri, e.getMessage());
                                }
                        }

                        ServerWebExchange modifiedExchange = exchange.mutate().request(r -> r.uri(newUri)).build();
                        return chain.filter(modifiedExchange);
                };
        }

        private void addAudienceSetsToLookups(MultiValueMap<String, String> params, String userId, String groupsStr) {
                // Top-level lookups
                List<String> lookupPrefixes = params.keySet().stream()
                                .filter(n -> n.startsWith("filter[lookup]["))
                                .map(name -> {
                                        int start = "filter[lookup][".length();
                                        int end = name.indexOf(']', start);
                                        if (end > start) {
                                                return name.substring(0, end + 1); // filter[lookup][X]
                                        }
                                        return null;
                                })
                                .filter(s -> s != null)
                                .distinct().collect(Collectors.toList());

                for (String prefix : lookupPrefixes) {
                        String setPrefix = prefix + "[set]";
                        boolean hasSets = params.keySet().stream().anyMatch(n -> n.startsWith(setPrefix + "[and][0]"));

                        if (hasSets) {
                                params.add(setPrefix + "[and][1][audience][userIds]", userId);
                                params.add(setPrefix + "[and][1][audience][groupIds]", groupsStr);
                        } else {
                                params.add(setPrefix + "[audience][userIds]", userId);
                                params.add(setPrefix + "[audience][groupIds]", groupsStr);
                        }
                }

                // Nested lookups inside lookups: filter[lookup][X][scope][lookup][Y]
                List<String> nestedPrefixes = params.keySet().stream()
                                .filter(n -> n.startsWith("filter[lookup]["))
                                .filter(n -> n.contains("[scope][lookup]"))
                                .map(name -> {
                                        int start = name.indexOf("[lookup][");
                                        int end = name.indexOf("]",
                                                        name.indexOf("[scope][lookup][") + "[scope][lookup][".length());

                                        if (start > 0 && end > 0) {
                                                return name.substring(0, end + 1); // filter[lookup][X][scope][lookup][Y]
                                        }
                                        return null;
                                })
                                .filter(s -> s != null)
                                .distinct().collect(Collectors.toList());

                for (String prefix : nestedPrefixes) {
                        String setPrefix = prefix + "[set]";
                        boolean hasSets = params.keySet().stream().anyMatch(n -> n.startsWith(setPrefix + "[and][0]"));

                        if (hasSets) {
                                params.add(setPrefix + "[and][1][audience][userIds]", userId);
                                params.add(setPrefix + "[and][1][audience][groupIds]", groupsStr);
                        } else {
                                params.add(setPrefix + "[audience][userIds]", userId);
                                params.add(setPrefix + "[audience][groupIds]", groupsStr);
                        }
                }
        }

        public static class Config {
                // No specific configuration needed for relations filter
        }
}