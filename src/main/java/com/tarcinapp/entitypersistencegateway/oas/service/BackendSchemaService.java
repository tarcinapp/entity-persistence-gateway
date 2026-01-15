package com.tarcinapp.entitypersistencegateway.oas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.oas.client.BackendOasClient;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BackendSchemaService {

    private final BackendOasClient backendOasClient;
    private final OasOrchestratorProperties properties;
    
    // Store parsed JsonNodes for specific backend operations
    // Key format: "POST /entities", "PATCH /entities/{id}"
    private final Map<String, JsonNode> operationSchemas = new ConcurrentHashMap<>();

    // Configured paths for controllers (could be injected or static as in
    // OasTransformationEngine)
    // We replicate the mapping to ensure we know which path belongs to which
    // controller
    private static final Map<String, String> CONTROLLER_PATHS = Map.of(
            "entities", "/entities",
            "lists", "/lists",
            "relations", "/relations",
            "entityReactions", "/entity-reactions",
            "listReactions", "/list-reactions",
            "entitiesThroughList", "/lists/{id}/entities",
            "listsThroughEntity", "/entities/{id}/lists",
            "reactionsThroughEntity", "/entities/{id}/reactions",
            "reactionsThroughList", "/lists/{id}/reactions");

    public BackendSchemaService(BackendOasClient backendOasClient,
            OasOrchestratorProperties properties,
            ObjectMapper objectMapper) {
        this.backendOasClient = backendOasClient;
        this.properties = properties;
    }

    /**
     * Creates an ObjectMapper configured to exclude null values.
     * This prevents null constraint values (like maximum: null) from being
     * serialized,
     * which would cause the JSON schema validator to fail.
     */
    private ObjectMapper createSchemaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        return mapper;
    }

    @PostConstruct
    public void initializeSchemas() {
        log.info("Initializing Backend Schema Service...");
        fetchAndCompileSchemas()
                .block(Duration.ofSeconds(60));
    }

    public Mono<Void> fetchAndCompileSchemas() {
        return backendOasClient.fetchRawOas()
                .retry(properties.getBackend().getRetry().getMaxAttempts())
                .doOnNext(this::indexSchemasFromOas)
                .doOnError(e -> log.error("CRITICAL: Failed to fetch backend OAS after retries", e))
                .then();
    }

    private void indexSchemasFromOas(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            log.warn("Backend OAS contains no paths!");
            return;
        }

        // iterate over all controllers we care about
        CONTROLLER_PATHS.forEach((controllerName, basePath) -> {

            // 1. Capture POST (Create) Schema
            extractAndCacheSchema(openApi, basePath, "POST", controllerName);

            // 2. Capture PATCH (Update) Schema - usually at /{id}
            String itemPath = basePath + "/{id}";
            // Some paths might already have params like /lists/{id}/entities
            // Logic to determine item path if needed, but assuming standard /{id} suffix
            // for now

            extractAndCacheSchema(openApi, itemPath, "PATCH", controllerName);
            extractAndCacheSchema(openApi, itemPath, "PUT", controllerName);

            // 3. Capture GET (Resource) Schema - usually at /{id} Response
            // This is critical for reading full resource definition (inc. read-only fields
            // like id)
            extractAndCacheResponseSchema(openApi, itemPath, "GET", controllerName);
        });

        log.info("Indexed {} backend operation schemas", operationSchemas.size());
    }

    private void extractAndCacheSchema(OpenAPI openApi, String path, String method, String controllerName) {
        io.swagger.v3.oas.models.PathItem pathItem = openApi.getPaths().get(path);
        if (pathItem == null)
            return;

        io.swagger.v3.oas.models.Operation op = null;
        switch (method) {
            case "POST":
                op = pathItem.getPost();
                break;
            case "PATCH":
                op = pathItem.getPatch();
                break;
            case "PUT":
                op = pathItem.getPut();
                break;
        }

        if (op != null && op.getRequestBody() != null) {
            io.swagger.v3.oas.models.media.Content content = op.getRequestBody().getContent();
            if (content != null && content.get("application/json") != null) {
                Schema<?> schema = content.get("application/json").getSchema();
                if (schema != null) {
                    processAndCacheSchema(openApi, schema, controllerName, method);
                }
            }
        }
    }

    private void extractAndCacheResponseSchema(OpenAPI openApi, String path, String method, String controllerName) {
        io.swagger.v3.oas.models.PathItem pathItem = openApi.getPaths().get(path);
        if (pathItem == null)
            return;

        io.swagger.v3.oas.models.Operation op = null;
        if ("GET".equals(method)) {
            op = pathItem.getGet();
        }

        if (op != null && op.getResponses() != null && op.getResponses().get("200") != null) {
            io.swagger.v3.oas.models.responses.ApiResponse response = op.getResponses().get("200");
            if (response.getContent() != null && response.getContent().get("application/json") != null) {
                Schema<?> schema = response.getContent().get("application/json").getSchema();
                if (schema != null) {
                    processAndCacheSchema(openApi, schema, controllerName, method);
                }
            }
        }
    }

    private void processAndCacheSchema(OpenAPI openApi, Schema<?> schema, String controllerName, String method) {
        try {
            // Resolve $ref if present to inline the definition
            Schema<?> resolvedSchema = resolveSchema(openApi, schema);

            // Use a dedicated ObjectMapper that excludes null values to prevent
            // "null found, array expected" errors in the JSON schema validator
            ObjectMapper schemaMapper = createSchemaObjectMapper();
            JsonNode schemaNode = schemaMapper.convertValue(resolvedSchema, JsonNode.class);
            String key = buildKey(controllerName, method);
            operationSchemas.put(key, schemaNode);
        } catch (IllegalArgumentException e) {
            log.error("Failed to convert schema for {} {} (controller: {})", method, schema, controllerName, e);
        }
    }

    /**
     * Resolves a schema $ref to its component type definition.
     * Use simple recursion (depth=1 usually sufficient for Loopback, but we handle
     * direct chains).
     */
    private Schema<?> resolveSchema(OpenAPI openApi, Schema<?> schema) {
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            if (ref.startsWith("#/components/schemas/")) {
                String schemaName = ref.substring("#/components/schemas/".length());
                if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                    Schema<?> componentSchema = openApi.getComponents().getSchemas().get(schemaName);
                    if (componentSchema != null) {
                        // Recursively resolve if the component itself is a ref
                        return resolveSchema(openApi, componentSchema);
                    }
                }
            }
        }
        return schema;
    }

    private String buildKey(String controllerName, String method) {
        return controllerName + ":" + method;
    }

    /**
     * Retrieves the backend schema for a specific controller and method.
     * e.g. "entities", "POST" -> Schema for creating an entity
     */
    public JsonNode getBackendSchemaForController(String controllerName, String method) {
        return operationSchemas.get(buildKey(controllerName, method));
    }

    /**
     * Helper to get the full components section if needed for $ref resolution
     */
    public Map<String, Schema> getComponentsSchemas() {
        // We might want to cache this too if we need to manually resolve refs
        return null; // TODO implement if needed
    }
}
