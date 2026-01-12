package com.tarcinapp.entitypersistencegateway.unit.oas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.*;
import com.tarcinapp.entitypersistencegateway.config.TogglesProperties;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
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

/**
 * Unit tests for OasTransformationEngine.
 * Verifies baseUri prefix AND controllerBasePath are applied to all virtualized paths.
 */
@DisplayName("OasTransformationEngine Unit Tests")
class OasTransformationEngineTest {

    private OasTransformationEngine engine;
    private OpenApiProperties openApiProperties;
    private OasOrchestratorProperties orchestratorProperties;
    private TogglesProperties togglesProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        openApiProperties = new OpenApiProperties();
        orchestratorProperties = new OasOrchestratorProperties();
        togglesProperties = new TogglesProperties();
        objectMapper = new ObjectMapper();
        
        engine = new OasTransformationEngine(
            openApiProperties,
            orchestratorProperties,
            togglesProperties,
            objectMapper
        );
        
        // Set default controller base paths
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
            "_name", new StringSchema()
        ));
        return schema;
    }
}
