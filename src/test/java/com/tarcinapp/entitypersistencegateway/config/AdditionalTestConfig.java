package com.tarcinapp.entitypersistencegateway.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Additional test configuration for common beans and overrides.
 */
@TestConfiguration
public class AdditionalTestConfig {

    // Add any test-specific bean overrides here
    // Example:
    // @Bean
    // @Primary
    // public SomeService testSomeService() {
    //     return new MockSomeService();
    // }
}
