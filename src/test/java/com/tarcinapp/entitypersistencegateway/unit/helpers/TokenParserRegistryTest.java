package com.tarcinapp.entitypersistencegateway.unit.helpers;

import com.tarcinapp.entitypersistencegateway.config.AuthConfig;
import com.tarcinapp.entitypersistencegateway.helpers.TokenParserRegistry;
import com.tarcinapp.entitypersistencegateway.util.TestJwtGenerator;
import io.jsonwebtoken.JwtParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TokenParserRegistry.
 * Tests parser registration and retrieval for different issuers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenParserRegistry Unit Tests")
class TokenParserRegistryTest {

    private AuthConfig authConfig;
    private TokenParserRegistry tokenParserRegistry;

    @BeforeEach
    void setUp() {
        authConfig = new AuthConfig();
    }

    @Nested
    @DisplayName("Parser Registration Tests")
    class ParserRegistrationTests {

        @Test
        @DisplayName("Should register parser for provider with static public key")
        void shouldRegisterParserForStaticKeyProvider() {
            // Given
            List<AuthConfig.Provider> providers = new ArrayList<>();
            AuthConfig.Provider provider = new AuthConfig.Provider();
            provider.setIssuer("test-issuer");
            provider.setPublicKey(TestJwtGenerator.getPublicKeyBase64());
            provider.setClockSkewSeconds(60);
            providers.add(provider);
            authConfig.setProviders(providers);

            tokenParserRegistry = new TokenParserRegistry(authConfig);

            // When
            tokenParserRegistry.init();

            // Then
            assertThat(tokenParserRegistry.isConfigured()).isTrue();
            assertThat(tokenParserRegistry.getParser("test-issuer")).isNotNull();
        }

        @Test
        @DisplayName("Should return null for unknown issuer")
        void shouldReturnNullForUnknownIssuer() {
            // Given
            List<AuthConfig.Provider> providers = new ArrayList<>();
            AuthConfig.Provider provider = new AuthConfig.Provider();
            provider.setIssuer("known-issuer");
            provider.setPublicKey(TestJwtGenerator.getPublicKeyBase64());
            provider.setClockSkewSeconds(60);
            providers.add(provider);
            authConfig.setProviders(providers);

            tokenParserRegistry = new TokenParserRegistry(authConfig);
            tokenParserRegistry.init();

            // When
            JwtParser parser = tokenParserRegistry.getParser("unknown-issuer");

            // Then
            assertThat(parser).isNull();
        }

        @Test
        @DisplayName("Should not be configured when no providers exist")
        void shouldNotBeConfiguredWhenNoProviders() {
            // Given
            authConfig.setProviders(null);
            tokenParserRegistry = new TokenParserRegistry(authConfig);

            // When
            tokenParserRegistry.init();

            // Then
            assertThat(tokenParserRegistry.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("Should not be configured with empty provider list")
        void shouldNotBeConfiguredWithEmptyProviders() {
            // Given
            authConfig.setProviders(new ArrayList<>());
            tokenParserRegistry = new TokenParserRegistry(authConfig);

            // When
            tokenParserRegistry.init();

            // Then
            assertThat(tokenParserRegistry.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("Should register multiple providers")
        void shouldRegisterMultipleProviders() {
            // Given
            List<AuthConfig.Provider> providers = new ArrayList<>();
            
            AuthConfig.Provider provider1 = new AuthConfig.Provider();
            provider1.setIssuer("issuer-1");
            provider1.setPublicKey(TestJwtGenerator.getPublicKeyBase64());
            provider1.setClockSkewSeconds(60);
            providers.add(provider1);

            AuthConfig.Provider provider2 = new AuthConfig.Provider();
            provider2.setIssuer("issuer-2");
            provider2.setPublicKey(TestJwtGenerator.getPublicKeyBase64());
            provider2.setClockSkewSeconds(30);
            providers.add(provider2);

            authConfig.setProviders(providers);
            tokenParserRegistry = new TokenParserRegistry(authConfig);

            // When
            tokenParserRegistry.init();

            // Then
            assertThat(tokenParserRegistry.isConfigured()).isTrue();
            assertThat(tokenParserRegistry.getParser("issuer-1")).isNotNull();
            assertThat(tokenParserRegistry.getParser("issuer-2")).isNotNull();
        }

        @Test
        @DisplayName("Should skip provider without key configuration")
        void shouldSkipProviderWithoutKeyConfiguration() {
            // Given
            List<AuthConfig.Provider> providers = new ArrayList<>();
            
            // Provider without key
            AuthConfig.Provider providerNoKey = new AuthConfig.Provider();
            providerNoKey.setIssuer("no-key-issuer");
            providers.add(providerNoKey);
            
            // Valid provider
            AuthConfig.Provider validProvider = new AuthConfig.Provider();
            validProvider.setIssuer("valid-issuer");
            validProvider.setPublicKey(TestJwtGenerator.getPublicKeyBase64());
            validProvider.setClockSkewSeconds(60);
            providers.add(validProvider);

            authConfig.setProviders(providers);
            tokenParserRegistry = new TokenParserRegistry(authConfig);

            // When
            tokenParserRegistry.init();

            // Then
            assertThat(tokenParserRegistry.isConfigured()).isTrue();
            assertThat(tokenParserRegistry.getParser("no-key-issuer")).isNull();
            assertThat(tokenParserRegistry.getParser("valid-issuer")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Token Parsing Tests")
    class TokenParsingTests {

        @Test
        @DisplayName("Should successfully parse valid token with registered parser")
        void shouldParseValidToken() {
            // Given
            List<AuthConfig.Provider> providers = new ArrayList<>();
            AuthConfig.Provider provider = new AuthConfig.Provider();
            provider.setIssuer(TestJwtGenerator.getDefaultIssuer());
            provider.setPublicKey(TestJwtGenerator.getPublicKeyBase64());
            provider.setClockSkewSeconds(60);
            providers.add(provider);
            authConfig.setProviders(providers);

            tokenParserRegistry = new TokenParserRegistry(authConfig);
            tokenParserRegistry.init();

            String token = TestJwtGenerator.generateValidToken("test-user", List.of("tarcinapp.member"));
            JwtParser parser = tokenParserRegistry.getParser(TestJwtGenerator.getDefaultIssuer());

            // When
            var claims = parser.parseClaimsJws(token).getBody();

            // Then
            assertThat(claims.getSubject()).isEqualTo("test-user");
            assertThat(claims.getIssuer()).isEqualTo(TestJwtGenerator.getDefaultIssuer());
        }
    }
}
