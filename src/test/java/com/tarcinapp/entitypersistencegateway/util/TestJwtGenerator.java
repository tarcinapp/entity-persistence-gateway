package com.tarcinapp.entitypersistencegateway.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

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
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("email_verified", true);
        claims.put("sub", subject);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuer(DEFAULT_ISSUER)
                .setIssuedAt(new Date())
                .setExpiration(expiration)
                .signWith(KEY_PAIR.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * Generate a token with custom claims.
     */
    public static String generateTokenWithClaims(Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new HashMap<>(additionalClaims);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject((String) claims.getOrDefault("sub", "test-user"))
                .setIssuer((String) claims.getOrDefault("iss", DEFAULT_ISSUER))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(KEY_PAIR.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * Generate a token with custom issuer.
     */
    public static String generateTokenWithIssuer(String subject, List<String> roles, String issuer) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("email_verified", true);
        claims.put("sub", subject);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuer(issuer)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(KEY_PAIR.getPrivate(), SignatureAlgorithm.RS256)
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
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("email_verified", false);
        claims.put("sub", subject);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuer(DEFAULT_ISSUER)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(KEY_PAIR.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * Get the default issuer used for test tokens.
     */
    public static String getDefaultIssuer() {
        return DEFAULT_ISSUER;
    }
}
