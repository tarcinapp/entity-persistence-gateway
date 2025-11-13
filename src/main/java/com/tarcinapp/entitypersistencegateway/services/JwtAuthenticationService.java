package com.tarcinapp.entitypersistencegateway.services;

import java.security.Key;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * Service responsible for JWT authentication operations.
 * Validates Bearer tokens and extracts JWT claims.
 */
@Service
public class JwtAuthenticationService {

    @Autowired(required = false)
    private Key key;

    @Value("${app.auth.issuer:#{null}}")
    private String tokenIssuer;

    @Value("${app.auth.clockSkewSeconds:60}")
    private long clockSkewSeconds;

    private static final Logger logger = LogManager.getLogger(JwtAuthenticationService.class);

    /**
     * Check if JWT authentication is configured
     */
    public boolean isConfigured() {
        return this.key != null;
    }

    /**
     * Authenticates a request by validating the JWT token.
     * Extracts the Bearer token from Authorization header, validates it,
     * and returns the claims if valid.
     * 
     * @param exchange The server web exchange
     * @return Mono containing JWT claims if authentication succeeds
     */
    public Mono<Claims> authenticate(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        if (!request.getHeaders().containsKey("Authorization")) {
            return Mono.error(new JwtAuthenticationException("No Authorization header"));
        }

        String authHeader = request.getHeaders().get("Authorization").get(0);

        if (!authHeader.startsWith("Bearer ")) {
            return Mono.error(new JwtAuthenticationException("Only token authorization is allowed"));
        }

        String jwt = authHeader.replaceFirst("Bearer\\s", "");

        return validateToken(jwt, exchange);
    }

    /**
     * Validates the JWT token and returns claims
     */
    private Mono<Claims> validateToken(String jwt, ServerWebExchange exchange) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(this.key)
                    .setAllowedClockSkewSeconds(this.clockSkewSeconds)
                    .build()
                    .parseClaimsJws(jwt)
                    .getBody();

            if (!claims.getIssuer().equals(this.tokenIssuer)) {
                throw new JwtException("Invalid issuer");
            }

            logger.debug("JWT token is validated.");

            // Store JWT in security context for downstream use
            GatewaySecurityContext securityContext = exchange.getAttribute("GatewaySecurityContext");
            if (securityContext != null) {
                securityContext.setEncodedJwt(jwt);
            }

            return Mono.just(claims);
        } catch (JwtException e) {
            logger.error("JWT validation failed: " + e.getMessage(), e);
            return Mono.error(new JwtAuthenticationException("Invalid Authorization header", e));
        }
    }

    /**
     * Custom exception for JWT authentication failures
     */
    public static class JwtAuthenticationException extends Exception {
        public JwtAuthenticationException(String message) {
            super(message);
        }

        public JwtAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
