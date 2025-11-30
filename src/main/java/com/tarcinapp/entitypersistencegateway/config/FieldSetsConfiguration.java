package com.tarcinapp.entitypersistencegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for fieldsets that can be applied to API responses.
 * Fieldsets allow filtering fields from responses based on predefined configurations.
 * 
 * Configuration structure supports:
 * - Global fieldsets (apply to all resources)
 * - Resource-specific fieldsets (entities, lists, relations, reactions)
 * - Default fieldsets per resource type
 * - Show mode (only specified fields are returned)
 * - Hide mode (specified fields are excluded from response)
 */
@Component
@ConfigurationProperties("app.fieldsets")
@Data
public class FieldSetsConfiguration {
    
    /**
     * Global fieldset definitions that apply to all resources unless overridden
     */
    private Map<String, FieldsetDefinition> global = new HashMap<>();
    
    /**
     * Resource-specific fieldset configurations
     */
    private ResourceFieldsets entities = new ResourceFieldsets();
    private ResourceFieldsets lists = new ResourceFieldsets();
    private ResourceFieldsets relations = new ResourceFieldsets();
    private ResourceFieldsets reactions = new ResourceFieldsets();

    /**
     * Get the appropriate resource fieldsets based on resource type
     */
    public ResourceFieldsets getResourceFieldsets(String resourceType) {
        if (resourceType == null) {
            return null;
        }
        
        switch (resourceType.toLowerCase()) {
            case "entities":
                return entities;
            case "lists":
                return lists;
            case "relations":
                return relations;
            case "reactions":
                return reactions;
            default:
                return null;
        }
    }

    /**
     * Resource-specific fieldset configuration
     */
    @Data
    public static class ResourceFieldsets {
        /**
         * Default fieldset to apply when no fieldset is specified in the query
         */
        private String defaultFieldset;
        
        /**
         * Fieldset definitions specific to this resource
         */
        private Map<String, FieldsetDefinition> fieldsets = new HashMap<>();
    }

    /**
     * Definition of a fieldset with mode and field paths
     */
    @Data
    public static class FieldsetDefinition {
        /**
         * Mode: 'show' to only include listed fields, 'hide' to exclude listed fields
         */
        private FieldsetMode mode;
        
        /**
         * List of field paths (JSON paths) to show or hide
         * Examples:
         * - "_id" (top-level field)
         * - "data.user.name" (nested field)
         * - "data.users[*].address" (array elements)
         */
        private List<String> fields;

        public boolean isShowMode() {
            return mode == FieldsetMode.SHOW;
        }

        public boolean isHideMode() {
            return mode == FieldsetMode.HIDE;
        }
    }

    /**
     * Fieldset mode enumeration
     */
    public enum FieldsetMode {
        SHOW,  // Only listed fields are included in response
        HIDE   // Listed fields are excluded from response
    }
}
