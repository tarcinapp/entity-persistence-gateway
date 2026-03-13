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
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import lombok.extern.slf4j.Slf4j;

/**
 * This filter restricts the returned entities or lists based on user permissions and intermediary record status.
 * * IMPORTANT: This filter is specifically designed for ENTITIES and LISTS accessed through other records.
 * For direct entity/list access, use AddSetsToEntityListOrReactionViaRecordQuery filter instead.
 * For relations, use AddSetsToRelationQuery filter instead.
 * * How this filter works:
 * This filter applies two distinct types of restrictions:
 * * 1. Record Visibility via set[audience]:
 * - Restricts the final records based on the user's ownership or viewership (userId and group memberships).
 * - User's custom set[...] queries are preserved and combined with this audience check using an [and] logic.
 * * 2. Intermediary Record Status via setThrough[actives]:
 * - Restricts the query to only include items where the intermediary record (the one being accessed through) is active.
 * - User's custom setThrough[...] queries are preserved and combined with this status check using an [and] logic.
 * * The filter constructs (when user has existing queries):
 * set[and][0][audience][userIds/groupIds] - Enforced: Final record visibility check
 * set[and][1][...] - User's custom set queries (if any)
 * * setThrough[and][0][actives]=true - Enforced: Intermediary record status check
 * setThrough[and][1][...] - User's custom setThrough queries (if any)
 * * When user has no existing queries:
 * set[audience][userIds/groupIds] - Direct record visibility check
 * setThrough[actives]=true - Direct intermediary status check
 */
@Component
@Slf4j
public class AddSetsToThroughRecordQuery extends AbstractGatewayFilterFactory<AddSetsToThroughRecordQuery.Config> {
    @Value("${app.shortcode:#{tarcinapp}}")
    private String appShortcode;

    public AddSetsToThroughRecordQuery() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            log.debug("AddSetsToThroughRecordQuery filter is started.");

            GatewaySecurityContext gc = (GatewaySecurityContext) exchange.getAttributes()
                    .get(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
            
            if (gc == null || gc.getRoles() == null) {
                log.debug("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            ArrayList<String> roles = gc.getRoles();
            String userId = gc.getAuthSubject();
            ArrayList<String> groups = gc.getGroups();

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
                    appShortcode + ".entities.admin",
                    appShortcode + ".entities.find.admin",
                    appShortcode + ".entities.editor",
                    appShortcode + ".entities.find.editor",
                    appShortcode + ".lists.admin",
                    appShortcode + ".lists.find.admin",
                    appShortcode + ".lists.editor",
                    appShortcode + ".lists.find.editor");

            /**
             * This filter only applies when user has lower authority than editor user.
             */
            if (prefixedRolesStream.anyMatch(roles::contains)) {

                log.debug(
                        "No need to limit response items for these roles. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            URI uri = exchange.getRequest().getURI();
            log.debug("Original URI: {}", uri);

            
            MultiValueMap<String, String> originalQueryParams = exchange.getRequest().getQueryParams();
            MultiValueMap<String, String> newQueryParams = new LinkedMultiValueMap<>();


            /**
             * Check if caller has any existing sets that need wrapping.
             */
            boolean callerHasSet = originalQueryParams.keySet().stream()
                    .anyMatch(name -> name.startsWith("set["));
            boolean callerHasSetThrough = originalQueryParams.keySet().stream()
                    .anyMatch(name -> name.startsWith("setThrough["));
            
            log.debug("Caller has - set: {}, setThrough: {}", callerHasSet, callerHasSetThrough);

            /**
             * Transform user's existing queries by wrapping them under [and][1] only if they exist.
             */
            for (Map.Entry<String, List<String>> entry : originalQueryParams.entrySet()) {
                String paramName = entry.getKey();
                List<String> values = entry.getValue();
                String newName = paramName;

                // Wrap user's set queries under [and][1] only if they have sets
                if (callerHasSet && paramName.startsWith("set[")) {
                    newName = paramName.replaceFirst("^set\\[", "set[and][1][");
                }
                // Wrap user's setThrough queries under [and][1] only if they have setThrough
                else if (callerHasSetThrough && paramName.startsWith("setThrough[")) {
                    newName = paramName.replaceFirst("^setThrough\\[", "setThrough[and][1][");
                }

                newQueryParams.addAll(newName, values);
            }

            /**
             * Add audience set to restrict records based on user's visibility.
             * If user has existing sets, audience check goes at [and][0]
             */
            String groupsStr = groups.stream().collect(Collectors.joining(","));
            
            if (callerHasSet) {
                // User has sets - add our audience check at [and][0]
                newQueryParams.add("set[and][0][audience][userIds]", userId);
                newQueryParams.add("set[and][0][audience][groupIds]", groupsStr);
            } else {
                // No user sets - add audience check directly
                newQueryParams.add("set[audience][userIds]", userId);
                newQueryParams.add("set[audience][groupIds]", groupsStr);
            }

            log.debug("Added set audience - userIds: {}, groupIds: {}", userId, groupsStr);

            /**
             * Add setThrough actives to restrict to active intermediary records (list or entity).
             * If user has existing setThrough, actives check goes at [and][0]
             */
            if (callerHasSetThrough) {
                // User has setThrough - add our actives check at [and][0]
                newQueryParams.add("setThrough[and][0][actives]", "true");
            } else {
                // No user setThrough - add actives check directly
                newQueryParams.add("setThrough[actives]", "true");
            }

            log.debug("Added setThrough actives");

            // Build new URI
            URI newUri = UriComponentsBuilder.fromUri(uri)
                    .replaceQueryParams(newQueryParams)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            // Log decoded URI for easier reading
            if (log.isDebugEnabled()) {
                try {
                    String decodedQuery = UriComponentsBuilder.newInstance().queryParams(newQueryParams).build().encode().getQuery();
                    String decodedUri = newUri.getScheme() + "://" + newUri.getAuthority() + newUri.getPath();
                    if (decodedQuery != null && !decodedQuery.isEmpty()) {
                        decodedUri += "?" + java.net.URLDecoder.decode(decodedQuery, StandardCharsets.UTF_8.name());
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

    public static class Config {
        // No specific configuration needed
    }
}