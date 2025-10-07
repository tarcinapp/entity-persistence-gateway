package com.tarcinapp.entitypersistencegateway.filters.common;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

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

/**
 * This filter restricts the returned records from entity query.
 * Restrictions are based on user's role, owners of each record and record's
 * visibility.
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
 */
@Component
public class AddSetsToQuery
                extends AbstractGatewayFilterFactory<AddSetsToQuery.Config> {

        private Logger logger = LogManager.getLogger(AddSetsToQuery.class);

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

                        logger.debug("User roles are: " + roles);

                        /**
                         * For relations, skip adding sets for now (future implementation)
                         */
                        if ("relations".equals(recordType)) {
                                logger.debug("Skipping set addition for relations recordType. Exiting filter without any modification.");
                                return chain.filter(exchange);
                        }

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
                         * If user asks for specific sets, make sure they are valid under enforced
                         * scope.
                         * To that end, move all set queries under the 'and' clause and combine with
                         * enforced sets.
                         */
                        List<NameValuePair> newQuery = query.stream()
                                        .map(nvp -> {
                                                String newName = nvp.getName()
                                                                .replace("set[", "set[and][0][");
                                                return new BasicNameValuePair(newName, nvp.getValue());
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
                        newQuery.add(new BasicNameValuePair("set[and][1][audience][userIds]", userId));
                        newQuery.add(new BasicNameValuePair("set[and][1][audience][groupIds]", groupsStr));

                        // as we built new query string, now we can go ahead and change the query from
                        // the original request
                        ServerWebExchange modifiedExchange = exchange.mutate()
                                        .request(originalRequest -> {

                                                String newQueryStr = newQuery.stream()
                                                                .map(v -> v.getName() + "=" + v.getValue())
                                                                .collect(Collectors.joining("&"));

                                                URI newUri = UriComponentsBuilder.fromUri(uri)
                                                                .replaceQuery(newQueryStr)
                                                                .encode()
                                                                .build()
                                                                .toUri();

                                                logger.debug("New URI " + newUri);

                                                originalRequest
                                                                .uri(newUri);
                                        })
                                        .build();

                        return chain.filter(modifiedExchange);
                };
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
