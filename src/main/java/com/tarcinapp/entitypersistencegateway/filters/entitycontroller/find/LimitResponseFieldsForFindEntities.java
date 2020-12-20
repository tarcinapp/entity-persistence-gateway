package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.authorization.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyData;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

/**
 * This filter is used to prevent some fields of the records return from the response.
 * 
 * For example, visitor users are not permitted to see the 'visibility', 'validFromDateTime', 'validUntilDateTime' fields.
 * However, permitted fields list is not certain, as this filter asks the forbidden fields list to the PDP.
 */
@Component
public class LimitResponseFieldsForFindEntities
        extends AbstractGatewayFilterFactory<LimitResponseFieldsForFindEntities.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    private Logger logger = LogManager.getLogger(LimitResponseFieldsForFindEntities.class);

    private final static Pattern FIELD_QUERY_PATTERN = Pattern.compile("filter\\[fields\\]\\[([^\\]]+)\\]");

    public LimitResponseFieldsForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String token = this.extractToken(request);

            logger.debug("LimitResponseFieldsForFindEntities filter is started.");

            if (token == null) {
                logger.debug("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            HttpMethod httpMethod = request.getMethod();

            // instantiate policy data
            PolicyData policyData = new PolicyData();
            policyData.setPolicyName(config.getPolicyName());
            policyData.setHttpMethod(httpMethod);
            policyData.setEncodedJwt(token);
            policyData.setQueryParams(request.getQueryParams());
            policyData.setRequestPath(request.getPath());

            if (logger.getLevel().compareTo(Level.DEBUG) >= 0) {
                logger.debug("Policy data is prepared to ask PDP.");
                ObjectMapper mapper = new ObjectMapper();

                try {
                    String policyDataStr = mapper.writeValueAsString(policyData);
                    logger.debug("Policy data: {}", policyDataStr);
                } catch (JsonProcessingException e) {
                    logger.debug("Unable to serialize policy data to JSON string.");
                }
            }

            /**
             * Ask to the PDP to get the list of forbidden fields for that specific call
             */
            return authorizationClient.executePolicy(policyData, PolicyResponse.class).flatMap(pr -> {
                List<String> hideFields = pr.getFields();

                if (hideFields.size() == 0) {
                    logger.debug("No field is configured to be hidden for this invocation. Exiting from filter.");
                    return chain.filter(exchange);
                }

                logger.debug(
                        "Following fields will be hidden by modifying the query parameters: " + hideFields.toString());

                return this.limitTheFieldsInRequest(hideFields, exchange, chain);
            });
        };
    }

    private Mono<Void> limitTheFieldsInRequest(List<String> fields, ServerWebExchange exchange,
            GatewayFilterChain chain) {
        URI uri = exchange.getRequest().getURI();
        logger.debug("Original URI: " + uri);

        List<NameValuePair> query = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));

        // Check if there is any filter[field][*]=true
        boolean anyFieldTrue = query.stream().anyMatch(nvp -> {

            return nvp.getName() != null && nvp.getName().matches("filter\\[fields\\]\\[([^\\]]+)\\]")
                    && nvp.getValue() != null && nvp.getValue().equals("true");
        });

        if (anyFieldTrue) {
            // seems that caller wants some specific fields in response
            // we will check if all those fields listed in query are valid
            // if there are unwanted fields, we are just removing them from query variable
            logger.debug("User asks for some specific filters. Removing managed fields from the query if any.");

            query.removeIf((nvp) -> {
                Matcher matcher = FIELD_QUERY_PATTERN.matcher(nvp.getName());

                if (matcher.find()) {
                    String matched = matcher.group(1);
                    boolean willBeRemoved = fields.contains(matcher.group(1));

                    if (willBeRemoved) {
                        logger.debug("User asked for the field: '" + matched
                                + "'. This field will be removed from query string.");
                        return true;
                    }
                }

                return false;
            });
        }

        // check hidden fields are added to the query as false, if not, add to the query
        // as false
        fields.stream().forEach(f -> {
            String queryName = "filter[fields][" + f + "]";
            String queryValue = "false";
            NameValuePair nvp = new BasicNameValuePair(queryName, queryValue);

            if (!query.contains(nvp))
                query.add(nvp);
        });

        ServerWebExchange modifiedExchange = exchange.mutate().request(originalRequest -> {

            String newQueryStr = query.stream().map(v -> v.getName() + "=" + v.getValue())
                    .collect(Collectors.joining("&"));

            URI newUri = UriComponentsBuilder.fromUri(uri).replaceQuery(newQueryStr).encode().build().toUri();

            logger.debug("New URI " + newUri);

            originalRequest.uri(newUri);
        }).build();

        return chain.filter(modifiedExchange);
    }

    private String extractToken(ServerHttpRequest request) {
        if (!request.getHeaders().containsKey("Authorization"))
            return null;

        String authHeader = request.getHeaders().get("Authorization").get(0);

        if (!authHeader.startsWith("Bearer "))
            return null;

        return authHeader.replaceFirst("Bearer\\s", "");
    }

    /**
     * This POJO is used to map PDP response of inquiry of forbidden fields.
     */
    private static class PolicyResponse {
        ArrayList<String> fields;

        public ArrayList<String> getFields() {
            return this.fields;
        }

        public void setFields(ArrayList<String> fields) {
            this.fields = fields;
        }
    }

    /**
     * This filter uses the policy name to inquiry PDP. PDP evaluates the PolicyData
     * and returns the list of forbidden fields for that specific inquiry.
     */
    public static class Config {
        String policyName;

        public String getPolicyName() {
            return this.policyName;
        }

        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }
    }
}