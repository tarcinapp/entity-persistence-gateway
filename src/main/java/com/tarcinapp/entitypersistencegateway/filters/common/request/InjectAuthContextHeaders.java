package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Serializes the in-process {@link GatewaySecurityContext} into downstream HTTP headers
 * so that EPBO (and any other downstream service) can read caller identity without
 * needing access to the Gateway's in-process exchange attributes.
 *
 * <p>Headers injected:
 * <ul>
 *   <li>{@code X-User-Id}     — JWT {@code sub} claim; absent when unauthenticated</li>
 *   <li>{@code X-User-Roles}  — comma-separated JWT {@code roles} claim; absent when empty or unauthenticated</li>
 *   <li>{@code X-User-Groups} — comma-separated JWT {@code groups} claim; absent when empty or unauthenticated</li>
 * </ul>
 *
 * <p>All three headers are omitted (not set to an empty string) when the corresponding
 * value is null or empty. This is consistent with the null-safe pattern used throughout
 * the rest of the Gateway's managed-field injection.
 *
 * <p>This filter must be placed <em>after</em> {@code AuthenticateRequest} in every route
 * filter chain so that {@link GatewaySecurityContext} is already populated when it runs.
 * It is registered as a per-route filter in {@code application-routes.yml}.
 */
@Component
@Slf4j
public class InjectAuthContextHeaders extends AbstractGatewayFilterFactory<InjectAuthContextHeaders.Config> {

    public static final String HEADER_USER_ID     = "X-User-Id";
    public static final String HEADER_USER_ROLES  = "X-User-Roles";
    public static final String HEADER_USER_GROUPS = "X-User-Groups";

    public InjectAuthContextHeaders() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            GatewaySecurityContext gc = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

            if (gc == null || gc.getAuthSubject() == null) {
                log.debug("InjectAuthContextHeaders: no authenticated subject — skipping header injection");
                return chain.filter(exchange);
            }

            ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

            requestBuilder.header(HEADER_USER_ID, gc.getAuthSubject());
            log.debug("InjectAuthContextHeaders: injected {}={}", HEADER_USER_ID, gc.getAuthSubject());

            ArrayList<String> roles = gc.getRoles();
            if (roles != null && !roles.isEmpty()) {
                requestBuilder.header(HEADER_USER_ROLES, String.join(",", roles));
            }

            ArrayList<String> groups = gc.getGroups();
            if (groups != null && !groups.isEmpty()) {
                requestBuilder.header(HEADER_USER_GROUPS, String.join(",", groups));
            }

            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
        };
    }

    public static class Config {
    }
}
