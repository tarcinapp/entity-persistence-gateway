package com.tarcinapp.entitypersistencegateway.fixtures;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pre-built OPA policy responses for different scenarios in tests.
 */
public class PolicyResponseFixtures {

    // ==================== JSON Responses ====================

    /**
     * OPA response that allows the request.
     */
    public static String allowedPolicyJson() {
        return """
            {
                "result": {
                    "allow": true,
                    "forbiddenFields": []
                }
            }
            """;
    }

    /**
     * OPA response that denies the request.
     */
    public static String deniedPolicyJson() {
        return """
            {
                "result": {
                    "allow": false,
                    "forbiddenFields": []
                }
            }
            """;
    }

    /**
     * OPA response that allows with forbidden fields.
     */
    public static String forbiddenFieldsPolicyJson(List<String> fields) {
        String fieldsJson = fields.stream()
                .map(f -> "\"" + f + "\"")
                .collect(Collectors.joining(", "));
        return String.format("""
            {
                "result": {
                    "allow": true,
                    "forbiddenFields": [%s]
                }
            }
            """, fieldsJson);
    }

    /**
     * OPA response for member role with typical forbidden fields.
     */
    public static String memberPolicyJson() {
        return forbiddenFieldsPolicyJson(memberForbiddenFields());
    }

    /**
     * OPA response for editor role with fewer forbidden fields.
     */
    public static String editorPolicyJson() {
        return forbiddenFieldsPolicyJson(editorForbiddenFields());
    }

    /**
     * OPA response for admin role with no forbidden fields.
     */
    public static String adminPolicyJson() {
        return allowedPolicyJson();
    }

    // ==================== Forbidden Field Lists ====================

    /**
     * Forbidden fields for member role.
     */
    public static List<String> memberForbiddenFields() {
        return Arrays.asList(
                "ownerUsers",
                "ownerGroups",
                "viewerUsers",
                "viewerGroups",
                "_createdDateTime",
                "createdBy",
                "lastUpdatedDateTime",
                "lastUpdatedBy"
        );
    }

    /**
     * Forbidden fields for editor role.
     */
    public static List<String> editorForbiddenFields() {
        return Arrays.asList(
                "ownerUsers",
                "ownerGroups"
        );
    }

    /**
     * Forbidden fields for visitor role.
     */
    public static List<String> visitorForbiddenFields() {
        return Arrays.asList(
                "ownerUsers",
                "ownerGroups",
                "viewerUsers",
                "viewerGroups",
                "_createdDateTime",
                "createdBy",
                "lastUpdatedDateTime",
                "lastUpdatedBy",
                "visibility",
                "idempotencyKey"
        );
    }

    /**
     * No forbidden fields (admin).
     */
    public static List<String> noForbiddenFields() {
        return Collections.emptyList();
    }

    // ==================== Special Scenarios ====================

    /**
     * OPA response with reduce scope flag.
     */
    public static String reduceScopePolicyJson() {
        return """
            {
                "result": {
                    "allow": true,
                    "forbiddenFields": [],
                    "reduceScope": true
                }
            }
            """;
    }

    /**
     * OPA response with custom message for denial.
     */
    public static String deniedWithMessagePolicyJson(String message) {
        return String.format("""
            {
                "result": {
                    "allow": false,
                    "forbiddenFields": [],
                    "message": "%s"
                }
            }
            """, message);
    }

    /**
     * Empty OPA response (error case).
     */
    public static String emptyPolicyJson() {
        return "{}";
    }

    /**
     * Invalid JSON response.
     */
    public static String invalidPolicyJson() {
        return "{ invalid json }";
    }

    // ==================== Policy Paths ====================

    /**
     * Policy paths for different operations.
     */
    public static class PolicyPaths {
        public static final String CREATE_ENTITY = "/v1/data/tarcinapp/entities/create";
        public static final String FIND_ALL_ENTITIES = "/v1/data/tarcinapp/entities/findall";
        public static final String FIND_ENTITY_BY_ID = "/v1/data/tarcinapp/entities/findbyid";
        public static final String UPDATE_ENTITY = "/v1/data/tarcinapp/entities/update";
        public static final String REPLACE_ENTITY = "/v1/data/tarcinapp/entities/replace";
        public static final String DELETE_ENTITY = "/v1/data/tarcinapp/entities/delete";

        public static final String CREATE_LIST = "/v1/data/tarcinapp/lists/create";
        public static final String FIND_ALL_LISTS = "/v1/data/tarcinapp/lists/findall";
        public static final String FIND_LIST_BY_ID = "/v1/data/tarcinapp/lists/findbyid";

        public static final String CREATE_REACTION = "/v1/data/tarcinapp/reactions/create";
        public static final String FIND_ALL_REACTIONS = "/v1/data/tarcinapp/reactions/findall";

        public static final String FORBIDDEN_FIELDS = "/v1/data/tarcinapp/fields/forbidden";
    }
}
