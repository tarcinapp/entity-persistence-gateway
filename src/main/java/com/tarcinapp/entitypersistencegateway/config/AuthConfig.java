package com.tarcinapp.entitypersistencegateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "app.auth")
@Data
public class AuthConfig {

    private List<Provider> providers;

    @Data
    public static class Provider {
        private String issuer;
        private String jwkSetUri;
        private String publicKey; 
        private long clockSkewSeconds = 60; // Default
        private String audience;
    }
}