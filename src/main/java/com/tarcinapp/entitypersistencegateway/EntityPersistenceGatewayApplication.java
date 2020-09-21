package com.tarcinapp.entitypersistencegateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;

import reactor.core.publisher.Mono;

@SpringBootApplication
public class EntityPersistenceGatewayApplication {

	@Value("${app.requestHeaders.authenticationSubject}")
    private String authSubjectHeader;

	public static void main(String[] args) {
		SpringApplication.run(EntityPersistenceGatewayApplication.class, args);
	}

	@Bean
	KeyResolver userKeyResolver() {
		return (exchange) -> {
			String subject = exchange.getRequest()
				.getHeaders()
				.getFirst(authSubjectHeader);

			return Mono.just(subject);
		};
	}
}
