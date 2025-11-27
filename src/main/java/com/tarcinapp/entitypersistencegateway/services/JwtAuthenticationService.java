package com.tarcinapp.entitypersistencegateway.services;

import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.helpers.TokenParserRegistry;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import reactor.core.publisher.Mono;

@Service
public class JwtAuthenticationService {

    private final TokenParserRegistry tokenParserRegistry;
    private static final Logger logger = LogManager.getLogger(JwtAuthenticationService.class);

    // ObjectMapper for simple decoding
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public JwtAuthenticationService(TokenParserRegistry tokenParserRegistry) {
        this.tokenParserRegistry = tokenParserRegistry;
    }

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

    private Mono<Claims> validateToken(String jwt, ServerWebExchange exchange) {
        try {

            // before validating the token, we extract the ISSUER from its payload.
            String issuer = extractIssuerWithoutValidation(jwt);

            if (issuer == null) {
                throw new JwtException("Token does not contain issuer claim");
            }

            // Identify the correct parser based on issuer
            var parser = tokenParserRegistry.getParser(issuer);

            if (parser == null) {
                throw new JwtException("Unknown issuer: " + issuer);
            }

            // Validate the signature and claims
            Claims claims = parser.parseClaimsJws(jwt).getBody();

            logger.debug("JWT token validated for issuer: {}", issuer);

            GatewaySecurityContext securityContext = exchange.getAttribute("GatewaySecurityContext");
            if (securityContext != null) {
                securityContext.setEncodedJwt(jwt);
            }

            return Mono.just(claims);

        } catch (Exception e) {
            logger.error("JWT validation failed: " + e.getMessage());
            return Mono.error(new JwtAuthenticationException("Invalid token", e));
        }
    }

    // Reads the issuer from the token by splitting and base64 decoding (does not verify signature)
    private String extractIssuerWithoutValidation(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2)
                return null;

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            var node = objectMapper.readTree(payload);

            if (node.has("iss")) {
                return node.get("iss").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if any authentication provider is configured.
     * Used by AuthenticationFilter to skip logic if no providers are set.
     */
    public boolean isConfigured() {
        // Registry null ise (henüz inject olmadıysa) veya içi boşsa false döner
        return tokenParserRegistry != null && tokenParserRegistry.isConfigured();
    }

    public static class JwtAuthenticationException extends Exception {
        public JwtAuthenticationException(String message) {
            super(message);
        }

        public JwtAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}