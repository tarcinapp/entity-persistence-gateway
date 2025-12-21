package com.tarcinapp.entitypersistencegateway.unit.filters;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.filters.common.request.AuthenticateRequest;
import com.tarcinapp.entitypersistencegateway.services.JwtAuthenticationService;
import com.tarcinapp.entitypersistencegateway.services.SecurityContextBuilder;
import com.tarcinapp.entitypersistencegateway.services.policydata.PolicyDataBuilderRegistry;
import com.tarcinapp.entitypersistencegateway.util.MockExchangeBuilder;
import com.tarcinapp.entitypersistencegateway.util.MockGatewayFilterChain;
import com.tarcinapp.entitypersistencegateway.fixtures.JwtFixtures;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthenticateRequest filter.
 * Tests authentication flow, error handling, and context initialization.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticateRequest Filter Unit Tests")
class AuthenticateRequestTest {

    @Mock
    private JwtAuthenticationService jwtAuthenticationService;

    @Mock
    private SecurityContextBuilder securityContextBuilder;

    @Mock
    private PolicyDataBuilderRegistry policyDataBuilderRegistry;

    @InjectMocks
    private AuthenticateRequest authenticateRequest;

    private MockGatewayFilterChain filterChain;
    private AuthenticateRequest.Config config;

    @BeforeEach
    void setUp() {
        filterChain = new MockGatewayFilterChain();
        config = new AuthenticateRequest.Config();
        ReflectionTestUtils.setField(authenticateRequest, "appShortcode", "tarcinapp");
    }

    @Nested
    @DisplayName("Authentication Not Configured Scenarios")
    class AuthNotConfiguredTests {

        @Test
        @DisplayName("Should skip authentication when not configured and continue chain")
        void shouldSkipAuthWhenNotConfigured() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(false);
            doNothing().when(securityContextBuilder).initializeSecurityContext(any());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .build();

            GatewayFilter filter = authenticateRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            verify(securityContextBuilder).initializeSecurityContext(any());
        }
    }

    @Nested
    @DisplayName("Authentication Success Scenarios")
    class AuthSuccessTests {

        @Test
        @DisplayName("Should authenticate and continue chain on valid token")
        void shouldAuthenticateValidToken() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            doNothing().when(securityContextBuilder).initializeSecurityContext(any());
            doNothing().when(securityContextBuilder).buildFromClaims(any(), any());

            Claims claims = createTestClaims("test-user", "test-issuer");
            when(jwtAuthenticationService.authenticate(any())).thenReturn(Mono.just(claims));

            // Mock policy data builder
            var mockPolicyDataBuilder = mock(com.tarcinapp.entitypersistencegateway.services.policydata.PolicyDataBuilder.class);
            when(policyDataBuilderRegistry.selectBuilder(any())).thenReturn(mockPolicyDataBuilder);
            when(mockPolicyDataBuilder.buildPolicyData(any(), any(), any())).thenReturn(Mono.empty());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.memberToken())
                    .build();

            GatewayFilter filter = authenticateRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            verify(securityContextBuilder).buildFromClaims(eq(claims), any());
        }
    }

    @Nested
    @DisplayName("Authentication Failure Scenarios")
    class AuthFailureTests {

        @Test
        @DisplayName("Should return 401 when authentication fails")
        void shouldReturn401OnAuthFailure() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            doNothing().when(securityContextBuilder).initializeSecurityContext(any());

            when(jwtAuthenticationService.authenticate(any()))
                    .thenReturn(Mono.error(new JwtAuthenticationService.JwtAuthenticationException("Invalid token")));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.invalidToken())
                    .build();

            GatewayFilter filter = authenticateRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            assertThat(filterChain.wasFilterCalled()).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should return 504 on timeout")
        void shouldReturn504OnTimeout() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(true);
            doNothing().when(securityContextBuilder).initializeSecurityContext(any());

            when(jwtAuthenticationService.authenticate(any()))
                    .thenReturn(Mono.error(new java.util.concurrent.TimeoutException("Request timed out")));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.memberToken())
                    .build();

            GatewayFilter filter = authenticateRequest.apply(config);

            // When & Then
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            assertThat(filterChain.wasFilterCalled()).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("Context Initialization Tests")
    class ContextInitializationTests {

        @Test
        @DisplayName("Should initialize security context before authentication")
        void shouldInitializeSecurityContext() {
            // Given
            when(jwtAuthenticationService.isConfigured()).thenReturn(false);
            doNothing().when(securityContextBuilder).initializeSecurityContext(any());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .build();

            GatewayFilter filter = authenticateRequest.apply(config);

            // When
            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            // Then
            verify(securityContextBuilder).initializeSecurityContext(any());
            
            // Verify PolicyData was added to attributes
            PolicyData policyData = exchange.getAttribute(PolicyData.POLICY_INQUIRY_DATA_ATTR);
            assertThat(policyData).isNotNull();
            assertThat(policyData.getAppShortcode()).isEqualTo("tarcinapp");
        }
    }

    /**
     * Helper method to create test claims.
     */
    private Claims createTestClaims(String subject, String issuer) {
        Map<String, Object> claimsMap = new HashMap<>();
        claimsMap.put("sub", subject);
        claimsMap.put("iss", issuer);
        claimsMap.put("roles", java.util.Arrays.asList("tarcinapp.member"));
        return new DefaultClaims(claimsMap);
    }
}
