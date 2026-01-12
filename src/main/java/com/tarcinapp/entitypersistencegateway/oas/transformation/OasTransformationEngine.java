package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.*;
import com.tarcinapp.entitypersistencegateway.config.TogglesProperties;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core transformation engine that virtualizes the backend's OpenAPI specification.
 * 
 * <p>This engine transforms technical backend paths into domain-specific aliases,
 * rewrites operation IDs, summaries, and tags, and resolves hierarchical relationships.</p>
 * 
 * <h2>Transformation Rules:</h2>
 * <table border="1">
 *   <tr><th>Backend Pattern</th><th>Alias Config</th><th>Transformed Path</th></tr>
 *   <tr><td>/entities</td><td>alias: books, kind: book</td><td>/books</td></tr>
 *   <tr><td>/entities/{id}</td><td>alias: books</td><td>/books/{id}</td></tr>
 *   <tr><td>/entities/{id}/children</td><td>children: [{alias: chapters}]</td><td>/books/{id}/chapters</td></tr>
 * </table>
 * 
 * <h2>Controller Mappings:</h2>
 * <ul>
 *   <li>entities → Entity aliases (books, users, etc.)</li>
 *   <li>lists → List aliases (bookshelves, playlists, etc.)</li>
 *   <li>relations → Relation aliases (book-assignments, etc.)</li>
 *   <li>entityReactions → Reaction aliases (book-reviews, likes, etc.)</li>
 *   <li>listReactions → List reaction aliases</li>
 * </ul>
 */
@Component
@Slf4j
public class OasTransformationEngine {
    
    /**
     * Context containing dynamic request information for server URL extraction.
     * Used when static server configuration is not provided.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestContext {
        private String scheme;      // http or https
        private String host;        // hostname or IP
        private int port;           // port number (-1 means default)
        private String contextPath; // optional context path
        
        /**
         * Builds the full server URL from request components.
         */
        public String toServerUrl() {
            StringBuilder url = new StringBuilder();
            url.append(scheme != null ? scheme : "http");
            url.append("://");
            url.append(host != null ? host : "localhost");
            
            // Only append port if non-default
            if (port > 0 && port != 80 && port != 443) {
                url.append(":").append(port);
            } else if (port == -1) {
                // No port specified, use defaults based on scheme
                // Don't append anything for default ports
            }
            
            if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)) {
                if (!contextPath.startsWith("/")) {
                    url.append("/");
                }
                url.append(contextPath);
            }
            
            return url.toString();
        }
    }
    
    private final OpenApiProperties openApiProperties;
    private final OasOrchestratorProperties orchestratorProperties;
    private final TogglesProperties togglesProperties;
    private final ObjectMapper objectMapper;
    
    @Value("${app.commonBaseSchema:#{null}}")
    private String commonBaseSchema;
    
    @Value("${app.relationsBaseSchema:#{null}}")
    private String relationsBaseSchema;
    
    @Value("${app.inbound.baseUri:}")
    private String inboundBaseUri;
    
    // Inbound controller base paths from configuration
    @Value("${app.inbound.controllerBasePaths.entities:entities}")
    private String entitiesBasePath;
    
    @Value("${app.inbound.controllerBasePaths.lists:lists}")
    private String listsBasePath;
    
    @Value("${app.inbound.controllerBasePaths.relations:relations}")
    private String relationsBasePath;
    
    @Value("${app.inbound.controllerBasePaths.entityReactions:entity-reactions}")
    private String entityReactionsBasePath;
    
    @Value("${app.inbound.controllerBasePaths.listReactions:list-reactions}")
    private String listReactionsBasePath;
    
    @Value("${app.inbound.controllerBasePaths.entitiesThroughList:entities}")
    private String entitiesThroughListBasePath;
    
    @Value("${app.inbound.controllerBasePaths.listsThroughEntity:lists}")
    private String listsThroughEntityBasePath;
    
    @Value("${app.inbound.controllerBasePaths.reactionsThroughEntity:reactions}")
    private String reactionsThroughEntityBasePath;
    
    @Value("${app.inbound.controllerBasePaths.reactionsThroughList:reactions}")
    private String reactionsThroughListBasePath;
    
    // Thread-local storage for request context during transformation
    private final ThreadLocal<RequestContext> currentRequestContext = new ThreadLocal<>();
    
    // Maps backend controller names to their path prefixes
    private static final Map<String, String> CONTROLLER_PATH_PREFIXES = Map.of(
        "entities", "/entities",
        "lists", "/lists",
        "relations", "/relations",
        "entityReactions", "/entity-reactions",
        "listReactions", "/list-reactions",
        "entitiesThroughList", "/lists/{id}/entities",
        "listsThroughEntity", "/entities/{id}/lists",
        "reactionsThroughEntity", "/entities/{id}/reactions",
        "reactionsThroughList", "/lists/{id}/reactions"
    );
    
    // Pattern to match path parameters
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");
    
    // Maps backend operation prefixes to transformation patterns
    private static final Map<String, String> OPERATION_TRANSFORMS = Map.ofEntries(
        Map.entry("findEntities", "list%s"),
        Map.entry("findEntityById", "get%sById"),
        Map.entry("createEntity", "create%s"),
        Map.entry("updateEntityById", "update%s"),
        Map.entry("replaceEntityById", "replace%s"),
        Map.entry("deleteEntityById", "delete%s"),
        Map.entry("countEntities", "count%s"),
        Map.entry("findEntityChildren", "list%sChildren"),
        Map.entry("createEntityChild", "create%sChild"),
        Map.entry("findEntityParents", "list%sParents"),
        Map.entry("findLists", "list%s"),
        Map.entry("findListById", "get%sById"),
        Map.entry("createList", "create%s"),
        Map.entry("updateListById", "update%s"),
        Map.entry("replaceListById", "replace%s"),
        Map.entry("deleteListById", "delete%s"),
        Map.entry("countLists", "count%s")
    );
    
    public OasTransformationEngine(
            OpenApiProperties openApiProperties,
            OasOrchestratorProperties orchestratorProperties,
            TogglesProperties togglesProperties,
            ObjectMapper objectMapper) {
        this.openApiProperties = openApiProperties;
        this.orchestratorProperties = orchestratorProperties;
        this.togglesProperties = togglesProperties;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Transforms the raw backend OAS into a virtualized, domain-specific OAS.
     * 
     * @param rawOas The raw OpenAPI spec from the backend
     * @return Transformed OpenAPI spec with domain aliases
     */
    public OpenAPI transform(OpenAPI rawOas) {
        return transform(rawOas, null);
    }
    
    /**
     * Transforms the raw backend OAS into a virtualized, domain-specific OAS.
     * 
     * @param rawOas The raw OpenAPI spec from the backend
     * @param requestContext Dynamic request context for server URL extraction (fallback)
     * @return Transformed OpenAPI spec with domain aliases
     */
    public OpenAPI transform(OpenAPI rawOas, RequestContext requestContext) {
        log.debug("Starting OAS transformation");
        
        // Store request context for use in buildServers()
        if (requestContext != null) {
            currentRequestContext.set(requestContext);
        }
        
        try {
            OpenAPI transformed = new OpenAPI();
            
            // 1. Set API metadata from gateway configuration
            transformed.setInfo(buildInfo());
            transformed.setServers(buildServers());
            transformed.setTags(buildTags());
        
        // 2. Transform paths based on alias configurations (with route toggle filtering)
        Paths virtualizedPaths = transformPaths(rawOas.getPaths());
        transformed.setPaths(virtualizedPaths);
        
        // 3. Copy and optionally simplify schemas, then merge with base schemas
        if (rawOas.getComponents() != null) {
            transformed.setComponents(transformComponents(rawOas.getComponents()));
        }
        
        // 4. Add merged domain schemas (base + alias-specific)
        addMergedDomainSchemas(transformed);
        
        // 5. CRITICAL: Bind request bodies to domain-specific schemas (not generic NewEntity)
        bindRequestBodiesToDomainSchemas(transformed);
        
        // 6. Add gateway error responses to all operations
        addGatewayErrorResponses(transformed, rawOas);
        
        // 7. Fix broken $ref references (e.g., #/definitions/X → #/components/schemas/X)
        fixBrokenRefs(transformed);
        
        // 8. Fix schema validation keywords (uniqueItems must be on array, not items)
        fixSchemaValidationKeywords(transformed);
        
        // 9. Deduplicate parameters (name+in must be unique per operation)
        deduplicateParameters(transformed);
        
        log.info("OAS transformation complete: {} paths virtualized", 
            virtualizedPaths != null ? virtualizedPaths.size() : 0);
        
            return transformed;
        } finally {
            // Always clean up ThreadLocal
            currentRequestContext.remove();
        }
    }
    
    /**
     * Builds API info from gateway configuration.
     * MANDATORY: title and version are required per OpenAPI spec.
     */
    private Info buildInfo() {
        Info info = new Info();
        
        // Title is MANDATORY - fail loudly if not configured
        String title = openApiProperties.getTitle();
        if (title == null || title.isBlank()) {
            log.warn("API title not configured in app.oas.title - using default");
            title = "Entity Persistence Gateway API";
        }
        info.setTitle(title);
        
        // Version is MANDATORY - fail loudly if not configured
        String version = openApiProperties.getVersion();
        if (version == null || version.isBlank()) {
            log.warn("API version not configured in app.oas.version - using default");
            version = "1.0.0";
        }
        info.setVersion(version);
        
        // Description is optional but recommended
        if (openApiProperties.getDescription() != null && !openApiProperties.getDescription().isBlank()) {
            info.setDescription(openApiProperties.getDescription());
        }
        
        // Contact is optional
        if (openApiProperties.getContact() != null) {
            Contact contact = new Contact();
            if (openApiProperties.getContact().getName() != null) {
                contact.setName(openApiProperties.getContact().getName());
            }
            if (openApiProperties.getContact().getEmail() != null) {
                contact.setEmail(openApiProperties.getContact().getEmail());
            }
            if (openApiProperties.getContact().getUrl() != null) {
                contact.setUrl(openApiProperties.getContact().getUrl());
            }
            info.setContact(contact);
        }
        
        return info;
    }
    
    /**
     * Builds server list using HYBRID logic:
     * 
     * <ol>
     *   <li><b>CONDITION A (Config Mastery):</b> If app-oas.yml has populated servers list, use EXCLUSIVELY</li>
     *   <li><b>CONDITION B (Dynamic Fallback):</b> If config is empty, extract from incoming request</li>
     *   <li><b>CRITICAL:</b> NEVER return empty servers array - always provide at least one server</li>
     * </ol>
     */
    private List<Server> buildServers() {
        // CONDITION A: Check static configuration FIRST
        if (openApiProperties.getServers() != null && !openApiProperties.getServers().isEmpty()) {
            log.debug("Using {} configured servers from app-oas.yml", openApiProperties.getServers().size());
            return openApiProperties.getServers().stream()
                .map(s -> {
                    Server server = new Server();
                    server.setUrl(s.getUrl());
                    server.setDescription(s.getDescription());
                    return server;
                })
                .collect(Collectors.toList());
        }
        
        // CONDITION B: Dynamic fallback from request context
        RequestContext requestContext = currentRequestContext.get();
        if (requestContext != null) {
            String dynamicUrl = requestContext.toServerUrl();
            log.debug("No static servers configured, using dynamic URL from request: {}", dynamicUrl);
            
            Server dynamicServer = new Server();
            dynamicServer.setUrl(dynamicUrl);
            dynamicServer.setDescription("API Gateway (auto-detected)");
            return List.of(dynamicServer);
        }
        
        // CRITICAL FALLBACK: NEVER return empty - provide sensible default
        log.warn("No servers configured and no request context available - using localhost default");
        Server defaultServer = new Server();
        defaultServer.setUrl("http://localhost:8081");
        defaultServer.setDescription("API Gateway (default)");
        return List.of(defaultServer);
    }
    
    /**
     * Builds tags from gateway configuration.
     */
    private List<Tag> buildTags() {
        List<Tag> tags = new ArrayList<>();
        
        // Add tags from configuration
        if (openApiProperties.getTags() != null) {
            openApiProperties.getTags().forEach(t -> {
                Tag tag = new Tag();
                tag.setName(t.getName());
                tag.setDescription(t.getDescription());
                tags.add(tag);
            });
        }
        
        // Add tags derived from aliases
        openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
            if (controllerConfig.getAliases() != null) {
                controllerConfig.getAliases().forEach(alias -> {
                    String tagName = capitalizeFirst(alias.getAlias());
                    if (tags.stream().noneMatch(t -> t.getName().equals(tagName))) {
                        Tag tag = new Tag();
                        tag.setName(tagName);
                        tag.setDescription(alias.getDescription());
                        tags.add(tag);
                    }
                });
            }
        });
        
        return tags;
    }
    
    /**
     * Transforms all paths from the backend OAS.
     * Applies route toggle filtering based on TogglesProperties configuration.
     * Prefixes all virtualized paths with the configured base URI (e.g., /api/v1).
     */
    private Paths transformPaths(Paths rawPaths) {
        if (rawPaths == null) {
            return new Paths();
        }
        
        Paths virtualizedPaths = new Paths();
        String baseUri = getBaseUriPrefix();
        
        log.debug("Using base URI prefix for OAS paths: '{}'", baseUri);
        
        // 1. FIRST: Generate base controller routes (/entities, /lists, /relations, etc.)
        // These are the raw controller paths that work with 'kind' parameter
        generateBaseControllerRoutes(rawPaths, virtualizedPaths, baseUri);
        
        // 2. THEN: Process each controller's aliases (domain-specific paths)
        openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
            // Check if controller is disabled by toggles
            if (isControllerDisabled(controllerName)) {
                log.debug("Skipping controller '{}' - disabled by toggles", controllerName);
                return;
            }
            
            if (controllerConfig.getAliases() == null) {
                return;
            }
            
            controllerConfig.getAliases().forEach(aliasConfig -> {
                // Check if alias/tag is disabled by toggles
                String tagName = capitalizeFirst(aliasConfig.getAlias());
                if (isTagDisabled(tagName)) {
                    log.debug("Skipping alias '{}' - tag '{}' disabled by toggles", 
                        aliasConfig.getAlias(), tagName);
                    return;
                }
                
                List<TransformedPath> aliasPaths = generatePathsForAlias(
                    controllerName, aliasConfig, rawPaths
                );
                
                aliasPaths.forEach(tp -> {
                    // Check if specific route is disabled
                    if (isRouteDisabled(tp.getRouteId())) {
                        log.debug("Skipping route '{}' - disabled by toggles", tp.getRouteId());
                        return;
                    }
                    
                    // Prefix the path with base URI (e.g., /books → /api/v1/books)
                    String fullPath = baseUri + tp.getVirtualPath();
                    
                    if (virtualizedPaths.containsKey(fullPath)) {
                        log.warn("Duplicate virtualized path: {}", fullPath);
                    } else {
                        virtualizedPaths.addPathItem(fullPath, tp.getPathItem());
                    }
                });
            });
        });
        
        // Optionally include generic endpoints (also prefixed with base URI)
        if (orchestratorProperties.getTransformation().isIncludeGenericEndpoints()) {
            rawPaths.forEach((path, pathItem) -> {
                String fullPath = baseUri + path;
                if (!isPathVirtualized(fullPath, virtualizedPaths)) {
                    virtualizedPaths.addPathItem(fullPath, pathItem);
                }
            });
        }
        
        return virtualizedPaths;
    }
    
    /**
     * Generates base controller routes for all main controllers.
     * These expose the raw controller paths like:
     *   - /api/v1/entities, /api/v1/entities/{id}, /api/v1/entities/count
     *   - /api/v1/lists, /api/v1/lists/{id}, /api/v1/lists/count
     *   - /api/v1/relations, /api/v1/relations/{id}, /api/v1/relations/count
     *   - /api/v1/entity-reactions, /api/v1/entity-reactions/{id}, /api/v1/entity-reactions/count
     *   - /api/v1/list-reactions, /api/v1/list-reactions/{id}, /api/v1/list-reactions/count
     */
    private void generateBaseControllerRoutes(Paths rawPaths, Paths virtualizedPaths, String baseUri) {
        // Map of controller names to their inbound paths and backend prefixes
        Map<String, String[]> controllerMappings = Map.of(
            "entities", new String[]{entitiesBasePath, "/entities"},
            "lists", new String[]{listsBasePath, "/lists"},
            "relations", new String[]{relationsBasePath, "/relations"},
            "entityReactions", new String[]{entityReactionsBasePath, "/entity-reactions"},
            "listReactions", new String[]{listReactionsBasePath, "/list-reactions"}
        );
        
        controllerMappings.forEach((controllerName, paths) -> {
            String inboundPath = paths[0];  // e.g., "entities"
            String backendPrefix = paths[1]; // e.g., "/entities"
            
            // Check if controller is disabled by toggles
            if (isControllerDisabled(controllerName)) {
                log.debug("Skipping base routes for controller '{}' - disabled by toggles", controllerName);
                return;
            }
            
            // Build virtual path: baseUri already normalized, just append controller path
            // baseUri ends without trailing slash, so add / before inboundPath
            String virtualPathPrefix = baseUri + "/" + inboundPath;
            String tagName = capitalizeFirst(inboundPath.replace("-", " "));
            
            // Collection route: GET/POST /entities
            PathItem collectionPathItem = rawPaths.get(backendPrefix);
            if (collectionPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(collectionPathItem, controllerName, tagName, false);
                String fullPath = virtualPathPrefix;
                if (!virtualizedPaths.containsKey(fullPath)) {
                    virtualizedPaths.addPathItem(fullPath, transformed);
                    log.debug("Added base controller route: {}", fullPath);
                }
            }
            
            // Count route: GET /entities/count
            PathItem countPathItem = rawPaths.get(backendPrefix + "/count");
            if (countPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(countPathItem, controllerName, tagName, false);
                String fullPath = virtualPathPrefix + "/count";
                if (!virtualizedPaths.containsKey(fullPath)) {
                    virtualizedPaths.addPathItem(fullPath, transformed);
                    log.debug("Added base controller route: {}", fullPath);
                }
            }
            
            // Instance route: GET/PUT/PATCH/DELETE /entities/{id}
            PathItem instancePathItem = rawPaths.get(backendPrefix + "/{id}");
            if (instancePathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(instancePathItem, controllerName, tagName, true);
                String fullPath = virtualPathPrefix + "/{id}";
                if (!virtualizedPaths.containsKey(fullPath)) {
                    virtualizedPaths.addPathItem(fullPath, transformed);
                    log.debug("Added base controller route: {}", fullPath);
                }
            }
            
            // Children route: GET/POST /entities/{id}/children
            PathItem childrenPathItem = rawPaths.get(backendPrefix + "/{id}/children");
            if (childrenPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(childrenPathItem, controllerName, tagName, true);
                String fullPath = virtualPathPrefix + "/{id}/children";
                if (!virtualizedPaths.containsKey(fullPath)) {
                    virtualizedPaths.addPathItem(fullPath, transformed);
                    log.debug("Added base controller route: {}", fullPath);
                }
            }
            
            // Parents route: GET /entities/{id}/parents
            PathItem parentsPathItem = rawPaths.get(backendPrefix + "/{id}/parents");
            if (parentsPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(parentsPathItem, controllerName, tagName, true);
                String fullPath = virtualPathPrefix + "/{id}/parents";
                if (!virtualizedPaths.containsKey(fullPath)) {
                    virtualizedPaths.addPathItem(fullPath, transformed);
                    log.debug("Added base controller route: {}", fullPath);
                }
            }
        });
    }
    
    /**
     * Transforms a PathItem for base controller routes (non-aliased).
     * These operations are tagged with the controller name (e.g., "Entities").
     */
    private PathItem transformBaseControllerPathItem(PathItem original, String controllerName, String tagName, boolean isInstancePath) {
        PathItem transformed = new PathItem();
        transformed.setDescription(original.getDescription());
        
        if (original.getGet() != null) {
            transformed.setGet(transformBaseControllerOperation(original.getGet(), controllerName, tagName, "get", isInstancePath));
        }
        if (original.getPost() != null) {
            transformed.setPost(transformBaseControllerOperation(original.getPost(), controllerName, tagName, "post", isInstancePath));
        }
        if (original.getPut() != null) {
            transformed.setPut(transformBaseControllerOperation(original.getPut(), controllerName, tagName, "put", isInstancePath));
        }
        if (original.getPatch() != null) {
            transformed.setPatch(transformBaseControllerOperation(original.getPatch(), controllerName, tagName, "patch", isInstancePath));
        }
        if (original.getDelete() != null) {
            transformed.setDelete(transformBaseControllerOperation(original.getDelete(), controllerName, tagName, "delete", isInstancePath));
        }
        
        return transformed;
    }
    
    /**
     * Transforms an operation for base controller routes.
     */
    private Operation transformBaseControllerOperation(Operation original, String controllerName, String tagName, String httpMethod, boolean isInstancePath) {
        Operation transformed = new Operation();
        
        // Copy base properties
        transformed.setOperationId(original.getOperationId());
        transformed.setSummary(original.getSummary());
        transformed.setDescription(original.getDescription());
        transformed.setParameters(original.getParameters() != null 
            ? new ArrayList<>(original.getParameters()) 
            : new ArrayList<>());
        
        // Clone request body and responses to avoid shared references
        transformed.setRequestBody(cloneRequestBody(original.getRequestBody()));
        transformed.setResponses(original.getResponses() != null 
            ? cloneResponses(original.getResponses()) 
            : new ApiResponses());
        
        // Set tag for grouping
        transformed.setTags(List.of(tagName));
        
        return transformed;
    }
    
    /**
     * Generates all virtualized paths for a given alias configuration.
     * This includes base paths and hierarchical (children/parents) paths.
     */
    private List<TransformedPath> generatePathsForAlias(
            String controllerName, 
            AliasConfig aliasConfig,
            Paths rawPaths) {
        
        List<TransformedPath> result = new ArrayList<>();
        String pathPrefix = CONTROLLER_PATH_PREFIXES.get(controllerName);
        
        if (pathPrefix == null) {
            log.warn("Unknown controller name: {}", controllerName);
            return result;
        }
        
        // Generate base paths (collection and instance)
        result.addAll(generateBasePaths(pathPrefix, aliasConfig, rawPaths, controllerName, null));
        
        // Generate hierarchy paths (children and parents) - pass parent alias for correct tagging
        result.addAll(generateHierarchyPaths(pathPrefix, aliasConfig, rawPaths, controllerName, aliasConfig));
        
        return result;
    }
    
    /**
     * Generates base paths (collection: /entity-reactions/comments, instance: /entity-reactions/comments/{id}).
     * The path structure is: /{controllerBasePath}/{alias}
     * @param parentAlias If not null, this is a nested resource under the parent
     */
    private List<TransformedPath> generateBasePaths(
            String backendPrefix,
            AliasConfig aliasConfig,
            Paths rawPaths,
            String controllerName,
            AliasConfig parentAlias) {
        
        List<TransformedPath> result = new ArrayList<>();
        
        // Get the inbound controller base path (e.g., "entity-reactions" for entityReactions controller)
        String controllerBasePath = getInboundControllerBasePath(controllerName);
        
        // Build virtual prefix: /{controllerBasePath}/{alias}
        // e.g., /entity-reactions/comments (NOT just /comments)
        String virtualPrefix = "/" + controllerBasePath + "/" + aliasConfig.getAlias();
        
        // Determine the tag - use parent's alias if this is a nested resource
        String tagName = parentAlias != null 
            ? capitalizeFirst(parentAlias.getAlias()) 
            : capitalizeFirst(aliasConfig.getAlias());
        
        // Collection path: /entities → /books
        PathItem collectionPathItem = rawPaths.get(backendPrefix);
        if (collectionPathItem != null) {
            PathItem transformed = transformPathItem(
                collectionPathItem, aliasConfig, controllerName, false, tagName, parentAlias
            );
            // Inject kind filter into query parameters
            injectKindParameter(transformed, aliasConfig.getKind());
            result.add(new TransformedPath(virtualPrefix, transformed));
        }
        
        // Count path: /entities/count → /books/count
        PathItem countPathItem = rawPaths.get(backendPrefix + "/count");
        if (countPathItem != null) {
            PathItem transformed = transformPathItem(
                countPathItem, aliasConfig, controllerName, false, tagName, parentAlias
            );
            injectKindParameter(transformed, aliasConfig.getKind());
            result.add(new TransformedPath(virtualPrefix + "/count", transformed));
        }
        
        // Instance path: /entities/{id} → /books/{id}
        PathItem instancePathItem = rawPaths.get(backendPrefix + "/{id}");
        if (instancePathItem != null) {
            PathItem transformed = transformPathItem(
                instancePathItem, aliasConfig, controllerName, true, tagName, parentAlias
            );
            result.add(new TransformedPath(virtualPrefix + "/{id}", transformed));
        }
        
        return result;
    }
    
    /**
     * Generates hierarchy paths for children and parents.
     * Children/parents are nested under the parent's tag for proper grouping.
     * Path structure: /{controllerBasePath}/{parentAlias}/{id}/{childAlias}
     */
    private List<TransformedPath> generateHierarchyPaths(
            String backendPrefix,
            AliasConfig aliasConfig,
            Paths rawPaths,
            String controllerName,
            AliasConfig rootParentAlias) {
        
        List<TransformedPath> result = new ArrayList<>();
        
        // Get the inbound controller base path (e.g., "entities" for entities controller)
        String controllerBasePath = getInboundControllerBasePath(controllerName);
        
        // Build virtual prefix: /{controllerBasePath}/{alias}
        String virtualPrefix = "/" + controllerBasePath + "/" + aliasConfig.getAlias();
        
        // The tag for all nested resources is the ROOT parent's alias
        String parentTagName = capitalizeFirst(rootParentAlias.getAlias());
        
        // Children paths: /entities/{id}/children → /books/{id}/chapters
        if (aliasConfig.getChildren() != null) {
            PathItem childrenPathItem = rawPaths.get(backendPrefix + "/{id}/children");
            
            for (AliasConfig childAlias : aliasConfig.getChildren()) {
                if (childrenPathItem != null) {
                    PathItem transformed = transformPathItemForHierarchy(
                        childrenPathItem, childAlias, controllerName, 
                        parentTagName, aliasConfig, "children"
                    );
                    injectKindParameter(transformed, childAlias.getKind());
                    
                    String virtualPath = virtualPrefix + "/{id}/" + childAlias.getAlias();
                    result.add(new TransformedPath(virtualPath, transformed));
                }
                
                // Recursively process nested hierarchies (children of children)
                // Keep the same root parent for consistent tagging
                result.addAll(generateHierarchyPaths(
                    backendPrefix + "/{id}/children", 
                    childAlias, 
                    rawPaths, 
                    controllerName,
                    rootParentAlias  // Keep root parent for tag consistency
                ));
            }
        }
        
        // Parents paths: /entities/{id}/parents → /books/{id}/authors
        if (aliasConfig.getParents() != null) {
            PathItem parentsPathItem = rawPaths.get(backendPrefix + "/{id}/parents");
            
            for (AliasConfig parentAlias : aliasConfig.getParents()) {
                if (parentsPathItem != null) {
                    PathItem transformed = transformPathItemForHierarchy(
                        parentsPathItem, parentAlias, controllerName,
                        parentTagName, aliasConfig, "parents"
                    );
                    injectKindParameter(transformed, parentAlias.getKind());
                    
                    String virtualPath = virtualPrefix + "/{id}/" + parentAlias.getAlias();
                    result.add(new TransformedPath(virtualPath, transformed));
                }
            }
        }
        
        return result;
    }
    
    /**
     * Transforms a PathItem for hierarchy (children/parents) endpoints.
     * These get special summary/description treatment and are tagged under the parent.
     */
    private PathItem transformPathItemForHierarchy(
            PathItem original,
            AliasConfig childAlias,
            String controllerName,
            String parentTagName,
            AliasConfig parentAlias,
            String relationType) {
        
        PathItem transformed = new PathItem();
        
        String parentResourceName = capitalizeFirst(singularize(parentAlias.getAlias()));
        String childResourceName = capitalizeFirst(childAlias.getAlias());
        
        transformed.setDescription(String.format(
            "%s of a %s", childResourceName, parentResourceName.toLowerCase()
        ));
        
        // Transform each HTTP method's operation with hierarchy-aware summaries
        if (original.getGet() != null) {
            transformed.setGet(transformHierarchyOperation(
                original.getGet(), childAlias, parentAlias, parentTagName, "get", relationType
            ));
        }
        if (original.getPost() != null) {
            transformed.setPost(transformHierarchyOperation(
                original.getPost(), childAlias, parentAlias, parentTagName, "post", relationType
            ));
        }
        if (original.getPut() != null) {
            transformed.setPut(transformHierarchyOperation(
                original.getPut(), childAlias, parentAlias, parentTagName, "put", relationType
            ));
        }
        if (original.getPatch() != null) {
            transformed.setPatch(transformHierarchyOperation(
                original.getPatch(), childAlias, parentAlias, parentTagName, "patch", relationType
            ));
        }
        if (original.getDelete() != null) {
            transformed.setDelete(transformHierarchyOperation(
                original.getDelete(), childAlias, parentAlias, parentTagName, "delete", relationType
            ));
        }
        
        return transformed;
    }
    
    /**
     * Transforms an operation for hierarchy endpoints with proper summaries.
     */
    private Operation transformHierarchyOperation(
            Operation original,
            AliasConfig childAlias,
            AliasConfig parentAlias,
            String parentTagName,
            String httpMethod,
            String relationType) {
        
        Operation transformed = new Operation();
        
        // Copy base properties
        transformed.setParameters(original.getParameters() != null 
            ? new ArrayList<>(original.getParameters()) 
            : new ArrayList<>());
        
        // CRITICAL: Deep clone request body to avoid sharing between virtualized paths!
        // Without this, /books/{id}/chapters and /authors/{id}/books would share the same
        // RequestBody object, and binding one would overwrite the other.
        transformed.setRequestBody(cloneRequestBody(original.getRequestBody()));
        
        // CRITICAL: Deep clone responses to avoid sharing response schemas between paths!
        // Without this, GET /books and GET /authors would share response bindings.
        if (original.getResponses() != null && !original.getResponses().isEmpty()) {
            transformed.setResponses(cloneResponses(original.getResponses()));
        } else {
            io.swagger.v3.oas.models.responses.ApiResponses defaultResponses = 
                new io.swagger.v3.oas.models.responses.ApiResponses();
            io.swagger.v3.oas.models.responses.ApiResponse defaultResponse = 
                new io.swagger.v3.oas.models.responses.ApiResponse();
            defaultResponse.setDescription("Successful operation");
            defaultResponses.addApiResponse("200", defaultResponse);
            transformed.setResponses(defaultResponses);
        }
        
        transformed.setDeprecated(original.getDeprecated());
        transformed.setSecurity(original.getSecurity());
        
        String parentSingular = singularize(parentAlias.getAlias());
        String childPlural = childAlias.getAlias();
        String childSingular = singularize(childAlias.getAlias());
        
        // Generate operation ID: list{Parent}{Children}, create{Parent}{Child}
        String operationId;
        String summary;
        
        switch (httpMethod.toLowerCase()) {
            case "get":
                operationId = String.format("list%s%s", 
                    capitalizeFirst(parentSingular), capitalizeFirst(childPlural));
                summary = String.format("List %s belonging to a %s", 
                    childPlural.toLowerCase(), parentSingular.toLowerCase());
                break;
            case "post":
                operationId = String.format("create%s%s", 
                    capitalizeFirst(parentSingular), capitalizeFirst(childSingular));
                summary = String.format("Create a %s under a %s", 
                    childSingular.toLowerCase(), parentSingular.toLowerCase());
                break;
            default:
                operationId = String.format("%s%s%s", httpMethod,
                    capitalizeFirst(parentSingular), capitalizeFirst(childSingular));
                summary = String.format("%s %s of a %s", 
                    capitalizeFirst(httpMethod), childSingular.toLowerCase(), parentSingular.toLowerCase());
        }
        
        transformed.setOperationId(operationId);
        transformed.setSummary(summary);
        
        // CRITICAL: Tag under PARENT, not child - maintains proper grouping
        transformed.setTags(Collections.singletonList(parentTagName));
        
        return transformed;
    }
    
    /**
     * Transforms a PathItem by rewriting operations.
     * @param tagName The tag to use for operations (may be parent's tag for nested resources)
     * @param parentAlias If not null, indicates this is a nested resource
     */
    private PathItem transformPathItem(
            PathItem original, 
            AliasConfig aliasConfig,
            String controllerName,
            boolean isInstancePath,
            String tagName,
            AliasConfig parentAlias) {
        
        PathItem transformed = new PathItem();
        transformed.setDescription(aliasConfig.getDescription());
        
        // Transform each HTTP method's operation
        if (original.getGet() != null) {
            transformed.setGet(transformOperation(
                original.getGet(), aliasConfig, controllerName, "get", isInstancePath, tagName
            ));
        }
        if (original.getPost() != null) {
            transformed.setPost(transformOperation(
                original.getPost(), aliasConfig, controllerName, "post", isInstancePath, tagName
            ));
        }
        if (original.getPut() != null) {
            transformed.setPut(transformOperation(
                original.getPut(), aliasConfig, controllerName, "put", isInstancePath, tagName
            ));
        }
        if (original.getPatch() != null) {
            transformed.setPatch(transformOperation(
                original.getPatch(), aliasConfig, controllerName, "patch", isInstancePath, tagName
            ));
        }
        if (original.getDelete() != null) {
            transformed.setDelete(transformOperation(
                original.getDelete(), aliasConfig, controllerName, "delete", isInstancePath, tagName
            ));
        }
        
        return transformed;
    }
    
    /**
     * Transforms an individual operation.
     */
    private Operation transformOperation(
            Operation original,
            AliasConfig aliasConfig,
            String controllerName,
            String httpMethod,
            boolean isInstancePath,
            String tagName) {
        
        Operation transformed = new Operation();
        
        // Copy base properties
        transformed.setParameters(original.getParameters() != null 
            ? new ArrayList<>(original.getParameters()) 
            : new ArrayList<>());
        
        // CRITICAL: Deep clone request body to avoid sharing between virtualized paths!
        // Without this, /books and /authors would share the same RequestBody object,
        // and binding /books→NewBook then /authors→NewAuthor would overwrite both.
        transformed.setRequestBody(cloneRequestBody(original.getRequestBody()));
        
        // CRITICAL: Deep clone responses to avoid sharing response schemas between paths!
        // Without this, GET /books and GET /authors would share response bindings.
        if (original.getResponses() != null && !original.getResponses().isEmpty()) {
            transformed.setResponses(cloneResponses(original.getResponses()));
        } else {
            // Provide default response if none exists
            io.swagger.v3.oas.models.responses.ApiResponses defaultResponses = 
                new io.swagger.v3.oas.models.responses.ApiResponses();
            io.swagger.v3.oas.models.responses.ApiResponse defaultResponse = 
                new io.swagger.v3.oas.models.responses.ApiResponse();
            defaultResponse.setDescription("Successful operation");
            defaultResponses.addApiResponse("200", defaultResponse);
            transformed.setResponses(defaultResponses);
        }
        
        transformed.setDeprecated(original.getDeprecated());
        transformed.setSecurity(original.getSecurity());
        
        // Generate or lookup operation ID
        String originalOpId = original.getOperationId();
        String routeId = inferRouteId(originalOpId, controllerName);
        
        // Check for route-specific configuration
        RouteConfig routeConfig = aliasConfig.getRoutes() != null 
            ? aliasConfig.getRoutes().get(routeId) 
            : null;
        
        if (routeConfig != null) {
            // Use explicit route configuration
            transformed.setOperationId(routeConfig.getOperationId() != null 
                ? routeConfig.getOperationId() 
                : generateOperationId(originalOpId, aliasConfig));
            transformed.setSummary(routeConfig.getSummary());
            transformed.setDescription(routeConfig.getDescription());
            transformed.setTags(routeConfig.getTags() != null && !routeConfig.getTags().isEmpty()
                ? routeConfig.getTags()
                : Collections.singletonList(tagName));
        } else if (orchestratorProperties.getTransformation().isAutoGenerateOperationIds()) {
            // Auto-generate operation metadata
            transformed.setOperationId(generateOperationId(originalOpId, aliasConfig));
            transformed.setSummary(generateSummary(originalOpId, aliasConfig, httpMethod, isInstancePath));
            transformed.setTags(Collections.singletonList(tagName));
        } else {
            // Fall back to original
            transformed.setOperationId(originalOpId);
            transformed.setSummary(original.getSummary());
            transformed.setTags(original.getTags() != null ? original.getTags() : Collections.singletonList(tagName));
        }
        
        return transformed;
    }
    
    /**
     * Generates an operation ID based on the alias.
     * E.g., "findEntities" + alias "books" → "listBooks"
     */
    private String generateOperationId(String originalOpId, AliasConfig aliasConfig) {
        String singular = singularize(aliasConfig.getAlias());
        String capitalized = capitalizeFirst(singular);
        
        for (Map.Entry<String, String> entry : OPERATION_TRANSFORMS.entrySet()) {
            if (originalOpId != null && originalOpId.startsWith(entry.getKey())) {
                return String.format(entry.getValue(), capitalized);
            }
        }
        
        // Fallback: append alias to operation
        return originalOpId + capitalizeFirst(aliasConfig.getAlias());
    }
    
    /**
     * Generates a human-readable summary.
     */
    private String generateSummary(String originalOpId, AliasConfig aliasConfig, 
                                   String httpMethod, boolean isInstancePath) {
        String resourceName = capitalizeFirst(singularize(aliasConfig.getAlias()));
        String pluralName = capitalizeFirst(aliasConfig.getAlias());
        
        if (originalOpId == null) {
            return httpMethod.toUpperCase() + " " + resourceName;
        }
        
        if (originalOpId.contains("find") && !isInstancePath) {
            return "List all " + pluralName.toLowerCase();
        } else if (originalOpId.contains("find") && isInstancePath) {
            return "Get " + resourceName.toLowerCase() + " by ID";
        } else if (originalOpId.contains("create")) {
            return "Create a new " + resourceName.toLowerCase();
        } else if (originalOpId.contains("update")) {
            return "Update " + resourceName.toLowerCase();
        } else if (originalOpId.contains("replace")) {
            return "Replace " + resourceName.toLowerCase();
        } else if (originalOpId.contains("delete")) {
            return "Delete " + resourceName.toLowerCase();
        } else if (originalOpId.contains("count")) {
            return "Count " + pluralName.toLowerCase();
        } else if (originalOpId.contains("Children")) {
            return "Get children of " + resourceName.toLowerCase();
        } else if (originalOpId.contains("Parents")) {
            return "Get parents of " + resourceName.toLowerCase();
        }
        
        return resourceName + " operation";
    }
    
    /**
     * Injects a kind parameter as a hidden default for filtering.
     */
    private void injectKindParameter(PathItem pathItem, String kind) {
        if (kind == null || kind.isBlank()) {
            return;
        }
        
        // For each operation, we don't expose the _kind param to users,
        // but the gateway will inject it. We just need to document it's pre-filtered.
        // Optionally add a description note.
    }
    
    /**
     * Transforms components (schemas, etc.).
     */
    private Components transformComponents(Components original) {
        Components transformed = new Components();
        
        if (original.getSchemas() != null) {
            Map<String, Schema> transformedSchemas = new LinkedHashMap<>();
            
            original.getSchemas().forEach((name, schema) -> {
                String newName = orchestratorProperties.getTransformation().isSimplifySchemaNames()
                    ? simplifySchemaName(name)
                    : name;
                transformedSchemas.put(newName, schema);
            });
            
            transformed.setSchemas(transformedSchemas);
        }
        
        // Copy other component types as-is
        transformed.setSecuritySchemes(original.getSecuritySchemes());
        transformed.setParameters(original.getParameters());
        transformed.setRequestBodies(original.getRequestBodies());
        transformed.setResponses(original.getResponses());
        transformed.setHeaders(original.getHeaders());
        transformed.setExamples(original.getExamples());
        transformed.setLinks(original.getLinks());
        transformed.setCallbacks(original.getCallbacks());
        
        return transformed;
    }
    
    /**
     * Simplifies verbose backend schema names.
     * E.g., "GenericEntityExcluding__idempotencyKey-..." → "Entity"
     */
    private String simplifySchemaName(String name) {
        // Common simplifications
        if (name.startsWith("GenericEntity")) {
            if (name.contains("WithRelations")) {
                return "EntityWithRelations";
            } else if (name.contains("Partial")) {
                return "PartialEntity";
            } else if (name.contains("New")) {
                return "NewEntity";
            }
            return "Entity";
        }
        
        if (name.startsWith("List") && !name.equals("List")) {
            if (name.contains("Reaction")) {
                return name.contains("New") ? "NewListReaction" : "ListReaction";
            }
            return name.contains("New") ? "NewList" : "List";
        }
        
        // Keep other names as-is or apply minimal cleanup
        return name.replaceAll("Excluding__.*?_", "");
    }
    
    /**
     * Infers the route ID from an operation ID.
     */
    private String inferRouteId(String operationId, String controllerName) {
        if (operationId == null) {
            return null;
        }
        // Most operation IDs match route IDs directly
        return operationId;
    }
    
    /**
     * Checks if a path has been virtualized.
     */
    private boolean isPathVirtualized(String path, Paths virtualizedPaths) {
        return virtualizedPaths.keySet().stream()
            .anyMatch(vp -> pathPatternsOverlap(path, vp));
    }
    
    private boolean pathPatternsOverlap(String path1, String path2) {
        // Simple check - could be made more sophisticated
        String normalized1 = PATH_PARAM_PATTERN.matcher(path1).replaceAll("{param}");
        String normalized2 = PATH_PARAM_PATTERN.matcher(path2).replaceAll("{param}");
        return normalized1.equals(normalized2);
    }
    
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    private String singularize(String plural) {
        if (plural == null || plural.isEmpty()) {
            return plural;
        }
        // Simple singularization rules
        if (plural.endsWith("ies")) {
            return plural.substring(0, plural.length() - 3) + "y";
        } else if (plural.endsWith("es")) {
            return plural.substring(0, plural.length() - 2);
        } else if (plural.endsWith("s") && !plural.endsWith("ss")) {
            return plural.substring(0, plural.length() - 1);
        }
        return plural;
    }
    
    /**
     * Gets the normalized base URI path prefix for all API endpoints.
     * Removes trailing slashes and returns empty string if not configured.
     * 
     * @return Normalized base URI (e.g., "/api/v1") or empty string
     */
    private String getBaseUriPrefix() {
        if (inboundBaseUri == null || inboundBaseUri.isEmpty()) {
            return "";
        }
        // Remove trailing slashes but keep leading slash
        String normalized = inboundBaseUri.replaceAll("/+$", "");
        // Ensure it starts with /
        if (!normalized.isEmpty() && !normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }
    
    /**
     * Gets the inbound controller base path for the given controller name.
     * This is the path segment used in actual gateway routes (e.g., "entity-reactions" for entityReactions).
     * 
     * @param controllerName The controller name (e.g., "entities", "entityReactions")
     * @return The controller base path (e.g., "entities", "entity-reactions")
     */
    private String getInboundControllerBasePath(String controllerName) {
        return switch (controllerName) {
            case "entities" -> entitiesBasePath;
            case "lists" -> listsBasePath;
            case "relations" -> relationsBasePath;
            case "entityReactions" -> entityReactionsBasePath;
            case "listReactions" -> listReactionsBasePath;
            case "entitiesThroughList" -> entitiesThroughListBasePath;
            case "listsThroughEntity" -> listsThroughEntityBasePath;
            case "reactionsThroughEntity" -> reactionsThroughEntityBasePath;
            case "reactionsThroughList" -> reactionsThroughListBasePath;
            default -> {
                log.warn("Unknown controller name for inbound path: {}", controllerName);
                yield controllerName;
            }
        };
    }
    
    /**
     * Deep clones a RequestBody to ensure virtualized paths don't share the same object.
     * This is critical because multiple aliases (books, authors) come from the same
     * backend path (/entities) and would otherwise share the same requestBody reference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private io.swagger.v3.oas.models.parameters.RequestBody cloneRequestBody(
            io.swagger.v3.oas.models.parameters.RequestBody original) {
        if (original == null) {
            return null;
        }
        
        io.swagger.v3.oas.models.parameters.RequestBody cloned = 
            new io.swagger.v3.oas.models.parameters.RequestBody();
        cloned.setDescription(original.getDescription());
        cloned.setRequired(original.getRequired());
        cloned.set$ref(original.get$ref());
        
        if (original.getContent() != null) {
            Content clonedContent = new Content();
            original.getContent().forEach((mediaTypeName, mediaType) -> {
                io.swagger.v3.oas.models.media.MediaType clonedMediaType = 
                    new io.swagger.v3.oas.models.media.MediaType();
                
                // Clone schema (shallow is OK - we'll replace with $ref anyway)
                if (mediaType.getSchema() != null) {
                    Schema clonedSchema = new Schema();
                    clonedSchema.set$ref(mediaType.getSchema().get$ref());
                    clonedSchema.setType(mediaType.getSchema().getType());
                    clonedSchema.setProperties(mediaType.getSchema().getProperties());
                    clonedSchema.setRequired(mediaType.getSchema().getRequired());
                    clonedSchema.setDescription(mediaType.getSchema().getDescription());
                    clonedMediaType.setSchema(clonedSchema);
                }
                
                clonedMediaType.setExample(mediaType.getExample());
                clonedMediaType.setExamples(mediaType.getExamples());
                clonedMediaType.setEncoding(mediaType.getEncoding());
                
                clonedContent.addMediaType(mediaTypeName, clonedMediaType);
            });
            cloned.setContent(clonedContent);
        }
        
        return cloned;
    }
    
    /**
     * Deep clones ApiResponses to avoid sharing response objects between virtualized paths.
     * CRITICAL: Without this cloning, binding response schemas for /books would also affect
     * /authors if they share the same backend operation.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private io.swagger.v3.oas.models.responses.ApiResponses cloneResponses(
            io.swagger.v3.oas.models.responses.ApiResponses original) {
        if (original == null) {
            return null;
        }
        
        io.swagger.v3.oas.models.responses.ApiResponses cloned = 
            new io.swagger.v3.oas.models.responses.ApiResponses();
        
        original.forEach((statusCode, response) -> {
            io.swagger.v3.oas.models.responses.ApiResponse clonedResponse = 
                new io.swagger.v3.oas.models.responses.ApiResponse();
            clonedResponse.setDescription(response.getDescription());
            clonedResponse.setHeaders(response.getHeaders());
            clonedResponse.set$ref(response.get$ref());
            clonedResponse.setExtensions(response.getExtensions());
            clonedResponse.setLinks(response.getLinks());
            
            if (response.getContent() != null) {
                Content clonedContent = new Content();
                response.getContent().forEach((mediaTypeName, mediaType) -> {
                    io.swagger.v3.oas.models.media.MediaType clonedMediaType = 
                        new io.swagger.v3.oas.models.media.MediaType();
                    
                    // Clone schema (shallow is OK - we'll replace with $ref anyway)
                    if (mediaType.getSchema() != null) {
                        Schema clonedSchema = new Schema();
                        clonedSchema.set$ref(mediaType.getSchema().get$ref());
                        clonedSchema.setType(mediaType.getSchema().getType());
                        clonedSchema.setProperties(mediaType.getSchema().getProperties());
                        clonedSchema.setRequired(mediaType.getSchema().getRequired());
                        clonedSchema.setDescription(mediaType.getSchema().getDescription());
                        
                        // Clone array items if present
                        if (mediaType.getSchema().getItems() != null) {
                            Schema itemsSchema = mediaType.getSchema().getItems();
                            Schema clonedItems = new Schema();
                            clonedItems.set$ref(itemsSchema.get$ref());
                            clonedItems.setType(itemsSchema.getType());
                            clonedSchema.setItems(clonedItems);
                        }
                        
                        clonedMediaType.setSchema(clonedSchema);
                    }
                    
                    clonedMediaType.setExample(mediaType.getExample());
                    clonedMediaType.setExamples(mediaType.getExamples());
                    clonedMediaType.setEncoding(mediaType.getEncoding());
                    
                    clonedContent.addMediaType(mediaTypeName, clonedMediaType);
                });
                clonedResponse.setContent(clonedContent);
            }
            
            cloned.addApiResponse(statusCode, clonedResponse);
        });
        
        return cloned;
    }
    
    /**
     * Internal class representing a transformed path.
     */
    @Data
    private static class TransformedPath {
        private final String virtualPath;
        private final PathItem pathItem;
        private final String routeId; // For toggle filtering
        
        TransformedPath(String virtualPath, PathItem pathItem) {
            this(virtualPath, pathItem, null);
        }
        
        TransformedPath(String virtualPath, PathItem pathItem, String routeId) {
            this.virtualPath = virtualPath;
            this.pathItem = pathItem;
            this.routeId = routeId;
        }
    }
    
    // ========================================================================
    // BROKEN $ref FIXING METHODS
    // ========================================================================
    
    /**
     * Fixes broken $ref references throughout the OpenAPI spec.
     * Common issues fixed:
     * - #/definitions/X → #/components/schemas/X (OpenAPI 2.0 to 3.x migration)
     * - References to non-existent schemas are removed (inlined as empty object)
     */
    private void fixBrokenRefs(OpenAPI openApi) {
        Set<String> existingSchemas = new HashSet<>();
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            existingSchemas.addAll(openApi.getComponents().getSchemas().keySet());
        }
        
        int fixedCount = 0;
        
        // Fix refs in all paths
        if (openApi.getPaths() != null) {
            for (PathItem pathItem : openApi.getPaths().values()) {
                fixedCount += fixRefsInPathItem(pathItem, existingSchemas);
            }
        }
        
        // Fix refs in components/schemas (schemas can reference each other)
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                fixedCount += fixRefsInSchema(schema, existingSchemas);
            }
        }
        
        if (fixedCount > 0) {
            log.info("Fixed {} broken $ref references", fixedCount);
        }
    }
    
    /**
     * Fixes broken refs in a PathItem (all its operations).
     */
    private int fixRefsInPathItem(PathItem pathItem, Set<String> existingSchemas) {
        int count = 0;
        
        if (pathItem.getGet() != null) count += fixRefsInOperation(pathItem.getGet(), existingSchemas);
        if (pathItem.getPost() != null) count += fixRefsInOperation(pathItem.getPost(), existingSchemas);
        if (pathItem.getPut() != null) count += fixRefsInOperation(pathItem.getPut(), existingSchemas);
        if (pathItem.getPatch() != null) count += fixRefsInOperation(pathItem.getPatch(), existingSchemas);
        if (pathItem.getDelete() != null) count += fixRefsInOperation(pathItem.getDelete(), existingSchemas);
        if (pathItem.getHead() != null) count += fixRefsInOperation(pathItem.getHead(), existingSchemas);
        if (pathItem.getOptions() != null) count += fixRefsInOperation(pathItem.getOptions(), existingSchemas);
        if (pathItem.getTrace() != null) count += fixRefsInOperation(pathItem.getTrace(), existingSchemas);
        
        return count;
    }
    
    /**
     * Fixes broken refs in an Operation.
     */
    private int fixRefsInOperation(Operation operation, Set<String> existingSchemas) {
        int count = 0;
        
        // Fix refs in parameters
        if (operation.getParameters() != null) {
            for (io.swagger.v3.oas.models.parameters.Parameter param : operation.getParameters()) {
                count += fixRefsInParameter(param, existingSchemas);
            }
        }
        
        // Fix refs in request body
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            for (io.swagger.v3.oas.models.media.MediaType mediaType : operation.getRequestBody().getContent().values()) {
                if (mediaType.getSchema() != null) {
                    count += fixRefsInSchema(mediaType.getSchema(), existingSchemas);
                }
            }
        }
        
        // Fix refs in responses
        if (operation.getResponses() != null) {
            for (io.swagger.v3.oas.models.responses.ApiResponse response : operation.getResponses().values()) {
                if (response.getContent() != null) {
                    for (io.swagger.v3.oas.models.media.MediaType mediaType : response.getContent().values()) {
                        if (mediaType.getSchema() != null) {
                            count += fixRefsInSchema(mediaType.getSchema(), existingSchemas);
                        }
                    }
                }
            }
        }
        
        return count;
    }
    
    /**
     * Fixes broken refs in a Parameter.
     */
    private int fixRefsInParameter(io.swagger.v3.oas.models.parameters.Parameter param, Set<String> existingSchemas) {
        int count = 0;
        
        if (param.getSchema() != null) {
            count += fixRefsInSchema(param.getSchema(), existingSchemas);
        }
        
        if (param.getContent() != null) {
            for (io.swagger.v3.oas.models.media.MediaType mediaType : param.getContent().values()) {
                if (mediaType.getSchema() != null) {
                    count += fixRefsInSchema(mediaType.getSchema(), existingSchemas);
                }
            }
        }
        
        return count;
    }
    
    /**
     * Recursively fixes broken refs in a Schema.
     * This handles nested schemas, arrays, oneOf, anyOf, allOf, etc.
     */
    @SuppressWarnings("unchecked")
    private int fixRefsInSchema(Schema<?> schema, Set<String> existingSchemas) {
        if (schema == null) {
            return 0;
        }
        
        int count = 0;
        
        // Fix direct $ref
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            String fixedRef = fixRef(ref, existingSchemas);
            
            if (fixedRef == null) {
                // Reference doesn't exist - remove the $ref and make it a generic object
                log.debug("Removing broken $ref: {}", ref);
                schema.set$ref(null);
                schema.setType("object");
                schema.setDescription("(Schema reference was unavailable)");
                count++;
            } else if (!fixedRef.equals(ref)) {
                log.debug("Fixed $ref: {} → {}", ref, fixedRef);
                schema.set$ref(fixedRef);
                count++;
            }
        }
        
        // Fix refs in properties
        if (schema.getProperties() != null) {
            for (Object propSchema : schema.getProperties().values()) {
                if (propSchema instanceof Schema) {
                    count += fixRefsInSchema((Schema<?>) propSchema, existingSchemas);
                }
            }
        }
        
        // Fix refs in items (for arrays)
        if (schema.getItems() != null) {
            count += fixRefsInSchema(schema.getItems(), existingSchemas);
        }
        
        // Fix refs in additionalProperties
        if (schema.getAdditionalProperties() instanceof Schema) {
            count += fixRefsInSchema((Schema<?>) schema.getAdditionalProperties(), existingSchemas);
        }
        
        // Fix refs in oneOf
        if (schema.getOneOf() != null) {
            for (Schema<?> oneOfSchema : schema.getOneOf()) {
                count += fixRefsInSchema(oneOfSchema, existingSchemas);
            }
        }
        
        // Fix refs in anyOf
        if (schema.getAnyOf() != null) {
            for (Schema<?> anyOfSchema : schema.getAnyOf()) {
                count += fixRefsInSchema(anyOfSchema, existingSchemas);
            }
        }
        
        // Fix refs in allOf
        if (schema.getAllOf() != null) {
            for (Schema<?> allOfSchema : schema.getAllOf()) {
                count += fixRefsInSchema(allOfSchema, existingSchemas);
            }
        }
        
        // Fix refs in not
        if (schema.getNot() != null) {
            count += fixRefsInSchema(schema.getNot(), existingSchemas);
        }
        
        return count;
    }
    
    /**
     * Fixes a single $ref string.
     * 
     * @param ref The original reference
     * @param existingSchemas Set of schema names that exist in components/schemas
     * @return The fixed reference, or null if the target doesn't exist
     */
    private String fixRef(String ref, Set<String> existingSchemas) {
        if (ref == null) {
            return null;
        }
        
        // Convert #/definitions/X to #/components/schemas/X (OpenAPI 2.0 → 3.x)
        if (ref.startsWith("#/definitions/")) {
            String schemaName = ref.substring("#/definitions/".length());
            
            // Check if the schema exists in components/schemas
            if (existingSchemas.contains(schemaName)) {
                return "#/components/schemas/" + schemaName;
            }
            
            // Schema doesn't exist - return null to indicate broken ref
            log.debug("Schema '{}' does not exist in components/schemas", schemaName);
            return null;
        }
        
        // Already in correct format - verify it exists
        if (ref.startsWith("#/components/schemas/")) {
            String schemaName = ref.substring("#/components/schemas/".length());
            if (existingSchemas.contains(schemaName)) {
                return ref; // Valid, no change needed
            }
            // Schema doesn't exist
            log.debug("Schema '{}' does not exist in components/schemas", schemaName);
            return null;
        }
        
        // Other ref formats (external refs, etc.) - leave unchanged
        return ref;
    }
    
    // ========================================================================
    // SCHEMA VALIDATION KEYWORD FIXES
    // ========================================================================
    
    /**
     * Fixes misplaced JSON Schema validation keywords.
     * - uniqueItems belongs on array schema, NOT on items schema
     * - minItems/maxItems belong on array schema, NOT on items schema
     */
    private void fixSchemaValidationKeywords(OpenAPI openApi) {
        int fixedCount = 0;
        
        // Fix in all paths
        if (openApi.getPaths() != null) {
            for (PathItem pathItem : openApi.getPaths().values()) {
                fixedCount += fixValidationKeywordsInPathItem(pathItem);
            }
        }
        
        // Fix in components/schemas
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                fixedCount += fixValidationKeywordsInSchema(schema);
            }
        }
        
        if (fixedCount > 0) {
            log.info("Fixed {} misplaced schema validation keywords", fixedCount);
        }
    }
    
    private int fixValidationKeywordsInPathItem(PathItem pathItem) {
        int count = 0;
        if (pathItem.getGet() != null) count += fixValidationKeywordsInOperation(pathItem.getGet());
        if (pathItem.getPost() != null) count += fixValidationKeywordsInOperation(pathItem.getPost());
        if (pathItem.getPut() != null) count += fixValidationKeywordsInOperation(pathItem.getPut());
        if (pathItem.getPatch() != null) count += fixValidationKeywordsInOperation(pathItem.getPatch());
        if (pathItem.getDelete() != null) count += fixValidationKeywordsInOperation(pathItem.getDelete());
        if (pathItem.getHead() != null) count += fixValidationKeywordsInOperation(pathItem.getHead());
        if (pathItem.getOptions() != null) count += fixValidationKeywordsInOperation(pathItem.getOptions());
        if (pathItem.getTrace() != null) count += fixValidationKeywordsInOperation(pathItem.getTrace());
        return count;
    }
    
    private int fixValidationKeywordsInOperation(Operation operation) {
        int count = 0;
        
        if (operation.getParameters() != null) {
            for (io.swagger.v3.oas.models.parameters.Parameter param : operation.getParameters()) {
                if (param.getSchema() != null) {
                    count += fixValidationKeywordsInSchema(param.getSchema());
                }
                if (param.getContent() != null) {
                    for (io.swagger.v3.oas.models.media.MediaType mt : param.getContent().values()) {
                        if (mt.getSchema() != null) {
                            count += fixValidationKeywordsInSchema(mt.getSchema());
                        }
                    }
                }
            }
        }
        
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            for (io.swagger.v3.oas.models.media.MediaType mt : operation.getRequestBody().getContent().values()) {
                if (mt.getSchema() != null) {
                    count += fixValidationKeywordsInSchema(mt.getSchema());
                }
            }
        }
        
        if (operation.getResponses() != null) {
            for (io.swagger.v3.oas.models.responses.ApiResponse response : operation.getResponses().values()) {
                if (response.getContent() != null) {
                    for (io.swagger.v3.oas.models.media.MediaType mt : response.getContent().values()) {
                        if (mt.getSchema() != null) {
                            count += fixValidationKeywordsInSchema(mt.getSchema());
                        }
                    }
                }
            }
        }
        
        return count;
    }
    
    /**
     * Recursively fixes validation keywords in a schema.
     * If this is an array with items, and items has uniqueItems, move it to parent.
     */
    @SuppressWarnings("unchecked")
    private int fixValidationKeywordsInSchema(Schema<?> schema) {
        if (schema == null) {
            return 0;
        }
        
        int count = 0;
        
        // If this is an array schema with items
        if ("array".equals(schema.getType()) && schema.getItems() != null) {
            Schema<?> items = schema.getItems();
            
            // Check if items incorrectly has uniqueItems (should be on parent array)
            if (items.getUniqueItems() != null && items.getUniqueItems()) {
                log.debug("Moving uniqueItems from items to parent array schema");
                schema.setUniqueItems(true);
                items.setUniqueItems(null);
                count++;
            }
            
            // Recursively fix items schema
            count += fixValidationKeywordsInSchema(items);
        }
        
        // Fix in properties
        if (schema.getProperties() != null) {
            for (Object propSchema : schema.getProperties().values()) {
                if (propSchema instanceof Schema) {
                    count += fixValidationKeywordsInSchema((Schema<?>) propSchema);
                }
            }
        }
        
        // Fix in additionalProperties
        if (schema.getAdditionalProperties() instanceof Schema) {
            count += fixValidationKeywordsInSchema((Schema<?>) schema.getAdditionalProperties());
        }
        
        // Fix in oneOf/anyOf/allOf
        if (schema.getOneOf() != null) {
            for (Schema<?> s : schema.getOneOf()) {
                count += fixValidationKeywordsInSchema(s);
            }
        }
        if (schema.getAnyOf() != null) {
            for (Schema<?> s : schema.getAnyOf()) {
                count += fixValidationKeywordsInSchema(s);
            }
        }
        if (schema.getAllOf() != null) {
            for (Schema<?> s : schema.getAllOf()) {
                count += fixValidationKeywordsInSchema(s);
            }
        }
        
        return count;
    }
    
    // ========================================================================
    // PARAMETER DEDUPLICATION
    // ========================================================================
    
    /**
     * Removes duplicate parameters from all operations.
     * In OpenAPI, the combination of name + in must be unique per operation.
     */
    private void deduplicateParameters(OpenAPI openApi) {
        int removedCount = 0;
        
        if (openApi.getPaths() != null) {
            for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
                String path = entry.getKey();
                PathItem pathItem = entry.getValue();
                removedCount += deduplicateParametersInPathItem(path, pathItem);
            }
        }
        
        if (removedCount > 0) {
            log.info("Removed {} duplicate parameters", removedCount);
        }
    }
    
    private int deduplicateParametersInPathItem(String path, PathItem pathItem) {
        int count = 0;
        if (pathItem.getGet() != null) count += deduplicateParametersInOperation(path, "GET", pathItem.getGet());
        if (pathItem.getPost() != null) count += deduplicateParametersInOperation(path, "POST", pathItem.getPost());
        if (pathItem.getPut() != null) count += deduplicateParametersInOperation(path, "PUT", pathItem.getPut());
        if (pathItem.getPatch() != null) count += deduplicateParametersInOperation(path, "PATCH", pathItem.getPatch());
        if (pathItem.getDelete() != null) count += deduplicateParametersInOperation(path, "DELETE", pathItem.getDelete());
        if (pathItem.getHead() != null) count += deduplicateParametersInOperation(path, "HEAD", pathItem.getHead());
        if (pathItem.getOptions() != null) count += deduplicateParametersInOperation(path, "OPTIONS", pathItem.getOptions());
        if (pathItem.getTrace() != null) count += deduplicateParametersInOperation(path, "TRACE", pathItem.getTrace());
        return count;
    }
    
    /**
     * Removes duplicate parameters from a single operation.
     * Keeps the FIRST occurrence of each name+in combination.
     */
    private int deduplicateParametersInOperation(String path, String method, Operation operation) {
        if (operation.getParameters() == null || operation.getParameters().isEmpty()) {
            return 0;
        }
        
        List<io.swagger.v3.oas.models.parameters.Parameter> params = operation.getParameters();
        Set<String> seen = new HashSet<>();
        List<io.swagger.v3.oas.models.parameters.Parameter> deduped = new ArrayList<>();
        int removedCount = 0;
        
        for (io.swagger.v3.oas.models.parameters.Parameter param : params) {
            String key = param.getName() + "|" + param.getIn();
            
            if (seen.contains(key)) {
                log.debug("Removing duplicate parameter '{}' (in={}) from {} {}", 
                    param.getName(), param.getIn(), method, path);
                removedCount++;
            } else {
                seen.add(key);
                deduped.add(param);
            }
        }
        
        if (removedCount > 0) {
            operation.setParameters(deduped);
        }
        
        return removedCount;
    }
    
    // ============================================================================
    // ROUTE TOGGLE CHECKING (mirrors CheckIfRouteEnabled filter logic)
    // ============================================================================
    
    /**
     * Checks if a controller is disabled by toggles configuration.
     * Mirrors the logic from CheckIfRouteEnabled filter.
     */
    private boolean isControllerDisabled(String controllerName) {
        if (controllerName == null || controllerName.trim().isEmpty()) {
            return false;
        }
        
        List<String> controllersOn = normalizeList(togglesProperties.getControllers().getOn());
        List<String> controllersOff = normalizeList(togglesProperties.getControllers().getOff());
        
        controllerName = controllerName.trim();
        
        if (!controllersOn.isEmpty()) {
            // Only controllers in controllersOn are enabled
            return !controllersOn.contains(controllerName);
        } else if (!controllersOff.isEmpty()) {
            // Controllers in controllersOff are disabled
            return controllersOff.contains(controllerName);
        }
        
        return false;
    }
    
    /**
     * Checks if a tag is disabled by toggles configuration.
     */
    private boolean isTagDisabled(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            return false;
        }
        
        List<String> tagsOn = normalizeList(togglesProperties.getTags().getOn());
        List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());
        
        tagName = tagName.trim();
        
        if (!tagsOn.isEmpty()) {
            return !tagsOn.contains(tagName);
        } else if (!tagsOff.isEmpty()) {
            return tagsOff.contains(tagName);
        }
        
        return false;
    }
    
    /**
     * Checks if a specific route is disabled by toggles configuration.
     */
    private boolean isRouteDisabled(String routeId) {
        if (routeId == null || routeId.trim().isEmpty()) {
            return false;
        }
        
        List<String> routesOn = normalizeList(togglesProperties.getRoutes().getOn());
        List<String> routesOff = normalizeList(togglesProperties.getRoutes().getOff());
        
        routeId = routeId.trim();
        
        if (!routesOn.isEmpty()) {
            return !routesOn.contains(routeId);
        } else if (!routesOff.isEmpty()) {
            return routesOff.contains(routeId);
        }
        
        return false;
    }
    
    private List<String> normalizeList(List<String> items) {
        return items == null ? Collections.emptyList()
            : items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.toList());
    }
    
    // ============================================================================
    // SCHEMA MERGING (mirrors ValidateRequestBodyByKindSchema logic)
    // ============================================================================
    
    /**
     * Adds merged domain schemas (base + alias-specific) to the OAS components.
     * This mirrors the schema merging logic from ValidateRequestBodyByKindSchema.
     * Processes top-level aliases AND their children/parents hierarchies.
     */
    @SuppressWarnings("unchecked")
    private void addMergedDomainSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }
        
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        
        try {
            // Parse base schemas
            JsonNode baseSchemaNode = commonBaseSchema != null 
                ? objectMapper.readTree(commonBaseSchema) 
                : objectMapper.createObjectNode();
            
            JsonNode relationsBaseNode = relationsBaseSchema != null 
                ? objectMapper.readTree(relationsBaseSchema) 
                : baseSchemaNode;
            
            // Process each controller's aliases
            openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
                if (controllerConfig.getAliases() == null) return;
                
                boolean isRelationsController = "relations".equals(controllerName);
                JsonNode effectiveBase = isRelationsController ? relationsBaseNode : baseSchemaNode;
                
                controllerConfig.getAliases().forEach(aliasConfig -> {
                    String parentAlias = aliasConfig.getAlias();
                    
                    // Process the top-level alias: /authors → Author
                    addSchemaForAlias(schemas, aliasConfig, effectiveBase, null, null, controllerName);
                    
                    // Process children hierarchy: /books/{id}/chapters → BookChildChapter
                    processHierarchySchemas(schemas, aliasConfig.getChildren(), effectiveBase, parentAlias, "Child", controllerName);
                    
                    // Process parents hierarchy: /books/{id}/authors → BookParentAuthor
                    processHierarchySchemas(schemas, aliasConfig.getParents(), effectiveBase, parentAlias, "Parent", controllerName);
                });
            });
            
        } catch (Exception e) {
            log.error("Failed to process base schemas: {}", e.getMessage());
        }
    }
    
    /**
     * Recursively processes hierarchy (children/parents) and adds their schemas.
     * @param parentAlias The parent alias (e.g., "books")
     * @param hierarchyType "Child" or "Parent" for naming
     * @param controllerName The record type name (entities, lists, relations, etc.)
     */
    private void processHierarchySchemas(Map<String, Schema> schemas, 
            List<AliasConfig> hierarchy,
            JsonNode effectiveBase,
            String parentAlias,
            String hierarchyType,
            String controllerName) {
        if (hierarchy == null || hierarchy.isEmpty()) return;
        
        for (var nestedConfig : hierarchy) {
            // Add schema for this hierarchy level with path-specific name
            // e.g., BookChildChapter, BookParentAuthor
            addSchemaForAlias(schemas, nestedConfig, effectiveBase, parentAlias, hierarchyType, controllerName);
            
            // Recurse into nested children (nested under the current hierarchy item)
            String newParent = parentAlias + hierarchyType + capitalizeFirst(nestedConfig.getKind());
            processHierarchySchemas(schemas, nestedConfig.getChildren(), effectiveBase, newParent, "Child", controllerName);
            processHierarchySchemas(schemas, nestedConfig.getParents(), effectiveBase, newParent, "Parent", controllerName);
        }
    }
    
    /**
     * Creates and adds merged schema for a single alias config.
     * @param parentAlias If not null, creates path-specific schema name (e.g., BookChildChapter)
     * @param hierarchyType "Child" or "Parent" - used in naming for nested schemas
     */
    private void addSchemaForAlias(Map<String, Schema> schemas,
            AliasConfig aliasConfig,
            JsonNode effectiveBase,
            String parentAlias,
            String hierarchyType,
            String controllerName) {
        if (aliasConfig == null) {
            log.info("addSchemaForAlias: aliasConfig is null, skipping");
            return;
        }
        if (aliasConfig.getSchema() == null) {
            log.info("addSchemaForAlias: schema is null for alias={}, kind={}, parent={}", 
                aliasConfig.getAlias(), aliasConfig.getKind(), parentAlias);
            return;
        }
        if (aliasConfig.getKind() == null) {
            log.info("addSchemaForAlias: kind is null for alias={}", aliasConfig.getAlias());
            return;
        }
        
        try {
            // Build schema name based on path context
            // Top-level: Author, Book
            // Nested: BookChildChapter, BookParentAuthor
            String kindName = capitalizeFirst(aliasConfig.getKind());
            String schemaName;
            if (parentAlias != null && hierarchyType != null) {
                schemaName = capitalizeFirst(parentAlias) + hierarchyType + kindName;
            } else {
                schemaName = kindName;
            }
            
            // Each path gets its own schema - no overwriting needed
            if (schemas.containsKey(schemaName)) {
                return; // Already added (same path processed twice somehow)
            }
            
            Schema mergedSchema = mergeSchemaWithBase(aliasConfig.getSchema(), effectiveBase, controllerName);
            schemas.put(schemaName, mergedSchema);
            
            // Also add "New" variant without required base fields for POST
            Schema newSchema = mergeSchemaWithBase(aliasConfig.getSchema(), effectiveBase, controllerName);
            newSchema.setRequired(null); // No required for creation (gateway adds defaults)
            schemas.put("New" + schemaName, newSchema);
            
            log.debug("Added merged domain schema: {} (from kind: {}, alias: {}, parent: {}, recordType: {})", 
                schemaName, aliasConfig.getKind(), aliasConfig.getAlias(), parentAlias, controllerName);
        } catch (Exception e) {
            log.warn("Failed to merge schema for kind '{}': {}", 
                aliasConfig.getKind(), e.getMessage());
        }
    }
    
    /**
     * Merges an alias-specific schema with the base schema.
     * Properties from alias override base; required arrays are merged.
     * Injects x-record-type vendor extension for security policy evaluation.
     * 
     * @param aliasSchemaJson The alias-specific schema JSON
     * @param baseSchemaNode The base schema node (entities or relations base)
     * @param controllerName The record type (entities, lists, relations, entityReactions, listReactions)
     * @return Merged schema with x-record-type extension
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Schema mergeSchemaWithBase(String aliasSchemaJson, JsonNode baseSchemaNode, String controllerName) 
            throws JsonProcessingException {
        
        JsonNode aliasNode = objectMapper.readTree(aliasSchemaJson);
        
        Schema merged = new Schema();
        merged.setType("object");
        
        // Merge properties
        Map<String, Schema> properties = new LinkedHashMap<>();
        
        // First add base properties
        if (baseSchemaNode.has("properties")) {
            baseSchemaNode.get("properties").fields().forEachRemaining(entry -> {
                try {
                    Schema propSchema = objectMapper.treeToValue(entry.getValue(), Schema.class);
                    properties.put(entry.getKey(), propSchema);
                } catch (Exception e) {
                    log.debug("Could not convert property {}: {}", entry.getKey(), e.getMessage());
                }
            });
        }
        
        // Then overlay alias properties (overrides base)
        if (aliasNode.has("properties")) {
            aliasNode.get("properties").fields().forEachRemaining(entry -> {
                try {
                    Schema propSchema = objectMapper.treeToValue(entry.getValue(), Schema.class);
                    properties.put(entry.getKey(), propSchema);
                } catch (Exception e) {
                    log.debug("Could not convert property {}: {}", entry.getKey(), e.getMessage());
                }
            });
        }
        
        merged.setProperties(properties);
        
        // Merge required arrays
        Set<String> requiredSet = new LinkedHashSet<>();
        if (baseSchemaNode.has("required") && baseSchemaNode.get("required").isArray()) {
            for (JsonNode req : baseSchemaNode.get("required")) {
                requiredSet.add(req.asText());
            }
        }
        if (aliasNode.has("required") && aliasNode.get("required").isArray()) {
            for (JsonNode req : aliasNode.get("required")) {
                requiredSet.add(req.asText());
            }
        }
        if (!requiredSet.isEmpty()) {
            merged.setRequired(new ArrayList<>(requiredSet));
        }
        
        // Copy additionalProperties if specified
        if (aliasNode.has("additionalProperties")) {
            JsonNode addProps = aliasNode.get("additionalProperties");
            if (addProps.isBoolean()) {
                merged.setAdditionalProperties(addProps.asBoolean());
            }
        }
        
        // Inject x-record-type vendor extension for security policy evaluation
        // This creates a deterministic link between schema and record type
        if (merged.getExtensions() == null) {
            merged.setExtensions(new LinkedHashMap<>());
        }
        merged.getExtensions().put("x-record-type", controllerName);
        
        return merged;
    }
    
    // ============================================================================
    // SCHEMA BINDING (REQUEST BODIES AND RESPONSES)
    // ============================================================================
    
    /**
     * CRITICAL: Binds request bodies AND responses to domain-specific schemas.
     * 
     * This replaces inline "NewEntity", "GenericEntity" schemas with $ref to the merged
     * domain schemas (e.g., NewBook, Book, BooksChildChapter, BooksParentAuthor).
     * 
     * Schema naming:
     * - Top-level /authors → Author, NewAuthor
     * - Nested /books/{id}/chapters → BooksChildChapter, NewBooksChildChapter  
     * - Nested /books/{id}/authors → BooksParentAuthor, NewBooksParentAuthor
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void bindRequestBodiesToDomainSchemas(OpenAPI openApi) {
        if (openApi.getPaths() == null) return;
        
        // Build a map of path → aliasConfig for quick lookup
        Map<String, AliasContext> pathToAliasContext = buildPathToAliasContextMap();
        
        int requestBindCount = 0;
        int responseBindCount = 0;
        
        for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem pathItem = entry.getValue();
            
            AliasContext aliasContext = findAliasContextForPath(path, pathToAliasContext);
            if (aliasContext == null) {
                continue; // No alias config for this path, skip binding
            }
            
            // Use path-specific schema name (e.g., Author, BooksChildChapter, BooksParentAuthor)
            String schemaName = aliasContext.getSchemaName();
            boolean isCollectionPath = !path.contains("{id}") && !path.endsWith("/count");
            boolean isCountPath = path.endsWith("/count");
            
            // === BIND REQUEST BODIES ===
            
            // Bind POST request body → NewXxx schema
            if (pathItem.getPost() != null) {
                if (bindOperationRequestBody(pathItem.getPost(), "New" + schemaName, openApi)) {
                    requestBindCount++;
                    log.debug("Bound POST {} requestBody → New{}", path, schemaName);
                }
                // Bind POST 200/201 response → Xxx schema (single object)
                if (bindOperationResponse(pathItem.getPost(), schemaName, openApi, false)) {
                    responseBindCount++;
                    log.debug("Bound POST {} response → {}", path, schemaName);
                }
            }
            
            // Bind PUT request body → Xxx schema (full replacement)
            if (pathItem.getPut() != null) {
                if (bindOperationRequestBody(pathItem.getPut(), schemaName, openApi)) {
                    requestBindCount++;
                    log.debug("Bound PUT {} requestBody → {}", path, schemaName);
                }
                // Bind PUT response → Xxx schema
                if (bindOperationResponse(pathItem.getPut(), schemaName, openApi, false)) {
                    responseBindCount++;
                    log.debug("Bound PUT {} response → {}", path, schemaName);
                }
            }
            
            // Bind PATCH request body → Xxx schema (partial update)
            if (pathItem.getPatch() != null) {
                if (bindOperationRequestBody(pathItem.getPatch(), schemaName, openApi)) {
                    requestBindCount++;
                    log.debug("Bound PATCH {} requestBody → {}", path, schemaName);
                }
                // Bind PATCH response → Xxx schema
                if (bindOperationResponse(pathItem.getPatch(), schemaName, openApi, false)) {
                    responseBindCount++;
                    log.debug("Bound PATCH {} response → {}", path, schemaName);
                }
            }
            
            // === BIND RESPONSE SCHEMAS FOR GET ===
            
            if (pathItem.getGet() != null && !isCountPath) {
                // GET on collection → array of Xxx schema
                // GET on instance → single Xxx schema
                if (bindOperationResponse(pathItem.getGet(), schemaName, openApi, isCollectionPath)) {
                    responseBindCount++;
                    log.debug("Bound GET {} response → {}{}",  path, isCollectionPath ? "array of " : "", schemaName);
                }
            }
            
            // === BIND RESPONSE SCHEMAS FOR DELETE ===
            
            if (pathItem.getDelete() != null) {
                // DELETE typically returns the deleted object
                if (bindOperationResponse(pathItem.getDelete(), schemaName, openApi, false)) {
                    responseBindCount++;
                    log.debug("Bound DELETE {} response → {}", path, schemaName);
                }
            }
        }
        
        log.info("Bound {} request bodies and {} responses to domain-specific schemas", 
            requestBindCount, responseBindCount);
    }
    
    /**
     * Binds an operation's request body to a domain schema using $ref.
     * Returns true if binding was performed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean bindOperationRequestBody(Operation operation, String schemaName, OpenAPI openApi) {
        if (operation == null || operation.getRequestBody() == null) {
            return false;
        }
        
        // Check if the schema exists in components
        if (openApi.getComponents() == null || 
            openApi.getComponents().getSchemas() == null ||
            !openApi.getComponents().getSchemas().containsKey(schemaName)) {
            log.debug("Schema '{}' not found in components, skipping binding", schemaName);
            return false;
        }
        
        Content content = operation.getRequestBody().getContent();
        if (content == null) return false;
        
        // Replace schema in application/json media type
        io.swagger.v3.oas.models.media.MediaType mediaType = content.get("application/json");
        if (mediaType == null) {
            // Try to get any media type
            mediaType = content.values().stream().findFirst().orElse(null);
        }
        
        if (mediaType != null) {
            // Replace inline schema with $ref
            Schema refSchema = new Schema();
            refSchema.set$ref("#/components/schemas/" + schemaName);
            mediaType.setSchema(refSchema);
            return true;
        }
        
        return false;
    }
    
    /**
     * Binds operation response schemas to domain-specific schemas.
     * 
     * @param operation The operation to bind responses for
     * @param schemaName The domain schema name (e.g., "Book", "Author")
     * @param openApi The OpenAPI spec to reference schemas from
     * @param isArray If true, wraps the schema reference in an array
     * @return true if at least one response was bound
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean bindOperationResponse(Operation operation, String schemaName, OpenAPI openApi, boolean isArray) {
        if (operation == null || operation.getResponses() == null) {
            return false;
        }
        
        // Check if the schema exists in components
        if (openApi.getComponents() == null || 
            openApi.getComponents().getSchemas() == null ||
            !openApi.getComponents().getSchemas().containsKey(schemaName)) {
            log.debug("Schema '{}' not found in components, skipping response binding", schemaName);
            return false;
        }
        
        boolean bound = false;
        
        // Bind 200 OK response
        ApiResponse response200 = operation.getResponses().get("200");
        if (response200 != null && bindResponseContent(response200, schemaName, isArray)) {
            bound = true;
        }
        
        // Bind 201 Created response (for POST)
        ApiResponse response201 = operation.getResponses().get("201");
        if (response201 != null && bindResponseContent(response201, schemaName, false)) {
            bound = true;
        }
        
        return bound;
    }
    
    /**
     * Helper to bind a specific response's content to a schema reference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean bindResponseContent(ApiResponse response, String schemaName, boolean isArray) {
        if (response == null || response.getContent() == null) {
            return false;
        }
        
        Content content = response.getContent();
        
        // Try application/json first, then any other media type
        io.swagger.v3.oas.models.media.MediaType mediaType = content.get("application/json");
        if (mediaType == null) {
            mediaType = content.values().stream().findFirst().orElse(null);
        }
        
        if (mediaType != null) {
            Schema refSchema = new Schema();
            refSchema.set$ref("#/components/schemas/" + schemaName);
            
            if (isArray) {
                // Wrap in array schema for collection endpoints
                ArraySchema arraySchema = new ArraySchema();
                arraySchema.setItems(refSchema);
                mediaType.setSchema(arraySchema);
            } else {
                mediaType.setSchema(refSchema);
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Context class to track alias information for schema binding.
     */
    @Data
    @AllArgsConstructor
    private static class AliasContext {
        private String alias;
        private String kind;
        private String controllerName;
        private boolean isNested;        // true if this is a child/parent path
        private String parentAlias;      // the parent alias (e.g., "books" for /books/{id}/chapters)
        private String hierarchyType;    // "Child" or "Parent" or null for top-level
        
        /**
         * Gets the schema name for this context.
         * Top-level: Author, Book
         * Nested: BooksChildChapter, BooksParentAuthor
         */
        public String getSchemaName() {
            String kindName = kind.substring(0, 1).toUpperCase() + kind.substring(1);
            if (parentAlias != null && hierarchyType != null) {
                String parentName = parentAlias.substring(0, 1).toUpperCase() + parentAlias.substring(1);
                return parentName + hierarchyType + kindName;
            }
            return kindName;
        }
    }
    
    /**
     * Builds a map of virtualized paths to their alias context.
     * CRITICAL: Children/parent paths are NESTED under the parent alias.
     * e.g., /authors/{id}/books, NOT /books (which is top-level)
     */
    private Map<String, AliasContext> buildPathToAliasContextMap() {
        Map<String, AliasContext> map = new HashMap<>();
        
        openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
            if (controllerConfig.getAliases() == null) return;
            
            for (AliasConfig aliasConfig : controllerConfig.getAliases()) {
                String alias = aliasConfig.getAlias();
                String kind = aliasConfig.getKind();
                
                if (alias == null || kind == null) continue;
                
                // Add paths for top-level alias
                // /{alias} - collection (POST creates new)
                map.put("/" + alias, new AliasContext(alias, kind, controllerName, false, null, null));
                // /{alias}/count - count
                map.put("/" + alias + "/count", new AliasContext(alias, kind, controllerName, false, null, null));
                // /{alias}/{id} - instance (PUT/PATCH updates)
                map.put("/" + alias + "/{id}", new AliasContext(alias, kind, controllerName, false, null, null));
                
                // Add paths for children - these are NESTED under THIS alias
                // Path pattern: /{thisAlias}/{id}/{childAlias}
                if (aliasConfig.getChildren() != null) {
                    for (AliasConfig childConfig : aliasConfig.getChildren()) {
                        String childAlias = childConfig.getAlias();
                        String childKind = childConfig.getKind();
                        
                        if (childAlias == null || childKind == null) continue;
                        
                        // /{alias}/{id}/{childAlias} - creates child under this parent
                        String childPath = "/" + alias + "/{id}/" + childAlias;
                        map.put(childPath, new AliasContext(childAlias, childKind, controllerName, true, alias, "Child"));
                    }
                }
                
                // Add paths for parents - these are NESTED under THIS alias to query parents
                // Path pattern: /{thisAlias}/{id}/{parentAlias}
                if (aliasConfig.getParents() != null) {
                    for (AliasConfig parentConfig : aliasConfig.getParents()) {
                        String parentAlias = parentConfig.getAlias();
                        String parentKind = parentConfig.getKind();
                        
                        if (parentAlias == null || parentKind == null) continue;
                        
                        // /{alias}/{id}/{parentAlias} - queries parents of this entity
                        String parentPath = "/" + alias + "/{id}/" + parentAlias;
                        map.put(parentPath, new AliasContext(parentAlias, parentKind, controllerName, true, alias, "Parent"));
                    }
                }
            }
        });
        
        log.debug("Built path-to-alias map with {} entries: {}", map.size(), map.keySet());
        return map;
    }
    
    /**
     * Finds the alias context for a given path.
     */
    private AliasContext findAliasContextForPath(String path, Map<String, AliasContext> pathMap) {
        // Direct match
        if (pathMap.containsKey(path)) {
            return pathMap.get(path);
        }
        
        // Try pattern matching for paths with IDs
        for (Map.Entry<String, AliasContext> entry : pathMap.entrySet()) {
            String pattern = entry.getKey();
            if (pathMatchesPattern(path, pattern)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Checks if a path matches a pattern (handles {id} placeholders).
     */
    private boolean pathMatchesPattern(String path, String pattern) {
        // Simple pattern matching - convert {id} to regex
        String regex = pattern.replaceAll("\\{[^}]+}", "[^/]+");
        return path.matches("^" + regex + "$");
    }
    
    // ============================================================================
    // ERROR RESPONSE HANDLING
    // ============================================================================
    
    /**
     * Adds gateway-standard error responses to all operations.
     * Preserves backend error responses (409, 422, 429) and adds gateway errors (400, 500).
     */
    private void addGatewayErrorResponses(OpenAPI transformed, OpenAPI rawOas) {
        // First, ensure error schemas exist in components
        addErrorSchemas(transformed);
        
        if (transformed.getPaths() == null) return;
        
        // Collect backend error responses for reference
        Map<String, Map<String, ApiResponse>> backendErrors = collectBackendErrorResponses(rawOas);
        
        // Add error responses to each operation
        transformed.getPaths().forEach((path, pathItem) -> {
            addErrorResponsesToPathItem(pathItem, backendErrors, path);
        });
        
        log.debug("Added gateway error responses to all operations");
    }
    
    /**
     * Adds standard error schemas to components.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addErrorSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }
        
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        
        // Gateway Validation Error schema
        if (!schemas.containsKey("GatewayValidationError")) {
            Schema validationError = new Schema();
            validationError.setType("object");
            validationError.setDescription("Gateway validation error response");
            
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("statusCode", new Schema().type("integer").example(400));
            props.put("error", new Schema().type("string").example("Bad Request"));
            props.put("message", new Schema().type("string").example("Validation failed"));
            props.put("validationErrors", new Schema().type("array")
                .items(new Schema().$ref("#/components/schemas/ValidationErrorDetail")));
            
            validationError.setProperties(props);
            validationError.setRequired(Arrays.asList("statusCode", "error", "message"));
            schemas.put("GatewayValidationError", validationError);
        }
        
        // Validation Error Detail schema
        if (!schemas.containsKey("ValidationErrorDetail")) {
            Schema detail = new Schema();
            detail.setType("object");
            
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("path", new Schema().type("string").example("$.name"));
            props.put("message", new Schema().type("string").example("is required"));
            props.put("code", new Schema().type("string").example("required"));
            
            detail.setProperties(props);
            schemas.put("ValidationErrorDetail", detail);
        }
        
        // Gateway Internal Error schema
        if (!schemas.containsKey("GatewayInternalError")) {
            Schema internalError = new Schema();
            internalError.setType("object");
            internalError.setDescription("Gateway internal server error");
            
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("statusCode", new Schema().type("integer").example(500));
            props.put("error", new Schema().type("string").example("Internal Server Error"));
            props.put("message", new Schema().type("string").example("An unexpected error occurred"));
            props.put("requestId", new Schema().type("string").description("Unique request identifier for tracing"));
            
            internalError.setProperties(props);
            internalError.setRequired(Arrays.asList("statusCode", "error", "message"));
            schemas.put("GatewayInternalError", internalError);
        }
        
        // Rate Limit Error schema (429)
        if (!schemas.containsKey("RateLimitError")) {
            Schema rateLimitError = new Schema();
            rateLimitError.setType("object");
            rateLimitError.setDescription("Rate limit exceeded error");
            
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("statusCode", new Schema().type("integer").example(429));
            props.put("error", new Schema().type("string").example("Too Many Requests"));
            props.put("message", new Schema().type("string").example("Rate limit exceeded"));
            props.put("retryAfter", new Schema().type("integer").description("Seconds to wait before retrying"));
            
            rateLimitError.setProperties(props);
            schemas.put("RateLimitError", rateLimitError);
        }
    }
    
    /**
     * Collects error responses from backend OAS.
     */
    private Map<String, Map<String, ApiResponse>> collectBackendErrorResponses(OpenAPI rawOas) {
        Map<String, Map<String, ApiResponse>> result = new HashMap<>();
        
        if (rawOas.getPaths() == null) return result;
        
        // Collect unique error responses by status code
        rawOas.getPaths().forEach((path, pathItem) -> {
            collectErrorsFromOperation(pathItem.getGet(), result);
            collectErrorsFromOperation(pathItem.getPost(), result);
            collectErrorsFromOperation(pathItem.getPut(), result);
            collectErrorsFromOperation(pathItem.getPatch(), result);
            collectErrorsFromOperation(pathItem.getDelete(), result);
        });
        
        return result;
    }
    
    private void collectErrorsFromOperation(Operation op, Map<String, Map<String, ApiResponse>> collector) {
        if (op == null || op.getResponses() == null) return;
        
        op.getResponses().forEach((code, response) -> {
            // Collect 4xx and 5xx responses
            if (code.startsWith("4") || code.startsWith("5")) {
                collector.computeIfAbsent(code, k -> new HashMap<>())
                    .putIfAbsent("default", response);
            }
        });
    }
    
    /**
     * Adds error responses to all operations in a PathItem.
     */
    private void addErrorResponsesToPathItem(PathItem pathItem, 
            Map<String, Map<String, ApiResponse>> backendErrors, String path) {
        
        if (pathItem.getGet() != null) addErrorResponsesToOperation(pathItem.getGet(), backendErrors, "GET");
        if (pathItem.getPost() != null) addErrorResponsesToOperation(pathItem.getPost(), backendErrors, "POST");
        if (pathItem.getPut() != null) addErrorResponsesToOperation(pathItem.getPut(), backendErrors, "PUT");
        if (pathItem.getPatch() != null) addErrorResponsesToOperation(pathItem.getPatch(), backendErrors, "PATCH");
        if (pathItem.getDelete() != null) addErrorResponsesToOperation(pathItem.getDelete(), backendErrors, "DELETE");
    }
    
    /**
     * Adds standard error responses to an operation.
     * 400 validation errors are only added to POST/PUT/PATCH (methods with request bodies).
     */
    private void addErrorResponsesToOperation(Operation operation, 
            Map<String, Map<String, ApiResponse>> backendErrors, String method) {
        
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        
        ApiResponses responses = operation.getResponses();
        
        // Check if this is a method that can have request body validation
        boolean hasRequestBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        
        // Add 400 Bad Request (gateway validation) - ONLY for methods with request bodies
        if (hasRequestBody && !responses.containsKey("400")) {
            ApiResponse badRequest = new ApiResponse();
            badRequest.setDescription("Bad Request - Validation failed");
            badRequest.setContent(createJsonContent("#/components/schemas/GatewayValidationError"));
            responses.addApiResponse("400", badRequest);
        }
        
        // Add 401 Unauthorized
        if (!responses.containsKey("401")) {
            ApiResponse unauthorized = new ApiResponse();
            unauthorized.setDescription("Unauthorized - Authentication required");
            responses.addApiResponse("401", unauthorized);
        }
        
        // Add 403 Forbidden
        if (!responses.containsKey("403")) {
            ApiResponse forbidden = new ApiResponse();
            forbidden.setDescription("Forbidden - Insufficient permissions");
            responses.addApiResponse("403", forbidden);
        }
        
        // Add 404 Not Found
        if (!responses.containsKey("404")) {
            ApiResponse notFound = new ApiResponse();
            notFound.setDescription("Not Found - Resource does not exist");
            responses.addApiResponse("404", notFound);
        }
        
        // Add 409 Conflict (from backend, typically for POST)
        if (!responses.containsKey("409") && backendErrors.containsKey("409")) {
            if ("POST".equals(method) || "PUT".equals(method)) {
                ApiResponse conflict = new ApiResponse();
                conflict.setDescription("Conflict - Resource already exists or state conflict");
                responses.addApiResponse("409", conflict);
            }
        }
        
        // Add 422 Unprocessable Entity (from backend) - ONLY for methods with request bodies
        if (hasRequestBody && !responses.containsKey("422") && backendErrors.containsKey("422")) {
            ApiResponse unprocessable = new ApiResponse();
            unprocessable.setDescription("Unprocessable Entity - Semantic validation failed");
            responses.addApiResponse("422", unprocessable);
        }
        
        // Add 429 Too Many Requests (rate limiting)
        if (!responses.containsKey("429")) {
            ApiResponse rateLimit = new ApiResponse();
            rateLimit.setDescription("Too Many Requests - Rate limit exceeded");
            rateLimit.setContent(createJsonContent("#/components/schemas/RateLimitError"));
            responses.addApiResponse("429", rateLimit);
        }
        
        // Add 500 Internal Server Error
        if (!responses.containsKey("500")) {
            ApiResponse internalError = new ApiResponse();
            internalError.setDescription("Internal Server Error");
            internalError.setContent(createJsonContent("#/components/schemas/GatewayInternalError"));
            responses.addApiResponse("500", internalError);
        }
    }
    
    /**
     * Creates JSON content with a schema reference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Content createJsonContent(String schemaRef) {
        Content content = new Content();
        MediaType mediaType = new MediaType();
        mediaType.setSchema(new Schema().$ref(schemaRef));
        content.addMediaType("application/json", mediaType);
        return content;
    }
}
