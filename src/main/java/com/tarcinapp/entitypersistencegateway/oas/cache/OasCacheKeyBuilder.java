package com.tarcinapp.entitypersistencegateway.oas.cache;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builder for generating deterministic cache keys based on user permissions.
 * 
 * <p>The cache key is derived from the user's roles and groups, ensuring that
 * users with identical permission sets share the same cached OAS spec.</p>
 * 
 * <h2>Key Format:</h2>
 * <pre>
 * oas:v1:{sha256(sorted(roles+groups))}
 * </pre>
 * 
 * <h2>Examples:</h2>
 * <ul>
 *   <li>Anonymous user: "oas:v1:anonymous"</li>
 *   <li>Admin user: "oas:v1:abc123..." (hash of ["admin"])</li>
 *   <li>User with roles: "oas:v1:def456..." (hash of sorted roles)</li>
 * </ul>
 */
@Component
@Slf4j
public class OasCacheKeyBuilder {
    
    private static final String KEY_PREFIX = "oas:v1:";
    private static final String ANONYMOUS_KEY = KEY_PREFIX + "anonymous";
    
    /**
     * Builds a cache key from the security context.
     * 
     * @param securityContext The user's security context (may be null)
     * @return Deterministic cache key string
     */
    public String buildCacheKey(GatewaySecurityContext securityContext) {
        if (securityContext == null || securityContext.getEncodedJwt() == null) {
            log.debug("Building anonymous cache key");
            return ANONYMOUS_KEY;
        }
        
        List<String> components = new ArrayList<>();
        
        // Add roles
        if (securityContext.getRoles() != null) {
            securityContext.getRoles().forEach(role -> components.add("r:" + role));
        }
        
        // Add groups
        if (securityContext.getGroups() != null) {
            securityContext.getGroups().forEach(group -> components.add("g:" + group));
        }
        
        if (components.isEmpty()) {
            log.debug("Building cache key for user with no roles/groups");
            return KEY_PREFIX + "authenticated-no-roles";
        }
        
        // Sort for determinism
        Collections.sort(components);
        
        // Create hash
        String fingerprint = sha256Hex(String.join("|", components));
        String cacheKey = KEY_PREFIX + fingerprint;
        
        log.debug("Built cache key with {} components: {}", components.size(), 
            cacheKey.substring(0, Math.min(cacheKey.length(), 30)) + "...");
        
        return cacheKey;
    }
    
    /**
     * Computes SHA-256 hash and returns as hex string.
     */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Builds a cache key directly from a field permission fingerprint.
     * 
     * @param permissionFingerprint The fingerprint from FieldPermissionContext
     * @return Cache key string
     */
    public String buildCacheKeyFromFingerprint(String permissionFingerprint) {
        if (permissionFingerprint == null || permissionFingerprint.isEmpty()) {
            return ANONYMOUS_KEY;
        }
        
        if (permissionFingerprint.equals("full-visibility")) {
            return KEY_PREFIX + "full-visibility";
        }
        
        return KEY_PREFIX + permissionFingerprint;
    }
    
    /**
     * Extracts the fingerprint portion from a cache key.
     * 
     * @param cacheKey The full cache key
     * @return The fingerprint portion
     */
    public String extractFingerprint(String cacheKey) {
        if (cacheKey == null || !cacheKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        return cacheKey.substring(KEY_PREFIX.length());
    }
}
