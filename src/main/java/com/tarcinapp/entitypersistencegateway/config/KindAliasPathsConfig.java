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

    @PostConstruct
    public void init() {
        kindAliasPaths.forEach(config -> {
            defaultKindPathAliasToKindMap.put(config.getAlias(), config.getName());
        });
    }

    @Data
    public static class KindAliasPathSingleConfig {
        private String alias;
        private String name;
        private String schema;
        private String recordType;
    }
}