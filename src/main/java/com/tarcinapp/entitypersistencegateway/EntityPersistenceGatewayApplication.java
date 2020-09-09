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

			/**
			 * All paths behind this gateway are subjected to the global request limits.
			 */
			.route("proxy", r -> r.path("/**")
				.filters(f -> f
					.addRequestHeader("TarcinappUserID", "261b3278-631f-4655-8b87-b8a29a51213f"))
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
