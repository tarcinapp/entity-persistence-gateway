package com.tarcinapp.entitypersistencegateway.filters;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;

import com.tarcinapp.entitypersistencegateway.GatewayContext;

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

@Component
public class GlobalAuthenticationFilter implements GlobalFilter, Ordered   {

    @Value("${app.auth.rs256PublicKey:#{null}}")
    private String rs256PublicKey;

    @Value("${app.requestHeaders.authenticationSubject}")
    private String authSubjectHeader;

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    Logger logger = LogManager.getLogger(GlobalAuthenticationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        if(rs256PublicKey==null || rs256PublicKey.equals("false")) {
            logger.warn("RS256 key is not configured. Requests won't be authenticated! Please configure RS256 public key to enable authentication.");

            // instantiate empty gateway context
            GatewayContext gc = new GatewayContext();

            exchange.getAttributes()
                .put(GATEWAY_CONTEXT_ATTR, gc);

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
            exchange.getRequest()
                .mutate()
                .header(authSubjectHeader, subject);
            
            /**
             * Add security fields to GatewayContext to share with other filters (authorization, rate limiting).
             * This approach also prevents need for parsing JWT in other filters and saves time and CPU.
             * */
            GatewayContext gc = new GatewayContext();
                gc.setAuthSubject(subject);
                gc.setGroups(groups);
                gc.setRoles(roles);
                gc.setAuthParty(authParth);

            exchange.getAttributes()
                .put(GATEWAY_CONTEXT_ATTR, gc);

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

    private Claims validateAuthorization(String jwtToken) throws NoSuchAlgorithmException, InvalidKeySpecException, JwtException {
        Key privateKey = loadPrivateKey();

        Jws<Claims> claims = Jwts
            .parserBuilder()
            .setSigningKey(privateKey)
            .build()
            .parseClaimsJws(jwtToken);

        return claims.getBody();        
	}
	
	private Key loadPrivateKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] data = Base64.getDecoder().decode((rs256PublicKey.getBytes()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        KeyFactory fact = KeyFactory.getInstance("RSA");
        return fact.generatePublic(spec);
	}
	
    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        return response.setComplete();
    }

    private ArrayList<String> getRolesFromClaims(Claims claims) {
        LinkedHashMap realm_access = (LinkedHashMap)claims.get("realm_access");

        if(resourceAccess==null) return null;

        return (ArrayList<String>) resourceAccess.get("roles");
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
