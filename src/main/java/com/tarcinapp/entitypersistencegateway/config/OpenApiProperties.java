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
                Set<String> seenAliases = new HashSet<>();
                controllerConfig.getAliases()
                        .forEach(aliasConfig -> registerAlias(controllerName, aliasConfig, seenAliases));
            }
        });
    }

    private void registerAlias(String controllerName, AliasConfig aliasConfig, Set<String> seenAliases) {
        if (aliasConfig == null || aliasConfig.getAlias() == null) {
            return;
        }

        if (!seenAliases.add(aliasConfig.getAlias())) {
            String message = "Duplicate alias '" + aliasConfig.getAlias() + "' detected for controller '"
                    + controllerName + "'";
            log.error(message);
            throw new IllegalStateException(message);
        }

        AliasContext aliasContext = new AliasContext(controllerName, aliasConfig);

        // Store per-controller lookup
        aliasLookupByController.computeIfAbsent(controllerName, key -> new HashMap<>())
                .put(aliasConfig.getAlias(), aliasContext);

        // Store combined key lookup
        aliasLookupByControllerAndAlias.put(buildAliasKey(controllerName, aliasConfig.getAlias()), aliasContext);

        if (aliasConfig.getSchema() != null && aliasConfig.getKind() != null) {
            schemaByControllerAndKind.put(buildSchemaKey(controllerName, aliasConfig.getKind()), aliasConfig.getSchema());
        }

        if (aliasConfig.getChildren() != null) {
            aliasConfig.getChildren().forEach(child -> registerAlias(controllerName, child, seenAliases));
        }

        if (aliasConfig.getParents() != null) {
            aliasConfig.getParents().forEach(parent -> registerAlias(controllerName, parent, seenAliases));
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
        private String kind;
        private String description;
        private String schema;
        private Boolean validationEnabled;
        private List<AliasConfig> children = new ArrayList<>();
        private List<AliasConfig> parents = new ArrayList<>();
        private Map<String, RouteConfig> routes = new HashMap<>();
    }

    @Data
    public static class RouteConfig {
        private String operationId;
        private String summary;
        private String description;
        private String schema;
        private Boolean validationEnabled;
        private List<String> tags = new ArrayList<>();
        private Map<String, Object> request;
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