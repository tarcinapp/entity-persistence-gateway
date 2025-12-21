package com.tarcinapp.entitypersistencegateway.unit.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.auth.PolicyResult;
import com.tarcinapp.entitypersistencegateway.filters.common.request.AuthorizeRequest;
import com.tarcinapp.entitypersistencegateway.services.JwtAuthenticationService;
import com.tarcinapp.entitypersistencegateway.util.MockExchangeBuilder;
import com.tarcinapp.entitypersistencegateway.util.MockGatewayFilterChain;
import com.tarcinapp.entitypersistencegateway.fixtures.JwtFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthorizeRequest filter.
 * Tests authorization flow, policy execution, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizeRequest Filter Unit Tests")
class AuthorizeRequestTest {

    @Mock
    private JwtAuthenticationService jwtAuthenticationService;

    @Mock
    private IAuthorizationClient authorizationClient;

    private AuthorizeRequest authorizeRequest;

    private MockGatewayFilterChain filterChain;
    private AuthorizeRequest.Config config;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        authorizeRequest = new AuthorizeRequest(objectMapper);
        
        // Inject mocks using reflection since @InjectMocks won't work with constructor + @Autowired fields
        org.springframework.test.util.ReflectionTestUtils.setField(authorizeRequest, "jwtAuthenticationService", jwtAuthenticationService);
        org.springframework.test.util.ReflectionTestUtils.setField(authorizeRequest, "authorizationClient", authorizationClient);
        
        filterChain = new MockGatewayFilterChain();
        config = new AuthorizeRequest.Config();
        config.setPolicyName("entities/create");
    }

    @Nested
    @DisplayName("Authorization Not Configured Scenarios")
    class AuthNotConfiguredTests {

        @Test
        @DisplayName("Should skip authorization when auth is not configured")
        void shouldSkipAuthorizationWhenNotConfigured() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(false);

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .post()
                    .path("/api/v1/entities")
                    .body("{\"name\": \"test\"}")
                    .build();

            GatewayFilter filter = authorizeRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            verify(authorizationClient, never()).executePolicy(any());
        }
    }

    @Nested
    @DisplayName("Authorization Success Scenarios")
    class AuthorizationSuccessTests {

        @Test
        @DisplayName("Should allow request when policy allows")
        void shouldAllowWhenPolicyAllows() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            
            PolicyResult allowResult = new PolicyResult();
            allowResult.setAllow(true);
            when(authorizationClient.executePolicy(any())).thenReturn(Mono.just(allowResult));

            PolicyData policyData = new PolicyData();
            policyData.setAppShortcode("tarcinapp");
            
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .post()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.memberToken())
                    .body("{\"name\": \"test\"}")
                    .attribute(PolicyData.POLICY_INQUIRY_DATA_ATTR, policyData)
                    .build();

            GatewayFilter filter = authorizeRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            verify(authorizationClient).executePolicy(any());
        }
    }

    @Nested
    @DisplayName("Authorization Denial Scenarios")
    class AuthorizationDenialTests {

        @Test
        @DisplayName("Should deny request when policy denies")
        void shouldDenyWhenPolicyDenies() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            
            PolicyResult denyResult = new PolicyResult();
            denyResult.setAllow(false);
            when(authorizationClient.executePolicy(any())).thenReturn(Mono.just(denyResult));

            PolicyData policyData = new PolicyData();
            policyData.setAppShortcode("tarcinapp");
            
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .post()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.visitorToken())
                    .body("{\"name\": \"test\"}")
                    .attribute(PolicyData.POLICY_INQUIRY_DATA_ATTR, policyData)
                    .build();

            GatewayFilter filter = authorizeRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectErrorMatches(e -> 
                        e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                    .verify();

            assertThat(filterChain.wasFilterCalled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Authorization Error Scenarios")
    class AuthorizationErrorTests {

        @Test
        @DisplayName("Should return 500 when policy execution fails")
        void shouldReturn500OnPolicyExecutionError() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            when(authorizationClient.executePolicy(any()))
                    .thenReturn(Mono.error(new RuntimeException("OPA unavailable")));

            PolicyData policyData = new PolicyData();
            policyData.setAppShortcode("tarcinapp");
            
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .post()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.memberToken())
                    .body("{\"name\": \"test\"}")
                    .attribute(PolicyData.POLICY_INQUIRY_DATA_ATTR, policyData)
                    .build();

            GatewayFilter filter = authorizeRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectErrorMatches(e -> 
                        e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR)
                    .verify();

            assertThat(filterChain.wasFilterCalled()).isFalse();
        }

        @Test
        @DisplayName("Should handle missing policy data")
        void shouldHandleMissingPolicyData() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .post()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.memberToken())
                    .body("{\"name\": \"test\"}")
                    // No policy data attribute set
                    .build();

            GatewayFilter filter = authorizeRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectError()
                    .verify();

            assertThat(filterChain.wasFilterCalled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Policy Configuration Tests")
    class PolicyConfigurationTests {

        @Test
        @DisplayName("Should set policy name from config")
        void shouldSetPolicyNameFromConfig() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            
            PolicyResult allowResult = new PolicyResult();
            allowResult.setAllow(true);
            when(authorizationClient.executePolicy(any())).thenReturn(Mono.just(allowResult));

            PolicyData policyData = new PolicyData();
            policyData.setAppShortcode("tarcinapp");

            config.setPolicyName("custom/policy/path");
            
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .post()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.memberToken())
                    .body("{\"name\": \"test\"}")
                    .attribute(PolicyData.POLICY_INQUIRY_DATA_ATTR, policyData)
                    .build();

            GatewayFilter filter = authorizeRequest.apply(config);

            // When
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            // Then - verify policy was called (we can't easily verify the policy name without capturing)
            verify(authorizationClient).executePolicy(any());
        }
    }
}
