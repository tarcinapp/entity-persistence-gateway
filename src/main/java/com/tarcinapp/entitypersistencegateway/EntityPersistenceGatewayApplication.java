package com.tarcinapp.entitypersistencegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EntityPersistenceGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(EntityPersistenceGatewayApplication.class, args);
	}

	@Bean
	public RouteLocator configurePersistenceAppRoutes(RouteLocatorBuilder builder) {
		return builder.routes()
			.route("proxy", r -> r.path("/**")
				.filters(f -> f.addRequestHeader("TarcinappUserID", "261b3278-631f-4655-8b87-b8a29a51213f"))
				.uri("http://entity-persistence-service/")
			)
			.build();
	}
}
