package com.tarcinapp.entitypersistencegateway.filters;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

@Component
public class AuthFilterFactory extends AbstractGatewayFilterFactory<AuthFilterFactory.Config> {

    public static class Config {
        // Put the configuration properties
    }

    private boolean isAuthorizationValid(String jwtToken) {
        boolean isValid = true;

        System.out.println("token!!!");
        System.out.println(jwtToken);

        Object claims = Jwts.parser()
            .setSigningKey("LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQpNSUlDb1RDQ0FZa0NCZ0YwRzdFb1VEQU5CZ2txaGtpRzl3MEJBUXNGQURBVU1SSXdFQVlEVlFRRERBbDBZWEpqYVc1aGNIQXdIaGNOTWpBd09ESXpNVFF4T0RRNVdoY05NekF3T0RJek1UUXlNREk1V2pBVU1SSXdFQVlEVlFRRERBbDBZWEpqYVc1aGNIQXdnZ0VpTUEwR0NTcUdTSWIzRFFFQkFRVUFBNElCRHdBd2dnRUtBb0lCQVFEQjdJcjNGb0pWQ2lyN1B6aTlSVmNTTUI1QytFQ3FQQWt5MGZtcTkzdEE0R1dUQlA5R1dEdE42RldReW5PaDFyY1VrcGtUdjBFdms4QWoxUDN2a2ZLVEx6a05uYXpFV3R0RWRmYm5WeXo4NzN5SGgzUm4wQVFaSFlYU2M3aHJGcXFnYXFZM1dyS1pHUm8wS29NMnRkNmlhUnVPK0FBM2JmMGpaTkZTcXVTRlpLTkgzLzkxK2ljb0NGb3ZxS20veVd2azl6L2J2cHUxMXBhRk1lY3dkaS81T296NHJRVFpycmpIK2VNTmlxUDhlRXVXMHMzcnJ0SGdlSmg4cElYc1BaVUp5VGV1ajFkcTAxVUZhN01WUFY2ejg1bm5YTlVjbkI5QkxudHoxcks2OGR4b2VpQzRPVS9HZm5hTWhJRVNPajBoZWt1YUE2R3dackhBN2hCejM5WmJBZ01CQUFFd0RRWUpLb1pJaHZjTkFRRUxCUUFEZ2dFQkFMa0NWVjRqYVVaWEYxUDQxOG9TTzNTV1MvMk15VUUxOWFNTVd2anZ5RHlvQVlXM2dUMlpKblNJcUpxb1NzekZEdUpmUVNKTU5rRXEySHFyd2FVMnJFZlh3bmtleG40T2xSL2txRGh6M2lTTXFDa2R2cU44bjlqQ1lZcFB3ejVmeEtQQUk4T2p6bTRLN3hGV0tIWGRDeGdGdDJKMmFKSWdXaDJQa1g5cTlUNXV3UE5hVGRzQmQxQWFCekRBekF3Sm1zYUVOaFNnYWpHcmVSUFEvOUd4R0VTRUtaU3VpRHNjcDdZeDQ2Ui9USkt3QkFST0xPTjN6VTZ1WDlDT0N5TDlKU1pZcFlvbGJMa3YwenhmRjdqaHFSNWlFMGk2RzB2Q1ZvOXU5WU9hMkpFVXlnZVV3MktRcUlPZ2FlcVpWQmFhNnY0L0xDS21QQW9rQTNPQWFuK2srL2s9Ci0tLS0tRU5EIFJTQSBQUklWQVRFIEtFWS0tLS0t")
            .parse("eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICI3Rjg3S1NhenhHRjJNNGt2U3NXN25UWHI0RUdFWkVGNXhtU1dqRExFQ2ZvIn0.eyJleHAiOjE1OTk2NTE1NzIsImlhdCI6MTU5OTY1MDk3MiwianRpIjoiNGIwMTgxMGItMTBmYi00M2ZkLWI2NzktNTRlZDRiOGEyYjk1IiwiaXNzIjoiaHR0cHM6Ly90YXJjaW5hcHAtdGVzdC5idWx1dGFydGkuY29tL2F1dGgvcmVhbG1zL3RhcmNpbmFwcCIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiIyNjFiMzI3OC02MzFmLTQ2NTUtOGI4Ny1iOGEyOWE1MTIxM2YiLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJwb3N0bWFuIiwic2Vzc2lvbl9zdGF0ZSI6IjBlMzg2YTMxLWY2YTItNDk2Ny1iZTE1LWM2ZDNmODljZWYxNCIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiIl0sInJlYWxtX2FjY2VzcyI6eyJyb2xlcyI6WyJvZmZsaW5lX2FjY2VzcyIsInVtYV9hdXRob3JpemF0aW9uIl19LCJyZXNvdXJjZV9hY2Nlc3MiOnsiYWNjb3VudCI6eyJyb2xlcyI6WyJtYW5hZ2UtYWNjb3VudCIsIm1hbmFnZS1hY2NvdW50LWxpbmtzIiwidmlldy1wcm9maWxlIl19fSwic2NvcGUiOiJvcGVuaWQgcHJvZmlsZSBlbWFpbCIsImVtYWlsX3ZlcmlmaWVkIjp0cnVlLCJuYW1lIjoiS2FkaXIgS8O8csWfYXQgVG9rcHVuYXIiLCJncm91cHMiOlsiL2l6bWlybGlsZXIiXSwicHJlZmVycmVkX3VzZXJuYW1lIjoia3Vyc2F0dG9rcGluYXIiLCJnaXZlbl9uYW1lIjoiS2FkaXIgS8O8csWfYXQiLCJmYW1pbHlfbmFtZSI6IlRva3B1bmFyIiwiZW1haWwiOiJrdXJzYXR0b2twaW5hckBnbWFpbC5jb20ifQ.ceo_vwLp84qQ3IIwzHqwnchSpxxZqO7ixKHbSGDZwN_XZGuAqHCEzbde7CojeY5FoNjVG-TzTdrx1i6c8z10K6Vf42v3Mt6THUYBh88gVHA34TJj5DrWys--HPUglVKQZict3dsrMqzj-MKrpoAPkMY0NtNu9tPmYEVcmBaPDOXAeC2gLpMRp2vwMKSppFkdWtnDHv7kIlUs7PyLDRyAdqoZ0L96rGjAwbrwZEe15lq9O941jP4ZQJ6DzZLWzGOqkrDBvmWoGyyxfsyz9U1MdmRwdJb4vqWCBUjSmf5SrOEf5iVUi8Z59hoa7TSk_dA06k-NjnHsOmBqaV0LkxXvfg")
            .getBody();

        // Logic for checking the value
        System.out.println(claims.toString());

        return isValid;
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus)  {
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
            };

            String jwtToken = request.getHeaders().get("Authorization").get(0);

            if (!this.isAuthorizationValid(jwtToken)) {
                return this.onError(exchange, "Invalid Authorization header", HttpStatus.UNAUTHORIZED);
            }

            ServerHttpRequest modifiedRequest = exchange.getRequest().mutate().
                    header("TarcinappUserId", "user-id-from-filter").
                    build();

            return chain.filter(exchange.mutate().request(modifiedRequest).build());
        };
    }
}
