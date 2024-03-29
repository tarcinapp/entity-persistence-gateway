package com.tarcinapp.entitypersistencegateway.filters.base;

import java.security.Key;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * If a filter's logic requires a policy result, then it must extend this class.
 * 
 * This class evaluates the policy and passes the result to the implementing
 * filter logic. T is used for configuration and must extend
 * PolicyEvaluatingFilterConfigBase as it provides a standardized way to config
 * these filters. C is used for policy result
 * 
 * If authentication key is not configured, filter is not get executed but gives
 * it's order to the next filter without doing anything
 */
public abstract class AbstractPolicyAwareFilterFactory<C extends PolicyEvaluatingFilterConfig, PR>
        extends AbstractGatewayFilterFactory<C> {

    @Autowired(required = false)
    Key key;

    @Autowired
    IAuthorizationClient authorizationClient;

    private Logger logger = LogManager.getLogger(AbstractPolicyAwareFilterFactory.class);

    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private Class<PR> policyResultClass;

    public AbstractPolicyAwareFilterFactory(Class<C> configClass, Class<PR> policyResultClass) {
        super(configClass);

        this.policyResultClass = policyResultClass;
    }

    public abstract GatewayFilter apply(C config, PR policyResult);

    @Override
    public GatewayFilter apply(C config) {

        return (exchange, chain) -> {

            if (key == null) {
                logger.warn("Policy evaluation is skipped as security key is not configured.");
                return chain.filter(exchange);
            }

            return this.filter(config, exchange, chain).onErrorResume(e -> {
                logger.error("An error occured while evaluating the policy.", e);

                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

                return response.setComplete();
            });
        };
    }

    /**
     * This method gets executed when a security key is configured. This is
     * controller by the overrided apply method.
     * 
     * This is the first method where the logic begins.
     */
    private Mono<Void> filter(C config, ServerWebExchange exchange, GatewayFilterChain chain) {
        logger.info("Policy inquiry is started for policy name: " + config.getPolicyName());

        PolicyData policyInquiryData = this.getPolicyInquriyData(exchange);
        policyInquiryData.setPolicyName(config.getPolicyName());
        
        return this.executePolicy(policyInquiryData).flatMap(pr -> {

            logger.debug("Policy evaluation is completed.");

            if (logger.getLevel() == Level.DEBUG)
                logger.trace("Policy response is: ", this.serializeObjectAsJsonForLogging(pr));

            return this.apply(config, pr).filter(exchange, chain);
        });
    }

    protected Mono<PR> executePolicy(PolicyData policyInquiryData) {

        if (logger.getLevel() == Level.DEBUG) {
            logger.trace("Policy inquiry data is: ", this.serializeObjectAsJsonForLogging(policyInquiryData));
        }

        return this.authorizationClient.executePolicy(policyInquiryData, policyResultClass);
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * 
     * @param exchange
     * @return     
     */
    protected PolicyData getPolicyInquriyData(ServerWebExchange exchange) {
        PolicyData policyInquiryDataClone = null;

        try {
            PolicyData policyInquiryData = exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);

            if (policyInquiryData != null) {
                policyInquiryDataClone = (PolicyData) policyInquiryData.clone();
            }
        } catch (CloneNotSupportedException e) {
            logger.debug("Cloning policy inquiry data is failed.", e);
        }

        return policyInquiryDataClone;
    }

    private String serializeObjectAsJsonForLogging(Object o) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "Unable to serialize object to JSON.";
        }
    }
}
