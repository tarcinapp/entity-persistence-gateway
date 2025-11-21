package com.tarcinapp.entitypersistencegateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "app")
public class KindAliasPathsConfig {
    
    private List<KindAliasPathSingleConfig> kindAliasPaths = new ArrayList<>();

    public List<KindAliasPathSingleConfig> getKindAliasPaths() {
        return this.kindAliasPaths;
    }

    public void setKindAliasPaths(List<KindAliasPathSingleConfig> kindAliasPaths) {
        this.kindAliasPaths = kindAliasPaths;
    }

    @Override
    public String toString() {
        return "KindAliasPathsConfig [kindAliasPaths=" + kindAliasPaths + "]";
    }



    public static class KindAliasPathSingleConfig {

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
            return "KindAliasPathSingleConfig [name=" + name + ", alias=" + alias + ", schema=" + schema + "]";
        }
    }
}