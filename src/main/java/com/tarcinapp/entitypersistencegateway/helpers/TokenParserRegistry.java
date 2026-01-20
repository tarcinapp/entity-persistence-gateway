package com.tarcinapp.entitypersistencegateway.helpers;

import java.security.Key;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.tarcinapp.entitypersistencegateway.config.AuthConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import jakarta.annotation.PostConstruct;

@Component
public class TokenParserRegistry {

    private final AuthConfig authConfig;
    private final Map<String, JwtParser> parserMap = new HashMap<>();

    public TokenParserRegistry(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    @PostConstruct
    public void init() {
        if (authConfig.getProviders() == null)
            return;

        for (AuthConfig.Provider provider : authConfig.getProviders()) {
            JwtParser parser;

            // 1. Case: Providers with JWKS URL like Google
            if (provider.getJwkSetUri() != null && !provider.getJwkSetUri().isEmpty()) {
                parser = createJwksParser(provider);
            }
            // 2. Case: Static Public Key (Internal)
            else if (provider.getPublicKey() != null && !provider.getPublicKey().isEmpty()) {
                parser = createStaticKeyParser(provider);
            } else {
                continue;
            }

            parserMap.put(provider.getIssuer(), parser);
        }
    }

    /**
     * Parser for providers using dynamic keys like Google.
     * Finds the correct key according to the 'kid' (Key ID) value in the header.
     */
    private JwtParser createJwksParser(AuthConfig.Provider provider) {

        try {
            // Build JWKS Provider with cache (so it doesn't call Google every time)
            JwkProvider jwkProvider = new JwkProviderBuilder(new java.net.URI(provider.getJwkSetUri()).toURL())
                    .cached(10, 24, TimeUnit.HOURS) // Keep 10 keys for 24 hours
                    .rateLimited(10, 1, TimeUnit.MINUTES) // Rate limit protection
                    .build();

            return Jwts.parserBuilder()
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        @SuppressWarnings("rawtypes")
                        public Key resolveSigningKey(JwsHeader header, Claims claims) {
                            try {
                                String kid = header.getKeyId();
                                return jwkProvider.get(kid).getPublicKey();
                            } catch (Exception e) {
                                throw new RuntimeException("Key could not be found via JWKS", e);
                            }
                        }
                    })
                    .requireIssuer(provider.getIssuer()) // Issuer check
                    .setAllowedClockSkewSeconds(provider.getClockSkewSeconds()) // Clock skew
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JWKS parser", e);
        }
    }

    /**
     * Parser for providers using static Public Key (Internal).
     */
    private JwtParser createStaticKeyParser(AuthConfig.Provider provider) {
        
        try {
            Key key = parsePublicKey(provider.getPublicKey());
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(provider.getIssuer())
                    .setAllowedClockSkewSeconds(provider.getClockSkewSeconds())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create static key parser", e);
        }
    }

    public boolean isConfigured() {
        return !parserMap.isEmpty();
    }

    public JwtParser getParser(String issuer) {
        return parserMap.get(issuer);
    }

    // Helper method to convert PEM String to Java PublicKey object
    private Key parsePublicKey(String keyString) throws Exception {
        // Remove header/footer if present
        String realKey = keyString
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(realKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }
}