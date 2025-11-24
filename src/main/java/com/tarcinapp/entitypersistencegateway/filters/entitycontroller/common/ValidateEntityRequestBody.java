package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.common;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig.KindAliasPathSingleConfig;
import com.tarcinapp.entitypersistencegateway.helpers.JsonValidationException;

import reactor.core.publisher.Mono;

@Component
public class ValidateEntityRequestBody
        extends AbstractGatewayFilterFactory<ValidateEntityRequestBody.Config> {

    @Value("${app.commonBaseSchema:#{null}}")
    private String commonBaseSchema;

    @Autowired
    private KindAliasPathsConfig kindAliasPathsConfig;

    private Logger logger = LogManager.getLogger(ValidateEntityRequestBody.class);

    private static JsonSchema baseSchema;

    private HashMap<String, JsonSchema> combinedSchemas;

    @Autowired
    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilterFactory;

    public ValidateEntityRequestBody() {
        super(ValidateEntityRequestBody.Config.class);
    }

    @EventListener(ContextRefreshedEvent.class)
    private void createCombinedSchemas() {

        // if there is no entity kind configured, there is nothing to do in this
        // operation
        if (kindAliasPathsConfig.getKindAliasPaths().size() == 0) {
            return;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        combinedSchemas = new HashMap<String, JsonSchema>();

        try {
            // init the base schema
            if (this.commonBaseSchema != null)
                baseSchema = this.getJsonSchemaFromStringContent(this.commonBaseSchema);

            // merge each given schema with base schema
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
                    
                    // Copy schema version from user schema or base schema
                    if (kindAliasPathSchema.has("$schema")) {
                        combinedSchemaNode.set("$schema", kindAliasPathSchema.get("$schema"));
                    } else if (baseSchemaNode.has("$schema")) {
                        combinedSchemaNode.set("$schema", baseSchemaNode.get("$schema"));
                    }
                    
                    combinedSchemaNode.put("type", "object");
                    
                    // Merge properties from both schemas
                    ObjectNode mergedProperties = objectMapper.createObjectNode();
                    
                    // Add base schema properties
                    if (baseSchemaNode.has("properties")) {
                        JsonNode baseProperties = baseSchemaNode.get("properties");
                        baseProperties.fields().forEachRemaining(entry -> 
                            mergedProperties.set(entry.getKey(), entry.getValue())
                        );
                    }
                    
                    // Add/override with user schema properties
                    if (kindAliasPathSchema.has("properties")) {
                        JsonNode userProperties = kindAliasPathSchema.get("properties");
                        userProperties.fields().forEachRemaining(entry -> 
                            mergedProperties.set(entry.getKey(), entry.getValue())
                        );
                    }
                    
                    combinedSchemaNode.set("properties", mergedProperties);
                    
                    // Merge required fields from both schemas
                    ArrayNode mergedRequired = objectMapper.createArrayNode();

                    if (baseSchemaNode.has("required") && baseSchemaNode.get("required").isArray()) {
                        baseSchemaNode.get("required").forEach(mergedRequired::add);
                    }

                    if (kindAliasPathSchema.has("required") && kindAliasPathSchema.get("required").isArray()) {
                        kindAliasPathSchema.get("required").forEach(req -> {
                            // Avoid duplicates
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
                    
                    // Apply additionalProperties restriction if user schema has it set to false
                    if (userRestrictsAdditionalProps) {
                        combinedSchemaNode.put("additionalProperties", false);
                    }
                    // Otherwise, additionalProperties defaults to true (allow any extra properties)

                    JsonSchema combinedSchema = getJsonSchemaFromJsonNode(combinedSchemaNode);

                    combinedSchemas.put(kindAliasPath.getName(), combinedSchema);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            logger.debug("ValidateEntityRequestBody filter is started.");

            return modifyRequestBodyFilterFactory
                    .apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                            .setRewriteFunction(String.class, String.class, (ex, payload) -> {
                                Map<String, String> uriVariables = ServerWebExchangeUtils
                                        .getUriTemplateVariables(exchange);
                                String kindAlias = uriVariables.get("kindAlias");
                                ObjectMapper objectMapper = new ObjectMapper();

                                KindAliasPathSingleConfig foundKindAliasPathConfig = kindAliasPathsConfig.getKindAliasPaths()
                                        .stream()
                                        .filter(entityKind -> Optional.ofNullable(entityKind.getAlias())
                                                .equals(Optional.ofNullable(kindAlias)))
                                        .findFirst()
                                        .orElse(null);

                                if (foundKindAliasPathConfig == null) {
                                    logger.debug("There is no kind alias configuration found for path /" + kindAlias);
                                    logger.debug("Exiting ValidateEntityRequestBody filter with 404.");

                                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                                    return Mono.empty();
                                }

                                try {
                                    // start validation here
                                    JsonNode requestJsonNode = objectMapper.readTree(payload);
                                    Set<ValidationMessage> errors = new LinkedHashSet<ValidationMessage>();
                                    JsonSchema schema = combinedSchemas.get(foundKindAliasPathConfig.getName());

                                    // for create and replace operations, perform full validation
                                    if (exchange.getRequest().getMethod() == HttpMethod.POST
                                            || exchange.getRequest().getMethod() == HttpMethod.PUT) {
                                        errors = schema.validate(requestJsonNode);
                                    }

                                    // for update operation, perform validation only over the given properties
                                    // PATCH allows partial updates, so we only validate the fields that are present
                                    if (exchange.getRequest().getMethod() == HttpMethod.PATCH) {
                                        Set<ValidationMessage> patchValidationErrors = schema.validate(requestJsonNode);

                                        if(patchValidationErrors.size() > 0) {
                                            // For PATCH operations, filter out only the "required field missing" errors
                                            // at root level. We still want to enforce additionalProperties, type validation, etc.
                                            errors = patchValidationErrors.stream()
                                                .filter(pve -> {
                                                    String code = pve.getCode();
                                                    String path = pve.getPath();
                                                    
                                                    // Skip only "required" field errors at root level (code 1028)
                                                    // PATCH doesn't require all fields to be present
                                                    if ("1028".equals(code) && "$".equals(path)) {
                                                        return false;
                                                    }
                                                    
                                                    // Keep all other errors including:
                                                    // - additionalProperties violations (code 1001)
                                                    // - type mismatches
                                                    // - format violations
                                                    // - nested validation errors
                                                    return true;
                                                })
                                                .collect(Collectors.toCollection(LinkedHashSet::new));
                                        }
                                    }

                                    // throw exception if validation error found
                                    if (errors.size() > 0) {
                                        logger.debug("Validation errors found.");

                                        // Deduplicate errors by creating a unique key from code + path + message
                                        // This handles the case where allOf causes duplicate error messages
                                        Set<ValidationMessage> uniqueErrors = errors.stream()
                                            .collect(Collectors.toMap(
                                                vm -> vm.getCode() + "|" + vm.getPath() + "|" + vm.getMessage(),
                                                vm -> vm,
                                                (existing, replacement) -> existing, // keep first occurrence
                                                LinkedHashMap::new
                                            ))
                                            .values()
                                            .stream()
                                            .collect(Collectors.toCollection(LinkedHashSet::new));

                                        // Throw an exception with deduplicated validation errors
                                        throw new JsonValidationException(uniqueErrors);
                                    }

                                    logger.debug("No validation error.");

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
                            ObjectMapper objectMapper = new ObjectMapper();

                            // Prepare a response JSON with the validation errors
                            ArrayNode detailsArray = objectMapper.createArrayNode();

                            for (ValidationMessage validationMessage : jve.getErrors()) {
                                ObjectNode detailNode = objectMapper.createObjectNode();
                                detailNode.put("code", validationMessage.getCode());
                                detailNode.put("field", validationMessage.getPath());
                                detailNode.put("message", validationMessage.getMessage());
                                // You can add more details if needed

                                detailsArray.add(detailNode);
                            }

                            ObjectNode errorNode = objectMapper.createObjectNode();
                            errorNode.put("name", "ValidationError");
                            errorNode.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
                            errorNode.put("message", "The request is not valid.");
                            errorNode.set("details", detailsArray);

                            ObjectNode responseJson = objectMapper.createObjectNode();
                            responseJson.set("error", errorNode);

                            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                            response.getHeaders().add("Content-Type", "application/json");

                            // Write the response body
                            try {
                                return response.writeWith(Mono.just(response.bufferFactory()
                                        .wrap(objectMapper.writeValueAsString(responseJson).getBytes())));
                            } catch (JsonProcessingException e1) {
                                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                                // Handle the internal server error case appropriately
                                return response.setComplete();
                            }

                        } else {
                            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                            logger.error(e);
                        }

                        return response.setComplete();
                    });
        };
    }

    protected JsonSchema getJsonSchemaFromStringContent(String schemaContent) {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(VersionFlag.V4);
        return factory.getSchema(schemaContent);
    }

    protected JsonSchema getJsonSchemaFromJsonNode(JsonNode jsonNode) {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(VersionFlag.V4);
        return factory.getSchema(jsonNode);
    }

    static class Config {

    }

}
