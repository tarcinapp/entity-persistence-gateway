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
 * This filter restricts the returned records from entity and list queries.
 * 
 * IMPORTANT: This filter is for ENTITIES and LISTS only. 
 * Relations have different security dynamics and should use AddSetsToRelationQuery filter instead.
 * 
 * Restrictions are based on user's role, owners of each record and record's visibility.
 * 
 * What is record's ownership?
 * Each record has ownerGroups and ownerUsers arrays.
 * - If user's id present on ownerUsers array, then this user is the most
 * powerfull user on that specific record.
 * No matter what the record's visibility is.
 * - If user's group name present on ownerGroups array, and, the records
 * visibility is protected, then again user is the owner of the record.
 * --------
 * If user is not an editor or an admin, then:
 * - User can see it's own records.
 * - User can see active, public records.
 * 
 * For example;
 * If a record's visibility value is public, but it's validitiy is expired, then
 * this filter prevents this record to return from response.
 * If a record's visibility value is public and validUntilDateTime field is
 * empty whereas validFromDateTime field has a value in past, then this filter
 * let
 * that specific record to return from response.
 * 
 * How this filter works?
 * This filter utilizes the `set` feature of the backend.
 * In order to limit the items in response, this filter adds required sets as
 * the query variable. If user already used `set` in the query,
 * we are and'ing them with those emitted by this filter.
 * 
 * The filter also protects nested queries through:
 * - filter[include][X][set] - for included relations (set is at same level as scope)
 * - filter[lookup][X][set] - for looked-up references (set is at same level as scope)
 * - Nested combinations of includes and lookups
 * 
 * @see AddSetsToRelationQuery for relation-specific filtering
 * 
 */
@Component
public class AddSetsToQuery
                extends AbstractGatewayFilterFactory<AddSetsToQuery.Config> {

        private final Logger logger = LogManager.getLogger(AddSetsToQuery.class);

        private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

        @Value("${app.shortcode:#{tarcinapp}}")
        private String appShortcode;

        public AddSetsToQuery() {
                super(Config.class);
        }

        @Override
        public GatewayFilter apply(Config config) {

                return (exchange, chain) -> {

                        logger.debug("AddSetsToQuery filter is started.");

                        GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes()
                                        .get(GATEWAY_SECURITY_CONTEXT_ATTR);
                        ArrayList<String> roles = gc.getRoles();
                        String userId = gc.getAuthSubject();
                        ArrayList<String> groups = gc.getGroups();
                        String recordType = config.getRecordType();

                        if (roles == null) {
                                logger.debug("Authentication information not found. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

                        // Null safety checks for userId and groups
                        if (userId == null || groups == null) {
                                // TODO: Any risk here?!
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
                                logger.debug(
                                                "No need to limit response items for these roles. Exiting filter withouth any modification.");
                                return chain.filter(exchange);
                        }

                        URI uri = exchange.getRequest().getURI();
                        logger.debug("Original URI: " + uri);

                        List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

                        /**
                         * Check if caller has already specified any sets.
                         * If they have, we need to wrap everything (our audience set + their sets) under set[and][...]
                         * If they haven't, we just add set[audience][...] directly.
                         * 
                         * Examples:
                         * 1. No caller sets:
                         *    - Add: set[audience][userIds]={userId}&set[audience][groupIds]={groups}
                         * 
                         * 2. Caller has sets:
                         *    - Caller sends: set[actives]
                         *    - Transforms to: set[and][0][audience][userIds]={userId}&set[and][0][audience][groupIds]={groups}&set[and][1][actives]
                         * 
                         * 3. Caller has complex sets:
                         *    - Caller sends: set[or][0][mine]&set[or][1][public]
                         *    - Transforms to: set[and][0][audience][userIds]={userId}&set[and][0][audience][groupIds]={groups}&set[and][1][or][0][mine]&set[and][1][or][1][public]
                         * 
                         * Note: userIds and groupIds are both part of the same audience set under [and][0]
                         *       Caller's sets are wrapped under [and][1]
                         * 
                         * Also handle includes and lookups to prevent security bypass:
                         * - filter[include][X][set][...] (set is at same level as scope)
                         * - filter[lookup][X][set][...] (set is at same level as scope)
                         * - Nested combinations like filter[include][X][scope][lookup][Y][set][...]
                         */
                        
                        // Check if caller has any top-level set parameters
                        boolean callerHasSets = query.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith("set["));
                        
                        logger.debug("Caller has sets: {}", callerHasSets);
                        
                        // Transform the query parameters
                        List<NameValuePair> newQuery = query.stream()
                                .map(nvp -> {
                                        String paramName = nvp.getName();
                                        String newName = paramName;
                                        
                                        if (callerHasSets && paramName.startsWith("set[")) {
                                                // Caller has top-level sets - wrap them under [and][1]
                                                newName = paramName.replaceFirst("^set\\[", "set[and][1][");
                                        }
                                        
                        // Also transform nested sets in includes and lookups
                        // Pattern: filter[include][X][set][...] or filter[lookup][X][set][...]
                        // Note: set is at the same level as scope, not under it
                        if ((paramName.contains("filter[include][") || paramName.contains("filter[lookup][")) 
                            && paramName.contains("][set][") && !paramName.contains("][set][and][")) {
                                // Wrap existing nested sets under [and][0]
                                newName = paramName.replaceFirst("\\]\\[set\\]\\[", "][set][and][0][");
                        }                                        return new BasicNameValuePair(newName, nvp.getValue());
                                })
                                .collect(Collectors.toList());

                        /**
                         * Following set is added in order to reduce the scope of the query.
                         * Users with low authority can only see the public and active records with
                         * their own active and pending records.
                         * This is exactly what 'audience' set does.
                         * 
                         * Note: If a record has user's user id in its ownerUsers array, then this
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
                                // Wrap under set[and][0] - both userIds and groupIds are part of the same audience set
                                newQuery.add(new BasicNameValuePair("set[and][0][audience][userIds]", userId));
                                newQuery.add(new BasicNameValuePair("set[and][0][audience][groupIds]", groupsStr));
                        } else {
                                // Add directly as set[audience][...]
                                newQuery.add(new BasicNameValuePair("set[audience][userIds]", userId));
                                newQuery.add(new BasicNameValuePair("set[audience][groupIds]", groupsStr));
                        }
                        
                        // Add audience sets to all includes and lookups
                        addAudienceSetsToIncludes(newQuery, userId, groupsStr);
                        addAudienceSetsToLookups(newQuery, userId, groupsStr);

                        // as we built new query string, now we can go ahead and change the query from
                        // the original request
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

        /**
         * Adds audience sets to all filter[include][X] entries, whether they have a scope or not.
         * This ensures that included relations are also restricted by the same audience visibility rules.
         * 
         * For each include found:
         * - If NO sets exist: add filter[include][X][set][audience][userIds]={userId} and [groupIds]={groupIds}
         * - If sets exist: add filter[include][X][set][and][1][audience][userIds]={userId} and [groupIds]={groupIds}
         *   (caller's sets are at [and][0], audience is at [and][1], both userIds and groupIds under same [and][1])
         * 
         * CRITICAL: If an include does NOT have a set, we create one automatically.
         * This prevents security bypass where users query filter[include][X][relation]=... without set.
         * 
         * Also handles nested lookups within includes:
         * - filter[include][X][scope][lookup][Y][set][audience][...]
         * 
         * @param queryParams The list of query parameters to modify
         * @param userId The user ID to inject
         * @param groupsStr Comma-separated group IDs to inject
         */
        private void addAudienceSetsToIncludes(List<NameValuePair> queryParams, String userId, String groupsStr) {
                // Find ALL unique include indices (both with and without existing scopes)
                List<Integer> allIncludeIndices = queryParams.stream()
                        .map(nvp -> nvp.getName())
                        .filter(name -> name.startsWith("filter[include]["))
                        .map(name -> {
                                // Extract index from filter[include][X][...]
                                int start = "filter[include][".length();
                                int end = name.indexOf(']', start);
                                if (end > start) {
                                        try {
                                                return Integer.parseInt(name.substring(start, end));
                                        } catch (NumberFormatException e) {
                                                return null;
                                        }
                                }
                                return null;
                        })
                        .filter(idx -> idx != null)
                        .distinct()
                        .collect(Collectors.toList());

                logger.debug("Found {} include(s) to protect: {}", allIncludeIndices.size(), allIncludeIndices);

                // For each include, check if it has existing sets and add audience accordingly
                for (Integer idx : allIncludeIndices) {
                        String includePrefix = "filter[include][" + idx + "][set]";
                        
                        // Check if this include already has sets (after our transformation they'd be under [and][0])
                        boolean includeHasSets = queryParams.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith(includePrefix + "[and][0]"));
                        
                        if (includeHasSets) {
                                // Include has sets - add audience at [and][1] (both userIds and groupIds under same [and][1])
                                // Caller's sets are already at [and][0]
                                String audiencePrefix = includePrefix + "[and][1][audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to include[{}] with existing sets (wrapped under [and])", idx);
                        } else {
                                // No sets - add audience directly
                                // This handles cases where:
                                // 1. Include has no set at all: filter[include][X][relation]=...
                                // 2. Include has scope but no set: filter[include][X][scope][where][field]=...
                                // 3. Include has other params but no set
                                String audiencePrefix = includePrefix + "[audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to include[{}] without existing sets", idx);
                        }
                }
                
                // Also handle nested lookups within includes
                // Pattern: filter[include][X][scope][lookup][Y][...]
                // We need to find ALL lookups nested inside includes and ensure they have audience sets
                List<String> nestedLookupIndicesWithinIncludes = queryParams.stream()
                        .map(nvp -> nvp.getName())
                        .filter(name -> name.matches(".*filter\\[include\\]\\[\\d+\\]\\[scope\\]\\[lookup\\]\\[\\d+\\].*"))
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

                logger.debug("Found {} nested lookup(s) within includes to protect: {}", 
                        nestedLookupIndicesWithinIncludes.size(), nestedLookupIndicesWithinIncludes);

                for (String prefix : nestedLookupIndicesWithinIncludes) {
                        String lookupSetPrefix = prefix + "[set]";
                        
                        // Check if this nested lookup has sets (after transformation they'd be under [and][0])
                        boolean nestedHasSets = queryParams.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith(lookupSetPrefix + "[and][0]"));
                        
                        if (nestedHasSets) {
                                // Has sets - add audience at [and][1] (both userIds and groupIds under same [and][1])
                                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to nested lookup: {} (with existing sets)", prefix);
                        } else {
                                // No sets - add directly
                                // This handles cases where the nested lookup has no set
                                String audiencePrefix = lookupSetPrefix + "[audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to nested lookup: {} (without sets)", prefix);
                        }
                }
        }

        /**
         * Adds audience sets to all filter[lookup][X] entries, whether they have a scope or not.
         * This ensures that looked-up references are also restricted by the same audience visibility rules.
         * 
         * For each lookup found:
         * - If NO sets exist: add filter[lookup][X][set][audience][userIds]={userId} and [groupIds]={groupIds}
         * - If sets exist: add filter[lookup][X][set][and][1][audience][userIds]={userId} and [groupIds]={groupIds}
         *   (caller's sets are at [and][0], audience is at [and][1], both userIds and groupIds under same [and][1])
         * 
         * CRITICAL: If a lookup does NOT have a set, we create one automatically.
         * This prevents security bypass where users query filter[lookup][X][prop]=... without set.
         * 
         * Also handles nested lookups within lookups.
         * 
         * @param queryParams The list of query parameters to modify
         * @param userId The user ID to inject
         * @param groupsStr Comma-separated group IDs to inject
         */
        private void addAudienceSetsToLookups(List<NameValuePair> queryParams, String userId, String groupsStr) {
                // Find ALL top-level lookup indices (not nested within includes)
                List<Integer> allLookupIndices = queryParams.stream()
                        .map(nvp -> nvp.getName())
                        .filter(name -> name.startsWith("filter[lookup]["))
                        .filter(name -> !name.contains("filter[include][")) // Exclude nested ones handled in includes method
                        .map(name -> {
                                // Extract index from filter[lookup][X][...]
                                int start = "filter[lookup][".length();
                                int end = name.indexOf(']', start);
                                if (end > start) {
                                        try {
                                                return Integer.parseInt(name.substring(start, end));
                                        } catch (NumberFormatException e) {
                                                return null;
                                        }
                                }
                                return null;
                        })
                        .filter(idx -> idx != null)
                        .distinct()
                        .collect(Collectors.toList());

                logger.debug("Found {} top-level lookup(s) to protect: {}", allLookupIndices.size(), allLookupIndices);

                // For each lookup, check if it has existing sets and add audience accordingly
                for (Integer idx : allLookupIndices) {
                        String lookupPrefix = "filter[lookup][" + idx + "][set]";
                        
                        // Check if this lookup already has sets (after our transformation they'd be under [and][0])
                        boolean lookupHasSets = queryParams.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith(lookupPrefix + "[and][0]"));
                        
                        if (lookupHasSets) {
                                // Lookup has sets - add audience at [and][1] (both userIds and groupIds under same [and][1])
                                // Caller's sets are already at [and][0]
                                String audiencePrefix = lookupPrefix + "[and][1][audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to lookup[{}] with existing sets (wrapped under [and])", idx);
                        } else {
                                // No sets - add audience directly
                                // This handles cases where:
                                // 1. Lookup has no set at all: filter[lookup][X][localField]=...
                                // 2. Lookup has scope but no set: filter[lookup][X][scope][where][field]=...
                                // 3. Lookup has other params but no set
                                String audiencePrefix = lookupPrefix + "[audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to lookup[{}] without existing sets", idx);
                        }
                }
                
                // Handle nested lookups within lookups
                // Pattern: filter[lookup][X][scope][lookup][Y][...]
                List<String> nestedLookupIndicesWithinLookups = queryParams.stream()
                        .map(nvp -> nvp.getName())
                        .filter(name -> name.startsWith("filter[lookup]["))
                        .filter(name -> !name.contains("filter[include][")) // Only lookups, not within includes
                        .filter(name -> {
                                // Check if there are multiple [lookup] patterns
                                int firstLookup = name.indexOf("[lookup]");
                                int secondLookup = name.indexOf("[lookup]", firstLookup + "[lookup]".length());
                                return secondLookup > 0;
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

                logger.debug("Found {} nested lookup(s) within lookups to protect: {}", 
                        nestedLookupIndicesWithinLookups.size(), nestedLookupIndicesWithinLookups);

                for (String prefix : nestedLookupIndicesWithinLookups) {
                        String lookupSetPrefix = prefix + "[set]";
                        
                        // Check if this nested lookup has sets (after transformation they'd be under [and][0])
                        boolean nestedHasSets = queryParams.stream()
                                .anyMatch(nvp -> nvp.getName().startsWith(lookupSetPrefix + "[and][0]"));
                        
                        if (nestedHasSets) {
                                // Has sets - add audience at [and][1] (both userIds and groupIds under same [and][1])
                                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to nested lookup: {} (with existing sets)", prefix);
                        } else {
                                // No sets - add directly
                                // This handles cases where the nested lookup has no set
                                String audiencePrefix = lookupSetPrefix + "[audience]";
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[userIds]", userId));
                                queryParams.add(new BasicNameValuePair(audiencePrefix + "[groupIds]", groupsStr));
                                logger.debug("Added audience set to nested lookup: {} (without sets)", prefix);
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
