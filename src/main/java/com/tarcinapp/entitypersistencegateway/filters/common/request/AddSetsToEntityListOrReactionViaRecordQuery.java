package com.tarcinapp.entitypersistencegateway.filters.common.request;

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
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;
import lombok.extern.slf4j.Slf4j;

/**
 *  * This filter restricts the returned records from entity, list, and
 * reactions-through-record queries.
 *  *
 *  * IMPORTANT: This filter is for ENTITIES, LISTS, and REACTIONS ACCESSED
 * THROUGH AN ENTITY OR LIST ONLY.
 *  * It does NOT apply to direct reactions routes. For direct reactions, use
 * AddSetsToReactionsQuery instead.
 *  *
 *  * Restrictions are based on user's role, owners of each record and record's
 * visibility.
 *  *
 *  * What is record ownership?
 *  * Each record (entity, list, or reaction via record) has ownerGroups and
 * ownerUsers arrays.
 *  * - If user's id is present in ownerUsers, the user is the most powerful
 * user on that record.
 *  * - If user's group is present in ownerGroups and the record's visibility is
 * protected, the user is an owner.
 *  *
 *  * If user is not an editor or an admin, then:
 *  * - User can see their own records.
 *  * - User can see active, public records.
 *  *
 *  * This filter uses the set feature of the backend to limit items in the
 * response. It adds required sets as query parameters and merges with any sets
 * provided by the caller.
 *  *
 *  * The filter also protects nested queries:
 *  * - filter[include][X][set] for included relations (set is at same level as
 * scope)
 *  * - filter[lookup][X][set] for looked-up references (set is at same level as
 * scope)
 *  * - Nested combinations of includes and lookups
 *  *
 *  *
 *  * For relation-specific filtering, see: AddSetsToRelationQuery
 *  * For direct reactions filtering, see: AddSetsToReactionsQuery
 *  
 */
@Component
@Slf4j
public class AddSetsToEntityListOrReactionViaRecordQuery
                extends AbstractGatewayFilterFactory<AddSetsToEntityListOrReactionViaRecordQuery.Config> {
        @Value("${app.shortcode:#{tarcinapp}}")
        private String appShortcode;

        public AddSetsToEntityListOrReactionViaRecordQuery() {
                super(Config.class);
        }

        @Override
        public GatewayFilter apply(Config config) {
                return (exchange, chain) -> {
                        // Resolve recordType with hierarchical fallback
                        String recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "AddSetsToEntityListOrReactionViaRecordQuery");
                        log.debug("AddSetsToEntityListOrReactionViaRecordQuery filter is started. recordType: {}",
                                        recordType);

                        GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes()
                                        .get(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

                        // Null safety check for gc and roles
                        if (gc == null || gc.getRoles() == null) {
                                log.debug("Authentication information not found. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        ArrayList<String> roles = gc.getRoles();
                        String userId = gc.getAuthSubject();
                        ArrayList<String> groups = gc.getGroups();

                        // Null safety checks for userId and groups
                        if (userId == null || groups == null) {
                                // TODO: Any risk here?!
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
                                        appShortcode + ".records.admin",
                                        appShortcode + ".records.find.admin",
                                        appShortcode + ".records.editor",
                                        appShortcode + ".records.find.editor");

                        if (recordType != null) {
                                prefixedRolesStream = Stream.concat(prefixedRolesStream,
                                                Stream.of(
                                                                appShortcode + "." + recordType + ".find.admin",
                                                                appShortcode + "." + recordType + ".find.editor"));
                        }

                        /**
                         * TODO: Should I ask this to PEP?
                         * This filter only applies when user has lower authority then editor user.
                         */
                        if (prefixedRolesStream.anyMatch(roles::contains)) {
                                log.debug(
                                                "No need to limit response items for these roles. Exiting filter withouth any modification.");
                                return chain.filter(exchange);
                        }

                        URI uri = exchange.getRequest().getURI();
                        if (log.isDebugEnabled()) {
                                try {
                                        String decodedOriginalUri = java.net.URLDecoder.decode(uri.toString(),
                                                        StandardCharsets.UTF_8.name());
                                        log.debug("Original URI (decoded): {}", decodedOriginalUri);
                                } catch (Exception e) {
                                        log.debug("Original URI: {} (failed to decode: {})", uri, e.getMessage());
                                }
                        }

                        // Extract original query parameters
                        MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
                        MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();

                        /**
                         * Check if caller has already specified any sets.
                         * If they have, we need to wrap everything (our audience set + their sets)
                         * under set[and][...]
                         * If they haven't, we just add set[audience][...] directly.
                         * * Examples:
                         * 1. No caller sets:
                         *    - Add: set[audience][userIds]={userId}&set[audience][groupIds]={groups}
                         * * 2. Caller has sets:
                         *    - Caller sends: set[actives]
                         *    - Transforms to:
                         * set[and][0][audience][userIds]={userId}&set[and][0][audience][groupIds]={groups}&set[and][1][actives]
                         * * 3. Caller has complex sets:
                         *    - Caller sends: set[or][0][mine]&set[or][1][public]
                         *    - Transforms to:
                         * set[and][0][audience][userIds]={userId}&set[and][0][audience][groupIds]={groups}&set[and][1][or][0][mine]&set[and][1][or][1][public]
                         * * Note: userIds and groupIds are both part of the same audience set under
                         * [and][0]
                         *       Caller's sets are wrapped under [and][1]
                         * * Also handle includes and lookups to prevent security bypass:
                         * - filter[include][X][set][...] (set is at same level as scope)
                         * - filter[lookup][X][set][...] (set is at same level as scope)
                         * - Nested combinations like filter[include][X][scope][lookup][Y][set][...]
                         */

                        // Check if caller has any top-level set parameters
                        boolean callerHasSets = originalQueryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith("set["));

                        log.debug("Caller has sets: {}", callerHasSets);

                        // Transform the query parameters
                        for (Map.Entry<String, List<String>> entry : originalQueryParams.entrySet()) {
                                String paramName = entry.getKey();
                                List<String> values = entry.getValue();
                                String newName = paramName;

                                if (callerHasSets && paramName.startsWith("set[")) {
                                        // Caller has top-level sets - wrap them under [and][1]
                                        newName = paramName.replaceFirst("^set\\[", "set[and][1][");
                                }

                                // Also transform nested sets in includes and lookups
                                // Pattern: filter[include][X][set][...] or filter[lookup][X][set][...]
                                // Note: set is at the same level as scope, not under it
                                if ((paramName.contains("filter[include][") || paramName.contains("filter[lookup]["))
                                                && paramName.contains("][set][")
                                                && !paramName.contains("][set][and][")) {
                                        // Wrap existing nested sets under [and][0]
                                        newName = paramName.replaceFirst("\\]\\[set\\]\\[", "][set][and][0][");
                                }

                                // Add all values for the key to the new map
                                newQueryParams.addAll(newName, values);
                        }

                        /**
                         * Following set is added in order to reduce the scope of the query.
                         * Users with low authority can only see the public and active records with
                         * their own active and pending records.
                         * This is exactly what 'audience' set does.
                         * * Note: If a record has user's user id in its ownerUsers array, then this
                         * record belongs to that user.
                         * In addition to that, if user's group id presents in record's ownerGroups
                         * array and it's visibility is protected or public (not private) this record is
                         * also belongs to that user too.
                         * In addition if user's id is in viewerUsers, then user is allowed to see no
                         * matter what is the visibility.
                         * In addition if user's group id is in viewerGroups, then user is allowed to
                         * see as long as visibility is protected or public (not private)
                         */

                        // audience set requires user id and groups in following format
                        // [userId1,userId2][group1,group2]
                        String groupsStr = groups.stream().collect(Collectors.joining(","));

                        // Add audience set to top-level query
                        if (callerHasSets) {
                                // Wrap under set[and][0] - both userIds and groupIds are part of the same
                                // audience set
                                newQueryParams.add("set[and][0][audience][userIds]", userId);
                                newQueryParams.add("set[and][0][audience][groupIds]", groupsStr);
                        } else {
                                // Add directly as set[audience][...]
                                newQueryParams.add("set[audience][userIds]", userId);
                                newQueryParams.add("set[audience][groupIds]", groupsStr);
                        }

                        // Add audience sets to all includes and lookups
                        addAudienceSetsToIncludes(newQueryParams, userId, groupsStr);
                        addAudienceSetsToLookups(newQueryParams, userId, groupsStr);

                        // as we built new query string, now we can go ahead and change the query from
                        // the original request

                        URI newUri = UriComponentsBuilder.fromUri(uri)
                                        .replaceQueryParams(newQueryParams)
                                        .encode(StandardCharsets.UTF_8)
                                        .build()
                                        .toUri();

                        // Log decoded URI for easier reading
                        if (log.isDebugEnabled()) {
                                try {
                                        // replaceQueryParams otomatik olarak boş sorgu dizesini kaldırır
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

                        ServerWebExchange modifiedExchange = exchange.mutate()
                                        .request(originalRequest -> {
                                                originalRequest.uri(newUri);
                                        })
                                        .build();

                        return chain.filter(modifiedExchange);
                };
        }

        /**
         * Adds audience sets to all filter[include][X] entries, whether they have a
         * scope or not.
         * This ensures that included relations are also restricted by the same audience
         * visibility rules.
         * * For each include found:
         * - If NO sets exist: add filter[include][X][set][audience][userIds]={userId}
         * and [groupIds]={groupIds}
         * - If sets exist: add
         * filter[include][X][set][and][1][audience][userIds]={userId} and
         * [groupIds]={groupIds}
         *   (caller's sets are at [and][0], audience is at [and][1], both userIds and
         * groupIds under same [and][1])
         * * CRITICAL: If an include does NOT have a set, we create one automatically.
         * This prevents security bypass where users query
         * filter[include][X][relation]=... without set.
         * * Also handles nested lookups within includes:
         * - filter[include][X][scope][lookup][Y][set][audience][...]
         * * @param queryParams The MultiValueMap of query parameters to modify
         * 
         * @param userId    The user ID to inject
         * @param groupsStr Comma-separated group IDs to inject
         */
        private void addAudienceSetsToIncludes(MultiValueMap<String, String> queryParams, String userId,
                        String groupsStr) {
                // Find ALL unique include indices (both with and without existing scopes) by
                // extracting the base prefix
                List<String> allIncludePrefixes = queryParams.keySet().stream()
                                .filter(name -> name.startsWith("filter[include]["))
                                .map(name -> {
                                        // Extract index from filter[include][X][...] -> returns filter[include][X]
                                        int start = "filter[include][".length();
                                        int end = name.indexOf(']', start);
                                        if (end > start) {
                                                // Include the closing bracket for the prefix
                                                return name.substring(0, end + 1);
                                        }
                                        return null;
                                })
                                .filter(idx -> idx != null)
                                .distinct()
                                .collect(Collectors.toList());

                log.debug("Found {} include(s) to protect: {}", allIncludePrefixes.size(), allIncludePrefixes);

                // For each include, check if it has existing sets and add audience accordingly
                for (String prefix : allIncludePrefixes) {
                        String setKeyPrefix = prefix + "[set]";

                        // Check if this include already has sets (after our transformation they'd be
                        // under [and][0])
                        boolean includeHasSets = queryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith(setKeyPrefix + "[and][0]"));

                        if (includeHasSets) {
                                // Include has sets - add audience at [and][1] (both userIds and groupIds under
                                // same [and][1])
                                // Caller's sets are already at [and][0]
                                String audiencePrefix = setKeyPrefix + "[and][1][audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to include {} with existing sets (wrapped under [and])",
                                                prefix);
                        } else {
                                // No sets - add audience directly
                                String audiencePrefix = setKeyPrefix + "[audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to include {} without existing sets", prefix);
                        }
                }

                // --- Nested Lookups within Includes ---
                List<String> nestedLookupPrefixes = queryParams.keySet().stream()
                                .filter(name -> name.matches(
                                                ".*filter\\[include\\]\\[\\d+\\]\\[scope\\]\\[lookup\\]\\[\\d+\\].*"))
                                .map(name -> {
                                        // Extract up to filter[include][X][scope][lookup][Y]
                                        int lookupStart = name.indexOf("[lookup][");
                                        if (lookupStart > 0) {
                                                int lookupIndexStart = lookupStart + "[lookup][".length();
                                                int lookupIndexEnd = name.indexOf(']', lookupIndexStart);
                                                if (lookupIndexEnd > lookupIndexStart) {
                                                        // Extract the full prefix: filter[include][X][scope][lookup][Y]
                                                        return name.substring(0, lookupIndexEnd + 1);
                                                }
                                        }
                                        return null;
                                })
                                .filter(prefix -> prefix != null)
                                .distinct()
                                .collect(Collectors.toList());

                log.debug("Found {} nested lookup(s) within includes to protect: {}", nestedLookupPrefixes.size(),
                                nestedLookupPrefixes);

                for (String prefix : nestedLookupPrefixes) {
                        String lookupSetPrefix = prefix + "[set]";

                        // Check if this nested lookup has sets (after transformation they'd be under
                        // [and][0])
                        boolean nestedHasSets = queryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith(lookupSetPrefix + "[and][0]"));

                        if (nestedHasSets) {
                                // Has sets - add audience at [and][1]
                                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to nested lookup: {} (with existing sets)", prefix);
                        } else {
                                // No sets - add directly
                                String audiencePrefix = lookupSetPrefix + "[audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to nested lookup: {} (without sets)", prefix);
                        }
                }
        }

        /**
         * Adds audience sets to all filter[lookup][X] entries, whether they have a
         * scope or not.
         * This ensures that looked-up references are also restricted by the same
         * audience visibility rules.
         * * For each lookup found:
         * - If NO sets exist: add filter[lookup][X][set][audience][userIds]={userId}
         * and [groupIds]={groupIds}
         * - If sets exist: add
         * filter[lookup][X][set][and][1][audience][userIds]={userId} and
         * [groupIds]={groupIds}
         *   (caller's sets are at [and][0], audience is at [and][1], both userIds and
         * groupIds under same [and][1])
         * * CRITICAL: If a lookup does NOT have a set, we create one automatically.
         * This prevents security bypass where users query filter[lookup][X][prop]=...
         * without set.
         * * Also handles nested lookups within lookups.
         * * @param queryParams The MultiValueMap of query parameters to modify
         * 
         * @param userId    The user ID to inject
         * @param groupsStr Comma-separated group IDs to inject
         */
        private void addAudienceSetsToLookups(MultiValueMap<String, String> queryParams, String userId,
                        String groupsStr) {
                // Find ALL top-level lookup indices (not nested within includes)
                List<String> allLookupPrefixes = queryParams.keySet().stream()
                                .filter(name -> name.startsWith("filter[lookup]["))
                                .filter(name -> !name.contains("filter[include][")) // Exclude nested ones handled in
                                                                                    // includes method
                                .map(name -> {
                                        // Extract index from filter[lookup][X][...] -> returns filter[lookup][X]
                                        int start = "filter[lookup][".length();
                                        int end = name.indexOf(']', start);
                                        if (end > start) {
                                                return name.substring(0, end + 1);
                                        }
                                        return null;
                                })
                                .filter(idx -> idx != null)
                                .distinct()
                                .collect(Collectors.toList());

                log.debug("Found {} top-level lookup(s) to protect: {}", allLookupPrefixes.size(),
                                allLookupPrefixes);

                // For each lookup, check if it has existing sets and add audience accordingly
                for (String prefix : allLookupPrefixes) {
                        String lookupSetPrefix = prefix + "[set]";

                        // Check if this lookup already has sets (after our transformation they'd be
                        // under [and][0])
                        boolean lookupHasSets = queryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith(lookupSetPrefix + "[and][0]"));

                        if (lookupHasSets) {
                                // Lookup has sets - add audience at [and][1]
                                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to lookup {} with existing sets (wrapped under [and])",
                                                prefix);
                        } else {
                                // No sets - add audience directly
                                String audiencePrefix = lookupSetPrefix + "[audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to lookup {} without existing sets", prefix);
                        }
                }

                // --- Nested Lookups within Lookups ---
                List<String> nestedLookupPrefixes = queryParams.keySet().stream()
                                .filter(name -> name.startsWith("filter[lookup]["))
                                .filter(name -> !name.contains("filter[include]["))
                                .filter(name -> {
                                        // Check if there are multiple [lookup] patterns
                                        return name.contains("[scope][lookup]");
                                })
                                .map(name -> {
                                        // Extract up to filter[lookup][X][scope][lookup][Y]
                                        int firstLookupEnd = name.indexOf(']', "[lookup]".length());
                                        int secondLookupStart = name.indexOf("[lookup]", firstLookupEnd);
                                        if (secondLookupStart > 0) {
                                                int secondLookupIndexStart = secondLookupStart + "[lookup][".length();
                                                int secondLookupIndexEnd = name.indexOf(']', secondLookupIndexStart);
                                                if (secondLookupIndexEnd > secondLookupIndexStart) {
                                                        // Extract the full prefix: filter[lookup][X][scope][lookup][Y]
                                                        return name.substring(0, secondLookupIndexEnd + 1);
                                                }
                                        }
                                        return null;
                                })
                                .filter(prefix -> prefix != null)
                                .distinct()
                                .collect(Collectors.toList());

                log.debug("Found {} nested lookup(s) within lookups to protect: {}",
                                nestedLookupPrefixes.size(), nestedLookupPrefixes);

                for (String prefix : nestedLookupPrefixes) {
                        String lookupSetPrefix = prefix + "[set]";

                        // Check if this nested lookup has sets (after transformation they'd be under
                        // [and][0])
                        boolean nestedHasSets = queryParams.keySet().stream()
                                        .anyMatch(name -> name.startsWith(lookupSetPrefix + "[and][0]"));

                        if (nestedHasSets) {
                                // Has sets - add audience at [and][1]
                                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to nested lookup: {} (with existing sets)", prefix);
                        } else {
                                // No sets - add directly
                                String audiencePrefix = lookupSetPrefix + "[audience]";
                                queryParams.add(audiencePrefix + "[userIds]", userId);
                                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                                log.debug("Added audience set to nested lookup: {} (without sets)", prefix);
                        }
                }
        }

        public static class Config {
                private String recordType;

                public String getRecordType() {
                        return this.recordType;
                }

                public void setRecordType(String recordType) {
                        this.recordType = recordType;
                }
        }
}