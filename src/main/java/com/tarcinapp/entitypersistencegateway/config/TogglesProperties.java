package com.tarcinapp.entitypersistencegateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.toggles")
public class TogglesProperties {

    private Section routes = new Section();
    private Section controllers = new Section();

    public Section getRoutes() {
        return routes;
    }

    public void setRoutes(Section routes) {
        this.routes = routes;
    }

    public Section getControllers() {
        return controllers;
    }

    public void setControllers(Section controllers) {
        this.controllers = controllers;
    }

    public static class Section {
        private List<String> on = new ArrayList<>();
        private List<String> off = new ArrayList<>();

        public List<String> getOn() {
            return on;
        }

        public void setOn(List<String> on) {
            this.on = on;
        }

        public List<String> getOff() {
            return off;
        }

        public void setOff(List<String> off) {
            this.off = off;
        }
    }
}
