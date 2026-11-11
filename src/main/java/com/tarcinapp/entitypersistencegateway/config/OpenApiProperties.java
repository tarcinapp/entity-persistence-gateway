package com.tarcinapp.entitypersistencegateway.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Configuration
@ConfigurationProperties(prefix = "app.oas")
@Data
@Slf4j
public class OpenApiProperties {

    private String title;
    private String version;
    private String description;
    private Contact contact;
    private List<Server> servers = new ArrayList<>();
    private Map<String, ControllerConfig> controllers = new HashMap<>();
    private List<Tag> tags = new ArrayList<>();

    // Flattened lookups for fast access
    private final Map<String, AliasContext> aliasLookupByControllerAndAlias = new HashMap<>();
    private final Map<String, Map<String, AliasContext>> aliasLookupByController = new HashMap<>();
    private final Map<String, String> schemaByControllerAndKind = new HashMap<>();

    @PostConstruct
    public void init() {
        aliasLookupByControllerAndAlias.clear();
        aliasLookupByController.clear();
        schemaByControllerAndKind.clear();

        controllers.forEach((controllerName, controllerConfig) -> {
            if (controllerConfig.getAliases() != null) {
                Set<String> seenTopLevelAliases = new HashSet<>();
                controllerConfig.getAliases()
                        .forEach(aliasConfig -> registerAlias(controllerName, aliasConfig, seenTopLevelAliases, true));
            }
        });
    }

    private void registerAlias(String controllerName, AliasConfig aliasConfig, Set<String> seenTopLevelAliases, boolean isTopLevel) {
        if (aliasConfig == null || aliasConfig.getAlias() == null) {
            return;
        }

        // Only check for duplicates at the top level
        // Hierarchy aliases (children/parents) can reuse names since they're resolved
        // from the parent's children/parents arrays, not the global lookup
        if (isTopLevel && !seenTopLevelAliases.add(aliasConfig.getAlias())) {
            String message = "Duplicate top-level alias '" + aliasConfig.getAlias() + "' detected for controller '"
                    + controllerName + "'";
            log.error(message);
            throw new IllegalStateException(message);
        }

        AliasContext aliasContext = new AliasContext(controllerName, aliasConfig);

        // Only register in global lookups for top-level aliases
        if (isTopLevel) {
            // Store per-controller lookup
            aliasLookupByController.computeIfAbsent(controllerName, key -> new HashMap<>())
                    .put(aliasConfig.getAlias(), aliasContext);

            // Store combined key lookup
            aliasLookupByControllerAndAlias.put(buildAliasKey(controllerName, aliasConfig.getAlias()), aliasContext);
        }

        if (aliasConfig.getKind() != null) {
            String effective = resolveRequestSchema(aliasConfig);
            if (effective != null) {
                schemaByControllerAndKind.put(buildSchemaKey(controllerName, aliasConfig.getKind()), effective);
            }
        }

        if (aliasConfig.getChildren() != null) {
            aliasConfig.getChildren().forEach(child -> registerAlias(controllerName, child, seenTopLevelAliases, false));
        }

        if (aliasConfig.getParents() != null) {
            aliasConfig.getParents().forEach(parent -> registerAlias(controllerName, parent, seenTopLevelAliases, false));
        }
    }

    public AliasContext getAliasContext(String controllerName, String alias) {
        if (controllerName == null || alias == null) {
            return null;
        }

        Map<String, AliasContext> controllerAliases = aliasLookupByController.get(controllerName);
        if (controllerAliases != null) {
            AliasContext context = controllerAliases.get(alias);
            if (context != null) {
                return context;
            }
        }

        return aliasLookupByControllerAndAlias.get(buildAliasKey(controllerName, alias));
    }

    public String getSchema(String controllerName, String kind) {
        return schemaByControllerAndKind.get(buildSchemaKey(controllerName, kind));
    }

    private static String buildSchemaKey(String controllerName, String kind) {
        return controllerName + ":" + kind;
    }

    private static String buildAliasKey(String controllerName, String alias) {
        return controllerName + ":" + alias;
    }

    @Data
    public static class ControllerConfig {
        private List<AliasConfig> aliases = new ArrayList<>();
    }

    @Data
    public static class AliasConfig {
        private String alias;
        private String singular;
        private String kind;
        private String description;
        private String schema;
        private String requestSchema;
        private String responseSchema;
        private Boolean validationEnabled;
        private List<AliasConfig> children = new ArrayList<>();
        private List<AliasConfig> parents = new ArrayList<>();
        private ThroughConfig through;
        private Map<String, RouteConfig> routes = new HashMap<>();
    }

    @Data
    public static class ThroughConfig {
        private List<AliasConfig> reactions = new ArrayList<>();
        private List<AliasConfig> entities = new ArrayList<>();
        private List<AliasConfig> lists = new ArrayList<>();
    }

    @Data
    public static class RouteConfig {
        private String operationId;
        private String summary;
        private String description;
        private String schema;
        private String requestSchema;
        private String responseSchema;
        private Boolean validationEnabled;
        private List<String> tags = new ArrayList<>();
        private Map<String, Object> request;
    }

    /**
     * Returns the effective schema string to use when building request-body variants
     * (New{Kind} for POST, Patch{Kind} for PATCH, {Kind} for PUT).
     *
     * Resolution order:
     *   1. config.requestSchema  — explicit per-direction override
     *   2. config.schema         — unified fallback (unchanged behaviour)
     *   3. null                  — no schema configured
     */
    public static String resolveRequestSchema(AliasConfig config) {
        if (config == null) return null;
        if (config.getRequestSchema() != null) return config.getRequestSchema();
        return config.getSchema();
    }

    public static String resolveRequestSchema(RouteConfig config) {
        if (config == null) return null;
        if (config.getRequestSchema() != null) return config.getRequestSchema();
        return config.getSchema();
    }

    /**
     * Returns the effective schema string to use when building response variants
     * ({Kind} for GET / POST / PUT / PATCH / DELETE responses).
     *
     * Resolution order:
     *   1. config.responseSchema — explicit per-direction override
     *   2. config.schema         — unified fallback (unchanged behaviour)
     *   3. null                  — no schema configured
     *
     * NOTE: responseSchema never feeds ValidateRequestBodyByKindSchema.
     */
    public static String resolveResponseSchema(AliasConfig config) {
        if (config == null) return null;
        if (config.getResponseSchema() != null) return config.getResponseSchema();
        return config.getSchema();
    }

    public static String resolveResponseSchema(RouteConfig config) {
        if (config == null) return null;
        if (config.getResponseSchema() != null) return config.getResponseSchema();
        return config.getSchema();
    }

    @Data
    public static class Server {
        private String url;
        private String description;
    }

    @Data
    public static class Contact {
        private String name;
        private String email;
        private String url;
    }

    @Data
    public static class Tag {
        private String name;
        private String description;
    }

    @Data
    public static class AliasContext {
        private final String controllerName;
        private final AliasConfig aliasConfig;
    }
}