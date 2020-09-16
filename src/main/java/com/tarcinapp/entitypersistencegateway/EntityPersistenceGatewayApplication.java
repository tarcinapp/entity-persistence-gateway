package com.tarcinapp.entitypersistencegateway;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EntityPersistenceGatewayApplication {

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
}
