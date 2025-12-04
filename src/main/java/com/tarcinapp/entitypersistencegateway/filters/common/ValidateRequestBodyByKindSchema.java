package com.tarcinapp.entitypersistencegateway.filters.common;

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
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig.KindAliasPathSingleConfig;
import com.tarcinapp.entitypersistencegateway.helpers.JsonValidationException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ValidateRequestBodyByKindSchema extends AbstractGatewayFilterFactory<ValidateRequestBodyByKindSchema.Config> {

    @Value("${app.commonBaseSchema:#{null}}")
    private String commonBaseSchema;

    @Autowired
    private KindAliasPathsConfig kindAliasPathsConfig;

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

        if (kindAliasPathsConfig.getKindAliasPaths().isEmpty()) {
            return;
        }

        combinedSchemas = new HashMap<>();
        patchSchemas = new HashMap<>();

        try {
            // Init the base schema
            if (this.commonBaseSchema != null) {
                baseSchema = SCHEMA_FACTORY.getSchema(this.commonBaseSchema);
            }

            // Merge each given schema with base schema
            for (KindAliasPathSingleConfig kindAliasPath : kindAliasPathsConfig.getKindAliasPaths()) {
                String schema = kindAliasPath.getSchema();

                if (schema != null) {
                    JsonNode kindAliasPathSchema = objectMapper.readTree(schema);
                    JsonNode baseSchemaNode = baseSchema.getSchemaNode();

                    // Check if user schema has additionalProperties set to false
                    boolean userRestrictsAdditionalProps = false;
                    if (kindAliasPathSchema.has("additionalProperties")) {
                        JsonNode additionalPropsNode = kindAliasPathSchema.get("additionalProperties");
                        if (additionalPropsNode.isBoolean() && !additionalPropsNode.asBoolean()) {
                            userRestrictsAdditionalProps = true;
                        }
                    }

                    // Create a new merged schema by combining properties from both schemas
                    ObjectNode combinedSchemaNode = objectMapper.createObjectNode();

                    // Copy meta fields
                    if (kindAliasPathSchema.has("$schema"))
                        combinedSchemaNode.set("$schema", kindAliasPathSchema.get("$schema"));
                    else if (baseSchemaNode.has("$schema"))
                        combinedSchemaNode.set("$schema", baseSchemaNode.get("$schema"));

                    combinedSchemaNode.put("type", "object");

                    // Merge properties
                    ObjectNode mergedProperties = objectMapper.createObjectNode();

                    if (baseSchemaNode.has("properties")) {
                        baseSchemaNode.get("properties").fields()
                                .forEachRemaining(entry -> mergedProperties.set(entry.getKey(), entry.getValue()));
                    }

                    if (kindAliasPathSchema.has("properties")) {
                        kindAliasPathSchema.get("properties").fields()
                                .forEachRemaining(entry -> mergedProperties.set(entry.getKey(), entry.getValue()));
                    }

                    combinedSchemaNode.set("properties", mergedProperties);

                    // Merge required fields
                    ArrayNode mergedRequired = objectMapper.createArrayNode();

                    if (baseSchemaNode.has("required") && baseSchemaNode.get("required").isArray()) {
                        baseSchemaNode.get("required").forEach(mergedRequired::add);
                    }

                    if (kindAliasPathSchema.has("required") && kindAliasPathSchema.get("required").isArray()) {
                        kindAliasPathSchema.get("required").forEach(req -> {
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

                    // Build composite key using recordType and kindName
                    String schemaKey = buildSchemaKey(kindAliasPath.getRecordType(), kindAliasPath.getName());

                    // 1. Create and Store Full Schema (POST/PUT)
                    JsonSchema combinedSchema = SCHEMA_FACTORY.getSchema(combinedSchemaNode);
                    combinedSchemas.put(schemaKey, combinedSchema);

                    // 2. Create and Store Patch Schema (PATCH)
                    // We clone the node (or just verify we can modify it since we just created it)
                    // Removing "required" at the root level allows partial updates
                    ObjectNode patchSchemaNode = combinedSchemaNode.deepCopy();
                    patchSchemaNode.remove("required");

                    JsonSchema patchSchema = SCHEMA_FACTORY.getSchema(patchSchemaNode);
                    patchSchemas.put(schemaKey, patchSchema);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schemas", e);
        }
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
                                String schemaKey = buildSchemaKey(recordType, kindName);

                                try {
                                    JsonNode requestJsonNode = objectMapper.readTree(payload);
                                    Set<ValidationMessage> errors;

                                    HttpMethod method = exchange.getRequest().getMethod();

                                    // SELECT SCHEMA BASED ON METHOD
                                    if (method == HttpMethod.PATCH) {
                                        // Use the schema where root-level 'required' is removed
                                        JsonSchema schema = patchSchemas.get(schemaKey);
                                        if (schema == null) {
                                            // Fallback if no schema (shouldn't happen if initialized correctly)
                                            return Mono.just(payload);
                                        }
                                        errors = schema.validate(requestJsonNode);
                                    } else {
                                        // POST / PUT: Use full schema
                                        JsonSchema schema = combinedSchemas.get(schemaKey);
                                        if (schema == null) {
                                            return Mono.just(payload);
                                        }
                                        errors = schema.validate(requestJsonNode);
                                    }

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

    public static class Config {
    }
}
