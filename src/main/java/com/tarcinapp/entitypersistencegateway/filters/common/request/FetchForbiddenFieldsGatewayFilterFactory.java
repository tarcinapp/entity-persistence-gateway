package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * This filter runs AFTER Authentication and PolicyData building.
 * It uses the fully prepared PolicyData to ask OPA for the "Forbidden Fields
 * Library".
 * The result is stored in Exchange Attributes for later use (Query Blocking,
 * Response Filtering).
 */
@Component
@Slf4j
public class FetchForbiddenFieldsGatewayFilterFactory
        extends AbstractGatewayFilterFactory<FetchForbiddenFieldsGatewayFilterFactory.Config> {

    public static final String GATEWAY_CONTEXT_FORBIDDEN_FIELDS = "GatewayContextForbiddenFields";
    private static final String CENTRAL_POLICY_NAME = "/policies/gateway/forbidden_fields/policy/result";

    private final IAuthorizationClient authorizationClient;

    public FetchForbiddenFieldsGatewayFilterFactory(IAuthorizationClient authorizationClient) {
        super(Config.class);
        this.authorizationClient = authorizationClient;
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            // 1. Retrieve the fully prepared PolicyData (Put there by AuthenticateRequest -> Builder)
            PolicyData policyData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);

            if (policyData == null) {
                log.warn("PolicyData not found in attributes. Skipping Forbidden Fields fetch.");
                return chain.filter(exchange);
            }

            // 2. Set the specific policy name for this check
            // We clone it to avoid mutating the original data which might be used by other
            // filters later
            PolicyData forbiddenCheckData = policyData.clone();
            forbiddenCheckData.setPolicyName(CENTRAL_POLICY_NAME);

            log.debug("Fetching forbidden fields library from OPA/Redis.");

            // 3. Execute Policy
            return authorizationClient.executePolicy(forbiddenCheckData, ForbiddenFieldsLibrary.class)
                    .flatMap(library -> {
                        // 4. Store result in Exchange Attribute
                        if (library != null && library.getRules() != null) {
                            exchange.getAttributes().put(GATEWAY_CONTEXT_FORBIDDEN_FIELDS, library);
                            log.debug("Forbidden fields fetched. Rules for types: {}", library.getRules().keySet());
                        } else {
                            log.debug("No forbidden fields returned from OPA.");
                        }
                        return chain.filter(exchange);
                    })
                    // 5. Fallback: Fail Closed
                    // If OPA fails/timeouts, we MUST block the request for security.
                    // We return a 500 Internal Server Error to indicate a system failure preventing the security check.
                    .onErrorResume(e -> {
                        log.error("Failed to fetch forbidden fields due to error: {}. Blocking request (Fail Closed).", e.getMessage());
                        return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to retrieve security policies. Please try again later."));
                    });
        };
    }

    public static class Config {
        // No config needed yet
    }
}