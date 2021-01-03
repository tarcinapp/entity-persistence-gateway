package com.tarcinapp.entitypersistencegateway.filters.global;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;

import com.tarcinapp.entitypersistencegateway.GatewayContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * This filter authenticates the requests with Bearer token. If request is
 * authenticated GatewayContext is initialized and a policy data is prepared.
 * 
 * Preparing policy data at the authentication point lets subsequent filters to
 * use existing policy data without dealing with re-preparing it again.
 */
@Component
public class AuthenticateRequest extends AbstractGatewayFilterFactory<AuthenticateRequest.Config> {

    @Autowired
    private Key key;

    @Value("${app.requestHeaders.authenticationSubject}")
    private String authSubjectHeader;

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    Logger logger = LogManager.getLogger(AuthenticateRequest.class);

    public AuthenticateRequest() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        
        // implement in seperate method in order to reduce nesting
        return (exchange, chain) -> this.filter(exchange, chain);
    }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        if (this.key == null) {
            logger.warn(
                    "RS256 key is not configured. Requests won't be authenticated! Please configure a valid RS256 public key to enable authentication.");

            // instantiate empty gateway context
            GatewayContext gc = new GatewayContext();

            exchange.getAttributes().put(GATEWAY_CONTEXT_ATTR, gc);

            return chain.filter(exchange);
        }

        logger.debug("RS256 public key is configured. Request will be authenticated.");

        ServerHttpRequest request = exchange.getRequest();

        if (!request.getHeaders().containsKey("Authorization")) {
            return this.onError(exchange, "No Authorization header", HttpStatus.UNAUTHORIZED);
        }

        String authHeader = request.getHeaders().get("Authorization").get(0);

        if (!authHeader.startsWith("Bearer ")) {
            return this.onError(exchange, "Only token authorization is allowed", HttpStatus.UNAUTHORIZED);
        }

        String jwtToken = authHeader.replaceFirst("Bearer\\s", "");

        try {
            Claims claims = this.validateAuthorization(jwtToken);
            String subject = claims.getSubject();
            String authParth = claims.get("azp", String.class);
            ArrayList<String> groups = (ArrayList<String>) claims.get("groups", ArrayList.class);
            ArrayList<String> roles = getRolesFromClaims(claims);

            logger.debug("JWT token is validated.");
            logger.debug("Claims: " + claims);

            // add auth subject to the request header
            exchange.getRequest().mutate().header(authSubjectHeader, subject);

            /**
             * Add security fields to GatewayContext to share with other filters
             * (authorization, rate limiting). This approach also prevents need for parsing
             * JWT in other filters and saves time and CPU.
             */
            GatewayContext gc = new GatewayContext();
            gc.setAuthSubject(subject);
            gc.setGroups(groups);
            gc.setRoles(roles);
            gc.setAuthParty(authParth);
            gc.setEncodedJwt(jwtToken);

            exchange.getAttributes().put(GATEWAY_CONTEXT_ATTR, gc);

            return chain.filter(exchange);
        } catch (JwtException jwtException) {
            logger.error(jwtException);

            return this.onError(exchange, "Invalid Authorization header", HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            logger.error(e);
        }

        return this.onError(exchange, "Authorization validation could not be completed",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private Claims validateAuthorization(String jwtToken)
            throws NoSuchAlgorithmException, InvalidKeySpecException, JwtException {

        Jws<Claims> claims = Jwts.parserBuilder().setSigningKey(this.key).build().parseClaimsJws(jwtToken);

        return claims.getBody();
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        return response.setComplete();
    }

    private ArrayList<String> getRolesFromClaims(Claims claims) {
        return (ArrayList<String>) claims.get("roles");
    }

    public static class Config {

    }
}
