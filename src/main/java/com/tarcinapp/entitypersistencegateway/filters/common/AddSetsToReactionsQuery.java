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
 * Reaction query filter that enforces audience constraints for low authority users.
 * 
 * Valid reactionType values:
 * - entityReactions: Uses entitySet for filtering
 * - listReactions: Uses listSet for filtering
 * 
 * Differences from AddSetsToRecordQuery:
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

    private static final String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    private final Logger logger = LogManager.getLogger(AddSetsToReactionsQuery.class);

    @Value("${app.shortcode:#{tarcinapp}}")
    private String appShortcode;

    public AddSetsToReactionsQuery() { super(Config.class); }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String reactionType = config.getReactionType();
            logger.debug("AddSetsToReactionsQuery filter is started. reactionType: " + reactionType);

            GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);
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

            logger.debug("User roles are: " + roles);

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
                    String decodedOriginalUri = java.net.URLDecoder.decode(uri.toString(), java.nio.charset.StandardCharsets.UTF_8.name());
                    logger.debug("Original URI (decoded): " + decodedOriginalUri);
                } catch (Exception e) {
                    logger.debug("Original URI: " + uri + " (failed to decode: " + e.getMessage() + ")");
                }
            }

            List<NameValuePair> queryParams = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            // Check if caller has any top-level set parameters
            boolean callerHasSets = queryParams.stream().anyMatch(nvp -> nvp.getName().startsWith("set["));
            logger.debug("Caller has sets: {}", callerHasSets);

            // Transform lookup nested sets and top-level sets if present
            List<NameValuePair> newParams = queryParams.stream().map(nvp -> {
                String name = nvp.getName();
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
                return new BasicNameValuePair(newName, nvp.getValue());
            }).collect(Collectors.toList());

            String groupsStr = groups.stream().collect(Collectors.joining(","));

            // Determine target set key based on reactionType
            // If reactionType contains "list" use listSet, otherwise use entitySet
            boolean useListSet = reactionType != null && reactionType.toLowerCase().contains("list");
            String primarySetKey = useListSet ? "listSet" : "entitySet";

            // Check if caller supplied the primary set already
            boolean callerProvidedPrimary = queryParams.stream().anyMatch(nvp -> nvp.getName().startsWith(primarySetKey + "["));
            logger.debug("Caller provided {}: {}", primarySetKey, callerProvidedPrimary);

            if (callerProvidedPrimary) {
                // Wrap existing caller primary set by converting its prefix to primarySet[and][1]...
                newParams = newParams.stream().map(nvp -> {
                    String name = nvp.getName();
                    if (name.startsWith(primarySetKey + "[")) {
                        return new BasicNameValuePair(name.replaceFirst("^" + primarySetKey + "\\[", primarySetKey + "[and][1]["), nvp.getValue());
                    }
                    return nvp;
                }).collect(Collectors.toList());
                // Add audience at [and][0]
                newParams.add(new BasicNameValuePair(primarySetKey + "[and][0][audience][userIds]", userId));
                newParams.add(new BasicNameValuePair(primarySetKey + "[and][0][audience][groupIds]", groupsStr));
            } else {
                // Direct audience
                newParams.add(new BasicNameValuePair(primarySetKey + "[audience][userIds]", userId));
                newParams.add(new BasicNameValuePair(primarySetKey + "[audience][groupIds]", groupsStr));
            }

            // Add audience to top-level sets
            if (callerHasSets) {
                // Wrap under set[and][0] - both userIds and groupIds are part of the same audience set
                newParams.add(new BasicNameValuePair("set[and][0][audience][userIds]", userId));
                newParams.add(new BasicNameValuePair("set[and][0][audience][groupIds]", groupsStr));
            } else {
                // Add directly as set[audience][...]
                newParams.add(new BasicNameValuePair("set[audience][userIds]", userId));
                newParams.add(new BasicNameValuePair("set[audience][groupIds]", groupsStr));
            }

            // Protect lookups similar to records logic
            addAudienceSetsToLookups(newParams, userId, groupsStr);

            String newQueryStr = newParams.stream().map(v -> v.getName() + "=" + v.getValue()).collect(Collectors.joining("&"));
            URI newUri = UriComponentsBuilder.fromUri(uri).replaceQuery(newQueryStr).encode().build().toUri();

            // Log decoded URI for easier reading
            if (logger.isDebugEnabled()) {
                try {
                    String decodedQuery = java.net.URLDecoder.decode(newQueryStr, java.nio.charset.StandardCharsets.UTF_8.name());
                    String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                    if (!decodedQuery.isEmpty()) {
                        decodedUri += "?" + decodedQuery;
                    }
                    logger.debug("New URI (decoded): " + decodedUri);
                } catch (Exception e) {
                    logger.debug("New URI: " + newUri + " (failed to decode: " + e.getMessage() + ")");
                }
            }

            ServerWebExchange mutated = exchange.mutate().request(r -> r.uri(newUri)).build();
            return chain.filter(mutated);
        };
    }

    private void addAudienceSetsToLookups(List<NameValuePair> params, String userId, String groupsStr) {
        // Top-level lookups
        List<Integer> lookupIndices = params.stream().map(NameValuePair::getName)
            .filter(n -> n.startsWith("filter[lookup]["))
            .map(name -> {
                int start = "filter[lookup][".length();
                int end = name.indexOf(']', start);
                if (end > start) {
                    try { return Integer.parseInt(name.substring(start, end)); } catch (NumberFormatException e) { return null; }
                }
                return null;
            })
            .filter(i -> i != null)
            .distinct().collect(Collectors.toList());

        for (Integer idx : lookupIndices) {
            String prefix = "filter[lookup][" + idx + "][set]";
            boolean hasSets = params.stream().anyMatch(nvp -> nvp.getName().startsWith(prefix + "[and][0]"));
            if (hasSets) {
                params.add(new BasicNameValuePair(prefix + "[and][1][audience][userIds]", userId));
                params.add(new BasicNameValuePair(prefix + "[and][1][audience][groupIds]", groupsStr));
            } else {
                params.add(new BasicNameValuePair(prefix + "[audience][userIds]", userId));
                params.add(new BasicNameValuePair(prefix + "[audience][groupIds]", groupsStr));
            }
        }

        // Nested lookups inside lookups: filter[lookup][X][scope][lookup][Y]
        List<String> nestedPrefixes = params.stream().map(NameValuePair::getName)
            .filter(n -> n.matches(".*filter\\[lookup\\]\\[\\d+\\]\\[scope\\]\\[lookup\\]\\[\\d+\\].*"))
            .map(name -> {
                int idx = name.indexOf("[scope][lookup][");
                if (idx > 0) {
                    int start = idx + "[scope][lookup][".length();
                    int end = name.indexOf(']', start);
                    if (end > start) {
                        // Extract up to ...[lookup][Y]
                        return name.substring(0, end + 1);
                    }
                }
                return null;
            })
            .filter(s -> s != null)
            .distinct().collect(Collectors.toList());

        for (String prefix : nestedPrefixes) {
            String setPrefix = prefix + "[set]";
            boolean hasSets = params.stream().anyMatch(nvp -> nvp.getName().startsWith(setPrefix + "[and][0]"));
            if (hasSets) {
                params.add(new BasicNameValuePair(setPrefix + "[and][1][audience][userIds]", userId));
                params.add(new BasicNameValuePair(setPrefix + "[and][1][audience][groupIds]", groupsStr));
            } else {
                params.add(new BasicNameValuePair(setPrefix + "[audience][userIds]", userId));
                params.add(new BasicNameValuePair(setPrefix + "[audience][groupIds]", groupsStr));
            }
        }
    }

    public static class Config {
        private String reactionType; // determines entitySet vs listSet usage
        public String getReactionType() { return reactionType; }
        public void setReactionType(String reactionType) { this.reactionType = reactionType; }
    }
}
