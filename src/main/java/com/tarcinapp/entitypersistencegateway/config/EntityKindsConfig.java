package com.tarcinapp.entitypersistencegateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "app")
public class EntityKindsConfig {
    
    private List<EntityKindsSingleConfig> entityKinds = new ArrayList<EntityKindsSingleConfig>();

    public List<EntityKindsSingleConfig> getEntityKinds() {
        return this.entityKinds;
    }

    public void setEntityKinds(List<EntityKindsSingleConfig> entityKinds) {
        this.entityKinds = entityKinds;
    }

    public static class EntityKindsSingleConfig {

        private String name;
        private String pathMap;
        private String schema;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPathMap() {
            return this.pathMap;
        }

        public void setPathMap(String pathMap) {
            this.pathMap = pathMap;
        }

        public String getSchema() {
            return this.schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }
    }
}