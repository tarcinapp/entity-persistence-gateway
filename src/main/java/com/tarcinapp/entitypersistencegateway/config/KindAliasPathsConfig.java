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
@ConfigurationProperties(prefix = "app")
@Data
public class KindAliasPathsConfig {
    
    private List<KindAliasPathSingleConfig> kindAliasPaths = new ArrayList<>();
    private Map<String, String> defaultKindPathAliasToKindMap = new HashMap<>();
    
    // Maps "recordType:kindName" to schema string for O(1) lookup
    private Map<String, String> schemasByRecordTypeAndKind = new HashMap<>();

    @PostConstruct
    public void init() {
        kindAliasPaths.forEach(config -> {
            defaultKindPathAliasToKindMap.put(config.getAlias(), config.getName());
            
            // Store schema by composite key if schema is defined
            if (config.getSchema() != null && config.getRecordType() != null && config.getName() != null) {
                String compositeKey = buildSchemaKey(config.getRecordType(), config.getName());
                schemasByRecordTypeAndKind.put(compositeKey, config.getSchema());
            }
        });
    }
    
    /**
     * Gets the schema for a specific recordType and kindName combination.
     * @param recordType The record type (e.g., "entities", "lists", "reactions")
     * @param kindName The kind name
     * @return The schema string, or null if not found
     */
    public String getSchema(String recordType, String kindName) {
        return schemasByRecordTypeAndKind.get(buildSchemaKey(recordType, kindName));
    }
    
    /**
     * Builds a composite key for schema lookup.
     */
    private static String buildSchemaKey(String recordType, String kindName) {
        return recordType + ":" + kindName;
    }

    @Data
    public static class KindAliasPathSingleConfig {
        private String alias;
        private String name;
        private String schema;
        private String recordType;
    }
}