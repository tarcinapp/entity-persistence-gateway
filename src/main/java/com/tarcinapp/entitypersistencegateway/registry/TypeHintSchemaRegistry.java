package com.tarcinapp.entitypersistencegateway.registry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.AliasConfig;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.ControllerConfig;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.RouteConfig;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.ThroughConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * TypeHintSchemaRegistry pre-computes LoopBack 4 type hints (number / boolean)
 * from the OpenAPI alias schemas at application startup.
 *
 * Schema keys follow the same priority hierarchy used by
 * ValidateRequestBodyByKindSchema:
 *   0. hierarchy-route:{controller}:{rootKind}:{targetAlias}:{routeId}
 *   1. hierarchy:{controller}:{rootKind}:{targetAlias}
 *   2. through-route:{controller}:{rootKind}:{targetAlias}:{routeId}
 *   3. through:{controller}:{rootKind}:{targetAlias}
 *   4. {controller}:{kind}:{routeId}
 *   5. {controller}:{kind}
 *
 * Field paths use dot-notation for nested objects (e.g. "info.pageCount").
 */
@Component
@Slf4j
public class TypeHintSchemaRegistry {

    @Autowired
    private OpenApiProperties openApiProperties;

    private final ObjectMapper objectMapper;

    // schemaKey -> (dotted-field-path -> "number" | "boolean")
    // AtomicReference guarantees that in-flight readers always see a fully-built
    // map – never a partially populated one from a concurrent refresh.
    private final AtomicReference<Map<String, Map<String, String>>> registryRef =
            new AtomicReference<>(Collections.emptyMap());

    public TypeHintSchemaRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void initRegistry() {

        if (openApiProperties.getControllers() == null || openApiProperties.getControllers().isEmpty()) {
            return;
        }

        // Build into a local map so that the live registry is never partially populated
        // if ContextRefreshedEvent fires more than once (e.g. child-context refresh).
        Map<String, Map<String, String>> newRegistry = new HashMap<>();

        for (Map.Entry<String, ControllerConfig> controllerEntry : openApiProperties.getControllers().entrySet()) {
            String controllerName = controllerEntry.getKey();
            ControllerConfig controllerConfig = controllerEntry.getValue();

            if (controllerConfig.getAliases() == null) {
                continue;
            }

            for (AliasConfig aliasConfig : controllerConfig.getAliases()) {
                registerAliasHints(controllerName, aliasConfig, newRegistry);
            }
        }

        // Atomic swap – readers that were mid-lookup on the old map finish cleanly.
        registryRef.set(Collections.unmodifiableMap(newRegistry));
        log.debug("TypeHintSchemaRegistry initialized with {} schema keys.", newRegistry.size());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves a type hint for {@code fieldName} using the same 6-priority schema
     * key hierarchy as ValidateRequestBodyByKindSchema.
     *
     * @param attr      KindAliasConfigAttr from the exchange (may be null)
     * @param routeId   current route id (may be null)
     * @param fieldName dot-notation field path (e.g. "pageCount" or "info.pageCount")
     * @return an Optional containing "number" or "boolean", or empty if not found
     */
    public Optional<String> resolveHint(KindAliasConfigAttr attr, String routeId, String fieldName) {
        if (fieldName == null) {
            return Optional.empty();
        }

        // When no kind alias is configured there are no custom schemas to consult.
        if (attr == null || !attr.isKindAliasConfigured()) {
            return Optional.empty();
        }

        // PRIORITY 0: hierarchy-route-level
        String hierarchyRouteKey = attr.getHierarchyRouteSchemaKey();
        if (hierarchyRouteKey != null) {
            String hint = getHint(hierarchyRouteKey, fieldName);
            if (hint != null) {
                return Optional.of(hint);
            }
        }

        // PRIORITY 1: hierarchy-level (derive from hierarchyRouteKey if needed)
        String hierarchyKey = attr.getHierarchySchemaKey();
        if (hierarchyKey == null && hierarchyRouteKey != null && hierarchyRouteKey.startsWith("hierarchy-route:")) {
            String[] parts = hierarchyRouteKey.split(":");
            if (parts.length >= 5) {
                hierarchyKey = "hierarchy:" + parts[1] + ":" + parts[2] + ":" + parts[3];
            }
        }
        if (hierarchyKey != null) {
            String hint = getHint(hierarchyKey, fieldName);
            if (hint != null) {
                return Optional.of(hint);
            }
        }

        // PRIORITY 2: through-route-level
        String throughRouteKey = attr.getThroughRouteSchemaKey();
        if (throughRouteKey != null) {
            String hint = getHint(throughRouteKey, fieldName);
            if (hint != null) {
                return Optional.of(hint);
            }
        }

        // PRIORITY 3: through-level (derive from throughRouteKey if needed)
        String throughKey = attr.getThroughSchemaKey();
        if (throughKey == null && throughRouteKey != null && throughRouteKey.startsWith("through-route:")) {
            String[] parts = throughRouteKey.split(":");
            if (parts.length >= 5) {
                throughKey = "through:" + parts[1] + ":" + parts[2] + ":" + parts[3];
            }
        }
        if (throughKey != null) {
            String hint = getHint(throughKey, fieldName);
            if (hint != null) {
                return Optional.of(hint);
            }
        }

        // PRIORITY 4: route-level
        String controllerName = effectiveControllerName(attr);
        String kindName = attr.getKindName();
        if (controllerName != null && kindName != null && routeId != null) {
            String hint = getHint(controllerName + ":" + kindName + ":" + routeId, fieldName);
            if (hint != null) {
                return Optional.of(hint);
            }
        }

        // PRIORITY 5: alias-level
        if (controllerName != null && kindName != null) {
            String hint = getHint(controllerName + ":" + kindName, fieldName);
            if (hint != null) {
                return Optional.of(hint);
            }
        }

        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Registry construction helpers
    // -------------------------------------------------------------------------

    private void registerAliasHints(String controllerName, AliasConfig aliasConfig,
            Map<String, Map<String, String>> newRegistry) {
        if (aliasConfig == null || aliasConfig.getKind() == null) {
            return;
        }

        // Alias-level schema  (priority 5)
        if (aliasConfig.getSchema() != null) {
            parseAndStore(controllerName + ":" + aliasConfig.getKind(), aliasConfig.getSchema(), newRegistry);
        }

        // Route-level schemas (priority 4)
        if (aliasConfig.getRoutes() != null) {
            for (Map.Entry<String, RouteConfig> routeEntry : aliasConfig.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey();
                RouteConfig routeConfig = routeEntry.getValue();
                if (routeConfig.getSchema() != null) {
                    parseAndStore(controllerName + ":" + aliasConfig.getKind() + ":" + routeId,
                            routeConfig.getSchema(), newRegistry);
                }
            }
        }

        // Hierarchy schemas from children[]
        if (aliasConfig.getChildren() != null) {
            for (AliasConfig child : aliasConfig.getChildren()) {
                registerHierarchyHints(controllerName, aliasConfig.getKind(), child, newRegistry);
            }
        }

        // Hierarchy schemas from parents[]
        if (aliasConfig.getParents() != null) {
            for (AliasConfig parent : aliasConfig.getParents()) {
                registerHierarchyHints(controllerName, aliasConfig.getKind(), parent, newRegistry);
            }
        }

        // Through schemas
        ThroughConfig through = aliasConfig.getThrough();
        if (through != null) {
            registerThroughListHints(controllerName, aliasConfig.getKind(), through.getReactions(), newRegistry);
            registerThroughListHints(controllerName, aliasConfig.getKind(), through.getEntities(), newRegistry);
            registerThroughListHints(controllerName, aliasConfig.getKind(), through.getLists(), newRegistry);
        }
    }

    private void registerHierarchyHints(String controllerName, String rootKind, AliasConfig targetAlias,
            Map<String, Map<String, String>> newRegistry) {
        if (targetAlias == null || targetAlias.getAlias() == null) {
            return;
        }

        // Route-level hierarchy schemas (priority 0)
        if (targetAlias.getRoutes() != null) {
            for (Map.Entry<String, RouteConfig> routeEntry : targetAlias.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey();
                RouteConfig routeConfig = routeEntry.getValue();
                if (routeConfig.getSchema() != null) {
                    String key = "hierarchy-route:" + controllerName + ":" + rootKind + ":"
                            + targetAlias.getAlias() + ":" + routeId;
                    parseAndStore(key, routeConfig.getSchema(), newRegistry);
                }
            }
        }

        // Hierarchy-level schema (priority 1)
        if (targetAlias.getSchema() != null) {
            String key = "hierarchy:" + controllerName + ":" + rootKind + ":" + targetAlias.getAlias();
            parseAndStore(key, targetAlias.getSchema(), newRegistry);
        }
    }

    private void registerThroughListHints(String controllerName, String rootKind, List<AliasConfig> aliases,
            Map<String, Map<String, String>> newRegistry) {
        if (aliases == null) {
            return;
        }
        for (AliasConfig throughAlias : aliases) {
            registerThroughHints(controllerName, rootKind, throughAlias, newRegistry);
        }
    }

    private void registerThroughHints(String controllerName, String rootKind, AliasConfig throughAlias,
            Map<String, Map<String, String>> newRegistry) {
        if (throughAlias == null || throughAlias.getAlias() == null) {
            return;
        }

        // Route-level through schemas (priority 2)
        if (throughAlias.getRoutes() != null) {
            for (Map.Entry<String, RouteConfig> routeEntry : throughAlias.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey();
                RouteConfig routeConfig = routeEntry.getValue();
                if (routeConfig.getSchema() != null) {
                    String key = "through-route:" + controllerName + ":" + rootKind + ":"
                            + throughAlias.getAlias() + ":" + routeId;
                    parseAndStore(key, routeConfig.getSchema(), newRegistry);
                }
            }
        }

        // Through-level schema (priority 3)
        if (throughAlias.getSchema() != null) {
            String key = "through:" + controllerName + ":" + rootKind + ":" + throughAlias.getAlias();
            parseAndStore(key, throughAlias.getSchema(), newRegistry);
        }
    }

    // -------------------------------------------------------------------------
    // Schema parsing
    // -------------------------------------------------------------------------

    private void parseAndStore(String schemaKey, String schemaJson,
            Map<String, Map<String, String>> newRegistry) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            Map<String, String> hints = new HashMap<>();
            extractHints(hints, schemaNode, "");
            if (!hints.isEmpty()) {
                newRegistry.put(schemaKey, hints);
                log.debug("Registered type hints for schema key '{}': {} fields", schemaKey, hints.size());
            }
        } catch (Exception e) {
            log.warn("TypeHintSchemaRegistry: failed to parse schema for key '{}': {}", schemaKey, e.getMessage());
        }
    }

    /**
     * Recursively walk {@code schemaNode}'s "properties" and collect fields whose
     * JSON Schema type maps to "number" or "boolean".
     *
     * @param hints   accumulator
     * @param schemaNode current schema node
     * @param prefix  dot-notation prefix for the current depth (empty at root)
     */
    private void extractHints(Map<String, String> hints, JsonNode schemaNode, String prefix) {
        if (schemaNode == null || !schemaNode.isObject()) {
            return;
        }

        JsonNode propertiesNode = schemaNode.get("properties");
        if (propertiesNode == null || !propertiesNode.isObject()) {
            return;
        }

        propertiesNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldSchema = entry.getValue();
            String fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

            // Skip gateway-managed fields
            if (fieldName.startsWith("_")) {
                return;
            }

            JsonNode typeNode = fieldSchema.get("type");
            if (typeNode != null) {
                String hint = null;
                String resolvedType = null;
                if (typeNode.isTextual()) {
                    // Simple case: "type": "number"
                    resolvedType = typeNode.asText();
                    hint = mapToHint(resolvedType);
                } else if (typeNode.isArray()) {
                    // Nullable / union case: "type": ["number", "null"]
                    // Take the first element that maps to a supported hint.
                    Iterator<JsonNode> elements = typeNode.elements();
                    while (elements.hasNext()) {
                        JsonNode element = elements.next();
                        if (element.isTextual()) {
                            resolvedType = element.asText();
                            hint = mapToHint(resolvedType);
                            if (hint != null) {
                                break;
                            }
                        }
                    }
                }

                // For array fields, derive the hint from items.type so that
                // [inq] queries get correct type coercion (e.g. array of integer → "number")
                if ("array".equals(resolvedType)) {
                    JsonNode itemsNode = fieldSchema.get("items");
                    if (itemsNode != null) {
                        JsonNode itemsTypeNode = itemsNode.get("type");
                        if (itemsTypeNode != null && itemsTypeNode.isTextual()) {
                            hint = mapToHint(itemsTypeNode.asText());
                        }
                    }
                }

                if (hint != null) {
                    hints.put(fullPath, hint);
                }
            }

            // Recurse into nested object properties
            if (fieldSchema.has("properties")) {
                extractHints(hints, fieldSchema, fullPath);
            }
        });
    }

    private String mapToHint(String jsonSchemaType) {
        return switch (jsonSchemaType.toLowerCase()) {
            case "number", "integer", "float", "double" -> "number";
            case "boolean" -> "boolean";
            default -> null;
        };
    }

    // -------------------------------------------------------------------------
    // Lookup helpers
    // -------------------------------------------------------------------------

    private String getHint(String schemaKey, String fieldName) {
        Map<String, String> hints = registryRef.get().get(schemaKey);
        return (hints != null) ? hints.get(fieldName) : null;
    }

    private String effectiveControllerName(KindAliasConfigAttr attr) {
        String name = attr.getBaseControllerName();
        if (name == null || name.isBlank()) {
            name = attr.getControllerName();
        }
        if (name == null || name.isBlank()) {
            name = attr.getRecordType();
        }
        return name;
    }
}
