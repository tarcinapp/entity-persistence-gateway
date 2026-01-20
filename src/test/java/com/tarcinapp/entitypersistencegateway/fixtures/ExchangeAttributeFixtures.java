package com.tarcinapp.entitypersistencegateway.fixtures;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pre-built exchange attributes for tests.
 */
public class ExchangeAttributeFixtures {

    // ==================== Security Contexts ====================

    /**
     * Create a security context for an admin user.
     */
    public static GatewaySecurityContext adminSecurityContext() {
        return createSecurityContext(
                JwtFixtures.getAdminUserId(),
                "test-client",
                Arrays.asList("tarcinapp.admin")
        );
    }

    /**
     * Create a security context for an editor user.
     */
    public static GatewaySecurityContext editorSecurityContext() {
        return createSecurityContext(
                JwtFixtures.getEditorUserId(),
                "test-client",
                Arrays.asList("tarcinapp.editor")
        );
    }

    /**
     * Create a security context for a member user.
     */
    public static GatewaySecurityContext memberSecurityContext() {
        return createSecurityContext(
                JwtFixtures.getMemberUserId(),
                "test-client",
                Arrays.asList("tarcinapp.member")
        );
    }

    /**
     * Create a security context for a visitor user.
     */
    public static GatewaySecurityContext visitorSecurityContext() {
        return createSecurityContext(
                JwtFixtures.getVisitorUserId(),
                "test-client",
                Arrays.asList("tarcinapp.visitor")
        );
    }

    /**
     * Create a security context for an anonymous user (unauthenticated).
     */
    public static GatewaySecurityContext anonymousSecurityContext() {
        GatewaySecurityContext context = new GatewaySecurityContext();
        // Leave fields null to represent unauthenticated state
        return context;
    }

    /**
     * Create a security context with custom parameters.
     */
    public static GatewaySecurityContext createSecurityContext(
            String authSubject,
            String authParty,
            List<String> roles) {
        
        GatewaySecurityContext context = new GatewaySecurityContext();
        context.setAuthSubject(authSubject);
        context.setAuthParty(authParty);
        context.setRoles(new ArrayList<>(roles));
        context.setGroups(new ArrayList<>());
        return context;
    }

    /**
     * Create an unauthenticated security context.
     */
    public static GatewaySecurityContext unauthenticatedSecurityContext() {
        // Return context with null fields to represent unauthenticated state
        return new GatewaySecurityContext();
    }

    // ==================== Attribute Keys ====================

    /**
     * Common attribute keys used in the gateway.
     */
    public static class AttributeKeys {
        public static final String SECURITY_CONTEXT = "securityContext";
        public static final String FORBIDDEN_FIELDS = "forbiddenFields";
        public static final String POLICY_RESULT = "policyResult";
        public static final String ORIGINAL_RECORD = "originalRecord";
        public static final String REQUEST_ID = "requestId";
        public static final String RECORD_TYPE = "recordType";
        public static final String KIND_NAME = "kindName";
        public static final String KIND_ALIAS = "kindAlias";
    }

    // ==================== Sample Attribute Values ====================

    /**
     * Sample request ID.
     */
    public static String sampleRequestId() {
        return "tarcinapp-req-12345678";
    }

    /**
     * Sample forbidden fields list for member role.
     */
    public static List<String> memberForbiddenFields() {
        return PolicyResponseFixtures.memberForbiddenFields();
    }

    /**
     * Sample forbidden fields list for editor role.
     */
    public static List<String> editorForbiddenFields() {
        return PolicyResponseFixtures.editorForbiddenFields();
    }
}
