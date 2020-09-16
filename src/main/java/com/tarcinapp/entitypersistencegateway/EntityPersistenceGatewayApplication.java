package com.tarcinapp.entitypersistencegateway;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;


import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class EntityPersistenceGatewayApplication {
	
	@Value("${JWTS_PRIVATE_KEY}")
    private String privateKey;

	@Value( "${backendServiceEndpoint}" )
	private String backendServiceEndpoint;

	public static void main(String[] args) {
		SpringApplication.run(EntityPersistenceGatewayApplication.class, args);
	}

	@Bean
	public RouteLocator configurePersistenceAppRoutes(RouteLocatorBuilder builder) {
		return builder.routes()

			.route("proxy", r -> r.path("/**")
				.uri(backendServiceEndpoint)
			)
			

			/**
			 * Explorer is a design time utility for testing the backend services.
			 * It should not be exposed to the outside world.
			 */
			.route("explorer", r -> r.path("/explorer")
				.filters(f -> f
					.setStatus(404))
				.uri("no://op")
			)
			.build();
	}

	@Bean
	public GlobalFilter authenticate() {

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
                this.validateAuthorization(jwtToken);

                return chain.filter(exchange);
            } catch(JwtException jwtException) {
                jwtException.printStackTrace();
                return this.onError(exchange, "Invalid Authorization header", HttpStatus.UNAUTHORIZED);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return this.onError(exchange, "Authorization validation could not be completed", HttpStatus.INTERNAL_SERVER_ERROR);
        };
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
        byte[] data = Base64.getDecoder().decode((privateKey.getBytes()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        KeyFactory fact = KeyFactory.getInstance("RSA");
        return fact.generatePublic(spec);
	}
	
	
    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        return response.setComplete();
    }
}
