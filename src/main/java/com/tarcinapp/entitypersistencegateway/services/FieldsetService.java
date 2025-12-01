package com.tarcinapp.entitypersistencegateway.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.FieldsetDefinition;
import com.tarcinapp.entitypersistencegateway.config.FieldSetsConfiguration.ResourceFieldsets;

import lombok.extern.slf4j.Slf4j;

/**
 * Service for applying fieldset configurations to JSON responses.
 * Supports JSON path-based field filtering with show/hide modes.
 * Uses Jayway JsonPath library for efficient path-based operations.
 */
@Slf4j
@Service
public class FieldsetService {
    private final ObjectMapper objectMapper;
    private final Configuration jsonPathConfig;

    public FieldsetService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        
        // Configure JsonPath to use Jackson for JSON processing
        this.jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider(objectMapper))
            .mappingProvider(new JacksonMappingProvider(objectMapper))
            .build();
    }

    /**
     * Apply a fieldset definition to a JSON payload
     * 
     * @param payload JSON payload as string
     * @param fieldsetDefinition Fieldset definition with mode and fields
     * @return Modified JSON payload
     * @throws JsonProcessingException if JSON processing fails
     */
    public String applyFieldset(String payload, FieldsetDefinition fieldsetDefinition) 
            throws JsonProcessingException {
        
        if (fieldsetDefinition == null || fieldsetDefinition.getFields() == null 
                || fieldsetDefinition.getFields().isEmpty()) {
            log.debug("No fieldset definition or empty fields list, returning original payload");
            return payload;
        }

        DocumentContext doc = JsonPath.using(jsonPathConfig).parse(payload);
        
        if (fieldsetDefinition.isHideMode()) {
            return applyHideMode(doc, fieldsetDefinition.getFields());
        } else if (fieldsetDefinition.isShowMode()) {
            return applyShowMode(doc, fieldsetDefinition.getFields());
        }
        
        return payload;
    }

    /**
     * Apply hide mode using JsonPath - removes specified fields from the document
     */
    private String applyHideMode(DocumentContext doc, List<String> fieldPaths) {
        // Check if root is an array to adjust paths accordingly
        Object root = doc.read("$");
        boolean isRootArray = root instanceof List;
        
        for (String fieldPath : fieldPaths) {
            String jsonPath = convertToJsonPath(fieldPath, isRootArray);
            try {
                doc.delete(jsonPath);
                log.debug("Deleted field at path: {}", jsonPath);
            } catch (PathNotFoundException e) {
                log.debug("Path not found, skipping: {}", jsonPath);
            } catch (Exception e) {
                log.warn("Error deleting path {}: {}", jsonPath, e.getMessage());
            }
        }
        return doc.jsonString();
    }

    /**
     * Apply show mode - keeps only specified fields
     * This is more complex as JsonPath doesn't have native "keep only" functionality
     * We'll use a hybrid approach: read specified paths and rebuild the structure
     */
    private String applyShowMode(DocumentContext doc, List<String> fieldPaths) {
        try {
            // Get the root object to determine if it's an array or object
            Object root = doc.read("$");
            
            if (root instanceof List) {
                return applyShowModeToArray(doc, fieldPaths);
            } else if (root instanceof Map) {
                return applyShowModeToObject(doc, fieldPaths);
            }
            
            if (root != null) {
                log.warn("Unsupported root type for show mode: {}", root.getClass().getName());
            }
            return doc.jsonString();
        } catch (JsonProcessingException e) {
            log.error("Error processing JSON in show mode: {}", e.getMessage(), e);
            return doc.jsonString();
        } catch (PathNotFoundException e) {
            log.error("Path not found in show mode: {}", e.getMessage(), e);
            return doc.jsonString();
        }
    }

    /**
     * Apply show mode to an array of objects
     */
    private String applyShowModeToArray(DocumentContext doc, List<String> fieldPaths) 
            throws JsonProcessingException {
        List<Map<String, Object>> items = doc.read("$");
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (Map<String, Object> item : items) {
            Map<String, Object> filteredItem = extractFields(item, fieldPaths);
            result.add(filteredItem);
        }
        
        return objectMapper.writeValueAsString(result);
    }

    /**
     * Apply show mode to a single object
     */
    private String applyShowModeToObject(DocumentContext doc, List<String> fieldPaths) 
            throws JsonProcessingException {
        Map<String, Object> item = doc.read("$");
        Map<String, Object> filteredItem = extractFields(item, fieldPaths);
        return objectMapper.writeValueAsString(filteredItem);
    }

    /**
     * Extract only the specified fields from a map, preserving nested structures
     */
    private Map<String, Object> extractFields(Map<String, Object> data, List<String> fieldPaths) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> pathsToShow = buildPathsToShow(fieldPaths);
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            
            if (shouldIncludeInShow(key, fieldPaths, pathsToShow)) {
                Object value = entry.getValue();
                
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedMap = (Map<String, Object>) value;
                    List<String> nestedPaths = getNestedPaths(key, fieldPaths);
                    
                    if (!nestedPaths.isEmpty()) {
                        Map<String, Object> filteredNested = extractFields(nestedMap, nestedPaths);
                        if (!filteredNested.isEmpty()) {
                            result.put(key, filteredNested);
                        }
                    } else if (pathsToShow.contains(key)) {
                        result.put(key, value);
                    }
                } else if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;
                    List<String> nestedPaths = getNestedPathsForArray(key, fieldPaths);
                    
                    if (!nestedPaths.isEmpty()) {
                        List<Object> filteredList = extractFieldsFromList(list, nestedPaths);
                        if (!filteredList.isEmpty()) {
                            result.put(key, filteredList);
                        }
                    } else if (pathsToShow.contains(key)) {
                        result.put(key, value);
                    }
                } else {
                    result.put(key, value);
                }
            }
        }
        
        return result;
    }

    /**
     * Extract fields from a list of objects
     */
    private List<Object> extractFieldsFromList(List<Object> list, List<String> nestedPaths) {
        List<Object> result = new ArrayList<>();
        
        for (Object item : list) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) item;
                Map<String, Object> filteredItem = extractFields(itemMap, nestedPaths);
                
                if (!filteredItem.isEmpty()) {
                    result.add(filteredItem);
                }
            } else {
                result.add(item);
            }
        }
        
        return result;
    }

    /**
     * Convert simple dot notation to JsonPath format
     * Examples:
     *   "name" -> "$.name" or "$[*].name" (if root is array)
     *   "user.name" -> "$.user.name" or "$[*].user.name" (if root is array)
     *   "users[*].name" -> "$.users[*].name"
     * 
     * @param dotNotation The field path in dot notation
     * @param isRootArray Whether the root element is an array
     * @return JsonPath formatted string
     */
    private String convertToJsonPath(String dotNotation, boolean isRootArray) {
        if (dotNotation.startsWith("$")) {
            return dotNotation; // Already in JsonPath format
        }
        
        // If root is an array and the path doesn't start with array notation,
        // we need to prepend $[*]. to access fields in all array elements
        if (isRootArray && !dotNotation.startsWith("[")) {
            return "$[*]." + dotNotation;
        }
        
        return "$." + dotNotation;
    }

    /**
     * Build a set of all paths that should be shown, including parent paths
     */
    private Set<String> buildPathsToShow(List<String> fieldPaths) {
        Set<String> paths = new HashSet<>();
        
        for (String path : fieldPaths) {
            paths.add(path);
            
            // Add parent paths
            String[] parts = path.split("\\.");
            StringBuilder currentPath = new StringBuilder();
            
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) {
                    currentPath.append(".");
                }
                String part = parts[i].replace("[*]", "");
                currentPath.append(part);
                paths.add(currentPath.toString());
            }
        }
        
        return paths;
    }

    /**
     * Check if a field should be included in show mode
     */
    private boolean shouldIncludeInShow(String key, List<String> fieldPaths, Set<String> pathsToShow) {
        if (pathsToShow.contains(key)) {
            return true;
        }
        
        for (String path : fieldPaths) {
            if (path.startsWith(key + ".") || path.startsWith(key + "[")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Get nested paths for a given parent key
     */
    private List<String> getNestedPaths(String parentKey, List<String> fieldPaths) {
        List<String> nestedPaths = new ArrayList<>();
        String prefix = parentKey + ".";
        
        for (String path : fieldPaths) {
            if (path.startsWith(prefix)) {
                nestedPaths.add(path.substring(prefix.length()));
            }
        }
        
        return nestedPaths;
    }

    /**
     * Get nested paths for array elements
     */
    private List<String> getNestedPathsForArray(String parentKey, List<String> fieldPaths) {
        List<String> nestedPaths = new ArrayList<>();
        String prefix = parentKey + "[*].";
        String altPrefix = parentKey + ".";
        
        for (String path : fieldPaths) {
            if (path.startsWith(prefix)) {
                nestedPaths.add(path.substring(prefix.length()));
            } else if (path.startsWith(altPrefix)) {
                nestedPaths.add(path.substring(altPrefix.length()));
            }
        }
        
        return nestedPaths;
    }

    /**
     * Resolve the fieldset definition for a given resource type and fieldset name
     * 
     * @param config The fieldsets configuration
     * @param resourceType The resource type (entities, lists, etc.)
     * @param fieldsetName The name of the fieldset, or null to use default
     * @return The resolved fieldset definition, or null if not found
     */
    public FieldsetDefinition resolveFieldset(FieldSetsConfiguration config, 
                                               String resourceType, 
                                               String fieldsetName) {
        
        ResourceFieldsets resourceFieldsets = config.getResourceFieldsets(resourceType);
        
        // If fieldset name is provided, try to find it
        if (fieldsetName != null && !fieldsetName.isEmpty()) {
            // First check resource-specific fieldsets
            if (resourceFieldsets != null && resourceFieldsets.getFieldsets() != null) {
                FieldsetDefinition fieldset = resourceFieldsets.getFieldsets().get(fieldsetName);
                if (fieldset != null) {
                    log.debug("Found resource-specific fieldset '{}' for resource type '{}'", 
                                fieldsetName, resourceType);
                    return fieldset;
                }
            }
            
            // Fall back to global fieldsets
            if (config.getGlobal() != null) {
                FieldsetDefinition fieldset = config.getGlobal().get(fieldsetName);
                if (fieldset != null) {
                    log.debug("Found global fieldset '{}'", fieldsetName);
                    return fieldset;
                }
            }
            
            log.warn("Fieldset '{}' not found for resource type '{}'", fieldsetName, resourceType);
            return null;
        }
        
        // No fieldset name provided, try to use default
        if (resourceFieldsets != null && resourceFieldsets.getDefaultFieldset() != null) {
            String defaultFieldsetName = resourceFieldsets.getDefaultFieldset();
            
            // Prevent infinite recursion if default fieldset is empty
            if (defaultFieldsetName.isEmpty()) {
                log.debug("Default fieldset is empty for resource type '{}', returning null", resourceType);
                return null;
            }
            
            log.debug("Using default fieldset '{}' for resource type '{}'", 
                        defaultFieldsetName, resourceType);
            return resolveFieldset(config, resourceType, defaultFieldsetName);
        }
        
        log.debug("No fieldset specified and no default configured for resource type '{}'", resourceType);
        return null;
    }
}
