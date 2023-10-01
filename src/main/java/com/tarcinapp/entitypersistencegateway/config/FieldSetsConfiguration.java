package com.tarcinapp.entitypersistencegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties("app")
public class FieldSetsConfiguration {
    private Map<String, FieldsetProperties> fieldsets = new HashMap<>();

    public Map<String, FieldsetProperties> getFieldsets() {
        return fieldsets;
    }

    public void setFieldsets(Map<String, FieldsetProperties> fieldsets) {
        this.fieldsets = fieldsets;
    }

    public static class FieldsetProperties {
        private String show;
        private String hide;
        private List<String> showList;
        private List<String> hideList;

        public List<String> getShowList() {
            return showList;
        }

        public List<String> getHideList() {
            return hideList;
        }

        public String getShow() {
            return show;
        }

        public void setShow(String show) {
            this.show = show;

            this.showList = Arrays.stream(show.split("\\s*,\\s*"))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }

        public String getHide() {
            return hide;
        }

        public void setHide(String hide) {
            this.hide = hide;

            this.hideList = Arrays.stream(hide.split("\\s*,\\s*"))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }
    }
}
