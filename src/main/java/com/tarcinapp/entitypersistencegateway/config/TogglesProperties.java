package com.tarcinapp.entitypersistencegateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "app.toggles")
@Data
public class TogglesProperties {

    private Section routes = new Section();
    private Section controllers = new Section();
    private Section tags = new Section();

    @Data
    public static class Section {
        private List<String> on = new ArrayList<>();
        private List<String> off = new ArrayList<>();
    }
}
