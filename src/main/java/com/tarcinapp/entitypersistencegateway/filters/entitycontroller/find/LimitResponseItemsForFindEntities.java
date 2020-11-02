package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.regex.Pattern;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
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

@Component
public class LimitResponseItemsForFindEntities
        extends AbstractGatewayFilterFactory<LimitResponseItemsForFindEntities.Config> {

    private Logger logger = LogManager.getLogger(LimitResponseItemsForFindEntities.class);

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    private final static Pattern MANAGED_FIELDS_PATTERN = Pattern.compile("filter\\[where\\].*(?:ownerUsers|ownerGroups|visibility).*");

    public LimitResponseItemsForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("LimitResponseItemsForFindEntities filter is started.");

            GatewayContext gc = (GatewayContext)exchange.getAttributes().get(GATEWAY_CONTEXT_ATTR);
            ArrayList<String> roles = gc.getRoles();

            if(roles == null) {
                logger.debug("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }
            
            logger.debug("User roles are: " + roles);

            // check if user role contains any role whose value equal or above editor role level
            if(roles.indexOf("tarcinapp_admin") >= 0 || roles.indexOf("tarcinapp_editor") >= 0) {
                
                logger.debug("No need to limit response items for these roles. Exiting filter withouth any modification.");
                return chain.filter(exchange);
            }

            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: " + uri);

            List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            logger.debug("Remove if any of the managed field is used in query.");

            query.removeIf((nvp) -> {
                Matcher matcher = MANAGED_FIELDS_PATTERN.matcher(nvp.getName());
                
                if(matcher.matches()) {
                    logger.debug("User used a managed field in query: '" + nvp.getName() + "'. This query will be removed before going to downstream service.");
                    return true;
                }
                
                return false;
            });

            // move existing filters under and condition
            List<NameValuePair> newQuery = query.stream()
                .map(nvp -> {
                    String newName = nvp.getName()
                        .replace("filter[where]", "filter[where][and][0]");
                    return new BasicNameValuePair(newName, nvp.getValue());
                })
                .collect(Collectors.toList());

            // add values for managed fields

            // add those I am the owner
            newQuery.add(new BasicNameValuePair("filter[where][and][1][or][0][ownerUsers]", gc.getAuthSubject()));

            // add those public
            newQuery.add(new BasicNameValuePair("filter[where][and][1][or][1][visibility]", "public"));

            // add those protected and one of my group is the owner
            if(gc.getGroups().size() > 1)
                gc.getGroups().stream()
                    .forEach(g -> {
                        newQuery.add(new BasicNameValuePair("filter[where][and][1][or][2][ownerGroups][inq]", g));
                    });
            else
                newQuery.add(new BasicNameValuePair("filter[where][and][1][or][2][ownerGroups]", gc.getGroups().get(0)));
            
            newQuery.add(new BasicNameValuePair("filter[where][and][1][or][2][visibility]", "protected"));

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

                    originalRequest.uri(newUri);
                })
                .build();

            return chain.filter(modifiedExchange);
        };
    }
    
    public static class Config {

    }
}
