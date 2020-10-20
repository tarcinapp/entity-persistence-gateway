package com.tarcinapp.entitypersistencegateway.filters;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewayContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

@Component
public class ClearFieldQueriesGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ClearFieldQueriesGatewayFilterFactory.Config> {

    private Logger logger = LogManager.getLogger(ClearFieldQueriesGatewayFilterFactory.class);

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    private final static Pattern FIELD_QUERY_PATTERN = Pattern.compile("filter\\[fields\\]\\[([^\\]]+)\\]");

    public ClearFieldQueriesGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            
            logger.debug("ClearFieldQueries filter is started.");

            GatewayContext gc = (GatewayContext)exchange.getAttributes().get(GATEWAY_CONTEXT_ATTR);
            ArrayList<String> roles = gc.getRoles();

            if(roles == null) {
                logger.debug("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            logger.debug("User roles are: " + roles);

            // check if user role contains any role whose value equal or above editor role level
            if(roles.indexOf("tarcinapp_admin") >= 0 || roles.indexOf("tarcinapp_editor") >= 0) {
                
                logger.debug("No need to clear field queries for these roles. Exiting withouth any modification.");
                return chain.filter(exchange);
            }

            ArrayList<String> hideFields = new ArrayList<String>();
            hideFields.add("validFromDateTime");
            hideFields.add("validUntilDateTime");
            hideFields.add("visibility");

            if(hideFields.size() == 0) {
                logger.debug("No field is configured to be hidden for this invocation. Exiting from filter.");
                return chain.filter(exchange);
            }
            logger.debug("Following fields will be hidden by modifying the query parameters: " + hideFields.toString());

            URI uri = exchange.getRequest().getURI();
            logger.debug("Original URI: " + uri);

            List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

            // Check if there is any filter[field][*]=true
            boolean anyFieldTrue = query
                .stream()
                .anyMatch(nvp -> {

                    return nvp.getName() != null
                            && nvp.getName().matches("filter\\[fields\\]\\[([^\\]]+)\\]")
                            && nvp.getValue()  != null
                            && nvp.getValue().equals("true");
                });

            if(anyFieldTrue) {
                // seems that caller wants some specific fields in response
                // we will check if all those fields listed in query are valid
                // if there are unwanted fields, we are just removing them from query variable
                logger.debug("User asks for some specific filters. Removing managed fields from the query if any.");

                query.removeIf((nvp) -> {
                    Matcher matcher = FIELD_QUERY_PATTERN.matcher(nvp.getName());
                    
                    if(matcher.find())
                        return hideFields.contains(matcher.group(1));
                    
                    return false;
                });
            }

            // check hidden fields are added to the query as false, if not, add to the query as false
            hideFields
                .stream()
                .forEach(f -> {
                    String queryName = "filter[fields]["+f+"]";
                    String queryValue = "false";
                    NameValuePair nvp = new BasicNameValuePair(queryName, queryValue);
                
                    if(!query.contains(nvp))
                        query.add(nvp);
                });

            ServerWebExchange modifiedExchange = exchange.mutate()
                .request(originalRequest -> {

                    String newQueryStr = query.stream()
                        .map(v -> v.getName() + "=" +v.getValue())
                        .collect(Collectors.joining( "&" ));

                    URI newUri = UriComponentsBuilder.fromUri(uri)
                        .replaceQuery(newQueryStr)
                        .build()
                        .toUri();

                    logger.debug("New query string: " + newUri);

                    originalRequest.uri(newUri);
                })
                .build();

            return chain.filter(modifiedExchange);
        };
    }
     
    public static class Config {
        private String fieldNames;

        public String getFieldNames() {
            return this.fieldNames;
        }

        public void setFieldNames(String fieldNames) {
            this.fieldNames = fieldNames;
        }
    }
}