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

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.Header;
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

            Locator<Key> keyLocator = header -> {
                try {
                    String kid = ((ProtectedHeader) header).getKeyId();
                    return jwkProvider.get(kid).getPublicKey();
                } catch (Exception e) {
                    throw new RuntimeException("Key could not be found via JWKS", e);
                }
            };

            return Jwts.parser()
                    .keyLocator(keyLocator)
                    .requireIssuer(provider.getIssuer()) // Issuer check
                    .clockSkewSeconds(provider.getClockSkewSeconds()) // Clock skew
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
            Key key = parsePublicKey(provider.getPublicKey(), provider.getAlgorithm());
            Locator<Key> keyLocator = header -> key;
            return Jwts.parser()
                    .keyLocator(keyLocator)
                    .requireIssuer(provider.getIssuer())
                    .clockSkewSeconds(provider.getClockSkewSeconds())
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
    private Key parsePublicKey(String keyString, String algorithm) throws Exception {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("'algorithm' must be specified for static public key providers (e.g. RSA, EC)");
        }

        // Remove header/footer if present
        String realKey = keyString
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(realKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
        return keyFactory.generatePublic(spec);
    }
}