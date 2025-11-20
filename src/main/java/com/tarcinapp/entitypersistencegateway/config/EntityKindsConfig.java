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

    @Override
    public String toString() {
        return "EntityKindsConfig [entityKinds=" + entityKinds + "]";
    }



    public static class EntityKindsSingleConfig {

        private String name;
        private String alias;
        private String schema;

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAlias() {
            return this.alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        @Override
        public String toString() {
            return "EntityKindsSingleConfig [name=" + name + ", alias=" + alias + ", schema=" + schema + "]";
        }
    }
}