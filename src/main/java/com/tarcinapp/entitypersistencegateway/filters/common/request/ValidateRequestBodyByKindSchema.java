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
import com.tarcinapp.entitypersistencegateway.oas.service.BackendSchemaService;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * ValidateRequestBodyByKindSchema validates the request payload against the
 * schema
 * defined in the OpenAPI configuration for the resolved kind.
 */
@Component
@Slf4j
public class ValidateRequestBodyByKindSchema
        extends AbstractGatewayFilterFactory<ValidateRequestBodyByKindSchema.Config> {

    @Autowired
    private OpenApiProperties openApiProperties;

    // New service for dynamic schema loading
    @Autowired
    private BackendSchemaService backendSchemaService;

    private static JsonSchema baseSchema;
    private static JsonSchema relationsBaseSchema;

    // Stores schemas for POST (Creation) - based on Backend POST schema
    private Map<String, JsonSchema> postSchemasCommon;
    private Map<String, JsonSchema> postSchemasRelations;

    // Stores schemas for PUT (Replacement) - based on Backend Resource (GET) schema
    private Map<String, JsonSchema> putSchemasCommon;
    private Map<String, JsonSchema> putSchemasRelations;

    // Stores schemas for PATCH (Partial Update) - based on Backend PATCH schema
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

        postSchemasCommon = new HashMap<>();
        postSchemasRelations = new HashMap<>();
        putSchemasCommon = new HashMap<>();
        putSchemasRelations = new HashMap<>();
        patchSchemasCommon = new HashMap<>();
        patchSchemasRelations = new HashMap<>();

        try {
            // Iterate all controllers to build combined schemas
            for (Map.Entry<String, ControllerConfig> controllerEntry : openApiProperties.getControllers().entrySet()) {
                String controllerName = controllerEntry.getKey();
                ControllerConfig controllerConfig = controllerEntry.getValue();

                if (controllerConfig.getAliases() == null) {
                    continue;
                }

                // Fetch Backend Schemas for this specific controller
                // POST Schema (Creation) -> used for POST validation
                JsonNode backendPostSchema = backendSchemaService.getBackendSchemaForController(controllerName, "POST");

                // PATCH Schema (Partial Update) -> used for PATCH validation
                JsonNode backendPatchSchema = backendSchemaService.getBackendSchemaForController(controllerName,
                        "PATCH");

                // Resource Schema (GET) -> used for PUT validation (Replacement usually
                // requires full resource)
                JsonNode backendResourceSchema = backendSchemaService.getBackendSchemaForController(controllerName,
                        "GET");

                // Fallbacks
                if (backendPostSchema == null) {
                    log.warn("No POST schema found for controller '{}'. Using empty schema.", controllerName);
                    backendPostSchema = objectMapper.createObjectNode();
                }
                if (backendPatchSchema == null) {
                    log.warn("No PATCH schema found for controller '{}'. Using empty schema.", controllerName);
                    backendPatchSchema = objectMapper.createObjectNode();
                }
                if (backendResourceSchema == null) {
                    log.warn("No Resource (GET) schema found for controller '{}'. Using empty schema.", controllerName);
                    backendResourceSchema = objectMapper.createObjectNode();
                }

                for (AliasConfig aliasConfig : controllerConfig.getAliases()) {
                    registerSchemasForAlias(controllerName, aliasConfig,
                            backendPostSchema, backendPatchSchema, backendResourceSchema, true);
                    registerSchemasForAlias(controllerName, aliasConfig,
                            backendPostSchema, backendPatchSchema, backendResourceSchema, false);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schemas", e);
        }
    }

    private void registerSchemasForAlias(String controllerName, AliasConfig aliasConfig,
            JsonNode basePostSchemaNode,
            JsonNode basePatchSchemaNode,
            JsonNode baseResourceSchemaNode,
            boolean registerToCommon)
            throws JsonProcessingException {

        if (aliasConfig == null) {
            return;
        }

        // Process Alias Schema
        boolean aliasValidationEnabled = aliasConfig.getValidationEnabled() == null
                || aliasConfig.getValidationEnabled();
        if (aliasValidationEnabled && aliasConfig.getSchema() != null && aliasConfig.getKind() != null) {
            String aliasSchemaKey = buildSchemaKey(controllerName, aliasConfig.getKind());

            // 1. Create POST Schema (Creation)
            ObjectNode aliasPostSchemaNode = mergeSchemaWithBase(aliasConfig.getSchema(), basePostSchemaNode);
            JsonNode sanitizedPostSchema = sanitizeSchema(aliasPostSchemaNode);
            JsonSchema postSchema = SCHEMA_FACTORY.getSchema(sanitizedPostSchema);
            if (registerToCommon) {
                postSchemasCommon.put(aliasSchemaKey, postSchema);
            } else {
                postSchemasRelations.put(aliasSchemaKey, postSchema);
            }

            // 2. Create PUT Schema (Replacement)
            ObjectNode aliasPutSchemaNode = mergeSchemaWithBase(aliasConfig.getSchema(), baseResourceSchemaNode);
            JsonNode sanitizedPutSchema = sanitizeSchema(aliasPutSchemaNode);
            JsonSchema putSchema = SCHEMA_FACTORY.getSchema(sanitizedPutSchema);
            if (registerToCommon) {
                putSchemasCommon.put(aliasSchemaKey, putSchema);
            } else {
                putSchemasRelations.put(aliasSchemaKey, putSchema);
            }

            // 3. Create Patch Schema
            ObjectNode aliasPatchSchemaNode = mergeSchemaWithBase(aliasConfig.getSchema(), basePatchSchemaNode);
            // Explicitly remove required fields for PATCH variants to allow partial updates
            aliasPatchSchemaNode.remove("required");
            JsonNode sanitizedPatchSchema = sanitizeSchema(aliasPatchSchemaNode);

            JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(sanitizedPatchSchema);
            if (registerToCommon) {
                patchSchemasCommon.put(aliasSchemaKey, patchSchema);
            } else {
                patchSchemasRelations.put(aliasSchemaKey, patchSchema);
            }

            log.debug("Registered alias-level schemas: {}", aliasSchemaKey);
        }

        // Process Route-level Schemas
        if (aliasConfig.getRoutes() != null) {
            for (Map.Entry<String, OpenApiProperties.RouteConfig> routeEntry : aliasConfig.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey();
                OpenApiProperties.RouteConfig routeConfig = routeEntry.getValue();

                boolean routeValidationEnabled = routeConfig.getValidationEnabled() == null
                        || routeConfig.getValidationEnabled();
                if (routeValidationEnabled && routeConfig.getSchema() != null && aliasConfig.getKind() != null) {
                    String routeSchemaKey = buildSchemaKey(controllerName, aliasConfig.getKind(), routeId);

                    // POST Version
                    ObjectNode routePostNode = mergeSchemaWithBase(routeConfig.getSchema(), basePostSchemaNode);
                    JsonNode sanitizedRoutePost = sanitizeSchema(routePostNode);
                    JsonSchema postSchema = SCHEMA_FACTORY.getSchema(sanitizedRoutePost);
                    if (registerToCommon) {
                        postSchemasCommon.put(routeSchemaKey, postSchema);
                    } else {
                        postSchemasRelations.put(routeSchemaKey, postSchema);
                    }

                    // PUT Version
                    ObjectNode routePutNode = mergeSchemaWithBase(routeConfig.getSchema(), baseResourceSchemaNode);
                    JsonNode sanitizedRoutePut = sanitizeSchema(routePutNode);
                    JsonSchema putSchema = SCHEMA_FACTORY.getSchema(sanitizedRoutePut);
                    if (registerToCommon) {
                        putSchemasCommon.put(routeSchemaKey, putSchema);
                    } else {
                        putSchemasRelations.put(routeSchemaKey, putSchema);
                    }

                    // PATCH Version
                    ObjectNode routePatchNode = mergeSchemaWithBase(routeConfig.getSchema(), basePatchSchemaNode);
                    routePatchNode.remove("required");
                    JsonNode sanitizedRoutePatch = sanitizeSchema(routePatchNode);
                    JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(sanitizedRoutePatch);
                    if (registerToCommon) {
                        patchSchemasCommon.put(routeSchemaKey, patchSchema);
                    } else {
                        patchSchemasRelations.put(routeSchemaKey, patchSchema);
                    }

                    log.debug("Registered route-level schema: {}", routeSchemaKey);
                }
            }
        }

        // Register hierarchy-level schemas
        registerHierarchySchemas(controllerName, aliasConfig,
                basePostSchemaNode, basePatchSchemaNode, baseResourceSchemaNode, registerToCommon);
    }

    /**
     * Registers hierarchy-level schemas from children[] and parents[]
     * configurations.
     * These schemas have the highest priority when resolving validation for
     * hierarchical requests.
     * 
     * Key format: "hierarchy:{controller}:{rootKind}:{targetAlias}"
     */
    /**
     * Registers hierarchy-level schemas from children[] and parents[]
     * configurations.
     * These schemas have the highest priority when resolving validation for
     * hierarchical requests.
     * 
     * Key format: "hierarchy:{controller}:{rootKind}:{targetAlias}"
     */
    private void registerHierarchySchemas(String controllerName, AliasConfig parentAliasConfig,
            JsonNode basePostSchemaNode,
            JsonNode basePatchSchemaNode,
            JsonNode baseResourceSchemaNode,
            boolean registerToCommon)
            throws JsonProcessingException {

        String rootKind = parentAliasConfig.getKind();
        if (rootKind == null) {
            return;
        }

        // Process children hierarchy schemas
        if (parentAliasConfig.getChildren() != null) {
            for (AliasConfig child : parentAliasConfig.getChildren()) {
                registerSingleHierarchySchema(controllerName, rootKind, child,
                        basePostSchemaNode, basePatchSchemaNode, baseResourceSchemaNode, registerToCommon);
            }
        }

        // Process parents hierarchy schemas
        if (parentAliasConfig.getParents() != null) {
            for (AliasConfig parent : parentAliasConfig.getParents()) {
                registerSingleHierarchySchema(controllerName, rootKind, parent,
                        basePostSchemaNode, basePatchSchemaNode, baseResourceSchemaNode, registerToCommon);
            }
        }
    }

    /**
     * Registers a single hierarchy-level schema for a child or parent alias
     * configuration.
     */
    private void registerSingleHierarchySchema(String controllerName, String rootKind,
            AliasConfig targetAliasConfig,
            JsonNode basePostSchemaNode,
            JsonNode basePatchSchemaNode,
            JsonNode baseResourceSchemaNode,
            boolean registerToCommon)
            throws JsonProcessingException {

        if (targetAliasConfig == null || targetAliasConfig.getAlias() == null) {
            return;
        }

        // Only register if hierarchy-level has its own schema defined
        if (targetAliasConfig.getSchema() != null && !targetAliasConfig.getSchema().isBlank()) {
            String hierarchySchemaKey = buildHierarchySchemaKey(controllerName, rootKind, targetAliasConfig.getAlias());

            // 1. Create POST Schema
            ObjectNode schemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), basePostSchemaNode);
            JsonNode sanitizedHierarchyPost = sanitizeSchema(schemaNode);
            JsonSchema postSchema = SCHEMA_FACTORY.getSchema(sanitizedHierarchyPost);

            if (registerToCommon) {
                postSchemasCommon.put(hierarchySchemaKey, postSchema);
            } else {
                postSchemasRelations.put(hierarchySchemaKey, postSchema);
            }

            // 2. Create PUT Schema
            ObjectNode putSchemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), baseResourceSchemaNode);
            JsonNode sanitizedHierarchyPut = sanitizeSchema(putSchemaNode);
            JsonSchema putSchema = SCHEMA_FACTORY.getSchema(sanitizedHierarchyPut);

            if (registerToCommon) {
                putSchemasCommon.put(hierarchySchemaKey, putSchema);
            } else {
                putSchemasRelations.put(hierarchySchemaKey, putSchema);
            }

            // 3. Create Patch Schema
            ObjectNode patchSchemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), basePatchSchemaNode);
            patchSchemaNode.remove("required");
            JsonNode sanitizedHierarchyPatch = sanitizeSchema(patchSchemaNode);
            JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(sanitizedHierarchyPatch);

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

            // Check if already registered (in respective maps)
            // Since we have multiple maps, need to check strictly.
            // But this block is fallback logic. Priority 3.
            Map<String, JsonSchema> targetPostMap = registerToCommon ? postSchemasCommon : postSchemasRelations;

            if (!targetPostMap.containsKey(targetAliasKey)) {

                // POST Version
                ObjectNode schemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), basePostSchemaNode);
                JsonNode sanitizedFallbackPost = sanitizeSchema(schemaNode);
                JsonSchema postSchema = SCHEMA_FACTORY.getSchema(sanitizedFallbackPost);

                if (registerToCommon) {
                    postSchemasCommon.put(targetAliasKey, postSchema);
                } else {
                    postSchemasRelations.put(targetAliasKey, postSchema);
                }

                // PUT Version
                ObjectNode putSchemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), baseResourceSchemaNode);
                JsonNode sanitizedFallbackPut = sanitizeSchema(putSchemaNode);
                JsonSchema putSchema = SCHEMA_FACTORY.getSchema(sanitizedFallbackPut);

                if (registerToCommon) {
                    putSchemasCommon.put(targetAliasKey, putSchema);
                } else {
                    putSchemasRelations.put(targetAliasKey, putSchema);
                }

                // PATCH Version
                ObjectNode patchSchemaNode = mergeSchemaWithBase(targetAliasConfig.getSchema(), basePatchSchemaNode);
                patchSchemaNode.remove("required");
                JsonNode sanitizedFallbackPatch = sanitizeSchema(patchSchemaNode);
                JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(sanitizedFallbackPatch);

                if (registerToCommon) {
                    patchSchemasCommon.put(targetAliasKey, patchSchema);
                } else {
                    patchSchemasRelations.put(targetAliasKey, patchSchema);
                }

                log.debug("Registered alias-level schema for hierarchy target: {}", targetAliasKey);
            }
        }
    }

    /**
     * Sanitizes a JSON schema by removing invalid constraint values.
     * The backend may return schemas with numeric constraints (minimum, maximum,
     * etc.)
     * that have string values, which causes the JSON schema validator to fail.
     */
    private JsonNode sanitizeSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return schema;
        }

        ObjectNode sanitized = ((ObjectNode) schema).deepCopy();

        // List of numeric constraint keywords that must have numeric values
        String[] numericConstraints = { "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
                "multipleOf", "minLength", "maxLength", "minItems", "maxItems",
                "minProperties", "maxProperties" };

        for (String constraint : numericConstraints) {
            if (sanitized.has(constraint)) {
                JsonNode value = sanitized.get(constraint);
                // Remove if not a number
                if (!value.isNumber()) {
                    sanitized.remove(constraint);
                    log.debug("Removed invalid {} constraint with non-numeric value: {}", constraint, value);
                }
            }
        }

        // Recursively sanitize nested schemas
        if (sanitized.has("properties") && sanitized.get("properties").isObject()) {
            ObjectNode properties = (ObjectNode) sanitized.get("properties");
            ObjectNode sanitizedProps = objectMapper.createObjectNode();
            properties.fields().forEachRemaining(entry -> {
                sanitizedProps.set(entry.getKey(), sanitizeSchema(entry.getValue()));
            });
            sanitized.set("properties", sanitizedProps);
        }

        // Sanitize array items
        if (sanitized.has("items")) {
            sanitized.set("items", sanitizeSchema(sanitized.get("items")));
        }

        // Sanitize allOf, anyOf, oneOf
        for (String combiner : new String[] { "allOf", "anyOf", "oneOf" }) {
            if (sanitized.has(combiner) && sanitized.get(combiner).isArray()) {
                ArrayNode combinedSchemas = objectMapper.createArrayNode();
                sanitized.get(combiner).forEach(subSchema -> combinedSchemas.add(sanitizeSchema(subSchema)));
                sanitized.set(combiner, combinedSchemas);
            }
        }

        return sanitized;
    }

    /**
     * Merges a given schema string with the base schema using property/required
     * merging.
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
     * Validates the payload against the configured schema using priority-based
     * lookup.
     * 
     * Schema Resolution Priority:
     * 1. Hierarchy-level schema (if hierarchySchemaKey is set)
     * 2. Route-level schema for TARGET kind (targetKind + routeId)
     * 3. Alias-level schema for TARGET kind (targetKind only)
     * 
     * Error handling is scoped ONLY to validation logic - downstream chain errors
     * are NOT caught here.
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
        boolean isPut = HttpMethod.PUT.equals(method);

        try {
            JsonNode requestJsonNode = objectMapper.readTree(payload);

            // Perform priority-based schema lookup
            JsonSchema schema = lookupSchema(controllerName, targetKind, hierarchySchemaKey,
                    routeId, recordType, isPatch, isPut);

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

                // Return validation error response using the generic
                // JsonResponseStatusException
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

    private JsonSchema lookupSchema(String controllerName, String targetKind, String hierarchySchemaKey,
            String routeId, String recordType, boolean isPatch, boolean isPut) {

        // Select appropriate schema maps based on record type and HTTP method
        Map<String, JsonSchema> schemaMap = selectSchemaMap(recordType, isPatch, isPut);

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

    private Map<String, JsonSchema> selectSchemaMap(String recordType, boolean isPatch, boolean isPut) {
        boolean isRelations = "relations".equalsIgnoreCase(recordType);

        if (isRelations) {
            if (isPatch)
                return patchSchemasRelations;
            if (isPut)
                return putSchemasRelations;
            return postSchemasRelations;
        } else {
            if (isPatch)
                return patchSchemasCommon;
            if (isPut)
                return putSchemasCommon;
            return postSchemasCommon;
        }
    }

    /**
     * Handles validation errors by writing a structured error response.
     * This method is called ONLY for errors from this filter's validation logic.
     */
    private Mono<String> handleValidationError(JsonValidationException jve, ServerWebExchange exchange) {
        // For validation errors, we need to short-circuit the chain and return a custom
        // response
        // We do this by throwing a ResponseStatusException that will be handled by the
        // global error handler
        // or by returning an error Mono that will propagate upstream
        try {
            ObjectNode errorResponse = createErrorResponse(jve);
            String errorJson = objectMapper.writeValueAsString(errorResponse);

            // USE GENERIC EXCEPTION: Using JsonResponseStatusException instead of custom
            // inner class
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
     * Builds a composite key for route-specific schema lookup using controllerName,
     * kindName, and routeId.
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