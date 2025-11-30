package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

/**
 * Reaction query filter that enforces audience constraints for low authority users.
 *  * Valid reactionType values:
 * - entityReactions: Uses entitySet for filtering
 * - listReactions: Uses listSet for filtering
 *  * Differences from AddSetsToEntityListOrReactionViaRecordQuery:
 * - Accepts reactionType (not recordType)
 * - Applies audience sets to either entitySet or listSet depending on the reactionType
 * - Does NOT wrap or process filter[include] parameters as reactions do not support includes
 * - Still protects top-level sets and filter[lookup] chains (including nested lookups)
 *
 * Behavior:
 * - If caller does NOT provide the corresponding *Set (entitySet or listSet): add audience set directly
 * - If caller provides *Set already: wrap caller's set + audience set under set[and][0] (audience) & set[and][1] (caller) semantics
 * - Maintains existing role bypass logic (admins/editors bypass audience restriction)
 */
@Component
public class AddSetsToReactionsQuery extends AbstractGatewayFilterFactory<AddSetsToReactionsQuery.Config> {

    private final Logger logger = LogManager.getLogger(AddSetsToReactionsQuery.class);

    @Value("${app.shortcode:#{tarcinapp}}")
    private String appShortcode;

    public AddSetsToReactionsQuery() { super(Config.class); }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String reactionType = config.getReactionType();
            logger.debug("AddSetsToReactionsQuery filter is started. reactionType: {}", reactionType);

            GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes().get(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
            
            if (gc == null) {
                logger.debug("Security context missing; skipping modifications.");
                return chain.filter(exchange);
            }
            
            ArrayList<String> roles = gc.getRoles();
            String userId = gc.getAuthSubject();
            ArrayList<String> groups = gc.getGroups();

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

            logger.debug("User roles are: {}", roles);

            // Roles with bypass privileges
            Stream<String> privilegedRoles = Stream.of(
                appShortcode + ".admin",
                appShortcode + ".editor",
                appShortcode + ".reactions.admin",
                appShortcode + ".reactions.editor",
                appShortcode + ".reactions.find.admin",
                appShortcode + ".reactions.find.editor"
            );

            if (reactionType != null) {
                privilegedRoles = Stream.concat(privilegedRoles, Stream.of(
                    appShortcode + "." + reactionType + ".admin",
                    appShortcode + "." + reactionType + ".editor",
                    appShortcode + "." + reactionType + ".find.admin",
                    appShortcode + "." + reactionType + ".find.editor"
                ));
            }

            if (privilegedRoles.anyMatch(roles::contains)) {
                logger.debug("No need to limit response items for these roles. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            URI uri = exchange.getRequest().getURI();
            if (logger.isDebugEnabled()) {
                try {
                    String decodedOriginalUri = java.net.URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8.name());
                    logger.debug("Original URI (decoded): {}", decodedOriginalUri);
                } catch (Exception e) {
                    logger.debug("Original URI: {} (failed to decode: {})", uri, e.getMessage());
                }
            }

            // Extract original query parameters
            MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
            MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();

            // Check if caller has any top-level set parameters
            boolean callerHasSets = originalQueryParams.keySet().stream().anyMatch(name -> name.startsWith("set["));
            logger.debug("Caller has sets: {}", callerHasSets);

            // Transform lookup nested sets and top-level sets if present
            for (Map.Entry<String, List<String>> entry : originalQueryParams.entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();
                String newName = name;
                
                if (callerHasSets && name.startsWith("set[")) {
                    // Caller has top-level sets - wrap them under [and][1]
                    newName = name.replaceFirst("^set\\[", "set[and][1][");
                }
                
                if (name.startsWith("filter[lookup][") && name.contains("][set][") && !name.contains("][set][and][")) {
                    newName = name.replaceFirst("\\]\\[set\\]\\[", "][set][and][0][");
                }
                // Nested lookups inside lookups
                if (name.matches(".*filter\\[lookup\\]\\[\\d+\\]\\[scope\\]\\[lookup\\]\\[\\d+\\].*\\[set\\]\\[.*") && name.contains("][set][") && !name.contains("][set][and][")) {
                    newName = name.replaceFirst("\\]\\[set\\]\\[", "][set][and][0][");
                }
                
                newQueryParams.addAll(newName, values);
            }

            String groupsStr = groups.stream().collect(Collectors.joining(","));

            // Determine target set key based on reactionType
            // If reactionType contains "list" use listSet, otherwise use entitySet
            boolean useListSet = reactionType != null && reactionType.toLowerCase().contains("list");
            String primarySetKey = useListSet ? "listSet" : "entitySet";

            // Check if caller supplied the primary set already
            boolean callerProvidedPrimary = originalQueryParams.keySet().stream().anyMatch(name -> name.startsWith(primarySetKey + "["));
            logger.debug("Caller provided {}: {}", primarySetKey, callerProvidedPrimary);

            if (callerProvidedPrimary) {
                // Wrap existing caller primary set by converting its prefix to primarySet[and][1]...
                
                // Add audience at [and][0]
                newQueryParams.add(primarySetKey + "[and][0][audience][userIds]", userId);
                newQueryParams.add(primarySetKey + "[and][0][audience][groupIds]", groupsStr);
            } else {
                // Direct audience
                newQueryParams.add(primarySetKey + "[audience][userIds]", userId);
                newQueryParams.add(primarySetKey + "[audience][groupIds]", groupsStr);
            }

            // Add audience to top-level sets (This part mirrors the logic in the primary set check above, using [and][0] if callerHasSets)
            if (callerHasSets) {
                // Wrap under set[and][0] - both userIds and groupIds are part of the same audience set
                newQueryParams.add("set[and][0][audience][userIds]", userId);
                newQueryParams.add("set[and][0][audience][groupIds]", groupsStr);
            } else {
                // Add directly as set[audience][...]
                newQueryParams.add("set[audience][userIds]", userId);
                newQueryParams.add("set[audience][groupIds]", groupsStr);
            }

            // Protect lookups similar to records logic
            addAudienceSetsToLookups(newQueryParams, userId, groupsStr);

            URI newUri = UriComponentsBuilder.fromUri(uri)
                    .replaceQueryParams(newQueryParams) 
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            // Log decoded URI for easier reading
            if (logger.isDebugEnabled()) {
                try {
                    // Loglama için URI'ı temizleme
                    String decodedQuery = UriComponentsBuilder.newInstance().queryParams(newQueryParams).build().encode().getQuery();
                    String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                    if (decodedQuery != null && !decodedQuery.isEmpty()) {
                        decodedUri += "?" + java.net.URLDecoder.decode(decodedQuery, StandardCharsets.UTF_8.name());
                    }
                    logger.debug("New URI (decoded): {}", decodedUri);
                } catch (Exception e) {
                    logger.debug("New URI: {} (failed to decode: {})", newUri, e.getMessage());
                }
            }

            ServerWebExchange mutated = exchange.mutate().request(r -> r.uri(newUri)).build();
            return chain.filter(mutated);
        };
    }

    /**
     * Adds audience sets to all filter[lookup][X] entries, whether they have a scope or not.
     * This ensures that looked-up references are also restricted by the same audience visibility rules.
     * * For each lookup found:
     * - If NO sets exist: add filter[lookup][X][set][audience][userIds]={userId} and [groupIds]={groupIds}
     * - If sets exist: add filter[lookup][X][set][and][1][audience][userIds]={userId} and [groupIds]={groupIds}
     *   (caller's sets are at [and][0], audience is at [and][1], both userIds and groupIds under same [and][1])
     * * CRITICAL: If a lookup does NOT have a set, we create one automatically.
     * This prevents security bypass where users query filter[lookup][X][prop]=... without set.
     * * Also handles nested lookups within lookups.
     * * @param queryParams The MultiValueMap of query parameters to modify
     * @param userId The user ID to inject
     * @param groupsStr Comma-separated group IDs to inject
     */
    private void addAudienceSetsToLookups(MultiValueMap<String, String> queryParams, String userId, String groupsStr) {
        // Find ALL top-level lookup indices (not nested within includes, which reactions queries don't have)
        // We look for base prefixes like filter[lookup][X]...
        List<String> allLookupPrefixes = queryParams.keySet().stream()
                .filter(name -> name.startsWith("filter[lookup]["))
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

        logger.debug("Found {} top-level lookup(s) to protect: {}", allLookupPrefixes.size(), allLookupPrefixes);

        // For each lookup, check if it has existing sets and add audience accordingly
        for (String prefix : allLookupPrefixes) {
            String lookupSetPrefix = prefix + "[set]";
            
            // Check if this lookup already has sets (after our transformation they'd be under [and][0])
            boolean lookupHasSets = queryParams.keySet().stream()
                    .anyMatch(name -> name.startsWith(lookupSetPrefix + "[and][0]"));
            
            if (lookupHasSets) {
                // Lookup has sets - add audience at [and][1] 
                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                queryParams.add(audiencePrefix + "[userIds]", userId);
                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                logger.debug("Added audience set to lookup {} with existing sets (wrapped under [and])", prefix);
            } else {
                // No sets - add audience directly
                String audiencePrefix = lookupSetPrefix + "[audience]";
                queryParams.add(audiencePrefix + "[userIds]", userId);
                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                logger.debug("Added audience set to lookup {} without existing sets", prefix);
            }
        }
        
        // --- Nested Lookups within Lookups ---
        // Pattern: filter[lookup][X][scope][lookup][Y]
        List<String> nestedLookupPrefixes = queryParams.keySet().stream()
                .filter(name -> name.startsWith("filter[lookup]["))
                .filter(name -> name.contains("[scope][lookup]"))
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
                        nestedLookupPrefixes.size(), nestedLookupPrefixes);

        for (String prefix : nestedLookupPrefixes) {
            String lookupSetPrefix = prefix + "[set]";
            
            // Check if this nested lookup has sets (after transformation they'd be under [and][0])
            boolean nestedHasSets = queryParams.keySet().stream()
                    .anyMatch(name -> name.startsWith(lookupSetPrefix + "[and][0]"));
            
            if (nestedHasSets) {
                // Has sets - add audience at [and][1]
                String audiencePrefix = lookupSetPrefix + "[and][1][audience]";
                queryParams.add(audiencePrefix + "[userIds]", userId);
                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                logger.debug("Added audience set to nested lookup: {} (with existing sets)", prefix);
            } else {
                // No sets - add directly
                String audiencePrefix = lookupSetPrefix + "[audience]";
                queryParams.add(audiencePrefix + "[userIds]", userId);
                queryParams.add(audiencePrefix + "[groupIds]", groupsStr);
                logger.debug("Added audience set to nested lookup: {} (without sets)", prefix);
            }
        }
    }

    public static class Config {
        private String reactionType; // determines entitySet vs listSet usage
        public String getReactionType() { return reactionType; }
        public void setReactionType(String reactionType) { this.reactionType = reactionType; }
    }
}