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
import org.springframework.cloud.gateway.route.Route; // EKLENDİ: Route sınıfı import edildi
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
import com.tarcinapp.entitypersistencegateway.helpers.JsonValidationException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ValidateRequestBodyByKindSchema extends AbstractGatewayFilterFactory<ValidateRequestBodyByKindSchema.Config> {

    @Value("${app.commonBaseSchema:#{null}}")
    private String commonBaseSchema;

    @Autowired
    private OpenApiProperties openApiProperties;

    private static JsonSchema baseSchema;

    // Stores schemas with "required" fields enforced (for POST/PUT)
    private Map<String, JsonSchema> combinedSchemas;

    // Stores schemas with root-level "required" fields removed (for PATCH)
    private Map<String, JsonSchema> patchSchemas;

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

        combinedSchemas = new HashMap<>();
        patchSchemas = new HashMap<>();

        try {
            // Init the base schema
            if (this.commonBaseSchema != null) {
                baseSchema = SCHEMA_FACTORY.getSchema(this.commonBaseSchema);
            }

            JsonNode baseSchemaNode = baseSchema != null ? baseSchema.getSchemaNode() : objectMapper.createObjectNode();

            // Merge each given schema with base schema from app.oas.controllers
            for (Map.Entry<String, ControllerConfig> controllerEntry : openApiProperties.getControllers().entrySet()) {
                String controllerName = controllerEntry.getKey();
                ControllerConfig controllerConfig = controllerEntry.getValue();

                if (controllerConfig.getAliases() == null) {
                    continue;
                }

                for (AliasConfig aliasConfig : controllerConfig.getAliases()) {
                    registerSchemasForAlias(controllerName, aliasConfig, baseSchemaNode);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schemas", e);
        }
    }

    private void registerSchemasForAlias(String controllerName, AliasConfig aliasConfig, JsonNode baseSchemaNode)
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
            combinedSchemas.put(aliasSchemaKey, combinedSchema);
            
            ObjectNode patchSchemaNode = aliasSchemaNode.deepCopy();
            patchSchemaNode.remove("required");
            JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
            patchSchemas.put(aliasSchemaKey, patchSchema);
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
                    combinedSchemas.put(routeSchemaKey, combinedSchema);
                    
                    ObjectNode patchSchemaNode = routeSchemaNode.deepCopy();
                    patchSchemaNode.remove("required");
                    JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
                    patchSchemas.put(routeSchemaKey, patchSchema);
                }
            }
        }

        // Recursively register children under the same controller
        if (aliasConfig.getChildren() != null) {
            for (AliasConfig child : aliasConfig.getChildren()) {
                registerSchemasForAlias(controllerName, child, baseSchemaNode);
            }
        }
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

            return modifyRequestBodyFilterFactory
                    .apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                            .setRewriteFunction(String.class, String.class, (ex, payload) -> {

                                KindAliasConfigAttr kindAliasConfigAttr = exchange
                                        .getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);

                                if (kindAliasConfigAttr == null || !kindAliasConfigAttr.isKindAliasConfigured()) {
                                    log.debug(
                                            "No kind alias configuration found in attributes. Skipping payload modification.");
                                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                                            "Kind configuration not found for the provided alias"));
                                }

                                String kindName = kindAliasConfigAttr.getKindName();
                                String recordType = kindAliasConfigAttr.getRecordType();
                                
                                // DÜZELTME BAŞLANGICI: Route cast hatası giderildi.
                                Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                                String routeId = (route != null) ? route.getId() : null;
                                // DÜZELTME BİTİŞİ

                                // Resolve validation flag: Route -> Alias -> Default(true)
                                boolean validationEnabled = true;
                                OpenApiProperties.AliasContext aliasContext = openApiProperties.getAliasContext(kindAliasConfigAttr.getKindAlias());
                                if (aliasContext != null && aliasContext.getAliasConfig() != null) {
                                    AliasConfig aliasConfig = aliasContext.getAliasConfig();
                                    
                                    // Check route-level validationEnabled first
                                    if (routeId != null && aliasConfig.getRoutes() != null) {
                                        OpenApiProperties.RouteConfig routeConfig = aliasConfig.getRoutes().get(routeId);
                                        if (routeConfig != null && routeConfig.getValidationEnabled() != null) {
                                            validationEnabled = routeConfig.getValidationEnabled();
                                        } else if (aliasConfig.getValidationEnabled() != null) {
                                            // Fall back to alias-level validationEnabled
                                            validationEnabled = aliasConfig.getValidationEnabled();
                                        }
                                    } else if (aliasConfig.getValidationEnabled() != null) {
                                        // Use alias-level validationEnabled
                                        validationEnabled = aliasConfig.getValidationEnabled();
                                    }
                                }

                                // If validation is disabled, skip validation
                                if (!validationEnabled) {
                                    log.debug("Validation disabled for kind: {} at route: {}", kindName, routeId);
                                    return Mono.just(payload);
                                }

                                try {
                                    JsonNode requestJsonNode = objectMapper.readTree(payload);
                                    Set<ValidationMessage> errors;
                                    HttpMethod method = exchange.getRequest().getMethod();

                                    // 1. Try route-specific schema (if routeId available)
                                    String schemaKey = null;
                                    JsonSchema schema = null;
                                    
                                    if (routeId != null) {
                                        String routeSchemaKey = buildSchemaKey(recordType, kindName, routeId);
                                        schema = combinedSchemas.get(routeSchemaKey);
                                        if (schema != null) {
                                            schemaKey = routeSchemaKey;
                                            log.debug("Using route-specific schema for {}", routeSchemaKey);
                                        }
                                    }

                                    // 2. Fall back to alias schema
                                    if (schema == null) {
                                        schemaKey = buildSchemaKey(recordType, kindName);
                                        if (method == HttpMethod.PATCH) {
                                            schema = patchSchemas.get(schemaKey);
                                        } else {
                                            schema = combinedSchemas.get(schemaKey);
                                        }
                                    }

                                    // If no schema found, skip validation
                                    if (schema == null) {
                                        log.debug("No schema found for kind: {}, skipping validation", kindName);
                                        return Mono.just(payload);
                                    }

                                    errors = schema.validate(requestJsonNode);

                                    // If validation errors exist
                                    if (!errors.isEmpty()) {
                                        log.debug("Validation errors found for kind: {}", kindName);

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

                                        throw new JsonValidationException(uniqueErrors);
                                    }

                                    log.debug("No validation error.");
                                    return Mono.just(payload);

                                } catch (JsonProcessingException e) {
                                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON body", e);
                                }
                            }))
                    .filter(exchange, chain)
                    .onErrorResume(e -> {
                        ServerHttpResponse response = exchange.getResponse();

                        if (e instanceof ResponseStatusException) {
                            response.setStatusCode(((ResponseStatusException) e).getStatusCode());
                        } else if (e instanceof JsonValidationException) {
                            JsonValidationException jve = (JsonValidationException) e;

                            try {
                                ObjectNode errorResponse = createErrorResponse(jve);
                                byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);

                                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                                response.getHeaders().add("Content-Type", "application/json");

                                return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
                            } catch (JsonProcessingException ex) {
                                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                                return response.setComplete();
                            }
                        } else {
                            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                            log.error("Unexpected error in validation filter", e);
                        }

                        return response.setComplete();
                    });
        };
    }

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
     * Builds a composite key for schema lookup using recordType and kindName.
     */
    private static String buildSchemaKey(String recordType, String kindName) {
        return recordType + ":" + kindName;
    }

    /**
     * Builds a composite key for route-specific schema lookup using recordType, kindName, and routeId.
     */
    private static String buildSchemaKey(String recordType, String kindName, String routeId) {
        return recordType + ":" + kindName + ":" + routeId;
    }

    public static class Config {
    }
}