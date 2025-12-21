package com.tarcinapp.entitypersistencegateway.fixtures;

import com.tarcinapp.entitypersistencegateway.util.TestJwtGenerator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Pre-built JWT tokens for different roles and scenarios in tests.
 */
public class JwtFixtures {

    private static final String ADMIN_USER_ID = "admin-user-id-12345";
    private static final String EDITOR_USER_ID = "editor-user-id-12345";
    private static final String MEMBER_USER_ID = "member-user-id-12345";
    private static final String VISITOR_USER_ID = "visitor-user-id-12345";

    /**
     * Generate a token for an admin user with full privileges.
     */
    public static String adminToken() {
        return TestJwtGenerator.generateValidToken(
                ADMIN_USER_ID,
                Arrays.asList("tarcinapp.admin")
        );
    }

    /**
     * Generate a token for an editor user.
     */
    public static String editorToken() {
        return TestJwtGenerator.generateValidToken(
                EDITOR_USER_ID,
                Arrays.asList("tarcinapp.editor")
        );
    }

    /**
     * Generate a token for a member user.
     */
    public static String memberToken() {
        return TestJwtGenerator.generateValidToken(
                MEMBER_USER_ID,
                Arrays.asList("tarcinapp.member")
        );
    }

    /**
     * Generate a token for a visitor user with minimal privileges.
     */
    public static String visitorToken() {
        return TestJwtGenerator.generateValidToken(
                VISITOR_USER_ID,
                Arrays.asList("tarcinapp.visitor")
        );
    }

    /**
     * Generate a token for an anonymous user (no roles).
     */
    public static String anonymousToken() {
        return TestJwtGenerator.generateValidToken(
                "anonymous-user-id",
                Collections.emptyList()
        );
    }

    /**
     * Generate an expired token.
     */
    public static String expiredToken() {
        return TestJwtGenerator.generateExpiredToken(
                "expired-user-id",
                Arrays.asList("tarcinapp.member")
        );
    }

    /**
     * Return an invalid token string.
     */
    public static String invalidToken() {
        return "invalid.token.here";
    }

    /**
     * Return a malformed JWT (wrong number of parts).
     */
    public static String malformedToken() {
        return "only.two.parts";
    }

    /**
     * Generate a token with custom user ID.
     */
    public static String tokenForUser(String userId, String... roles) {
        return TestJwtGenerator.generateValidToken(userId, Arrays.asList(roles));
    }

    /**
     * Generate a token with multiple roles.
     */
    public static String multiRoleToken() {
        return TestJwtGenerator.generateValidToken(
                "multi-role-user",
                Arrays.asList("tarcinapp.member", "tarcinapp.editor")
        );
    }

    /**
     * Generate a token without email verification.
     */
    public static String unverifiedEmailToken() {
        return TestJwtGenerator.generateTokenWithoutEmailVerification(
                "unverified-user",
                Arrays.asList("tarcinapp.member")
        );
    }

    /**
     * Generate a token that expires soon (in 5 seconds).
     */
    public static String soonExpiringToken() {
        return TestJwtGenerator.generateTokenExpiringIn(
                "soon-expiring-user",
                Arrays.asList("tarcinapp.member"),
                5
        );
    }

    /**
     * Generate a token with custom claims.
     */
    public static String tokenWithCustomClaims(Map<String, Object> claims) {
        return TestJwtGenerator.generateTokenWithClaims(claims);
    }

    /**
     * Generate a token with a different issuer.
     */
    public static String tokenWithIssuer(String issuer) {
        return TestJwtGenerator.generateTokenWithIssuer(
                "custom-issuer-user",
                Arrays.asList("tarcinapp.member"),
                issuer
        );
    }

    // ==================== User IDs ====================

    public static String getAdminUserId() {
        return ADMIN_USER_ID;
    }

    public static String getEditorUserId() {
        return EDITOR_USER_ID;
    }

    public static String getMemberUserId() {
        return MEMBER_USER_ID;
    }

    public static String getVisitorUserId() {
        return VISITOR_USER_ID;
    }
}
