package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.AliasConfig;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.ControllerConfig;
import com.tarcinapp.entitypersistencegateway.exceptions.JsonResponseStatusException;
import com.tarcinapp.entitypersistencegateway.helpers.JsonValidationException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * ValidateRequestBodyByKindSchema validates the request payload against the schema 
 * defined in the OpenAPI configuration for the resolved kind.
 */
@Component
@Slf4j
public class ValidateRequestBodyByKindSchema extends AbstractGatewayFilterFactory<ValidateRequestBodyByKindSchema.Config> {

    @Value("${app.commonBaseSchema:#{null}}")
    private String commonBaseSchema;

    @Value("${app.relationsBaseSchema:#{null}}")
    private String relationsBaseSchemaString;

    @Autowired
    private OpenApiProperties openApiProperties;

    private static JsonSchema baseSchema;
    private static JsonSchema relationsBaseSchema;

    // Stores schemas with "required" fields enforced (for POST/PUT)
    private Map<String, JsonSchema> combinedSchemasCommon;
    private Map<String, JsonSchema> combinedSchemasRelations;

    // Stores schemas with root-level "required" fields removed (for PATCH)
    private Map<String, JsonSchema> patchSchemasCommon;
    private Map<String, JsonSchema> patchSchemasRelations;

    @Autowired
    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilterFactory;

    private final ObjectMapper objectMapper;

    // Use a static factory instance to avoid recreating it per request.
    // V7 corresponds to Draft-07 which matches your schema definition.
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public ValidateRequestBodyByKindSchema(ObjectMapper objectMapper) {
        super(ValidateRequestBodyByKindSchema.Config.class);
        this.objectMapper = objectMapper;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void createCombinedSchemas() {

        if (openApiProperties.getControllers() == null || openApiProperties.getControllers().isEmpty()) {
            return;
        }

        combinedSchemasCommon = new HashMap<>();
        patchSchemasCommon = new HashMap<>();
        combinedSchemasRelations = new HashMap<>();
        patchSchemasRelations = new HashMap<>();

        try {
            // Init the base schemas
            if (this.commonBaseSchema != null) {
                baseSchema = SCHEMA_FACTORY.getSchema(this.commonBaseSchema);
            }
            if (this.relationsBaseSchemaString != null) {
                relationsBaseSchema = SCHEMA_FACTORY.getSchema(this.relationsBaseSchemaString);
            }

            JsonNode defaultBaseSchemaNode = baseSchema != null ? baseSchema.getSchemaNode() : objectMapper.createObjectNode();
            JsonNode relationsBaseSchemaNode = relationsBaseSchema != null ? relationsBaseSchema.getSchemaNode() : defaultBaseSchemaNode;

            // Merge each given schema with base schema from app.oas.controllers
            for (Map.Entry<String, ControllerConfig> controllerEntry : openApiProperties.getControllers().entrySet()) {
                String controllerName = controllerEntry.getKey();
                ControllerConfig controllerConfig = controllerEntry.getValue();

                if (controllerConfig.getAliases() == null) {
                    continue;
                }

                for (AliasConfig aliasConfig : controllerConfig.getAliases()) {
                    // Register BOTH variants at initialization; runtime will choose based on route metadata
                    registerSchemasForAlias(controllerName, aliasConfig, defaultBaseSchemaNode, true);
                    registerSchemasForAlias(controllerName, aliasConfig, relationsBaseSchemaNode, false);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schemas", e);
        }
    }

    private void registerSchemasForAlias(String controllerName, AliasConfig aliasConfig, JsonNode baseSchemaNode, boolean registerToCommon)
            throws JsonProcessingException {

        if (aliasConfig == null) {
            return;
        }

        // Process Alias Schema
        boolean aliasValidationEnabled = aliasConfig.getValidationEnabled() == null || aliasConfig.getValidationEnabled();
        if (aliasValidationEnabled && aliasConfig.getSchema() != null && aliasConfig.getKind() != null) {
            ObjectNode aliasSchemaNode = mergeSchemaWithBase(aliasConfig.getSchema(), baseSchemaNode);
            String aliasSchemaKey = buildSchemaKey(controllerName, aliasConfig.getKind());
            
            JsonSchema combinedSchema = SCHEMA_FACTORY.getSchema(aliasSchemaNode);
            if (registerToCommon) {
                combinedSchemasCommon.put(aliasSchemaKey, combinedSchema);
            } else {
                combinedSchemasRelations.put(aliasSchemaKey, combinedSchema);
            }
            
            ObjectNode patchSchemaNode = aliasSchemaNode.deepCopy();
            patchSchemaNode.remove("required");
            JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
            if (registerToCommon) {
                patchSchemasCommon.put(aliasSchemaKey, patchSchema);
            } else {
                patchSchemasRelations.put(aliasSchemaKey, patchSchema);
            }
            
            log.debug("Registered alias-level schema: {}", aliasSchemaKey);
        }

        // Process Route-level Schemas
        if (aliasConfig.getRoutes() != null) {
            for (Map.Entry<String, OpenApiProperties.RouteConfig> routeEntry : aliasConfig.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey();
                OpenApiProperties.RouteConfig routeConfig = routeEntry.getValue();
                
                boolean routeValidationEnabled = routeConfig.getValidationEnabled() == null || routeConfig.getValidationEnabled();
                if (routeValidationEnabled && routeConfig.getSchema() != null && aliasConfig.getKind() != null) {
                    ObjectNode routeSchemaNode = mergeSchemaWithBase(routeConfig.getSchema(), baseSchemaNode);
                    String routeSchemaKey = buildSchemaKey(controllerName, aliasConfig.getKind(), routeId);
                    
                    JsonSchema combinedSchema = SCHEMA_FACTORY.getSchema(routeSchemaNode);
                    if (registerToCommon) {
                        combinedSchemasCommon.put(routeSchemaKey, combinedSchema);
                    } else {
                        combinedSchemasRelations.put(routeSchemaKey, combinedSchema);
                    }
                    
                    ObjectNode patchSchemaNode = routeSchemaNode.deepCopy();
                    patchSchemaNode.remove("required");
                    JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
                    if (registerToCommon) {
                        patchSchemasCommon.put(routeSchemaKey, patchSchema);
                    } else {
                        patchSchemasRelations.put(routeSchemaKey, patchSchema);
                    }
                    
                    log.debug("Registered route-level schema: {}", routeSchemaKey);
                }
            }
        }

        // Register hierarchy-level schemas from children[] and parents[]
        // These are registered with "hierarchy:" prefix for priority lookup
        registerHierarchySchemas(controllerName, aliasConfig, baseSchemaNode, registerToCommon);
    }

    /**
     * Registers hierarchy-level schemas from children[] and parents[] configurations.
     * These schemas have the highest priority when resolving validation for hierarchical requests.
     * 
     * Key format: "hierarchy:{controller}:{rootKind}:{targetAlias}"
     */
    private void registerHierarchySchemas(String controllerName, AliasConfig parentAliasConfig, 
                                          JsonNode baseSchemaNode, boolean registerToCommon)
            throws JsonProcessingException {
        
        String rootKind = parentAliasConfig.getKind();
        if (rootKind == null) {
            return;
        }

        // Process children hierarchy schemas
        if (parentAliasConfig.getChildren() != null) {
            for (AliasConfig child : parentAliasConfig.getChildren()) {
                registerSingleHierarchySchema(controllerName, rootKind, child, baseSchemaNode, registerToCommon);
            }
        }

        // Process parents hierarchy schemas
        if (parentAliasConfig.getParents() != null) {
            for (AliasConfig parent : parentAliasConfig.getParents()) {
                registerSingleHierarchySchema(controllerName, rootKind, parent, baseSchemaNode, registerToCommon);
            }
        }
    }

    /**
     * Registers a single hierarchy-level schema for a child or parent alias configuration.
     */
    private void registerSingleHierarchySchema(String controllerName, String rootKind, 
                                                AliasConfig targetAliasConfig, JsonNode baseSchemaNode, 
                                                boolean registerToCommon)
            throws JsonProcessingException {
        
        if (targetAliasConfig == null || targetAliasConfig.getAlias() == null) {
            return;
        }

        // Only register if hierarchy-level has its own schema defined
        if (targetAliasConfig.getSchema() != null && !targetAliasConfig.getSchema().isBlank()) {
            String hierarchySchemaKey = buildHierarchySchemaKey(controllerName, rootKind, targetAliasConfig.getAlias());
            
            ObjectNode schemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), baseSchemaNode);
            JsonSchema combinedSchema = SCHEMA_FACTORY.getSchema(schemaNode);
            
            if (registerToCommon) {
                combinedSchemasCommon.put(hierarchySchemaKey, combinedSchema);
            } else {
                combinedSchemasRelations.put(hierarchySchemaKey, combinedSchema);
            }
            
            // Also register PATCH variant
            ObjectNode patchSchemaNode = schemaNode.deepCopy();
            patchSchemaNode.remove("required");
            JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
            
            if (registerToCommon) {
                patchSchemasCommon.put(hierarchySchemaKey, patchSchema);
            } else {
                patchSchemasRelations.put(hierarchySchemaKey, patchSchema);
            }
            
            log.debug("Registered hierarchy-level schema: {}", hierarchySchemaKey);
        }

        // Also register the target's alias-level schema (if it has a kind)
        // This supports Priority 3 fallback: target kind's alias-level schema
        if (targetAliasConfig.getKind() != null && targetAliasConfig.getSchema() != null) {
            String targetAliasKey = buildSchemaKey(controllerName, targetAliasConfig.getKind());
            
            // Only register if not already registered by a top-level alias
            Map<String, JsonSchema> targetMap = registerToCommon ? combinedSchemasCommon : combinedSchemasRelations;
            if (!targetMap.containsKey(targetAliasKey)) {
                ObjectNode schemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), baseSchemaNode);
                JsonSchema combinedSchema = SCHEMA_FACTORY.getSchema(schemaNode);
                
                if (registerToCommon) {
                    combinedSchemasCommon.put(targetAliasKey, combinedSchema);
                } else {
                    combinedSchemasRelations.put(targetAliasKey, combinedSchema);
                }
                
                ObjectNode patchSchemaNode = schemaNode.deepCopy();
                patchSchemaNode.remove("required");
                JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
                
                if (registerToCommon) {
                    patchSchemasCommon.put(targetAliasKey, patchSchema);
                } else {
                    patchSchemasRelations.put(targetAliasKey, patchSchema);
                }
                
                log.debug("Registered alias-level schema for hierarchy target: {}", targetAliasKey);
            }
        }
        
        // NOTE: We intentionally DO NOT process targetAliasConfig.getRoutes()
        // Route-level schemas are only defined at the top-level alias, not within hierarchy configs
    }

    /**
     * Merges a given schema string with the base schema using property/required merging.
     * Returns the combined ObjectNode ready for conversion to JsonSchema.
     */
    private ObjectNode mergeSchemaWithBase(String schemaString, JsonNode baseSchemaNode)
            throws JsonProcessingException {
        
        JsonNode userSchema = objectMapper.readTree(schemaString);
        
        boolean userRestrictsAdditionalProps = false;
        if (userSchema.has("additionalProperties")) {
            JsonNode additionalPropsNode = userSchema.get("additionalProperties");
            if (additionalPropsNode.isBoolean() && !additionalPropsNode.asBoolean()) {
                userRestrictsAdditionalProps = true;
            }
        }

        ObjectNode combinedSchemaNode = objectMapper.createObjectNode();

        // Copy meta fields
        if (userSchema.has("$schema")) {
            combinedSchemaNode.set("$schema", userSchema.get("$schema"));
        } else if (baseSchemaNode.has("$schema")) {
            combinedSchemaNode.set("$schema", baseSchemaNode.get("$schema"));
        }

        combinedSchemaNode.put("type", "object");

        // Merge properties
        ObjectNode mergedProperties = objectMapper.createObjectNode();

        if (baseSchemaNode.has("properties")) {
            baseSchemaNode.get("properties").fields()
                    .forEachRemaining(entry -> mergedProperties.set(entry.getKey(), entry.getValue()));
        }

        if (userSchema.has("properties")) {
            userSchema.get("properties").fields()
                    .forEachRemaining(entry -> mergedProperties.set(entry.getKey(), entry.getValue()));
        }

        combinedSchemaNode.set("properties", mergedProperties);

        // Merge required fields
        ArrayNode mergedRequired = objectMapper.createArrayNode();

        if (baseSchemaNode.has("required") && baseSchemaNode.get("required").isArray()) {
            baseSchemaNode.get("required").forEach(mergedRequired::add);
        }

        if (userSchema.has("required") && userSchema.get("required").isArray()) {
            userSchema.get("required").forEach(req -> {
                boolean exists = false;
                for (JsonNode existing : mergedRequired) {
                    if (existing.equals(req)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    mergedRequired.add(req);
                }
            });
        }

        if (mergedRequired.size() > 0) {
            combinedSchemaNode.set("required", mergedRequired);
        }

        if (userRestrictsAdditionalProps) {
            combinedSchemaNode.put("additionalProperties", false);
        }

        return combinedSchemaNode;
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            log.debug("ValidateRequestBodyByKindSchema filter started.");

            // Early exit: Check pre-computed validation flag from resolution filters
            // This prevents expensive body buffering if validation is disabled
            KindAliasConfigAttr attr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);
            
            if (attr != null && attr.getEffectiveValidationEnabled() != null && !attr.getEffectiveValidationEnabled()) {
                log.debug("Validation disabled by effectiveValidationEnabled=false. Skipping body buffering.");
                return chain.filter(exchange);
            }

            return modifyRequestBodyFilterFactory
                    .apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                            .setRewriteFunction(String.class, String.class, (ex, payload) -> {
                                // Error handling is scoped ONLY to this validation logic
                                // Downstream chain errors will propagate upstream without being caught here
                                return validatePayload(exchange, payload);
                            }))
                    .filter(exchange, chain);
        };
    }

    /**
     * Validates the payload against the configured schema using priority-based lookup.
     * 
     * Schema Resolution Priority:
     * 1. Hierarchy-level schema (if hierarchySchemaKey is set)
     * 2. Route-level schema for TARGET kind (targetKind + routeId)
     * 3. Alias-level schema for TARGET kind (targetKind only)
     * 
     * Error handling is scoped ONLY to validation logic - downstream chain errors are NOT caught here.
     */
    private Mono<String> validatePayload(ServerWebExchange exchange, String payload) {
        KindAliasConfigAttr attr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

        if (attr == null || !attr.isKindAliasConfigured()) {
            log.debug("No kind alias configuration found in attributes. Skipping payload modification.");
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Kind configuration not found for the provided alias"));
        }

        // Get lookup parameters from the resolved attributes
        String targetKind = attr.getKindName();
        String hierarchySchemaKey = attr.getHierarchySchemaKey();
        String controllerName = attr.getBaseControllerName();
        if (controllerName == null || controllerName.isBlank()) {
            controllerName = attr.getControllerName();
        }
        if (controllerName == null || controllerName.isBlank()) {
            controllerName = attr.getRecordType();
        }
        
        // Get route info from exchange
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = (route != null) ? route.getId() : null;
        String recordType = (route != null && route.getMetadata().get("recordType") != null) 
            ? route.getMetadata().get("recordType").toString() 
            : null;

        HttpMethod method = exchange.getRequest().getMethod();
        boolean isPatch = HttpMethod.PATCH.equals(method);

        try {
            JsonNode requestJsonNode = objectMapper.readTree(payload);
            
            // Perform priority-based schema lookup
            JsonSchema schema = lookupSchema(controllerName, targetKind, hierarchySchemaKey, 
                                             routeId, recordType, isPatch);

            // If no schema found, skip validation with warning
            if (schema == null) {
                log.warn("No schema found for kind '{}' in controller '{}'. Skipping validation.", 
                         targetKind, controllerName);
                return Mono.just(payload);
            }

            Set<ValidationMessage> errors = schema.validate(requestJsonNode);

            // If validation errors exist
            if (!errors.isEmpty()) {
                log.debug("Validation errors found for kind: {}", targetKind);

                // Deduplicate errors
                Map<String, ValidationMessage> uniqueErrorsMap = errors.stream()
                        .collect(Collectors.toMap(
                                vm -> vm.getCode() + "|" + vm.getEvaluationPath().toString()
                                        + "|" + vm.getMessage(),
                                vm -> vm,
                                (existing, replacement) -> existing,
                                LinkedHashMap::new));

                Set<ValidationMessage> uniqueErrors = uniqueErrorsMap.values().stream()
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                // Return validation error response using the generic JsonResponseStatusException
                return handleValidationError(new JsonValidationException(uniqueErrors), exchange);
            }

            log.debug("No validation error.");
            return Mono.just(payload);

        } catch (JsonProcessingException e) {
            // Catches ONLY JSON parsing errors from this filter's logic
            log.error("Invalid JSON body in validation filter", e);
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON body", e));
        }
    }

    /**
     * Performs priority-based schema lookup:
     * 
     * Priority 1: Hierarchy-level schema (inline schema from children[]/parents[])
     * Priority 2: Route-level schema for TARGET kind
     * Priority 3: Alias-level schema for TARGET kind
     * 
     * @param controllerName The controller context for lookup
     * @param targetKind The target kind name (e.g., "chapter")
     * @param hierarchySchemaKey Pre-built hierarchy schema key (may be null)
     * @param routeId The current route ID for route-level lookup
     * @param recordType The record type for selecting common vs relations schema
     * @param isPatch Whether to use PATCH-specific schema (without required fields)
     * @return The resolved JsonSchema, or null if not found
     */
    private JsonSchema lookupSchema(String controllerName, String targetKind, String hierarchySchemaKey,
                                    String routeId, String recordType, boolean isPatch) {
        
        // Select appropriate schema maps based on record type and HTTP method
        Map<String, JsonSchema> schemaMap = selectSchemaMap(recordType, isPatch);
        
        // PRIORITY 1: Hierarchy-level schema (inline schema from children[]/parents[])
        if (hierarchySchemaKey != null) {
            JsonSchema schema = schemaMap.get(hierarchySchemaKey);
            if (schema != null) {
                log.debug("Using hierarchy-level schema: {}", hierarchySchemaKey);
                return schema;
            }
            log.debug("Hierarchy-level schema not found for key: {}", hierarchySchemaKey);
        }
        
        // PRIORITY 2: Route-level schema for TARGET kind
        if (routeId != null && targetKind != null) {
            String routeSchemaKey = buildSchemaKey(controllerName, targetKind, routeId);
            JsonSchema schema = schemaMap.get(routeSchemaKey);
            if (schema != null) {
                log.debug("Using route-level schema for target kind: {}", routeSchemaKey);
                return schema;
            }
            log.debug("Route-level schema not found for key: {}", routeSchemaKey);
        }
        
        // PRIORITY 3: Alias-level schema for TARGET kind
        if (targetKind != null) {
            String aliasSchemaKey = buildSchemaKey(controllerName, targetKind);
            JsonSchema schema = schemaMap.get(aliasSchemaKey);
            if (schema != null) {
                log.debug("Using alias-level schema for target kind: {}", aliasSchemaKey);
                return schema;
            }
            log.debug("Alias-level schema not found for key: {}", aliasSchemaKey);
        }
        
        // NOT FOUND - no cross-kind fallback
        return null;
    }

    /**
     * Selects the appropriate schema map based on record type and HTTP method.
     */
    private Map<String, JsonSchema> selectSchemaMap(String recordType, boolean isPatch) {
        boolean isRelations = "relations".equalsIgnoreCase(recordType);
        
        if (isRelations) {
            return isPatch ? patchSchemasRelations : combinedSchemasRelations;
        } else {
            return isPatch ? patchSchemasCommon : combinedSchemasCommon;
        }
    }

    /**
     * Handles validation errors by writing a structured error response.
     * This method is called ONLY for errors from this filter's validation logic.
     */
    private Mono<String> handleValidationError(JsonValidationException jve, ServerWebExchange exchange) {
        // For validation errors, we need to short-circuit the chain and return a custom response
        // We do this by throwing a ResponseStatusException that will be handled by the global error handler
        // or by returning an error Mono that will propagate upstream
        try {
            ObjectNode errorResponse = createErrorResponse(jve);
            String errorJson = objectMapper.writeValueAsString(errorResponse);
            
            // USE GENERIC EXCEPTION: Using JsonResponseStatusException instead of custom inner class
            return Mono.error(new JsonResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, errorJson));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize validation error response", ex);
            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Failed to process validation errors", ex));
        }
    }

    /**
     * Creates the standardized JSON error response body.
     */
    private ObjectNode createErrorResponse(JsonValidationException jve) {
        ArrayNode detailsArray = objectMapper.createArrayNode();

        for (ValidationMessage validationMessage : jve.getErrors()) {
            ObjectNode detailNode = objectMapper.createObjectNode();
            detailNode.put("code", validationMessage.getCode());
            detailNode.put("field", validationMessage.getEvaluationPath().toString());
            detailNode.put("message", validationMessage.getMessage());
            detailsArray.add(detailNode);
        }

        ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("name", "ValidationError");
        errorNode.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        errorNode.put("message", "The request is not valid.");
        errorNode.set("details", detailsArray);

        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.set("error", errorNode);

        return responseJson;
    }

    /**
     * Builds a composite key for schema lookup using controllerName and kindName.
     */
    private static String buildSchemaKey(String controllerName, String kindName) {
        return controllerName + ":" + kindName;
    }

    /**
     * Builds a composite key for route-specific schema lookup using controllerName, kindName, and routeId.
     */
    private static String buildSchemaKey(String controllerName, String kindName, String routeId) {
        return controllerName + ":" + kindName + ":" + routeId;
    }

    /**
     * Builds a hierarchy-specific schema key.
     * Format: "hierarchy:{controller}:{rootKind}:{targetAlias}"
     */
    private static String buildHierarchySchemaKey(String controllerName, String rootKind, String targetAlias) {
        return "hierarchy:" + controllerName + ":" + rootKind + ":" + targetAlias;
    }

    public static class Config {
    }
}