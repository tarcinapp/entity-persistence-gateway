package com.tarcinapp.entitypersistencegateway.util;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

/**
 * Utility class for generating JWT tokens in tests.
 * Provides methods to create valid, expired, and custom tokens.
 */
public class TestJwtGenerator {

    private static final KeyPair KEY_PAIR;
    private static final String DEFAULT_ISSUER = "test-issuer";

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEY_PAIR = generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate key pair for tests", e);
        }
    }

    /**
     * Get the public key for verifying tokens.
     */
    public static PublicKey getPublicKey() {
        return KEY_PAIR.getPublic();
    }

    /**
     * Get the private key for signing tokens.
     */
    public static PrivateKey getPrivateKey() {
        return KEY_PAIR.getPrivate();
    }

    /**
     * Get the public key as a Base64-encoded string.
     */
    public static String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
    }

    /**
     * Generate a valid token with the given subject and roles.
     */
    public static String generateValidToken(String subject, List<String> roles) {
        return generateToken(subject, roles, new Date(System.currentTimeMillis() + 3600000)); // 1 hour
    }

    /**
     * Generate an expired token.
     */
    public static String generateExpiredToken(String subject, List<String> roles) {
        return generateToken(subject, roles, new Date(System.currentTimeMillis() - 3600000)); // 1 hour ago
    }

    /**
     * Generate a token with custom expiration.
     */
    public static String generateToken(String subject, List<String> roles, Date expiration) {
        return Jwts.builder()
                .claim("roles", roles)
                .claim("email_verified", true)
                .subject(subject)
                .issuer(DEFAULT_ISSUER)
                .issuedAt(new Date())
                .expiration(expiration)
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Generate a token with custom claims.
     */
    public static String generateTokenWithClaims(Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new HashMap<>(additionalClaims);
        JwtBuilder builder = Jwts.builder();
        claims.forEach((k, v) -> builder.claim(k, v));
        return builder
                .subject((String) claims.getOrDefault("sub", "test-user"))
                .issuer((String) claims.getOrDefault("iss", DEFAULT_ISSUER))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Generate a token with custom issuer.
     */
    public static String generateTokenWithIssuer(String subject, List<String> roles, String issuer) {
        return Jwts.builder()
                .claim("roles", roles)
                .claim("email_verified", true)
                .subject(subject)
                .issuer(issuer)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Generate a token that expires in the given number of seconds.
     */
    public static String generateTokenExpiringIn(String subject, List<String> roles, int seconds) {
        return generateToken(subject, roles, new Date(System.currentTimeMillis() + (seconds * 1000L)));
    }

    /**
     * Generate a token without email verification.
     */
    public static String generateTokenWithoutEmailVerification(String subject, List<String> roles) {
        return Jwts.builder()
                .claim("roles", roles)
                .claim("email_verified", false)
                .subject(subject)
                .issuer(DEFAULT_ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Get the default issuer used for test tokens.
     */
    public static String getDefaultIssuer() {
        return DEFAULT_ISSUER;
    }
}
