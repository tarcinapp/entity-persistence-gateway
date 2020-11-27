package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.regex.Pattern;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find.LimitResponseFieldsForFindEntities.Config;
import org.apache.logging.log4j.LogManager;
import java.util.regex.Matcher;
import org.apache.logging.log4j.Logger;
import com.tarcinapp.entitypersistencegateway.GatewayContext;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.stream.Collectors;

/**
 * This filter restricts the returned records from entity query.
 * Restrictions are based on user's role, owners of each record and record's visibility.
 * 
 * What is record's ownership?
 * Each record has ownerGroups and ownerUsers arrays.
 * - If user's id present on ownerUsers array, then this user is the most powerfull user on that specific record. 
 * No matter what the record's visibility.
 * - If user's group name present on ownerGroups array, and, the records visibility is protected, then again user is the owner of the record.
 * --------
 * If user is not an editor or an admin, then:
 * - User can see it's own records.
 * - User can see active, public records.
 * 
 * For example;
 * If a record's visibility value is public, but it's validitiy is expired, then this filter prevents this record to return from response.
 * If a record's visibility value is public and validUntilDateTime field is empty whereas validFromDateTime field has a value in past, then this filter let
 * that specific record to return from response.
 */
@Component
public class LimitResponseItemsForFindEntities
        extends AbstractGatewayFilterFactory<LimitResponseItemsForFindEntities.Config> {

    private Logger logger = LogManager.getLogger(LimitResponseItemsForFindEntities.class);

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    public LimitResponseItemsForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("LimitResponseItemsForFindEntities filter is started.");

            GatewayContext gc = (GatewayContext)exchange.getAttributes().get(GATEWAY_CONTEXT_ATTR);
            ArrayList<String> roles = gc.getRoles();
            String userId = gc.getAuthParty();
            ArrayList<String> groups = gc.getGroups();

            if(roles == null) {
                logger.debug("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }
            
            logger.debug("User roles are: " + roles);

            /**
             * This filter only applies when user has lower authority then editor user. 
             */
            if(roles.indexOf("tarcinapp_admin") >= 0 || roles.indexOf("tarcinapp_editor") >= 0) {
                
                logger.debug("No need to limit response items for these roles. Exiting filter withouth any modification.");
                return chain.filter(exchange);
            }

            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: " + uri);

            List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            /**
             * If user asks for specific sets, make sure they are valid under enforced scope.
             * To that end, move all set queries under the 'and' clause and combine with enforced sets.
             */
            List<NameValuePair> newQuery = query.stream()
                .map(nvp -> {
                    String newName = nvp.getName()
                        .replace("set[", "set[and][0][");
                    return new BasicNameValuePair(newName, nvp.getValue());
                })
                .collect(Collectors.toList());

            /**
             * Following sets are added in order to reduce the scope of the query.
             * Users with low authority can only see the public and active records with their own records.
             * 
             * Note: If a record has user's user id in its ownerUsers array, then this record belongs to that user.
             * In addition to that, if user's group id presents in record's ownerGroups array and it's visibility is protected,
             * this record is also belongs to that user too.
             */
            newQuery.add(new BasicNameValuePair("set[and][1][actives]", ""));
            newQuery.add(new BasicNameValuePair("set[and][2][or][0][publics]", ""));

            // owners set requires user id and groups in following format userId1,userId2;group1,group2
            String groupsStr = groups.stream().collect(Collectors.joining(","));
            newQuery.add(new BasicNameValuePair("set[and][2][or][1][owners]", userId + ";" + groupsStr));

            // as we built new query string, now we can go ahead and change the query from the original request
            ServerWebExchange modifiedExchange = exchange.mutate()
                .request(originalRequest -> {

                    String newQueryStr = newQuery.stream()
                        .map(v -> v.getName() + "=" +v.getValue())
                        .collect(Collectors.joining( "&" ));

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

    }
}
