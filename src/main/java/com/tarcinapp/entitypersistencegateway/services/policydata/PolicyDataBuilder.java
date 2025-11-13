package com.tarcinapp.entitypersistencegateway.services.policydata;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import reactor.core.publisher.Mono;

/**
 * Interface for building policy data for authorization.
 * Different implementations can provide route-specific or resource-specific
 * policy data preparation logic.
 */
public interface PolicyDataBuilder {

    /**
     * Builds and enriches the policy data for the current request.
     * This method should populate the PolicyData object with all necessary
     * information for policy evaluation.
     * 
     * @param policyData The policy data object to enrich
     * @param exchange The server web exchange
     * @param chain The filter chain
     * @return Mono that continues the filter chain after policy data is prepared
     */
    Mono<Void> buildPolicyData(PolicyData policyData, ServerWebExchange exchange, GatewayFilterChain chain);
}
