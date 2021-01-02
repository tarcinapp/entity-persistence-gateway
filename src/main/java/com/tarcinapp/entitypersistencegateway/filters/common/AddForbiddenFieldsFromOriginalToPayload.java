package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.authorization.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * This filter is meant to be used in replaceById operations. replaceById
 * operations, as name suggests, replaces the target object with the object
 * given in payload.
 * 
 * However, there are some cases where user is not authorized to see certain
 * fields (e.g. validFromDateTime, validUntilDateTime, visibility). We call
 * those fields as forbiddenFields.
 * 
 * In such cases, users cannot change the value of those fields. Their update
 * attempts will be rejected with '401 - Unauthorized' error code. Note that, if
 * a user is authorized to update a forbidden field (with required roles), then
 * he will be able to change the value for that field according to the policy.
 * 
 * To make replaceById operations available for those users, this filter adds
 * values for the forbidden fields from the original record.
 * 
 * Note: The payload that the user provides must still be subjected to the
 * authorization logic. Please apply this filter after the authorization filter.
 */
@Component
public class AddForbiddenFieldsFromOriginalToPayload
        extends AbstractGatewayFilterFactory<AddForbiddenFieldsFromOriginalToPayload.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    private Logger logger = LogManager.getLogger(AddForbiddenFieldsFromOriginalToPayload.class);

    public AddForbiddenFieldsFromOriginalToPayload() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> this.filter(config, exchange, chain);
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String token = this.extractToken(request);

        HttpMethod httpMethod = request.getMethod();

        // instantiate policy data
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(config.getPolicyName());
        policyData.setHttpMethod(httpMethod);
        policyData.setEncodedJwt(token);
        policyData.setQueryParams(request.getQueryParams());
        policyData.setRequestPath(request.getPath());

        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                    
                    try {
                        // parse the inbound string as JSON
                        ObjectMapper objectMapper = new ObjectMapper();
                        Map<String, Object> payloadJSON;
                        payloadJSON = objectMapper.readValue(inboundJsonRequestStr,
                                new TypeReference<Map<String, Object>>() {
                                });

                        // todo: ask to policy with the original record for forbidden fields.

                        return Mono.just(inboundJsonRequestStr);
                    } catch (JsonProcessingException e) {
                        logger.error(e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    private String extractToken(ServerHttpRequest request) {
        if (!request.getHeaders().containsKey("Authorization"))
            return null;

        String authHeader = request.getHeaders().get("Authorization").get(0);

        if (!authHeader.startsWith("Bearer "))
            return null;

        return authHeader.replaceFirst("Bearer\\s", "");
    }

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
