package com.tarcinapp.entitypersistencegateway.unit.services;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.helpers.TokenParserRegistry;
import com.tarcinapp.entitypersistencegateway.services.JwtAuthenticationService;
import com.tarcinapp.entitypersistencegateway.util.MockExchangeBuilder;
import com.tarcinapp.entitypersistencegateway.util.TestJwtGenerator;
import com.tarcinapp.entitypersistencegateway.fixtures.JwtFixtures;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JwtAuthenticationService.
 * Tests JWT token validation, parsing, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationService Unit Tests")
class JwtAuthenticationServiceTest {

    @Mock
    private TokenParserRegistry tokenParserRegistry;

    private JwtAuthenticationService jwtAuthenticationService;

    @BeforeEach
    void setUp() {
        jwtAuthenticationService = new JwtAuthenticationService(tokenParserRegistry);
    }

    @Nested
    @DisplayName("Authentication Success Scenarios")
    class AuthenticationSuccessTests {

        @Test
        @DisplayName("Should authenticate valid JWT token")
        void shouldAuthenticateValidToken() {
            // Given
            String validToken = JwtFixtures.memberToken();
            when(tokenParserRegistry.getParser(TestJwtGenerator.getDefaultIssuer()))
                    .thenReturn(createTestParser());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(validToken)
                    .attribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext())
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .assertNext(claims -> {
                        assertThat(claims.getSubject()).isEqualTo(JwtFixtures.getMemberUserId());
                        assertThat(claims.getIssuer()).isEqualTo(TestJwtGenerator.getDefaultIssuer());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should authenticate admin token with admin role")
        void shouldAuthenticateAdminToken() {
            // Given
            String adminToken = JwtFixtures.adminToken();
            when(tokenParserRegistry.getParser(TestJwtGenerator.getDefaultIssuer()))
                    .thenReturn(createTestParser());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(adminToken)
                    .attribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext())
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .assertNext(claims -> {
                        assertThat(claims.getSubject()).isEqualTo(JwtFixtures.getAdminUserId());
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Authentication Failure Scenarios")
    class AuthenticationFailureTests {

        @Test
        @DisplayName("Should fail when Authorization header is missing")
        void shouldFailWithMissingAuthorizationHeader() {
            // Given
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .expectErrorMatches(e -> 
                        e instanceof JwtAuthenticationService.JwtAuthenticationException &&
                        e.getMessage().contains("No Authorization header"))
                    .verify();
        }

        @Test
        @DisplayName("Should fail when Authorization header doesn't start with Bearer")
        void shouldFailWithNonBearerAuthorization() {
            // Given
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .header("Authorization", "Basic dXNlcjpwYXNz")
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .expectErrorMatches(e -> 
                        e instanceof JwtAuthenticationService.JwtAuthenticationException &&
                        e.getMessage().contains("Only token authorization"))
                    .verify();
        }

        @Test
        @DisplayName("Should fail with invalid token format")
        void shouldFailWithInvalidTokenFormat() {
            // Given - no mock needed since parsing fails before getParser is called

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(JwtFixtures.invalidToken())
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .expectError(JwtAuthenticationService.JwtAuthenticationException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should fail with expired token")
        void shouldFailWithExpiredToken() {
            // Given
            String expiredToken = JwtFixtures.expiredToken();
            when(tokenParserRegistry.getParser(TestJwtGenerator.getDefaultIssuer()))
                    .thenReturn(createTestParser());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(expiredToken)
                    .attribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext())
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .expectError(JwtAuthenticationService.JwtAuthenticationException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should fail when issuer is unknown")
        void shouldFailWithUnknownIssuer() {
            // Given
            String tokenWithUnknownIssuer = JwtFixtures.tokenWithIssuer("unknown-issuer");
            when(tokenParserRegistry.getParser("unknown-issuer")).thenReturn(null);

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .get()
                    .path("/api/v1/entities")
                    .authorization(tokenWithUnknownIssuer)
                    .attribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext())
                    .build();

            // When & Then
            StepVerifier.create(jwtAuthenticationService.authenticate(exchange))
                    .expectError(JwtAuthenticationService.JwtAuthenticationException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should report configured when TokenParserRegistry is configured")
        void shouldReportConfiguredWhenRegistryConfigured() {
            // Given
            when(tokenParserRegistry.isConfigured()).thenReturn(true);

            // When & Then
            assertThat(jwtAuthenticationService.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("Should report not configured when TokenParserRegistry is not configured")
        void shouldReportNotConfiguredWhenRegistryNotConfigured() {
            // Given
            when(tokenParserRegistry.isConfigured()).thenReturn(false);

            // When & Then
            assertThat(jwtAuthenticationService.isConfigured()).isFalse();
        }
    }

    /**
     * Create a test JWT parser using the test key pair.
     */
    private io.jsonwebtoken.JwtParser createTestParser() {
        return io.jsonwebtoken.Jwts.parserBuilder()
                .setSigningKey(TestJwtGenerator.getPublicKey())
                .setAllowedClockSkewSeconds(60)
                .build();
    }
}
