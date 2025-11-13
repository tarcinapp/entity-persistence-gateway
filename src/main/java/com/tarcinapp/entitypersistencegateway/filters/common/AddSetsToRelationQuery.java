package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

/**
 * This filter restricts the returned relations based on user permissions.
 * 
 * IMPORTANT: This filter is specifically designed for RELATIONS only.
 * For entities and lists, use AddSetsToEntityListOrReactionViaRecordQuery filter instead.
 * 
 * Relations Security Model:
 * Relations have fundamentally different security dynamics than entities/lists:
 * 
 * 1. **No Direct Ownership**: Relations themselves don't have ownership.
 *    They inherit visibility from the list and entity they connect.
 * 
 * 2. **Dual Visibility**: A relation is visible ONLY if user can see BOTH:
 *    - The LIST that owns the relation (source)
 *    - The ENTITY that the relation points to (target)
 * 
 * 3. **Backend Set Model**: The backend uses TWO sets for relations:
 *    - listSet[audience][userIds/groupIds] - Checks if user can see the list
 *    - entitySet[audience][userIds/groupIds] - Checks if user can see the entity
 *    Both must pass for the relation to be visible.
 *    
 *    IMPORTANT: listSet and entitySet are TOP-LEVEL parameters, NOT nested under set[...]
 * 
 * 4. **Query Patterns**: Relations are typically queried by:
 *    - List ID: filter[where][_listId]=uuid
 *    - Entity reference: filter[where][_entityId]=tapp://localhost/entities/abc123
 *    - Relation type: filter[where][_kind]=isPartOf
 * 
 * How this filter works:
 * This filter applies THREE types of restrictions to relations:
 * 
 * 1. Relation status filtering via set[or][0][actives]&set[or][1][pendings]:
 *    - Shows only active OR pending relations (filters out expired)
 *    - User's custom set[...] queries are preserved and combined with this filter
 * 
 * 2. List visibility via listSet[audience]:
 *    - Checks if user can see the list that owns the relation
 *    - User's custom listSet[...] queries are preserved and combined with audience check
 * 
 * 3. Entity visibility via entitySet[audience]:
 *    - Checks if user can see the entity the relation points to  
 *    - User's custom entitySet[...] queries are preserved and combined with audience check
 * 
 * The filter constructs (when user has existing queries):
 * set[and][0][or][0][actives]&set[and][0][or][1][pendings] - Enforced: Relation status
 * set[and][1][...] - User's custom set queries (if any)
 * listSet[and][0][audience][userIds/groupIds] - Enforced: List visibility check
 * listSet[and][1][...] - User's custom listSet queries (if any)
 * entitySet[and][0][audience][userIds/groupIds] - Enforced: Entity visibility check  
 * entitySet[and][1][...] - User's custom entitySet queries (if any)
 * 
 * When user has no existing queries for a particular set type:
 * set[or][0][actives]&set[or][1][pendings] - Direct relation status filter
 * listSet[audience][userIds/groupIds] - Direct list visibility check
 * entitySet[audience][userIds/groupIds] - Direct entity visibility check
 * 
 * Note: Unlike entities/lists, relations typically don't use includes or lookups,
 * as they ARE the connecting mechanism themselves.
 * 
 */
@Component
public class AddSetsToRelationQuery
                extends AbstractGatewayFilterFactory<AddSetsToRelationQuery.Config> {

        private final Logger logger = LogManager.getLogger(AddSetsToRelationQuery.class);

        private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

        @Value("${app.shortcode:#{tarcinapp}}")
        private String appShortcode;

        public AddSetsToRelationQuery() {
                super(Config.class);
        }

        @Override
        public GatewayFilter apply(Config config) {

                return (exchange, chain) -> {

                        logger.debug("AddSetsToRelationQuery filter is started.");

                        GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes()
                                        .get(GATEWAY_SECURITY_CONTEXT_ATTR);
                        ArrayList<String> roles = gc.getRoles();
                        String userId = gc.getAuthSubject();
                        ArrayList<String> groups = gc.getGroups();

                        if (roles == null) {
                                logger.debug("Authentication information not found. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        // Null safety checks for userId and groups
                        if (userId == null || groups == null) {
                                logger.warn("User ID or groups not found in security context. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        logger.debug("User roles are: " + roles);

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

                                logger.debug(
                                                "No need to limit response items for these roles. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        URI uri = exchange.getRequest().getURI();
                        logger.debug("Original URI: " + uri);

                        List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

                        /**
                         * Check if caller has any existing sets that need wrapping.
                         * We need to handle three types: set[], listSet[], and entitySet[]
                         */
                        boolean callerHasSet = query.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith("set["));
                        boolean callerHasListSet = query.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith("listSet["));
                        boolean callerHasEntitySet = query.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith("entitySet["));
                        
                        logger.debug("Caller has - set: {}, listSet: {}, entitySet: {}", 
                                callerHasSet, callerHasListSet, callerHasEntitySet);

                        /**
                         * Transform user's existing queries by wrapping them under [and][1] only if they exist.
                         * This prevents unnecessary nesting when user hasn't provided specific set types.
                         */
                        List<NameValuePair> newQuery = query.stream()
                                        .map(nvp -> {
                                                String paramName = nvp.getName();
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
                                                
                                                return new BasicNameValuePair(newName, nvp.getValue());
                                        })
                                        .collect(Collectors.toList());

                        /**
                         * Add relation status filter: set[or][0][actives]&set[or][1][pendings]
                         * This filters relations to only show active or pending ones.
                         * If user has existing sets, this becomes set[and][0][or][0][actives]&set[and][0][or][1][pendings]
                         * and user's sets go under set[and][1][...]
                         */
                        if (callerHasSet) {
                                // User has sets - add our status filter at [and][0]
                                newQuery.add(new BasicNameValuePair("set[and][0][or][0][actives]", ""));
                                newQuery.add(new BasicNameValuePair("set[and][0][or][1][pendings]", ""));
                        } else {
                                // No user sets - add status filter directly
                                newQuery.add(new BasicNameValuePair("set[or][0][actives]", ""));
                                newQuery.add(new BasicNameValuePair("set[or][1][pendings]", ""));
                        }
                        
                        logger.debug("Added relation status filter (actives OR pendings)");

                        /**
                         * Add list and entity audience sets to restrict relations.
                         * 
                         * CRITICAL: Relations don't have their own ownership or visibility fields.
                         * Regular set[audience] has NO EFFECT on relations!
                         * 
                         * Relations inherit visibility from BOTH:
                         * 1. The LIST that owns the relation (source)
                         * 2. The ENTITY that the relation points to (target)
                         * 
                         * We use separate listSet and entitySet parameters:
                         * - listSet[audience] - Checks if user can see the list (source)
                         * - entitySet[audience] - Checks if user can see the entity (target)
                         * 
                         * Both checks must pass for the relation to be visible to the user.
                         * This ensures proper transitive security through the relation graph.
                         */

                        String groupsStr = groups.stream().collect(Collectors.joining(","));
                        
                        // Add listSet[audience] - checks list visibility
                        if (callerHasListSet) {
                                // User has listSet - add our audience check at [and][0]
                                newQuery.add(new BasicNameValuePair("listSet[and][0][audience][userIds]", userId));
                                newQuery.add(new BasicNameValuePair("listSet[and][0][audience][groupIds]", groupsStr));
                        } else {
                                // No user listSet - add audience check directly
                                newQuery.add(new BasicNameValuePair("listSet[audience][userIds]", userId));
                                newQuery.add(new BasicNameValuePair("listSet[audience][groupIds]", groupsStr));
                        }
                        
                        // Add entitySet[audience] - checks entity visibility
                        if (callerHasEntitySet) {
                                // User has entitySet - add our audience check at [and][0]
                                newQuery.add(new BasicNameValuePair("entitySet[and][0][audience][userIds]", userId));
                                newQuery.add(new BasicNameValuePair("entitySet[and][0][audience][groupIds]", groupsStr));
                        } else {
                                // No user entitySet - add audience check directly
                                newQuery.add(new BasicNameValuePair("entitySet[audience][userIds]", userId));
                                newQuery.add(new BasicNameValuePair("entitySet[audience][groupIds]", groupsStr));
                        }

                        logger.debug("Added listSet and entitySet audience - userIds: {}, groupIds: {}", userId, groupsStr);

                        // Build new query string with proper URL encoding
                        String newQueryStr = newQuery.stream()
                                        .map(v -> v.getName() + "=" + v.getValue())
                                        .collect(Collectors.joining("&"));

                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                        .replaceQuery(newQueryStr)
                                        .encode()
                                        .build()
                                        .toUri();

                        // Log decoded URI for easier reading
                        try {
                                String decodedQuery = java.net.URLDecoder.decode(newQueryStr, java.nio.charset.StandardCharsets.UTF_8.name());
                                String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                                if (!decodedQuery.isEmpty()) {
                                        decodedUri += "?" + decodedQuery;
                                }
                                logger.debug("New URI (decoded) " + decodedUri);
                        } catch (Exception e) {
                                logger.debug("New URI " + newUri + " (failed to decode: " + e.getMessage() + ")");
                        }

                        ServerWebExchange modifiedExchange = exchange.mutate()
                                        .request(originalRequest -> {
                                                originalRequest.uri(newUri);
                                        })
                                        .build();

                        return chain.filter(modifiedExchange);
                };
        }

        public static class Config {
                // No specific configuration needed for relations filter
                // Keeping the class for consistency with other filters
        }
}
