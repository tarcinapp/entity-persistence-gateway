package com.tarcinapp.entitypersistencegateway.services;

import java.util.ArrayList;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for building and managing the GatewaySecurityContext.
 * Extracts user information from JWT claims and populates the security context.
 */
@Slf4j
@Service
public class SecurityContextBuilder {

    /**
     * Initializes a new security context in the exchange attributes
     */
    public void initializeSecurityContext(ServerWebExchange exchange) {
        exchange.getAttributes().put(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext());
    }

    /**
     * Builds the security context from JWT claims.
     * Extracts subject, roles, groups, and auth party from the claims.
     * 
     * @param claims JWT claims from authenticated token
     * @param exchange The server web exchange
     */
    public void buildFromClaims(Claims claims, ServerWebExchange exchange) {
        GatewaySecurityContext gc = getSecurityContext(exchange);

        if (gc == null) {
            log.warn("Security context not initialized. Initializing now.");
            initializeSecurityContext(exchange);
            gc = getSecurityContext(exchange);
        }

        log.debug("Building security context from claims: " + claims.getSubject());

        // Extract data from claims
        String subject = claims.getSubject();
        String authParty = claims.get("azp", String.class);

        @SuppressWarnings("unchecked")
        ArrayList<String> groups = Optional.ofNullable((ArrayList<String>) claims.get("groups", ArrayList.class))
                .orElse(new ArrayList<String>());

        @SuppressWarnings("unchecked")
        ArrayList<String> roles = Optional.ofNullable((ArrayList<String>) claims.get("roles"))
                .orElse(new ArrayList<String>());

        // Populate security context
        gc.setAuthSubject(subject);
        gc.setGroups(groups);
        gc.setRoles(roles);
        gc.setAuthParty(authParty);

        log.debug("Security context built successfully for user: " + subject);
    }

    /**
     * Retrieves the security context from exchange attributes
     */
    public GatewaySecurityContext getSecurityContext(ServerWebExchange exchange) {
        return exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);
    }
}
