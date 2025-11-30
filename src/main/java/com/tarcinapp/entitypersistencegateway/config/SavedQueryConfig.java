package com.tarcinapp.entitypersistencegateway.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class SavedQueryConfig {
      private Map<String, String> queries;
}
