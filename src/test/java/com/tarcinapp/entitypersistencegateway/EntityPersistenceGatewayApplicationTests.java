package com.tarcinapp.entitypersistencegateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
	"app.backend.host = 185.169.55.181"
})
class EntityPersistenceGatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
