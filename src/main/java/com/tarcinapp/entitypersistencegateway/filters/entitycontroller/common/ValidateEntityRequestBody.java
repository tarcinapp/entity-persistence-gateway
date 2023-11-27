package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.common;

import java.util.HashMap;
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
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig.EntityKindsSingleConfig;
import com.tarcinapp.entitypersistencegateway.helpers.JsonValidationException;

import reactor.core.publisher.Mono;

@Component
public class ValidateEntityRequestBody
        extends AbstractGatewayFilterFactory<ValidateEntityRequestBody.Config> {

    @Value("${app.schemas.anyRecordBase:#{null}}")
    private String anyRecordBaseSchema;

    @Autowired
    private EntityKindsConfig entityKindsConfig;

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
        if (entityKindsConfig.getEntityKinds().size() == 0) {
            return;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        combinedSchemas = new HashMap<String, JsonSchema>();

        try {
            // init the base schema
            if (this.anyRecordBaseSchema != null)
                baseSchema = this.getJsonSchemaFromStringContent(this.anyRecordBaseSchema);

            // merge each given schema with base schema
            for (EntityKindsSingleConfig entityKinds : entityKindsConfig.getEntityKinds()) {
                String schema = entityKinds.getSchema();

                if (schema != null) {
                    JsonNode entityKindSchema = objectMapper.readTree(schema);

                    // create new arraynode
                    ArrayNode allOfArray = objectMapper.createArrayNode();
                    allOfArray.add(baseSchema.getSchemaNode());
                    allOfArray.add(entityKindSchema);

                    ObjectNode combinedSchemaNode = objectMapper.createObjectNode();
                    combinedSchemaNode.set("allOf", allOfArray);

                    JsonSchema combinedSchema = getJsonSchemaFromJsonNode(combinedSchemaNode);

                    combinedSchemas.put(entityKinds.getName(), combinedSchema);
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
                                String kindPath = uriVariables.get("kindPath");
                                ObjectMapper objectMapper = new ObjectMapper();

                                EntityKindsSingleConfig foundEntityKindConfig = entityKindsConfig.getEntityKinds()
                                        .stream()
                                        .filter(entityKind -> Optional.ofNullable(entityKind.getPathMap())
                                                .equals(Optional.ofNullable(kindPath)))
                                        .findFirst()
                                        .orElse(null);

                                if (foundEntityKindConfig == null) {
                                    logger.debug("There is no kindPath configuration found for path /" + kindPath);
                                    logger.debug("Exiting ValidateEntityRequestBody filter with 404.");

                                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                                    return Mono.empty();
                                }

                                try {
                                    // start validation here
                                    JsonNode requestJsonNode = objectMapper.readTree(payload);
                                    Set<ValidationMessage> errors = new LinkedHashSet<ValidationMessage>();
                                    JsonSchema schema = combinedSchemas.get(foundEntityKindConfig.getName());

                                    // for create and replace operations, perform full validation
                                    if (exchange.getRequest().getMethod() == HttpMethod.POST
                                            || exchange.getRequest().getMethod() == HttpMethod.PUT) {
                                        errors = schema.validate(requestJsonNode);
                                    }

                                    // for update operation, perform validation only over the given properties
                                    if (exchange.getRequest().getMethod() == HttpMethod.PATCH) {
                                        Set<ValidationMessage> patchValidationErrors = schema.validate(requestJsonNode);

                                        if(patchValidationErrors.size() > 0) {

                                            // skip errors indicating absence of a root field
                                            errors = patchValidationErrors.stream()
                                                .filter(pve -> !("1028".equals(pve.getCode()) && "$".equals(pve.getPath())))
                                                .collect(Collectors.toCollection(LinkedHashSet::new));
                                        }
                                    }

                                    // throw exception if validation error found
                                    if (errors.size() > 0) {
                                        logger.debug("Validation errors found.");

                                        // Throw an exception with validation errors
                                        throw new JsonValidationException(errors);
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
