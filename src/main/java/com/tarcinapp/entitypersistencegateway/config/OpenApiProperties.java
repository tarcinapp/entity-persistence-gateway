package com.tarcinapp.entitypersistencegateway.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "app.oas")
@Data
public class OpenApiProperties {

    private String title;
    private String version;
    private String description;
    private Contact contact;
    private List<Server> servers = new ArrayList<>();
    private Map<String, ControllerConfig> controllers = new HashMap<>();
    private List<Tag> tags = new ArrayList<>();

    // Flattened lookups for fast access
    private final Map<String, AliasContext> aliasLookup = new HashMap<>();
    private final Map<String, String> schemaByControllerAndKind = new HashMap<>();

    @PostConstruct
    public void init() {
        aliasLookup.clear();
        schemaByControllerAndKind.clear();

        controllers.forEach((controllerName, controllerConfig) -> {
            if (controllerConfig.getAliases() != null) {
                controllerConfig.getAliases().forEach(aliasConfig -> registerAlias(controllerName, aliasConfig));
            }
        });
    }

    private void registerAlias(String controllerName, AliasConfig aliasConfig) {
        if (aliasConfig == null || aliasConfig.getAlias() == null) {
            return;
        }

        aliasLookup.put(aliasConfig.getAlias(), new AliasContext(controllerName, aliasConfig));

        if (aliasConfig.getSchema() != null && aliasConfig.getKind() != null) {
            schemaByControllerAndKind.put(buildSchemaKey(controllerName, aliasConfig.getKind()), aliasConfig.getSchema());
        }

        if (aliasConfig.getChildren() != null) {
            aliasConfig.getChildren().forEach(child -> registerAlias(controllerName, child));
        }
    }

    public AliasContext getAliasContext(String alias) {
        return aliasLookup.get(alias);
    }

    public String getSchema(String controllerName, String kind) {
        return schemaByControllerAndKind.get(buildSchemaKey(controllerName, kind));
    }

    public Map<String, AliasContext> getAliasLookup() {
        return aliasLookup;
    }

    private static String buildSchemaKey(String controllerName, String kind) {
        return controllerName + ":" + kind;
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
        private Map<String, RouteConfig> routes = new HashMap<>();
    }

    @Data
    public static class RouteConfig {
        private String operationId;
        private String summary;
        private String description;
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
