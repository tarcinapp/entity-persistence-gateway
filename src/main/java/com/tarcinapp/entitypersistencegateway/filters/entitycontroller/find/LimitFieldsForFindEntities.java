package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.net.URI;
import java.nio.charset.Charset;
import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

/**
 * This filter is used to prevent some fields of the records return from the
 * response.
 * 
 * For example, visitor users are not permitted to see the 'visibility',
 * 'validFromDateTime', 'validUntilDateTime' fields. However, permitted fields
 * list is not certain, as this filter asks the forbidden fields list to the
 * PDP.
 */
@Component
public class LimitFieldsForFindEntities extends AbstractGatewayFilterFactory<LimitFieldsForFindEntities.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    @Autowired(required = false)
    Key key;

    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private Logger logger = LogManager.getLogger(LimitFieldsForFindEntities.class);

    private final static Pattern FIELD_QUERY_PATTERN = Pattern.compile("filter\\[fields\\]\\[([^\\]]+)\\]");

    public LimitFieldsForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("LimitResponseFieldsForFindEntities filter is started.");

            if (key == null) {
                logger.info("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain)
                .onErrorResume(e -> {
                    // An error occured while trying to limit fields.
                    // it's safer to return 500 Internal Server Error
                    logger.error(e);

                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

                    return response.setComplete();
                });
        };
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        PolicyData policyInquiryData;

        try {
            policyInquiryData = getPolicyInquriyData(exchange);
        } catch (CloneNotSupportedException e) {
            return Mono.error(e);
        }

        policyInquiryData.setPolicyName(config.policyName);

        /**
         * Ask to the PDP to get the list of forbidden fields for that specific call
         */
        return authorizationClient.executePolicy(policyInquiryData, PolicyResponse.class).flatMap(pr -> {
            List<String> hideFields = pr.getFields();

            if (hideFields.size() == 0) {
                logger.debug("No field is configured to be hidden for this invocation. Exiting from filter.");
                return chain.filter(exchange);
            }

            logger.debug("Following fields will be hidden by modifying the query parameters: " + hideFields.toString());

            return this.limitTheFieldsInRequest(hideFields, exchange, chain);
        });
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

        // apply modified query string
        ServerWebExchange modifiedExchange = exchange.mutate().request(originalRequest -> {

            String newQueryStr = query.stream().map(v -> v.getName() + "=" + v.getValue())
                    .collect(Collectors.joining("&"));

            URI newUri = UriComponentsBuilder.fromUri(uri).replaceQuery(newQueryStr).encode().build().toUri();

            logger.debug("New URI " + newUri);

            originalRequest.uri(newUri);
        }).build();

        return chain.filter(modifiedExchange);
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * 
     * @param exchange
     * @return
     * @throws CloneNotSupportedException
     */
    private PolicyData getPolicyInquriyData(ServerWebExchange exchange) throws CloneNotSupportedException {
        PolicyData policyInquiryData = exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);
        return (PolicyData) policyInquiryData.clone();
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
     * This filter uses the policy name to inquiry PEP. PEP evaluates the PolicyData
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