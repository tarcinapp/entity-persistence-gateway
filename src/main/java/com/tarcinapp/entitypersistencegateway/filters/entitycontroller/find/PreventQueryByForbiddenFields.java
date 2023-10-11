package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import reactor.core.publisher.Mono;

/**
 * If client is not allowed to see a field, they are unable to query by that field.
 * This field checks if there is a forbidden field queried. If there is, then returns emtpy 
 * array creating a warning level log.
 * 
 * We are returning empty array because this is consistent with backend service behavior. 
 * When a client queries backend with a field that does not exist, backend return emtpy array.
 */
@Component
public class PreventQueryByForbiddenFields extends AbstractPolicyAwareFilterFactory<PolicyEvaluatingFilterConfig, PreventQueryByForbiddenFields.PolicyResponse> {

    private Logger logger = LogManager.getLogger(PreventQueryByForbiddenFields.class);

    public PreventQueryByForbiddenFields() {
        super(PolicyEvaluatingFilterConfig.class, PreventQueryByForbiddenFields.PolicyResponse.class);
    }

    @Override
    public GatewayFilter apply(PolicyEvaluatingFilterConfig config, PolicyResponse policyResult) {
        return (exchange, chain) -> {
            List<String> fieldsToCheck = policyResult.getFields();

            boolean shouldReturnEmptyResponse = fieldsToCheck.stream()
                .anyMatch(fieldName -> exchange.getRequest().getQueryParams().keySet().stream()
                    .anyMatch(param -> param.startsWith("filter[where][" + fieldName)));

            if (shouldReturnEmptyResponse) {

                logger.warn("Client used a field name in it's query which it is not allowed to see! Returning an empty array.");

                // Modify the response to return an empty JSON array
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                exchange.getResponse().setStatusCode(HttpStatus.OK);
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap("[]".getBytes())));
            }

            // If the field doesn't exist, continue with the request
            return chain.filter(exchange);
        };
    }

    /**
     * This POJO is used to map PDP response of inquiry of forbidden fields.
     */
    static class PolicyResponse {
        @JsonProperty(value="which_fields_forbidden_for_finding")
        ArrayList<String> fields;

        public ArrayList<String> getFields() {
            return this.fields;
        }

        public void setFields(ArrayList<String> fields) {
            this.fields = fields;
        }
    }
}