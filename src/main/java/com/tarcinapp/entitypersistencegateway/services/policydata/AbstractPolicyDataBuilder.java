package com.tarcinapp.entitypersistencegateway.services.policydata;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

/**
 * Abstract base class for PolicyDataBuilder implementations.
 * Provides common functionality for populating basic policy data fields
 * including HTTP method, request path, query params, JWT, and operation type.
 */
public abstract class AbstractPolicyDataBuilder implements PolicyDataBuilder {

    /**
     * Populates common policy data fields from the request.
     * This method should be called at the beginning of buildPolicyData implementations.
     * 
     * @param policyData The policy data object to populate
     * @param exchange The server web exchange
     */
    protected void populateCommonData(PolicyData policyData, ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        
        // 1. Basic Fields
        policyData.setHttpMethod(request.getMethod());
        policyData.setRequestPath(request.getPath());
        policyData.setQueryParams(request.getQueryParams());

        // 2. Security Context
        GatewaySecurityContext securityContext = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
        if (securityContext != null) {
            policyData.setEncodedJwt(securityContext.getEncodedJwt());
        }

        // 3. Operation Logic
        String method = request.getMethod() != null ? request.getMethod().name() : "GET";
        String operation = "find";
        
        switch (method) {
            case "POST":
                operation = "create";
                break;
            case "PUT":
            case "PATCH":
                operation = "update";
                break;
            case "DELETE":
            case "GET":
                operation = "find";
                break;
        }
        
        policyData.setOperation(operation);
    }
}
