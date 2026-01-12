package com.tarcinapp.entitypersistencegateway.util;

import java.util.Arrays;

/**
 * Utility to print JWT token and public key for manual testing.
 * Run with: ./mvnw -q exec:java -Dexec.mainClass="com.tarcinapp.entitypersistencegateway.util.TokenPrinter" -Dexec.classpathScope=test
 */
public class TokenPrinter {
    
    public static void main(String[] args) {
        // Generate a token with issuer matching dev config
        String token = TestJwtGenerator.generateTokenWithIssuer(
            "test-user",
            Arrays.asList("admin", "user"),
            "tarcinapp-idm"  // Must match app.auth.providers[0].issuer in dev properties
        );
        
        System.out.println("=== Copy this public key to application-dev.properties ===");
        System.out.println("app.auth.providers[0].public-key=" + TestJwtGenerator.getPublicKeyBase64());
        System.out.println();
        System.out.println("=== Use this token for testing ===");
        System.out.println("TOKEN=" + token);
        System.out.println();
        System.out.println("=== curl command ===");
        System.out.println("curl -H 'Authorization: Bearer " + token + "' http://localhost:8081/openapi.json");
    }
}
