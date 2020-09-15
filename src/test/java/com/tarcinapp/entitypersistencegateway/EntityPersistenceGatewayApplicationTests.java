package com.tarcinapp.entitypersistencegateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"JWTS_PRIVATE_KEY = private-key"})
class EntityPersistenceGatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
