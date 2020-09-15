package com.tarcinapp.entitypersistencegateway.filters;

import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
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

@Component
public class AuthFilterFactory extends AbstractGatewayFilterFactory<AuthFilterFactory.Config> {

	@Value("${headers.userId}")
    private String userIdHeaderName;
    
    public static class Config {
        // Put the configuration properties
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

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        return response.setComplete();
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            if (!request.getHeaders().containsKey("Authorization")) {
                return this.onError(exchange, "No Authorization header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().get("Authorization").get(0);

            if(!authHeader.startsWith("Bearer ")) {
                return this.onError(exchange, "Only token authorization is allowed", HttpStatus.UNAUTHORIZED);
            }

            String jwtToken = authHeader.replaceFirst("Bearer\\s", "");

            try {
                Claims claims = this.validateAuthorization(jwtToken);
                String subject = claims.getSubject();

                ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                    .header(userIdHeaderName, subject).build();

                return chain.filter(exchange
                    .mutate()
                    .request(modifiedRequest)
                    .build()
                );
            } catch(JwtException jwtException) {
                jwtException.printStackTrace();
                return this.onError(exchange, "Invalid Authorization header", HttpStatus.UNAUTHORIZED);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return this.onError(exchange, "Authorization validation could not be completed", HttpStatus.INTERNAL_SERVER_ERROR);
        };
    }

    private static Key loadPrivateKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] data = Base64.getDecoder().decode(("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAweyK9xaCVQoq+z84vUVXEjAeQvhAqjwJMtH5qvd7QOBlkwT/Rlg7TehVkMpzoda3FJKZE79BL5PAI9T975Hyky85DZ2sxFrbRHX251cs/O98h4d0Z9AEGR2F0nO4axaqoGqmN1qymRkaNCqDNrXeomkbjvgAN239I2TRUqrkhWSjR9//dfonKAhaL6ipv8lr5Pc/276btdaWhTHnMHYv+TqM+K0E2a64x/njDYqj/HhLltLN667R4HiYfKSF7D2VCck3ro9XatNVBWuzFT1es/OZ51zVHJwfQS57c9ayuvHcaHoguDlPxn52jISBEjo9IXpLmgOhsGaxwO4Qc9/WWwIDAQAB".getBytes()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        KeyFactory fact = KeyFactory.getInstance("RSA");
        return fact.generatePublic(spec);
    }
}