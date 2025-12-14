package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;

/**
 * If client is not allowed to see a field, they are unable to query by that field.
 * This filter checks if there is a forbidden field queried. If there is, then returns 
 * a response appropriate to the HTTP method, creating a warning level log.
 * 
 * Response format depends on the HTTP method:
 * - GET operations: returns empty array [] (consistent with backend service behavior)
 * - DELETE operations: returns {"count": 0} (matches delete operation response format)
 * - PATCH operations: returns 204 No Content with empty body
 */
@Component
@Slf4j
public class PreventQueryByForbiddenFields extends AbstractPolicyAwareFilterFactory<PolicyEvaluatingFilterConfig, PreventQueryByForbiddenFields.PolicyResponse> {
    public PreventQueryByForbiddenFields(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        super(PolicyEvaluatingFilterConfig.class, PreventQueryByForbiddenFields.PolicyResponse.class, objectMapper);
    }

    @Override
    public GatewayFilter apply(PolicyEvaluatingFilterConfig config, PolicyResponse policyResult) {
        return (exchange, chain) -> {
            List<String> fieldsToCheck = policyResult.getFields();

            boolean shouldReturnEmptyResponse = fieldsToCheck.stream()
                .anyMatch(fieldName -> exchange.getRequest().getQueryParams().keySet().stream()
                    .anyMatch(param -> param.startsWith("filter[where][" + fieldName) || param.startsWith("where[" + fieldName)));

            if (shouldReturnEmptyResponse) {
                HttpMethod method = exchange.getRequest().getMethod();
                String routeId = getRouteId(exchange);
                log.warn("Client used a field name in it's query which it is not allowed to see! Returning response for {} operation.", method);

                // Determine response format based on HTTP method
                if (HttpMethod.DELETE.equals(method) || routeId.startsWith("count")) {
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap("{\"count\": 0}".getBytes())));
                } else if (HttpMethod.PATCH.equals(method)) {
                    exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                    return exchange.getResponse().setComplete();
                } else {
                    // GET and other operations return empty array
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap("[]".getBytes())));
                }
            }

            // If the field doesn't exist, continue with the request
            return chain.filter(exchange);
        };
    }

    /**
     * Extracts the route ID from the exchange
     */
    private String getRouteId(ServerWebExchange exchange) {
        Object routeIdAttr = exchange.getAttributes().get(ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR);
        return routeIdAttr != null ? routeIdAttr.toString() : "unknown";
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