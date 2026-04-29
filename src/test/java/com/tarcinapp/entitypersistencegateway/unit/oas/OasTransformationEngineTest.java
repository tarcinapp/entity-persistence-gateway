package com.tarcinapp.entitypersistencegateway.unit.oas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.*;
import com.tarcinapp.entitypersistencegateway.config.TogglesProperties;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import com.tarcinapp.entitypersistencegateway.oas.service.BackendSchemaService;
import com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService;
import com.tarcinapp.entitypersistencegateway.oas.transformation.OasTransformationEngine;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.Components;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OasTransformationEngine.
 * Verifies baseUri prefix AND controllerBasePath are applied to all virtualized
 * paths.
 */
@DisplayName("OasTransformationEngine Unit Tests")
class OasTransformationEngineTest {

    private OasTransformationEngine engine;
    private OpenApiProperties openApiProperties;
    private OasOrchestratorProperties orchestratorProperties;
    private TogglesProperties togglesProperties;
    private ObjectMapper objectMapper;
    private BackendSchemaService backendSchemaService;
    private RouteMetadataService routeMetadataService;

    @BeforeEach
    void setUp() {
        openApiProperties = new OpenApiProperties();
        orchestratorProperties = new OasOrchestratorProperties();
        togglesProperties = new TogglesProperties();
        objectMapper = new ObjectMapper();
        backendSchemaService = mock(BackendSchemaService.class);
        routeMetadataService = mock(RouteMetadataService.class);

        // Mock BackendSchemaService to return empty schemas (tests don't rely on schema
        // merging)
        when(backendSchemaService.getBackendSchemaForController(anyString(), anyString()))
                .thenReturn(objectMapper.createObjectNode());
        
        // Mock RouteMetadataService to return empty metadata map
        when(routeMetadataService.getAllRouteMetadata())
                .thenReturn(new HashMap<>());

        engine = new OasTransformationEngine(
                openApiProperties,
                orchestratorProperties,
                togglesProperties,
                objectMapper,
                backendSchemaService,
                routeMetadataService);

        // Set default controller base paths
        ReflectionTestUtils.setField(engine, "appShortcode", "test");
        ReflectionTestUtils.setField(engine, "entitiesBasePath", "entities");
        ReflectionTestUtils.setField(engine, "listsBasePath", "lists");
        ReflectionTestUtils.setField(engine, "relationsBasePath", "relations");
        ReflectionTestUtils.setField(engine, "entityReactionsBasePath", "entity-reactions");
        ReflectionTestUtils.setField(engine, "listReactionsBasePath", "list-reactions");
        ReflectionTestUtils.setField(engine, "entitiesThroughListBasePath", "entities");
        ReflectionTestUtils.setField(engine, "listsThroughEntityBasePath", "lists");
        ReflectionTestUtils.setField(engine, "reactionsThroughEntityBasePath", "reactions");
        ReflectionTestUtils.setField(engine, "reactionsThroughListBasePath", "reactions");
    }

    @Nested
    @DisplayName("Error Schema Tests")
    class ErrorSchemaTests {

        @Test
        @DisplayName("Should add ValidationErrorResponse and ValidationErrorDetail to components")
        void shouldAddStandardErrorSchemas() {
            configureAlias("entities", "books", "book");
            OpenAPI transformed = engine.transform(createRawOasWithEntitiesPath());

            Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                    transformed.getComponents().getSchemas();
            assertThat(schemas).containsKey("ValidationErrorResponse");
            assertThat(schemas).containsKey("ValidationErrorDetail");
        }

        @Test
        @DisplayName("Should NOT have old gateway error schemas in components")
        void shouldNotHaveOldErrorSchemas() {
            configureAlias("entities", "books", "book");
            OpenAPI transformed = engine.transform(createRawOasWithEntitiesPath());

            Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                    transformed.getComponents().getSchemas();
            assertThat(schemas).doesNotContainKey("ForbiddenErrorResponse");
            assertThat(schemas).doesNotContainKey("GatewayInternalError");
            assertThat(schemas).doesNotContainKey("GatewayValidationError");
        }

        @Test
        @DisplayName("Should include GATEWAY-NOT-FOUND in 404 response code examples")
        void shouldIncludeGatewayNotFoundIn404() {
            configureAlias("entities", "books", "book");
            OpenAPI transformed = engine.transform(createRawOasWithEntitiesPath());

            transformed.getPaths().forEach((path, pi) -> {
                if (pi.getGet() != null) {
                    io.swagger.v3.oas.models.responses.ApiResponse r404 =
                            pi.getGet().getResponses().get("404");
                    if (r404 != null && r404.getContent() != null) {
                        io.swagger.v3.oas.models.media.MediaType mt =
                                r404.getContent().get("application/json");
                        if (mt != null && mt.getExamples() != null) {
                            assertThat(mt.getExamples()).containsKey("GATEWAY-NOT-FOUND");
                        }
                    }
                }
            });
        }

        @Test
        @DisplayName("Should merge backend NOT-FOUND codes into 404 response examples")
        void shouldMergeBackendNotFoundCodesInto404() {
            configureAlias("entities", "books", "book");
            OpenAPI raw = createRawOasWithBackendErrorCodes(
                    List.of("ENTITY-NOT-FOUND", "LIST-NOT-FOUND", "ENTITY-UNIQUENESS-VIOLATION"));
            OpenAPI transformed = engine.transform(raw);

            // For an "entities" alias, per-controller filtering only includes ENTITY-* codes.
            // GATEWAY-NOT-FOUND is always appended. LIST-NOT-FOUND is correctly excluded.
            boolean checked = false;
            for (io.swagger.v3.oas.models.PathItem pi : transformed.getPaths().values()) {
                if (pi.getGet() == null) continue;
                io.swagger.v3.oas.models.responses.ApiResponse r404 =
                        pi.getGet().getResponses().get("404");
                if (r404 == null || r404.getContent() == null) continue;
                io.swagger.v3.oas.models.media.MediaType mt =
                        r404.getContent().get("application/json");
                if (mt == null || mt.getExamples() == null) continue;

                assertThat(mt.getExamples()).containsKey("ENTITY-NOT-FOUND");
                assertThat(mt.getExamples()).containsKey("GATEWAY-NOT-FOUND");
                // Per-controller filtering: entities alias should NOT include LIST-NOT-FOUND
                assertThat(mt.getExamples()).doesNotContainKey("LIST-NOT-FOUND");
                // Uniqueness violations are 409, not 404
                assertThat(mt.getExamples()).doesNotContainKey("ENTITY-UNIQUENESS-VIOLATION");
                checked = true;
                break;
            }
            assertThat(checked).as("Should have found a 404 response with code examples").isTrue();
        }

        @Test
        @DisplayName("Should include GATEWAY-INTERNAL-SERVER-ERROR in 500 response")
        void shouldIncludeGatewayInternalServerErrorIn500() {
            configureAlias("entities", "books", "book");
            OpenAPI transformed = engine.transform(createRawOasWithEntitiesPath());

            boolean checked = false;
            for (io.swagger.v3.oas.models.PathItem pi : transformed.getPaths().values()) {
                if (pi.getGet() == null) continue;
                io.swagger.v3.oas.models.responses.ApiResponse r500 =
                        pi.getGet().getResponses().get("500");
                if (r500 == null || r500.getContent() == null) continue;
                io.swagger.v3.oas.models.media.MediaType mt =
                        r500.getContent().get("application/json");
                if (mt == null || mt.getExamples() == null) continue;

                assertThat(mt.getExamples()).containsKey("GATEWAY-INTERNAL-SERVER-ERROR");
                checked = true;
                break;
            }
            assertThat(checked).as("Should have found a 500 response with code examples").isTrue();
        }

        @Test
        @DisplayName("422 response should $ref ValidationErrorResponse schema")
        void should422ReferenceValidationErrorResponse() {
            configureAlias("entities", "books", "book");
            OpenAPI raw = createRawOasWithEntitiesPath();
            // Add POST path to trigger 422
            raw.getPaths().addPathItem("/entities", createCollectionPathItem());
            OpenAPI transformed = engine.transform(raw);

            boolean checked = false;
            for (io.swagger.v3.oas.models.PathItem pi : transformed.getPaths().values()) {
                if (pi.getPost() == null) continue;
                io.swagger.v3.oas.models.responses.ApiResponse r422 =
                        pi.getPost().getResponses().get("422");
                if (r422 == null || r422.getContent() == null) continue;
                io.swagger.v3.oas.models.media.Schema schema =
                        r422.getContent().get("application/json").getSchema();
                assertThat(schema.get$ref())
                        .isEqualTo("#/components/schemas/ValidationErrorResponse");
                checked = true;
                break;
            }
            assertThat(checked).as("Should have found a POST 422 response").isTrue();
        }
    }

    @Nested
    @DisplayName("Path Generation Tests")
    class PathGenerationTests {

        @Test
        @DisplayName("Should generate correct path for entities controller: /api/v1/entities/books")
        void shouldGenerateCorrectPathForEntities() {
            // Given: Configure baseUri and entity alias
            ReflectionTestUtils.setField(engine, "inboundBaseUri", "/api/v1/");
            configureAlias("entities", "books", "book");

            // When: Transform the OAS
            OpenAPI transformed = engine.transform(createRawOasWithEntitiesPath());

            // Then: Path should be /api/v1/entities/books
            assertThat(transformed.getPaths().keySet())
                    .contains("/api/v1/entities/books", "/api/v1/entities/books/{id}");
        }

        @Test
        @DisplayName("Should generate correct path for entityReactions controller: /api/v1/entity-reactions/comments")
        void shouldGenerateCorrectPathForEntityReactions() {
            // Given: Configure baseUri and entityReaction alias
            ReflectionTestUtils.setField(engine, "inboundBaseUri", "/api/v1/");
            configureAlias("entityReactions", "comments", "comment");

            // When: Transform the OAS
            OpenAPI transformed = engine.transform(createRawOasWithEntityReactionsPath());

            // Then: Path should be /api/v1/entity-reactions/comments (NOT /api/v1/comments)
            assertThat(transformed.getPaths().keySet())
                    .contains("/api/v1/entity-reactions/comments", "/api/v1/entity-reactions/comments/{id}");

            // And: Should NOT contain the wrong path
            assertThat(transformed.getPaths().keySet())
                    .doesNotContain("/api/v1/comments");
        }

        @Test
        @DisplayName("Should generate correct path for lists controller: /api/v1/lists/playlists")
        void shouldGenerateCorrectPathForLists() {
            // Given: Configure baseUri and list alias
            ReflectionTestUtils.setField(engine, "inboundBaseUri", "/api/v1/");
            configureAlias("lists", "playlists", "playlist");

            // When: Transform the OAS
            OpenAPI transformed = engine.transform(createRawOasWithListsPath());

            // Then: Path should be /api/v1/lists/playlists
            assertThat(transformed.getPaths().keySet())
                    .contains("/api/v1/lists/playlists", "/api/v1/lists/playlists/{id}");
        }

        @Test
        @DisplayName("Should generate correct hierarchy path: /api/v1/entities/books/{id}/chapters")
        void shouldGenerateCorrectHierarchyPath() {
            // Given: Configure baseUri and entity with children
            ReflectionTestUtils.setField(engine, "inboundBaseUri", "/api/v1/");
            configureAliasWithChildren("entities", "books", "book", "chapters", "chapter");

            // When: Transform the OAS
            OpenAPI transformed = engine.transform(createRawOasWithHierarchyPaths());

            // Then: Hierarchy path should include controllerBasePath
            assertThat(transformed.getPaths().keySet())
                    .contains("/api/v1/entities/books/{id}/chapters");
        }

        @Test
        @DisplayName("Should handle empty baseUri with controllerBasePath")
        void shouldHandleEmptyBaseUri() {
            // Given: Empty baseUri but valid controller base path
            ReflectionTestUtils.setField(engine, "inboundBaseUri", "");
            configureAlias("entityReactions", "comments", "comment");

            // When
            OpenAPI transformed = engine.transform(createRawOasWithEntityReactionsPath());

            // Then: Path should still have controller base path
            assertThat(transformed.getPaths().keySet())
                    .contains("/entity-reactions/comments", "/entity-reactions/comments/{id}");
        }
    }

    // Helper methods

    private void configureAlias(String controller, String alias, String kind) {
        ControllerConfig controllerConfig = new ControllerConfig();
        AliasConfig aliasConfig = new AliasConfig();
        aliasConfig.setAlias(alias);
        aliasConfig.setKind(kind);
        aliasConfig.setDescription("Test " + alias);
        controllerConfig.setAliases(List.of(aliasConfig));

        openApiProperties.setControllers(Map.of(controller, controllerConfig));
    }

    private void configureAliasWithChildren(
            String controller, String alias, String kind, String childAlias, String childKind) {
        ControllerConfig controllerConfig = new ControllerConfig();
        AliasConfig aliasConfig = new AliasConfig();
        aliasConfig.setAlias(alias);
        aliasConfig.setKind(kind);
        aliasConfig.setDescription("Test " + alias);

        AliasConfig childConfig = new AliasConfig();
        childConfig.setAlias(childAlias);
        childConfig.setKind(childKind);
        childConfig.setDescription("Test " + childAlias);

        aliasConfig.setChildren(List.of(childConfig));
        controllerConfig.setAliases(List.of(aliasConfig));

        openApiProperties.setControllers(Map.of(controller, controllerConfig));
    }

    private OpenAPI createRawOasWithEntitiesPath() {
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();

        paths.addPathItem("/entities", createCollectionPathItem());
        paths.addPathItem("/entities/{id}", createInstancePathItem());

        openAPI.setPaths(paths);
        openAPI.setComponents(createComponents());

        return openAPI;
    }

    private OpenAPI createRawOasWithEntityReactionsPath() {
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();

        paths.addPathItem("/entity-reactions", createCollectionPathItem());
        paths.addPathItem("/entity-reactions/{id}", createInstancePathItem());

        openAPI.setPaths(paths);
        openAPI.setComponents(createComponents());

        return openAPI;
    }

    private OpenAPI createRawOasWithListsPath() {
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();

        paths.addPathItem("/lists", createCollectionPathItem());
        paths.addPathItem("/lists/{id}", createInstancePathItem());

        openAPI.setPaths(paths);
        openAPI.setComponents(createComponents());

        return openAPI;
    }

    private OpenAPI createRawOasWithHierarchyPaths() {
        OpenAPI openAPI = createRawOasWithEntitiesPath();

        PathItem childrenPath = new PathItem();
        childrenPath.setGet(createGetOperation("Find entity children"));
        childrenPath.setPost(createPostOperation("Add entity child"));
        openAPI.getPaths().addPathItem("/entities/{id}/children", childrenPath);

        return openAPI;
    }

    private PathItem createCollectionPathItem() {
        PathItem pathItem = new PathItem();
        pathItem.setGet(createGetOperation("Find all"));
        pathItem.setPost(createPostOperation("Create"));
        return pathItem;
    }

    private PathItem createInstancePathItem() {
        PathItem pathItem = new PathItem();
        pathItem.setGet(createGetOperation("Find by ID"));
        pathItem.setPut(createPutOperation("Update"));
        pathItem.setDelete(createDeleteOperation("Delete"));
        return pathItem;
    }

    private Operation createGetOperation(String summary) {
        Operation op = new Operation();
        op.setSummary(summary);
        op.setOperationId("get" + summary.replaceAll("\\s+", ""));
        op.setResponses(createResponses());
        return op;
    }

    private Operation createPostOperation(String summary) {
        Operation op = new Operation();
        op.setSummary(summary);
        op.setOperationId("post" + summary.replaceAll("\\s+", ""));
        op.setResponses(createResponses());
        return op;
    }

    private Operation createPutOperation(String summary) {
        Operation op = new Operation();
        op.setSummary(summary);
        op.setOperationId("put" + summary.replaceAll("\\s+", ""));
        op.setResponses(createResponses());
        return op;
    }

    private Operation createDeleteOperation(String summary) {
        Operation op = new Operation();
        op.setSummary(summary);
        op.setOperationId("delete" + summary.replaceAll("\\s+", ""));
        op.setResponses(createResponses());
        return op;
    }

    private ApiResponses createResponses() {
        ApiResponses responses = new ApiResponses();
        ApiResponse okResponse = new ApiResponse();
        okResponse.setDescription("Success");
        responses.addApiResponse("200", okResponse);
        return responses;
    }
    @SuppressWarnings("rawtypes")
    private Components createComponents() {
        Components components = new Components();
        Map<String, Schema> schemas = new HashMap<>();
        schemas.put("GenericEntity", createEntitySchema());
        components.setSchemas(schemas);
        return components;
    }

    @SuppressWarnings("rawtypes")
    private Schema createEntitySchema() {
        Schema<Object> schema = new Schema<>();
        schema.setType("object");
        schema.setProperties(Map.of(
                "_id", new StringSchema(),
                "_kind", new StringSchema(),
                "_name", new StringSchema()));
        return schema;
    }

    /**
     * Creates a raw OAS that includes an HttpErrorResponse schema with the given
     * error code examples in its `code` property (OAS 3.1 style).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenAPI createRawOasWithBackendErrorCodes(List<String> codes) {
        OpenAPI openAPI = createRawOasWithEntitiesPath();

        Schema<Object> codeSchema = new Schema<>();
        codeSchema.setType("string");
        codeSchema.setExamples(new ArrayList<>(codes));

        Schema<Object> httpErrSchema = new Schema<>();
        httpErrSchema.setType("object");
        httpErrSchema.setProperties(Map.of("code", codeSchema));

        openAPI.getComponents().getSchemas().put("HttpErrorResponse", httpErrSchema);
        return openAPI;
    }
}
