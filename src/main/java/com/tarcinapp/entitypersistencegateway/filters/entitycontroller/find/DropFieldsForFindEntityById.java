package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.security.Key;
import java.util.ArrayList;
import java.util.List;

import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

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

import reactor.core.publisher.Mono;

@Component
public class DropFieldsForFindEntityById extends AbstractGatewayFilterFactory<DropFieldsForFindEntityById.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    @Autowired(required = false)
    Key key;

    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private Logger logger = LogManager.getLogger(DropFieldsForFindEntityById.class);

    public DropFieldsForFindEntityById() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            logger.debug("DropFieldsForFindEntityById filter is started.");

            if (key == null) {
                logger.info("Authentication information not found. Exiting filter without any modification.");
                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain).onErrorResume(e -> {
                // An error occured while trying to limit fields.
                // it's safer to return 500 Internal Server Error
                logger.error(e);

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

                return response.setComplete();
            });
        };
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

            return this.dropFieldsFromResponse(hideFields, exchange, chain);
        });
    }

    private Mono<? extends Void> dropFieldsFromResponse(List<String> hideFields, ServerWebExchange exchange,
            GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();

            System.out.println("At least we reached here!" + hideFields.size());
        }));
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
