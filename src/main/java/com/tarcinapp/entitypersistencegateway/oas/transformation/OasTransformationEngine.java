package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.swagger.v3.oas.models.parameters.Parameter;
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
@SuppressWarnings({"rawtypes", "unchecked"})
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
    
    private final com.tarcinapp.entitypersistencegateway.oas.service.BackendSchemaService backendSchemaService;
    private final com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService routeMetadataService;
    
    // Cache of route metadata for tag-based filtering
    private final Map<String, com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata> routeMetadataCache;

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
    
    @Value("${app.shortcode:app}")
    private String appShortcode;
    
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
            ObjectMapper objectMapper,
            com.tarcinapp.entitypersistencegateway.oas.service.BackendSchemaService backendSchemaService,
            com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService routeMetadataService) {
        this.openApiProperties = openApiProperties;
        this.orchestratorProperties = orchestratorProperties;
        this.togglesProperties = togglesProperties;
        this.objectMapper = objectMapper;
        this.backendSchemaService = backendSchemaService;
        this.routeMetadataService = routeMetadataService;
        
        // Pre-load route metadata for efficient tag-based filtering
        this.routeMetadataCache = routeMetadataService.getAllRouteMetadata();
        log.info("Loaded metadata for {} routes", routeMetadataCache.size());
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
        
        // 6b. Add standard 403 Forbidden responses to all operations
        addForbiddenResponseToAllOperations(transformed);
        
        // 7. Fix broken $ref references (e.g., #/definitions/X → #/components/schemas/X)
        fixBrokenRefs(transformed);
        
        // 8. Fix schema validation keywords (uniqueItems must be on array, not items)
        fixSchemaValidationKeywords(transformed);
        
        // 9. Deduplicate parameters (name+in must be unique per operation)
        deduplicateParameters(transformed);
        
        // 11. Apply global security requirement (JWT Bearer Auth)
        applyGlobalSecurity(transformed);
        
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
                
                // Skip entityReactions if tag is disabled
                if ("entityReactions".equals(controllerName) && isTagDisabled("entityReactions")) {
                    log.debug("Skipping entityReactions alias '{}' - 'entityReactions' tag is disabled", 
                        aliasConfig.getAlias());
                    return;
                }
                
                // Skip listReactions if tag is disabled
                if ("listReactions".equals(controllerName) && isTagDisabled("listReactions")) {
                    log.debug("Skipping listReactions alias '{}' - 'listReactions' tag is disabled", 
                        aliasConfig.getAlias());
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
        log.info("generateBaseControllerRoutes called: rawPaths.size={}, baseUri={}", 
            rawPaths != null ? rawPaths.size() : 0, baseUri);
        
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
            
            log.info("Processing controller: {}, inboundPath={}, backendPrefix={}", controllerName, inboundPath, backendPrefix);
            
            // Check if controller is disabled by toggles
            if (isControllerDisabled(controllerName)) {
                log.info("Skipping base routes for controller '{}' - disabled by toggles", controllerName);
                return;
            }
            
            // Check if the controller's own tag is disabled (e.g., "entityReactions", "listReactions")
            if (isTagDisabled(controllerName)) {
                log.info("Skipping base routes for controller '{}' - '{}' tag is disabled by toggles", controllerName, controllerName);
                return;
            }
            
            // Check if "generic" tag is disabled (base controller routes are generic, using kind parameter)
            if (isTagDisabled("generic")) {
                log.info("Skipping base routes for controller '{}' - 'generic' tag is disabled by toggles", controllerName);
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
                // Only add if at least one operation remains after filtering
                if (hasAnyOperation(transformed)) {
                    String fullPath = virtualPathPrefix;
                    if (!virtualizedPaths.containsKey(fullPath)) {
                        virtualizedPaths.addPathItem(fullPath, transformed);
                        log.debug("Added base controller route: {}", fullPath);
                    }
                }
            }
            
            // Count route: GET /entities/count
            PathItem countPathItem = rawPaths.get(backendPrefix + "/count");
            if (countPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(countPathItem, controllerName, tagName, false);
                // Only add if at least one operation remains after filtering
                if (hasAnyOperation(transformed)) {
                    String fullPath = virtualPathPrefix + "/count";
                    if (!virtualizedPaths.containsKey(fullPath)) {
                        virtualizedPaths.addPathItem(fullPath, transformed);
                        log.debug("Added base controller route: {}", fullPath);
                    }
                }
            }
            
            // Instance route: GET/PUT/PATCH/DELETE /entities/{id}
            PathItem instancePathItem = rawPaths.get(backendPrefix + "/{id}");
            if (instancePathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(instancePathItem, controllerName, tagName, true);
                // Only add if at least one operation remains after filtering
                if (hasAnyOperation(transformed)) {
                    String fullPath = virtualPathPrefix + "/{id}";
                    if (!virtualizedPaths.containsKey(fullPath)) {
                        virtualizedPaths.addPathItem(fullPath, transformed);
                        log.debug("Added base controller route: {}", fullPath);
                    }
                }
            }
            
            // Children route: GET/POST /entities/{id}/children
            // Operation-level filtering is handled by transformBaseControllerPathItem
            PathItem childrenPathItem = rawPaths.get(backendPrefix + "/{id}/children");
            if (childrenPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(childrenPathItem, controllerName, tagName, true);
                // Only add if at least one operation remains after filtering
                if (hasAnyOperation(transformed)) {
                    String fullPath = virtualPathPrefix + "/{id}/children";
                    if (!virtualizedPaths.containsKey(fullPath)) {
                        virtualizedPaths.addPathItem(fullPath, transformed);
                        log.debug("Added base controller route: {}", fullPath);
                    }
                }
            }
            
            // Parents route: GET /entities/{id}/parents
            // Operation-level filtering is handled by transformBaseControllerPathItem
            PathItem parentsPathItem = rawPaths.get(backendPrefix + "/{id}/parents");
            if (parentsPathItem != null) {
                PathItem transformed = transformBaseControllerPathItem(parentsPathItem, controllerName, tagName, true);
                // Only add if at least one operation remains after filtering
                if (hasAnyOperation(transformed)) {
                    String fullPath = virtualPathPrefix + "/{id}/parents";
                    if (!virtualizedPaths.containsKey(fullPath)) {
                        virtualizedPaths.addPathItem(fullPath, transformed);
                        log.debug("Added base controller route: {}", fullPath);
                    }
                }
            }
        });
    }
    
    /**
     * Transforms a PathItem for base controller routes (non-aliased).
     * These operations are tagged with the controller name (e.g., "Entities").
     * Operations are filtered based on route tag configuration (tagsOn/tagsOff).
     */
    private PathItem transformBaseControllerPathItem(PathItem original, String controllerName, String tagName, boolean isInstancePath) {
        PathItem transformed = new PathItem();
        transformed.setDescription(original.getDescription());
        
        log.info("transformBaseControllerPathItem: controller={}, tagName={}, isInstancePath={}", 
            controllerName, tagName, isInstancePath);
        
        // For each operation, check if it should be filtered based on route tags
        if (original.getGet() != null) {
            String routeId = original.getGet().getOperationId();
            boolean filtered = isOperationFilteredByTags(routeId);
            log.info("  GET operation: routeId={}, filtered={}", routeId, filtered);
            if (!filtered) {
                transformed.setGet(transformBaseControllerOperation(original.getGet(), controllerName, tagName, "get", isInstancePath));
            }
        }
        if (original.getPost() != null) {
            String routeId = original.getPost().getOperationId();
            boolean filtered = isOperationFilteredByTags(routeId);
            log.info("  POST operation: routeId={}, filtered={}", routeId, filtered);
            if (!filtered) {
                transformed.setPost(transformBaseControllerOperation(original.getPost(), controllerName, tagName, "post", isInstancePath));
            }
        }
        if (original.getPut() != null) {
            String routeId = original.getPut().getOperationId();
            boolean filtered = isOperationFilteredByTags(routeId);
            log.info("  PUT operation: routeId={}, filtered={}", routeId, filtered);
            if (!filtered) {
                transformed.setPut(transformBaseControllerOperation(original.getPut(), controllerName, tagName, "put", isInstancePath));
            }
        }
        if (original.getPatch() != null) {
            String routeId = original.getPatch().getOperationId();
            boolean filtered = isOperationFilteredByTags(routeId);
            log.info("  PATCH operation: routeId={}, filtered={}", routeId, filtered);
            if (!filtered) {
                transformed.setPatch(transformBaseControllerOperation(original.getPatch(), controllerName, tagName, "patch", isInstancePath));
            }
        }
        if (original.getDelete() != null) {
            String routeId = original.getDelete().getOperationId();
            boolean filtered = isOperationFilteredByTags(routeId);
            log.info("  DELETE operation: routeId={}, filtered={}", routeId, filtered);
            if (!filtered) {
                transformed.setDelete(transformBaseControllerOperation(original.getDelete(), controllerName, tagName, "delete", isInstancePath));
            }
        }
        
        return transformed;
    }
    
    /**
     * Checks if a PathItem has at least one operation (GET, POST, PUT, PATCH, DELETE).
     * Used to avoid adding empty path items after operation-level filtering.
     */
    private boolean hasAnyOperation(PathItem pathItem) {
        return pathItem.getGet() != null 
            || pathItem.getPost() != null 
            || pathItem.getPut() != null 
            || pathItem.getPatch() != null 
            || pathItem.getDelete() != null;
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
        transformed.setParameters(cleanParameters(original.getParameters()));
        
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
        // Operation-level filtering is handled inside generateHierarchyPaths
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
     * 
     * Tag filtering is applied using the KindAlias route IDs from application-routes.yml
     * to ensure OAS generation matches runtime behavior of CheckIfRouteEnabled filter.
     */
    private PathItem transformPathItemForHierarchy(
            PathItem original,
            AliasConfig childAlias,
            String controllerName,
            String parentTagName,
            AliasConfig parentAlias,
            String relationType) {
        
        PathItem transformed = new PathItem();
        
        String parentResourceName = capitalizeFirst(getSingular(parentAlias));
        String childResourceName = capitalizeFirst(childAlias.getAlias());
        
        transformed.setDescription(String.format(
            "%s of a %s", childResourceName, parentResourceName.toLowerCase()
        ));
        
        // Determine the base controller name for mapping to KindAlias route IDs
        // e.g., "entities" from "entitiesKindAlias" or just "entities"
        String baseController = controllerName.replace("KindAlias", "");
        
        // Transform each HTTP method's operation with tag filtering and hierarchy-aware summaries
        if (original.getGet() != null) {
            // Map to KindAlias route: e.g., findEntityChildrenByKindAlias or findEntityParentsByKindAlias
            String backendOpId = original.getGet().getOperationId();
            String kindAliasRouteId = mapHierarchyOperationToKindAliasRouteId(backendOpId, baseController, relationType, "get");
            // Determine the base route ID for route-level schema lookup (e.g., findEntityChildren)
            String baseRouteId = mapHttpMethodToHierarchyRouteId(baseController, relationType, "get");
            
            if (!isKindAliasOperationFilteredByTags(kindAliasRouteId, backendOpId)) {
                transformed.setGet(transformHierarchyOperation(
                    original.getGet(), childAlias, parentAlias, parentTagName, "get", relationType, baseRouteId
                ));
            }
        }
        if (original.getPost() != null) {
            String backendOpId = original.getPost().getOperationId();
            String kindAliasRouteId = mapHierarchyOperationToKindAliasRouteId(backendOpId, baseController, relationType, "post");
            String baseRouteId = mapHttpMethodToHierarchyRouteId(baseController, relationType, "post");
            
            if (!isKindAliasOperationFilteredByTags(kindAliasRouteId, backendOpId)) {
                transformed.setPost(transformHierarchyOperation(
                    original.getPost(), childAlias, parentAlias, parentTagName, "post", relationType, baseRouteId
                ));
            }
        }
        if (original.getPut() != null) {
            String backendOpId = original.getPut().getOperationId();
            String kindAliasRouteId = mapHierarchyOperationToKindAliasRouteId(backendOpId, baseController, relationType, "put");
            String baseRouteId = mapHttpMethodToHierarchyRouteId(baseController, relationType, "put");
            
            if (!isKindAliasOperationFilteredByTags(kindAliasRouteId, backendOpId)) {
                transformed.setPut(transformHierarchyOperation(
                    original.getPut(), childAlias, parentAlias, parentTagName, "put", relationType, baseRouteId
                ));
            }
        }
        if (original.getPatch() != null) {
            String backendOpId = original.getPatch().getOperationId();
            String kindAliasRouteId = mapHierarchyOperationToKindAliasRouteId(backendOpId, baseController, relationType, "patch");
            String baseRouteId = mapHttpMethodToHierarchyRouteId(baseController, relationType, "patch");
            
            if (!isKindAliasOperationFilteredByTags(kindAliasRouteId, backendOpId)) {
                transformed.setPatch(transformHierarchyOperation(
                    original.getPatch(), childAlias, parentAlias, parentTagName, "patch", relationType, baseRouteId
                ));
            }
        }
        if (original.getDelete() != null) {
            String backendOpId = original.getDelete().getOperationId();
            String kindAliasRouteId = mapHierarchyOperationToKindAliasRouteId(backendOpId, baseController, relationType, "delete");
            String baseRouteId = mapHttpMethodToHierarchyRouteId(baseController, relationType, "delete");
            
            if (!isKindAliasOperationFilteredByTags(kindAliasRouteId, backendOpId)) {
                transformed.setDelete(transformHierarchyOperation(
                    original.getDelete(), childAlias, parentAlias, parentTagName, "delete", relationType, baseRouteId
                ));
            }
        }
        
        return transformed;
    }
    
    /**
     * Maps HTTP method + controller + relation type to the hierarchy route ID used in configuration.
     * These route IDs match what's used in children[n].routes.{routeId}.schema
     * 
     * @param baseController The base controller name (e.g., "entities", "lists")
     * @param relationType "children" or "parents"
     * @param httpMethod The HTTP method (get, post, etc.)
     * @return The route ID for configuration lookup (e.g., "createEntityChild", "findEntityChildren")
     */
    private String mapHttpMethodToHierarchyRouteId(String baseController, String relationType, String httpMethod) {
        String controllerSingular = capitalizeFirst(singularize(baseController));
        
        if ("children".equals(relationType)) {
            if ("get".equalsIgnoreCase(httpMethod)) {
                return "find" + controllerSingular + "Children";
            } else if ("post".equalsIgnoreCase(httpMethod)) {
                return "create" + controllerSingular + "Child";
            }
        } else if ("parents".equals(relationType)) {
            if ("get".equalsIgnoreCase(httpMethod)) {
                return "find" + controllerSingular + "Parents";
            }
        }
        
        // Fallback
        return httpMethod + controllerSingular + capitalizeFirst(relationType);
    }
    
    /**
     * Maps a hierarchy operation to its KindAlias route ID.
     * 
     * @param backendOpId The backend operationId (e.g., findChildrenByEntityId)
     * @param baseController The base controller name (e.g., entities, lists)
     * @param relationType "children" or "parents"
     * @param httpMethod The HTTP method (get, post, etc.)
     * @return The KindAlias route ID (e.g., findEntityChildrenByKindAlias)
     */
    private String mapHierarchyOperationToKindAliasRouteId(String backendOpId, String baseController, String relationType, String httpMethod) {
        // Normalize controller name to singular capitalized form
        String controllerSingular = capitalizeFirst(singularize(baseController));
        
        if ("children".equals(relationType)) {
            if ("get".equalsIgnoreCase(httpMethod)) {
                // findChildrenByEntityId → findEntityChildrenByKindAlias
                return "find" + controllerSingular + "ChildrenByKindAlias";
            } else if ("post".equalsIgnoreCase(httpMethod)) {
                // createChildEntity → createEntityChildByKindAlias
                return "create" + controllerSingular + "ChildByKindAlias";
            }
        } else if ("parents".equals(relationType)) {
            if ("get".equalsIgnoreCase(httpMethod)) {
                // findParentsByEntityId → findEntityParentsByKindAlias
                return "find" + controllerSingular + "ParentsByKindAlias";
            }
        }
        
        // Fallback for unknown combinations
        log.warn("Unknown hierarchy operation mapping: backendOpId={}, controller={}, relationType={}, httpMethod={}", 
            backendOpId, baseController, relationType, httpMethod);
        return backendOpId;
    }
    
    /**
     * Checks if a KindAlias operation should be filtered based on tag-based route toggles.
     * This is a simpler version that directly uses the KindAlias route ID for lookup.
     */
    private boolean isKindAliasOperationFilteredByTags(String kindAliasRouteId, String backendOpId) {
        // Get tag filtering configuration
        List<String> tagsOn = normalizeList(togglesProperties.getTags().getOn());
        List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());
        
        // If no tag filtering is configured, don't filter anything
        if (tagsOn.isEmpty() && tagsOff.isEmpty()) {
            return false;
        }
        
        // Get metadata for the KindAlias route
        com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata kindAliasMetadata = 
            routeMetadataCache.get(kindAliasRouteId);
        
        List<String> effectiveTags;
        
        if (kindAliasMetadata != null && kindAliasMetadata.getTags() != null && !kindAliasMetadata.getTags().isEmpty()) {
            effectiveTags = kindAliasMetadata.getTags();
            log.debug("Tag filter for hierarchy route '{}': using tags: {}", kindAliasRouteId, effectiveTags);
        } else {
            // No metadata found - allow by default
            log.warn("Tag filter for hierarchy route '{}': no metadata found, allowing by default", kindAliasRouteId);
            return false;
        }
        
        // Apply tag filtering logic
        if (!tagsOn.isEmpty()) {
            boolean hasTag = effectiveTags.stream().anyMatch(tag -> 
                tagsOn.stream().anyMatch(enabledTag -> enabledTag.equalsIgnoreCase(tag)));
            boolean filtered = !hasTag;
            log.debug("Tag filter (whitelist) for hierarchy route '{}': hasTag={}, filtered={}", 
                kindAliasRouteId, hasTag, filtered);
            return filtered;
        } else if (!tagsOff.isEmpty()) {
            boolean hasTag = effectiveTags.stream().anyMatch(tag -> 
                tagsOff.stream().anyMatch(disabledTag -> disabledTag.equalsIgnoreCase(tag)));
            log.debug("Tag filter (blacklist) for hierarchy route '{}': hasDisabledTag={}, filtered={}", 
                kindAliasRouteId, hasTag, hasTag);
            return hasTag;
        }
        
        return false;
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
            String relationType,
            String baseRouteId) {
        
        Operation transformed = new Operation();
        
        // Copy and clean parameters (remove tsType from inline schemas)
        transformed.setParameters(cleanParameters(original.getParameters()));
        
        // Add advanced 'set' parameter to GET operations (list operations for children/parents)
        if ("get".equalsIgnoreCase(httpMethod)) {
            addSetParameterIfNeeded(transformed);
        }
        
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
        
        // Set x-original-route-id extension for schema binding lookup
        // This allows resolveRequestBodySchemaName to find hierarchy-route-specific schemas
        if (baseRouteId != null) {
            Map<String, Object> extensions = transformed.getExtensions();
            if (extensions == null) {
                extensions = new LinkedHashMap<>();
                transformed.setExtensions(extensions);
            }
            extensions.put("x-original-route-id", baseRouteId);
        }
        
        String parentSingular = getSingular(parentAlias);
        String childPlural = childAlias.getAlias();
        String childSingular = getSingular(childAlias);
        
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
        
        // Apply route-level overrides from childAlias config (operationId, tags, summary, description)
        RouteConfig routeConfig = (childAlias.getRoutes() != null && baseRouteId != null)
            ? childAlias.getRoutes().get(baseRouteId)
            : null;
        
        if (routeConfig != null) {
            if (routeConfig.getOperationId() != null) {
                operationId = routeConfig.getOperationId();
            }
            if (routeConfig.getSummary() != null) {
                summary = routeConfig.getSummary();
            }
            if (routeConfig.getDescription() != null) {
                transformed.setDescription(routeConfig.getDescription());
            }
        }
        
        transformed.setOperationId(operationId);
        transformed.setSummary(summary);
        
        // CRITICAL: Tag under PARENT, not child - maintains proper grouping
        // Apply tags override from childAlias route config if present
        if (routeConfig != null && routeConfig.getTags() != null && !routeConfig.getTags().isEmpty()) {
            transformed.setTags(routeConfig.getTags());
        } else {
            transformed.setTags(Collections.singletonList(parentTagName));
        }
        
        return transformed;
    }
    
    /**
     * Transforms a PathItem by rewriting operations for aliased routes.
     * 
     * Tag filtering for aliased routes works as follows:
     * 1. First, compute the aliased operationId (e.g., "listBook" for books alias)
     * 2. Check if there's route metadata for that aliased operationId
     * 3. If yes, use those tags for filtering
     * 4. If no, derive tags from the base generic route but EXCLUDE 'generic' tag
     *    (since aliased routes are domain-specific, not generic)
     * 
     * This allows users to:
     * - Define specific tags for aliased routes in application-routes.yml
     * - Use tagsOn/tagsOff to filter any route including aliased ones
     * - Filter out generic routes while keeping aliased routes visible
     * 
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
        
        // Transform each HTTP method's operation with proper tag filtering for aliased routes
        if (original.getGet() != null) {
            String originalOpId = original.getGet().getOperationId();
            String aliasedOpId = generateOperationId(originalOpId, aliasConfig);
            if (!isAliasedOperationFilteredByTags(aliasedOpId, originalOpId)) {
                Operation op = transformOperation(
                    original.getGet(), aliasConfig, controllerName, "get", isInstancePath, tagName
                );
                if (op != null) {
                    transformed.setGet(op);
                }
            }
        }
        if (original.getPost() != null) {
            String originalOpId = original.getPost().getOperationId();
            String aliasedOpId = generateOperationId(originalOpId, aliasConfig);
            if (!isAliasedOperationFilteredByTags(aliasedOpId, originalOpId)) {
                Operation op = transformOperation(
                    original.getPost(), aliasConfig, controllerName, "post", isInstancePath, tagName
                );
                if (op != null) {
                    transformed.setPost(op);
                }
            }
        }
        if (original.getPut() != null) {
            String originalOpId = original.getPut().getOperationId();
            String aliasedOpId = generateOperationId(originalOpId, aliasConfig);
            if (!isAliasedOperationFilteredByTags(aliasedOpId, originalOpId)) {
                Operation op = transformOperation(
                    original.getPut(), aliasConfig, controllerName, "put", isInstancePath, tagName
                );
                if (op != null) {
                    transformed.setPut(op);
                }
            }
        }
        if (original.getPatch() != null) {
            String originalOpId = original.getPatch().getOperationId();
            String aliasedOpId = generateOperationId(originalOpId, aliasConfig);
            if (!isAliasedOperationFilteredByTags(aliasedOpId, originalOpId)) {
                Operation op = transformOperation(
                    original.getPatch(), aliasConfig, controllerName, "patch", isInstancePath, tagName
                );
                if (op != null) {
                    transformed.setPatch(op);
                }
            }
        }
        if (original.getDelete() != null) {
            String originalOpId = original.getDelete().getOperationId();
            String aliasedOpId = generateOperationId(originalOpId, aliasConfig);
            if (!isAliasedOperationFilteredByTags(aliasedOpId, originalOpId)) {
                Operation op = transformOperation(
                    original.getDelete(), aliasConfig, controllerName, "delete", isInstancePath, tagName
                );
                if (op != null) {
                    transformed.setDelete(op);
                }
            }
        }
        
        return transformed;
    }
    
    /**
     * Checks if an aliased operation should be filtered based on tag-based route toggles.
     * 
     * This method implements proper tag filtering for aliased routes:
     * 1. First checks if there's specific route metadata for the aliased operationId
     * 2. If yes, uses those tags for filtering
     * 3. If no, derives tags from the base generic route but EXCLUDES 'generic' tag
     *    (since aliased routes are domain-specific, not generic)
     * 
     * @param aliasedOperationId The generated aliased operation ID (e.g., "listBook")
     * @param originalOperationId The original backend operation ID (e.g., "findEntities")
     * @return true if the operation should be filtered out, false otherwise
     */
    private boolean isAliasedOperationFilteredByTags(String aliasedOperationId, String originalOperationId) {
        // Get tag filtering configuration
        List<String> tagsOn = normalizeList(togglesProperties.getTags().getOn());
        List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());
        
        // If no tag filtering is configured, don't filter anything
        if (tagsOn.isEmpty() && tagsOff.isEmpty()) {
            return false;
        }
        
        // Map the backend operationId to the correct KindAlias route ID
        // This is the route ID that CheckIfRouteEnabled filter uses at runtime
        String kindAliasRouteId = mapBackendOperationIdToKindAliasRouteId(originalOperationId);
        
        // Get metadata for the KindAlias route
        com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata kindAliasMetadata = 
            routeMetadataCache.get(kindAliasRouteId);
        
        List<String> effectiveTags;
        
        if (kindAliasMetadata != null && kindAliasMetadata.getTags() != null && !kindAliasMetadata.getTags().isEmpty()) {
            // Use the KindAlias route's tags (these don't have 'generic' tag)
            effectiveTags = kindAliasMetadata.getTags();
            log.info("Tag filter for aliased route '{}': using KindAlias route '{}' tags: {}", 
                aliasedOperationId, kindAliasRouteId, effectiveTags);
        } else {
            // Fallback: no specific KindAlias route metadata found
            // This shouldn't happen if application-routes.yml is complete
            log.warn("Tag filter for aliased route '{}': no metadata found for KindAlias route '{}', allowing by default", 
                aliasedOperationId, kindAliasRouteId);
            return false;
        }
        
        // Apply tag filtering logic
        if (!tagsOn.isEmpty()) {
            // Whitelist mode: keep only routes that have at least one of the enabled tags
            boolean hasTag = effectiveTags.stream().anyMatch(tag -> 
                tagsOn.stream().anyMatch(enabledTag -> enabledTag.equalsIgnoreCase(tag)));
            boolean filtered = !hasTag;
            log.info("Tag filter (whitelist) for aliased route '{}': hasTag={}, filtered={}", 
                aliasedOperationId, hasTag, filtered);
            return filtered;
        } else if (!tagsOff.isEmpty()) {
            // Blacklist mode: filter out routes that have any of the disabled tags
            boolean hasTag = effectiveTags.stream().anyMatch(tag -> 
                tagsOff.stream().anyMatch(disabledTag -> disabledTag.equalsIgnoreCase(tag)));
            log.info("Tag filter (blacklist) for aliased route '{}': hasDisabledTag={}, filtered={}", 
                aliasedOperationId, hasTag, hasTag);
            return hasTag;
        }
        
        return false;
    }
    
    /**
     * Maps a backend operationId to the corresponding KindAlias route ID.
     * These are the route IDs that CheckIfRouteEnabled filter uses at runtime.
     * 
     * Mapping pattern (for entities controller):
     * - findEntities → findAllEntitiesByKindAlias
     * - createEntity → createEntityByKindAlias
     * - findEntityById → findEntityByIdByKindAlias
     * - replaceEntityById → replaceEntityByIdByKindAlias
     * - updateEntityById → updateEntityByIdByKindAlias
     * - deleteEntityById → deleteEntityByIdByKindAlias
     * - countEntities → countEntitiesByKindAlias
     * - updateEntities → updateAllEntitiesByKindAlias
     * - findChildrenByEntityId → findEntityChildrenByKindAlias
     * - createChildEntity → createEntityChildByKindAlias
     * - findParentsByEntityId → findEntityParentsByKindAlias
     * 
     * Similar patterns apply for lists, relations, entityReactions, listReactions controllers.
     */
    private String mapBackendOperationIdToKindAliasRouteId(String backendOperationId) {
        if (backendOperationId == null) {
            return null;
        }
        
        // Define mappings for all controller types
        // The pattern: backend operationId -> KindAlias route ID
        
        // Entities
        if (backendOperationId.equals("findEntities")) return "findAllEntitiesByKindAlias";
        if (backendOperationId.equals("createEntity")) return "createEntityByKindAlias";
        if (backendOperationId.equals("findEntityById")) return "findEntityByIdByKindAlias";
        if (backendOperationId.equals("replaceEntityById")) return "replaceEntityByIdByKindAlias";
        if (backendOperationId.equals("updateEntityById")) return "updateEntityByIdByKindAlias";
        if (backendOperationId.equals("deleteEntityById")) return "deleteEntityByIdByKindAlias";
        if (backendOperationId.equals("countEntities")) return "countEntitiesByKindAlias";
        if (backendOperationId.equals("updateEntities")) return "updateAllEntitiesByKindAlias";
        if (backendOperationId.equals("findChildrenByEntityId")) return "findEntityChildrenByKindAlias";
        if (backendOperationId.equals("createChildEntity")) return "createEntityChildByKindAlias";
        if (backendOperationId.equals("findParentsByEntityId")) return "findEntityParentsByKindAlias";
        
        // Lists
        if (backendOperationId.equals("findLists")) return "findAllListsByKindAlias";
        if (backendOperationId.equals("createList")) return "createListByKindAlias";
        if (backendOperationId.equals("findListById")) return "findListByIdByKindAlias";
        if (backendOperationId.equals("replaceListById")) return "replaceListByIdByKindAlias";
        if (backendOperationId.equals("updateListById")) return "updateListByIdByKindAlias";
        if (backendOperationId.equals("deleteListById")) return "deleteListByIdByKindAlias";
        if (backendOperationId.equals("countLists")) return "countListsByKindAlias";
        if (backendOperationId.equals("updateLists")) return "updateAllListsByKindAlias";
        if (backendOperationId.equals("findChildrenByListId")) return "findListChildrenByKindAlias";
        if (backendOperationId.equals("createChildList")) return "createListChildByKindAlias";
        if (backendOperationId.equals("findParentsByListId")) return "findListParentsByKindAlias";
        
        // Relations
        if (backendOperationId.equals("findRelations")) return "findAllRelationsByKindAlias";
        if (backendOperationId.equals("createRelation")) return "createRelationByKindAlias";
        if (backendOperationId.equals("findRelationById")) return "findRelationByIdByKindAlias";
        if (backendOperationId.equals("replaceRelationById")) return "replaceRelationByIdByKindAlias";
        if (backendOperationId.equals("updateRelationById")) return "updateRelationByIdByKindAlias";
        if (backendOperationId.equals("deleteRelationById")) return "deleteRelationByIdByKindAlias";
        if (backendOperationId.equals("countRelations")) return "countRelationsByKindAlias";
        if (backendOperationId.equals("updateRelations")) return "updateAllRelationsByKindAlias";
        
        // Entity Reactions
        if (backendOperationId.equals("findEntityReactions")) return "findAllEntityReactionsByKindAlias";
        if (backendOperationId.equals("createEntityReaction")) return "createEntityReactionByKindAlias";
        if (backendOperationId.equals("findEntityReactionById")) return "findEntityReactionByIdByKindAlias";
        if (backendOperationId.equals("replaceEntityReactionById")) return "replaceEntityReactionByIdByKindAlias";
        if (backendOperationId.equals("updateEntityReactionById")) return "updateEntityReactionByIdByKindAlias";
        if (backendOperationId.equals("deleteEntityReactionById")) return "deleteEntityReactionByIdByKindAlias";
        if (backendOperationId.equals("countEntityReactions")) return "countEntityReactionsByKindAlias";
        if (backendOperationId.equals("updateEntityReactions")) return "updateAllEntityReactionsByKindAlias";
        if (backendOperationId.equals("findChildrenByEntityReactionId")) return "findEntityReactionChildrenByKindAlias";
        if (backendOperationId.equals("createChildEntityReaction")) return "createEntityReactionChildByKindAlias";
        if (backendOperationId.equals("findParentsByEntityReactionId")) return "findEntityReactionParentsByKindAlias";
        
        // List Reactions
        if (backendOperationId.equals("findListReactions")) return "findAllListReactionsByKindAlias";
        if (backendOperationId.equals("createListReaction")) return "createListReactionByKindAlias";
        if (backendOperationId.equals("findListReactionById")) return "findListReactionByIdByKindAlias";
        if (backendOperationId.equals("replaceListReactionById")) return "replaceListReactionByIdByKindAlias";
        if (backendOperationId.equals("updateListReactionById")) return "updateListReactionByIdByKindAlias";
        if (backendOperationId.equals("deleteListReactionById")) return "deleteListReactionByIdByKindAlias";
        if (backendOperationId.equals("countListReactions")) return "countListReactionsByKindAlias";
        if (backendOperationId.equals("updateListReactions")) return "updateAllListReactionsByKindAlias";
        if (backendOperationId.equals("findChildrenByListReactionId")) return "findListReactionChildrenByKindAlias";
        if (backendOperationId.equals("createChildListReaction")) return "createListReactionChildByKindAlias";
        if (backendOperationId.equals("findParentsByListReactionId")) return "findListReactionParentsByKindAlias";
        
        // Unknown - return original (will likely fail metadata lookup, which is correct behavior)
        log.warn("No KindAlias route mapping found for backend operationId: {}", backendOperationId);
        return backendOperationId;
    }
    
    /**
     * Checks if an operation should be filtered based on tag-based route toggles.
     * Uses the original operation ID to lookup route metadata and check tags.
     */
    private boolean isOperationFilteredByTags(String originalOperationId) {
        if (originalOperationId == null) {
            return false;
        }
        
        // The originalOperationId in backend OAS typically matches route IDs
        // E.g., "findEntities", "createEntity", "findEntityChildren", etc.
        return isRouteFilteredByTags(originalOperationId);
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
        
        // Copy and clean parameters (remove tsType from inline schemas)
        transformed.setParameters(cleanParameters(original.getParameters()));
        
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
        
        // Store original route ID as extension for later schema binding
        // This allows us to map from aliased operation back to route-specific schemas
        if (routeId != null) {
            transformed.addExtension("x-original-route-id", routeId);
        }
        
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
        String singular = getSingular(aliasConfig);
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
        String resourceName = capitalizeFirst(getSingular(aliasConfig));
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
     * 
     * <p>This method performs the following transformations:</p>
     * <ul>
     *   <li>Optionally simplifies schema names</li>
     *   <li>Removes tsType references from schema descriptions (internal TS type hints)</li>
     *   <li>Removes x-typescript-type extensions</li>
     *   <li>Adds JWT Bearer security scheme if not already present</li>
     *   <li>Adds standard error response schemas (403 Forbidden)</li>
     * </ul>
     */
    private Components transformComponents(Components original) {
        Components transformed = new Components();
        
        if (original.getSchemas() != null) {
            Map<String, Schema> transformedSchemas = new LinkedHashMap<>();
            
            original.getSchemas().forEach((name, schema) -> {
                // Replace "loopback" in schema names with app shortcode
                String renamedSchema = name
                    .replace("loopback.", "")
                    .replace("Loopback.", "")
                    .replace("loopback", appShortcode)
                    .replace("Loopback", capitalizeFirst(appShortcode));
                
                String newName = orchestratorProperties.getTransformation().isSimplifySchemaNames()
                    ? simplifySchemaName(renamedSchema)
                    : renamedSchema;
                
                // Clean up schema: remove tsType from descriptions and x-typescript-type extension
                Schema<?> cleanedSchema = cleanupSchema(schema);
                transformedSchemas.put(newName, cleanedSchema);
            });
            
            // Add standard error response schemas
            addStandardErrorSchemas(transformedSchemas);
            
            transformed.setSchemas(transformedSchemas);
        } else {
            Map<String, Schema> schemas = new LinkedHashMap<>();
            addStandardErrorSchemas(schemas);
            transformed.setSchemas(schemas);
        }
        
        // Add JWT Bearer security scheme
        Map<String, io.swagger.v3.oas.models.security.SecurityScheme> securitySchemes = 
            original.getSecuritySchemes() != null 
                ? new LinkedHashMap<>(original.getSecuritySchemes())
                : new LinkedHashMap<>();
        
        // Ensure bearerAuth scheme exists
        if (!securitySchemes.containsKey("bearerAuth")) {
            io.swagger.v3.oas.models.security.SecurityScheme bearerScheme = 
                new io.swagger.v3.oas.models.security.SecurityScheme();
            bearerScheme.setType(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP);
            bearerScheme.setScheme("bearer");
            bearerScheme.setBearerFormat("JWT");
            bearerScheme.setDescription("JWT Bearer Authentication. Include 'Authorization: Bearer <token>' header.");
            securitySchemes.put("bearerAuth", bearerScheme);
            log.debug("Added bearerAuth security scheme to OAS");
        }
        
        transformed.setSecuritySchemes(securitySchemes);
        
        // Copy other component types as-is
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
     * Adds standard error response schemas to components.
     * Currently adds the 403 Forbidden error schema.
     */
    private void addStandardErrorSchemas(Map<String, Schema> schemas) {
        // Create 403 Forbidden Error schema
        Schema<Object> errorDetailsSchema = new Schema<>();
        errorDetailsSchema.setType("object");
        errorDetailsSchema.setDescription("Error response details");
        
        Map<String, Schema> errorProperties = new LinkedHashMap<>();
        
        Schema<Integer> statusCodeSchema = new Schema<>();
        statusCodeSchema.setType("integer");
        statusCodeSchema.setDescription("HTTP status code");
        statusCodeSchema.setExample(403);
        errorProperties.put("statusCode", statusCodeSchema);
        
        Schema<String> nameSchema = new Schema<>();
        nameSchema.setType("string");
        nameSchema.setDescription("Error name");
        nameSchema.setExample("ForbiddenError");
        errorProperties.put("name", nameSchema);
        
        Schema<String> messageSchema = new Schema<>();
        messageSchema.setType("string");
        messageSchema.setDescription("Human-readable error message");
        messageSchema.setExample("Access Denied by Policy");
        errorProperties.put("message", messageSchema);
        
        Schema<String> codeSchema = new Schema<>();
        codeSchema.setType("string");
        codeSchema.setDescription("Application error code");
        codeSchema.setExample("GATEWAY-FORBIDDEN");
        errorProperties.put("code", codeSchema);
        
        Schema<String> requestIdSchema = new Schema<>();
        requestIdSchema.setType("string");
        requestIdSchema.setDescription("Unique request identifier for tracking");
        String requestIdExample = appShortcode.toUpperCase() + "-POSTMAN-20260114173558214-FXBOM";
        requestIdSchema.setExample(requestIdExample);
        errorProperties.put("requestId", requestIdSchema);
        
        Schema<String> pathSchema = new Schema<>();
        pathSchema.setType("string");
        pathSchema.setDescription("Request path that was denied");
        pathSchema.setExample("/api/v1/entities");
        errorProperties.put("path", pathSchema);
        
        errorDetailsSchema.setProperties(errorProperties);
        
        // Create wrapper schema with error property
        Schema<Object> forbiddenErrorSchema = new Schema<>();
        forbiddenErrorSchema.setType("object");
        forbiddenErrorSchema.setDescription("403 Forbidden error response");
        
        Map<String, Schema> wrapperProperties = new LinkedHashMap<>();
        wrapperProperties.put("error", errorDetailsSchema);
        forbiddenErrorSchema.setProperties(wrapperProperties);
        
        schemas.put("ForbiddenErrorResponse", forbiddenErrorSchema);
        log.debug("Added ForbiddenErrorResponse schema");
    }
    
    /**
     * Adds 403 Forbidden response to all operations.
     * This ensures consistent error response documentation across all protected endpoints.
     */
    private void addForbiddenResponseToAllOperations(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        
        openApi.getPaths().forEach((path, pathItem) -> {
            if (pathItem != null) {
                addForbiddenResponseToOperation(pathItem.getGet());
                addForbiddenResponseToOperation(pathItem.getPost());
                addForbiddenResponseToOperation(pathItem.getPut());
                addForbiddenResponseToOperation(pathItem.getPatch());
                addForbiddenResponseToOperation(pathItem.getDelete());
            }
        });
    }
    
    /**
     * Adds 403 Forbidden response to a single operation if it doesn't already have one.
     */
    private void addForbiddenResponseToOperation(Operation operation) {
        if (operation == null) {
            return;
        }
        
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        
        // Add 403 response if not already present
        if (!operation.getResponses().containsKey("403")) {
            ApiResponse forbiddenResponse = new ApiResponse();
            forbiddenResponse.setDescription("Access Denied by Policy - User not authorized to access this resource");
            
            // Create content with schema reference
            Content content = new Content();
            MediaType mediaType = new MediaType();
            Schema<Object> schemaRef = new Schema<>();
            schemaRef.set$ref("#/components/schemas/ForbiddenErrorResponse");
            mediaType.setSchema(schemaRef);
            content.addMediaType("application/json", mediaType);
            forbiddenResponse.setContent(content);
            
            operation.getResponses().addApiResponse("403", forbiddenResponse);
        }
    }
    
    /**
     * Cleans up a schema by removing tsType references from descriptions
     * and removing x-typescript-type extensions.
     * 
     * <p>The backend LoopBack OAS includes internal TypeScript type hints like:</p>
     * <pre>
     * "description": "(tsType: Omit&lt;Entity, '_id'&gt;, schemaOptions: {...})"
     * "x-typescript-type": "Omit&lt;Entity, '_id'&gt;"
     * </pre>
     * 
     * <p>These internal references must be removed from the public API documentation.</p>
     * 
     * @param schema The original schema from backend
     * @return Cleaned schema without tsType references
     */
    private Schema<?> cleanupSchema(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        
        // Replace "loopback" in schema title with app shortcode
        String title = schema.getTitle();
        if (title != null) {
            if (title.contains("loopback") || title.contains("Loopback")) {
                String newTitle = title
                    .replace("loopback.", "")
                    .replace("Loopback.", "")
                    .replace("loopback", appShortcode)
                    .replace("Loopback", capitalizeFirst(appShortcode));
                schema.setTitle(newTitle);
            } else if (shouldRemoveTitle(title)) {
                // Clean up verbose titles that reveal internal field names
                schema.setTitle(null);
            }
        }
        
        // Remove tsType and schemaOptions from description
        String description = schema.getDescription();
        if (description != null && (description.contains("tsType:") || description.contains("schemaOptions:"))) {
            // Pattern 1: "(tsType: ..., schemaOptions: {...})" or just "(tsType: ...)"
            // Pattern 2: "(schemaOptions: {...})" alone
            // Pattern 3: Raw TypeScript type definitions like "Omit<Partial<Entity>,...>"
            String cleaned = description
                .replaceAll("(?s)\\(tsType:.*?\\)", "")  // Remove (tsType: ...)
                .replaceAll("(?s)\\(schemaOptions:.*?\\)", "")  // Remove (schemaOptions: ...)
                .replaceAll("(?s)Omit<[^>]+>", "")  // Remove Omit<...>
                .replaceAll("(?s)Partial<[^>]+>", "")  // Remove Partial<...>
                .trim();
            // If description becomes empty or only whitespace, set to null
            if (cleaned.isEmpty()) {
                schema.setDescription(null);
            } else {
                schema.setDescription(cleaned);
            }
        }
        
        // Remove x-typescript-type extension
        if (schema.getExtensions() != null) {
            schema.getExtensions().remove("x-typescript-type");
        }
        
        // Recursively clean nested schemas (properties, items, allOf, etc.)
        if (schema.getProperties() != null) {
            schema.getProperties().forEach((propName, propSchema) -> {
                cleanupSchema((Schema<?>) propSchema);
            });
        }
        
        if (schema.getItems() != null) {
            cleanupSchema(schema.getItems());
        }
        
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(s -> cleanupSchema((Schema<?>) s));
        }
        
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(s -> cleanupSchema((Schema<?>) s));
        }
        
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(s -> cleanupSchema((Schema<?>) s));
        }
        
        return schema;
    }
    
    /**
     * Determines if a schema title should be removed because it exposes internal implementation details.
     * 
     * Patterns that indicate internal/verbose titles:
     * - Contains "Excluding__" (LoopBack generated exclusion types)
     * - Contains "__" (double underscores - internal naming)
     * - Contains "-_" (field exclusion patterns)
     * - Contains "idempotencyKey" (internal field name)
     * - Contains "ownerUsers" or "ownerGroups" (internal permission fields)
     * - Contains "viewerUsers" or "viewerGroups" (internal permission fields)
     * - Contains "parentsCount" or "childrenCount" (internal count fields)
     * - Excessively long (> 60 characters - likely auto-generated)
     * - Contains "Count_" pattern (internal count field exposure)
     */
    private boolean shouldRemoveTitle(String title) {
        if (title == null) {
            return false;
        }
        
        // Excessively long titles are auto-generated and expose internal details
        if (title.length() > 60) {
            return true;
        }
        
        // Patterns that indicate internal field exposure
        return title.contains("Excluding__")
            || title.contains("__")
            || title.contains("-_")
            || title.contains("idempotencyKey")
            || title.contains("ownerUsers")
            || title.contains("ownerGroups")
            || title.contains("viewerUsers")
            || title.contains("viewerGroups")
            || title.contains("parentsCount")
            || title.contains("childrenCount")
            || title.contains("Count_")
            || title.contains("_Count");
    }
    
    /**
     * Cleans parameter schemas to remove tsType references from inline schemas.
     * Parameters may contain inline schemas in their content (e.g., filter parameter
     * with complex JSON schema) that have tsType descriptions that need cleaning.
     * 
     * @param param The parameter to clean
     */
    private void cleanParameterSchemas(Parameter param) {
        if (param == null) {
            return;
        }
        
        // Clean schema directly on parameter
        if (param.getSchema() != null) {
            cleanupSchema(param.getSchema());
        }
        
        // Clean schemas in content (e.g., application/json content with complex schemas)
        if (param.getContent() != null) {
            param.getContent().forEach((mediaTypeName, mediaType) -> {
                if (mediaType != null && mediaType.getSchema() != null) {
                    cleanupSchema(mediaType.getSchema());
                }
            });
        }
    }
    
    /**
     * Cleans all parameters in a list, removing tsType from inline schemas
     * and applying deepObject style for complex query parameters.
     * 
     * @param params List of parameters to clean
     * @return The same list with cleaned parameters
     */
    private List<Parameter> cleanParameters(List<Parameter> params) {
        if (params == null) {
            return new ArrayList<>();
        }
        List<Parameter> result = new ArrayList<>(params);
        result.forEach(param -> {
            cleanParameterSchemas(param);
            applyDeepObjectStyleIfNeeded(param);
        });
        return result;
    }
    
    /**
     * Parameter names that should use deepObject style with explode=true.
     * These are complex object parameters passed as query strings.
     */
    private static final Set<String> DEEP_OBJECT_PARAMS = Set.of(
        "filter", "set", "where",
        "listFilter", "listSet", "listWhere",
        "entityFilter", "entitySet", "entityWhere"
    );
    
    /**
     * Applies deepObject style and explode=true to complex query parameters.
     * 
     * <p>While backend OAS defines these parameters with content/application-json,
     * they are actually passed as deepObject with explode=true in front of the gateway.
     * When using style/explode, the 'content' field is NOT allowed - schema must be
     * used directly on the parameter.</p>
     * 
     * @param param The parameter to potentially transform
     */
    private void applyDeepObjectStyleIfNeeded(Parameter param) {
        if (param == null || param.getName() == null) {
            return;
        }
        
        // Only apply to query parameters with matching names
        if ("query".equals(param.getIn()) && DEEP_OBJECT_PARAMS.contains(param.getName())) {
            // If parameter uses content (application/json), extract the schema and use it directly
            // OpenAPI spec: "content" and "style/explode" are mutually exclusive
            if (param.getContent() != null && !param.getContent().isEmpty()) {
                // Extract schema from content/application-json
                MediaType jsonMediaType = param.getContent().get("application/json");
                if (jsonMediaType != null && jsonMediaType.getSchema() != null) {
                    param.setSchema(jsonMediaType.getSchema());
                }
                // Remove content - cannot have both content and style/explode
                param.setContent(null);
            }
            
            // Enhance "set" parameter with detailed schema
            if ("set".equals(param.getName())) {
                enhanceSetParameterSchema(param);
            }
            
            param.setStyle(Parameter.StyleEnum.DEEPOBJECT);
            param.setExplode(true);
            log.trace("Applied deepObject style to parameter: {}", param.getName());
        }
    }
    
    /**
     * Enhances the "set" parameter schema with detailed properties for:
     * 1. Static boolean flags (publics, privates, protecteds, actives, expireds, pendings, roots)
     * 2. Nested parametric objects (owners, viewers, audience with userIds and groupIds)
     * 3. Dynamic time-bounded sets via additionalProperties
     */
    private void enhanceSetParameterSchema(Parameter param) {
        Schema<?> schema = param.getSchema();
        if (schema == null) {
            schema = new Schema<>();
            schema.setType("object");
            param.setSchema(schema);
        }
        
        // Add static boolean properties
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (String flag : List.of("publics", "privates", "protecteds", "actives", "expireds", "pendings", "roots")) {
            Schema<Boolean> boolSchema = new Schema<>();
            boolSchema.setType("boolean");
            boolSchema.setDescription("Filter by " + flag + " records");
            properties.put(flag, boolSchema);
        }
        
        // Add nested parametric objects (owners, viewers, audience)
        for (String nestedKey : List.of("owners", "viewers", "audience")) {
            Schema<Object> nestedSchema = new Schema<>();
            nestedSchema.setType("object");
            nestedSchema.setDescription(capitalizeFirst(nestedKey) + " filtering parameters");
            
            Map<String, Schema> nestedProps = new LinkedHashMap<>();
            
            Schema<String> userIdsSchema = new Schema<>();
            userIdsSchema.setType("string");
            userIdsSchema.setDescription("Comma-separated user IDs, e.g., user1,user2");
            nestedProps.put("userIds", userIdsSchema);
            
            Schema<String> groupIdsSchema = new Schema<>();
            groupIdsSchema.setType("string");
            groupIdsSchema.setDescription("Comma-separated group IDs, e.g., group1,group2");
            nestedProps.put("groupIds", groupIdsSchema);
            
            nestedSchema.setProperties(nestedProps);
            properties.put(nestedKey, nestedSchema);
        }
        
        schema.setProperties(properties);
        
        // Ensure additionalProperties is set for dynamic time-bounded sets
        if (schema.getAdditionalProperties() == null || schema.getAdditionalProperties() instanceof Boolean) {
            Schema<Boolean> additionalPropsSchema = new Schema<>();
            additionalPropsSchema.setType("boolean");
            additionalPropsSchema.setDescription("Supports dynamic keys following the pattern <base>-<N><unit> " +
                "(e.g., createds-10m, expireds-2w). Base must be one of: createds, expireds, actives, pendings. " +
                "Units: m/min (minutes), d/day (days), w (weeks), mo/mon (months).");
            schema.setAdditionalProperties(additionalPropsSchema);
        }
        
        // Update parameter description
        param.setDescription("Advanced filtering with boolean flags, parametric objects (owners/viewers/audience), " +
            "and dynamic time-bounded sets (e.g., createds-10m, expireds-2w)");
        
        log.trace("Enhanced 'set' parameter schema with {} properties", properties.size());
    }
    
    /**
     * Creates an advanced 'set' query parameter with complex schema supporting:
     * 1. Static boolean flags (publics, privates, protecteds, actives, expireds, pendings, roots)
     * 2. Nested parametric objects (owners, viewers, audience with userIds and groupIds)
     * 3. Dynamic time-bounded sets via additionalProperties
     * 
     * @return A Parameter configured with deepObject style and explode=true
     */
    private Parameter createAdvancedSetParameter() {
        Parameter setParam = new Parameter();
        setParam.setName("set");
        setParam.setIn("query");
        setParam.setDescription("Advanced filtering with boolean flags, parametric objects (owners/viewers/audience), " +
            "and dynamic time-bounded sets (e.g., createds-10m, expireds-2w)");
        setParam.setStyle(Parameter.StyleEnum.DEEPOBJECT);
        setParam.setExplode(true);
        setParam.setRequired(false);
        
        // Create main object schema
        Schema<Object> setSchema = new Schema<>();
        setSchema.setType("object");
        
        // Add static boolean properties
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (String flag : List.of("publics", "privates", "protecteds", "actives", "expireds", "pendings", "roots")) {
            Schema<Boolean> boolSchema = new Schema<>();
            boolSchema.setType("boolean");
            boolSchema.setDescription("Filter by " + flag + " records");
            properties.put(flag, boolSchema);
        }
        
        // Add nested parametric objects (owners, viewers, audience)
        for (String nestedKey : List.of("owners", "viewers", "audience")) {
            Schema<Object> nestedSchema = new Schema<>();
            nestedSchema.setType("object");
            nestedSchema.setDescription(capitalizeFirst(nestedKey) + " filtering parameters");
            
            Map<String, Schema> nestedProps = new LinkedHashMap<>();
            
            Schema<String> userIdsSchema = new Schema<>();
            userIdsSchema.setType("string");
            userIdsSchema.setDescription("Comma-separated user IDs, e.g., user1,user2");
            nestedProps.put("userIds", userIdsSchema);
            
            Schema<String> groupIdsSchema = new Schema<>();
            groupIdsSchema.setType("string");
            groupIdsSchema.setDescription("Comma-separated group IDs, e.g., group1,group2");
            nestedProps.put("groupIds", groupIdsSchema);
            
            nestedSchema.setProperties(nestedProps);
            properties.put(nestedKey, nestedSchema);
        }
        
        setSchema.setProperties(properties);
        
        log.debug("Created 'set' schema with {} properties: {}", 
            properties.size(), properties.keySet());
        
        // Add additionalProperties for dynamic time-bounded sets
        Schema<Boolean> additionalPropsSchema = new Schema<>();
        additionalPropsSchema.setType("boolean");
        additionalPropsSchema.setDescription("Supports dynamic keys following the pattern <base>-<N><unit> " +
            "(e.g., createds-10m, expireds-2w). Base must be one of: createds, expireds, actives, pendings. " +
            "Units: m/min (minutes), d/day (days), w (weeks), mo/mon (months).");
        setSchema.setAdditionalProperties(additionalPropsSchema);
        
        setParam.setSchema(setSchema);
        
        log.trace("Created advanced 'set' query parameter with deepObject style");
        return setParam;
    }
    
    /**
     * Adds the advanced 'set' parameter to an operation if it doesn't already exist.
     * Only adds to GET operations (list/query operations).
     * 
     * @param operation The operation to enhance with the set parameter
     */
    private void addSetParameterIfNeeded(Operation operation) {
        if (operation == null) {
            return;
        }
        
        // Initialize parameters list if null
        if (operation.getParameters() == null) {
            operation.setParameters(new ArrayList<>());
        }
        
        // Check if 'set' parameter already exists
        boolean hasSetParam = operation.getParameters().stream()
            .anyMatch(p -> "set".equals(p.getName()));
        
        if (!hasSetParam) {
            operation.getParameters().add(createAdvancedSetParameter());
            log.trace("Added advanced 'set' parameter to operation: {}", operation.getOperationId());
        }
    }
    
    /**
     * Applies global security requirement to the OpenAPI specification.
     * 
     * <p>This sets the JWT Bearer authentication as required for all endpoints
     * at the root level. Individual operations can override this if needed.</p>
     * 
     * <p>The security scheme 'bearerAuth' must be defined in components/securitySchemes
     * (this is done in transformComponents()).</p>
     */
    private void applyGlobalSecurity(OpenAPI openApi) {
        // Create security requirement referencing the bearerAuth scheme
        io.swagger.v3.oas.models.security.SecurityRequirement securityRequirement = 
            new io.swagger.v3.oas.models.security.SecurityRequirement();
        securityRequirement.addList("bearerAuth"); // Empty list = no specific scopes required
        
        // Apply at root level (applies to all operations)
        openApi.setSecurity(java.util.Collections.singletonList(securityRequirement));
        
        log.debug("Applied global JWT Bearer security requirement to OAS");
    }
    
    /**
     * Simplifies verbose backend schema names.
     * E.g., "GenericEntityExcluding__idempotencyKey-..." → "Entity"
     * 
     * IMPORTANT: Filter schemas (e.g., GenericEntityFilter) are preserved as filters
     * and should NOT be simplified to entity schemas.
     */
    private String simplifySchemaName(String name) {
        // CRITICAL: Filter schemas must NOT be simplified to entity schemas!
        // These are for query parameters, not response/request bodies
        if (name.contains("Filter")) {
            // Keep filter schemas with their "Filter" suffix, just clean up the name
            if (name.startsWith("GenericEntity")) {
                return "GenericEntityFilter";
            }
            if (name.startsWith("List") && name.contains("Relation")) {
                return "ListToEntityRelationFilter";
            }
            if (name.startsWith("EntityReaction")) {
                return "EntityReactionFilter";
            }
            if (name.startsWith("ListReaction")) {
                return "ListReactionFilter";
            }
            // Keep other filters as-is
            return name.replaceAll("Excluding__.*?_", "");
        }
        
        // These represent relations between lists and entities, not lists themselves.
        if (name.contains("ListToEntityRelation") || name.contains("Relation") && name.startsWith("List")) {
            // Keep relation schemas separate
            if (name.contains("Partial")) {
                return "PartialListEntityRelation";
            } else if (name.contains("New")) {
                return "NewListEntityRelation";
            } else if (name.contains("WithRelations")) {
                return "ListEntityRelationWithRelations";
            }
            return "ListEntityRelation";
        }
        
        // Strip noisy count suffixes that leak internal permission fields
        String cleanedName = name.replaceAll("(ownerUsersCount|ownerGroupsCount|viewerUsersCount|viewerGroupsCount|parentsCount|childrenCount)[-_]*", "");
        // If everything got stripped, fall back to original to avoid empty names
        if (cleanedName == null || cleanedName.isBlank()) {
            cleanedName = name;
        }

        // Common simplifications for entity/response schemas (NOT filters)
        if (cleanedName.startsWith("GenericEntity")) {
            if (name.contains("WithRelations")) {
                return "EntityWithRelations";
            } else if (name.contains("Partial")) {
                return "PartialEntity";
            } else if (name.contains("New")) {
                return "NewEntity";
            }
            return "Entity";
        }
        
        // Handle ListReaction schemas BEFORE List schemas to avoid incorrect simplification
        if (cleanedName.startsWith("ListReaction")) {
            if (name.contains("WithRelations")) {
                return "ListReactionWithRelations";
            } else if (name.contains("Partial")) {
                return "PartialListReaction";
            } else if (name.contains("New")) {
                return "NewListReaction";
            }
            return "ListReaction";
        }
        
        // Handle List schemas (excluding ListReaction and ListToEntityRelation handled above)
        if (cleanedName.startsWith("List") && !cleanedName.equals("List") && !cleanedName.contains("Reaction")) {
            if (name.contains("WithRelations")) {
                return "ListWithRelations";
            } else if (name.contains("Partial")) {
                return "PartialList";
            } else if (name.contains("New")) {
                return "NewList";
            }
            return "List";
        }
        
        // Keep other names as-is or apply minimal cleanup
        return cleanedName.replaceAll("Excluding__.*?_", "");
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
        
        // Handle hyphenated names: singularize only the last segment
        if (plural.contains("-")) {
            int lastHyphen = plural.lastIndexOf('-');
            String prefix = plural.substring(0, lastHyphen + 1);
            String lastSegment = plural.substring(lastHyphen + 1);
            return prefix + singularize(lastSegment);
        }
        
        // -ies → -y (cities → city, factories → factory, categories → category)
        if (plural.endsWith("ies")) {
            return plural.substring(0, plural.length() - 3) + "y";
        }
        
        // Sibilant/consonant-cluster plurals: strip "es"
        // These are words where the singular ends in a sound that requires "es" for pluralization
        if (plural.endsWith("sses")    // addresses → address, classes → class
                || plural.endsWith("shes")  // dishes → dish, crashes → crash
                || plural.endsWith("ches")  // watches → watch, batches → batch
                || plural.endsWith("xes"))  // boxes → box, indexes → index
        {
            return plural.substring(0, plural.length() - 2);
        }
        
        // All other words ending in "s" (including "es"): strip just "s"
        // This correctly handles words like garages, vehicles, likes, engines
        if (plural.endsWith("s") && !plural.endsWith("ss")) {
            return plural.substring(0, plural.length() - 1);
        }
        
        return plural;
    }
    
    /**
     * Returns the singular form of an alias, using the explicit singular override
     * if configured, otherwise falling back to the heuristic singularize() method.
     */
    private String getSingular(AliasConfig aliasConfig) {
        if (aliasConfig.getSingular() != null && !aliasConfig.getSingular().isBlank()) {
            return aliasConfig.getSingular();
        }
        return singularize(aliasConfig.getAlias());
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
     * 
     * Also updates $ref paths to use simplified schema names.
     */
    @SuppressWarnings({"rawtypes"})
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
                
                // Clone schema with $ref update to simplified names
                if (mediaType.getSchema() != null) {
                    Schema clonedSchema = new Schema();
                    
                    // Update $ref to use simplified schema name
                    String originalRef = mediaType.getSchema().get$ref();
                    if (originalRef != null) {
                        String simplifiedRef = transformSchemaRef(originalRef);
                        clonedSchema.set$ref(simplifiedRef);
                    }
                    
                    clonedSchema.setType(mediaType.getSchema().getType());
                    clonedSchema.setProperties(mediaType.getSchema().getProperties());
                    clonedSchema.setRequired(mediaType.getSchema().getRequired());
                    
                    // Clean up description to remove tsType references (use DOTALL for multiline)
                    String desc = mediaType.getSchema().getDescription();
                    if (desc != null && desc.contains("tsType:")) {
                        String cleaned = desc.replaceAll("(?s)\\(tsType:.*?\\)", "").trim();
                        clonedSchema.setDescription(cleaned.isEmpty() ? null : cleaned);
                    } else {
                        clonedSchema.setDescription(desc);
                    }
                    
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
     * 
     * Also updates $ref paths to use simplified schema names.
     */
    @SuppressWarnings({"rawtypes"})
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
                    
                    // Clone schema with $ref update to simplified names
                    if (mediaType.getSchema() != null) {
                        Schema clonedSchema = new Schema();
                        
                        // Update $ref to use simplified schema name
                        String originalRef = mediaType.getSchema().get$ref();
                        if (originalRef != null) {
                            String simplifiedRef = transformSchemaRef(originalRef);
                            clonedSchema.set$ref(simplifiedRef);
                        }
                        
                        clonedSchema.setType(mediaType.getSchema().getType());
                        clonedSchema.setProperties(mediaType.getSchema().getProperties());
                        clonedSchema.setRequired(mediaType.getSchema().getRequired());
                        
                        // Clean up description to remove tsType references (use DOTALL for multiline)
                        String desc = mediaType.getSchema().getDescription();
                        if (desc != null && desc.contains("tsType:")) {
                            String cleaned = desc.replaceAll("(?s)\\(tsType:.*?\\)", "").trim();
                            clonedSchema.setDescription(cleaned.isEmpty() ? null : cleaned);
                        } else {
                            clonedSchema.setDescription(desc);
                        }
                        
                        // Clone array items if present
                        if (mediaType.getSchema().getItems() != null) {
                            Schema itemsSchema = mediaType.getSchema().getItems();
                            Schema clonedItems = new Schema();
                            
                            // Update item $ref too
                            String itemRef = itemsSchema.get$ref();
                            if (itemRef != null) {
                                clonedItems.set$ref(transformSchemaRef(itemRef));
                            }
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
     * Transforms a schema $ref to use simplified schema name.
     * E.g., "#/components/schemas/GenericEntityExcluding..." → "#/components/schemas/Entity"
     */
    private String transformSchemaRef(String ref) {
        if (ref == null || !ref.startsWith("#/components/schemas/")) {
            return ref;
        }
        
        String schemaName = ref.substring("#/components/schemas/".length());
        String simplifiedName = orchestratorProperties.getTransformation().isSimplifySchemaNames()
            ? simplifySchemaName(schemaName)
            : schemaName;
        
        return "#/components/schemas/" + simplifiedName;
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
    // NULL EXAMPLE REMOVAL
    // ========================================================================
    // (Removed: removeNullExamplesDirectly and helpers - not currently used)
    
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
     * Checks if a controller-level tag (like "generic", "entityReactions", "listReactions") is disabled.
     * 
     * IMPORTANT: This method is for controller-level tag filtering only.
     * It only uses the tagsOff (blacklist) configuration, NOT tagsOn (whitelist).
     * 
     * The tagsOn/tagsOff whitelist/blacklist logic for route-level tags (like "write", "read-only")
     * is handled separately by isRouteFilteredByTags(), which reads tags from route metadata.
     * 
     * This distinction exists because:
     * - Controller-level tags (generic, entityReactions) control entire categories of routes
     * - Route-level tags (write, read-only) control individual operations based on their purpose
     * 
     * When tagsOn=write, we still want to show all controllers (entities, relations, etc.),
     * but only show the operations within them that have the "write" tag.
     */
    private boolean isTagDisabled(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            return false;
        }
        
        List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());
        
        tagName = tagName.trim();
        
        // Only use blacklist mode for controller-level tags
        // tagsOn is for route-level filtering, handled by isRouteFilteredByTags()
        if (!tagsOff.isEmpty()) {
            return tagsOff.contains(tagName);
        }
        
        return false;
    }
    
    /**
     * Checks if a route should be disabled based on its metadata tags.
     * This is a metadata-driven approach that reads tags from route configuration
     * and checks if any of those tags are disabled in toggles.
     * 
     * Mirrors the logic in CheckIfRouteEnabled filter:
     * - If tagsOn is set (whitelist mode): routes are disabled unless they have at least one enabled tag
     * - If tagsOff is set (blacklist mode): routes are disabled if they have any disabled tag
     * - tagsOn takes precedence over tagsOff
     * 
     * @param controllerName The controller name (e.g., "entities", "lists", "relations")
     * @param hierarchyType The hierarchy type ("children" or "parents")
     * @return true if the route should be disabled, false otherwise
     */
    private boolean isRouteDisabledByTags(String controllerName, String hierarchyType) {
        if (controllerName == null || hierarchyType == null) {
            return false;
        }

        log.debug("Checking if {} {} routes should be disabled", controllerName, hierarchyType);

        // Find all routes for this controller that match the hierarchy type
        List<com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata> matchingRoutes =
            routeMetadataCache.values().stream()
                .filter(metadata -> metadata.getControllerName() != null
                        && metadata.getControllerName().equals(controllerName))
                .filter(metadata -> metadata.getRouteId() != null
                        && metadata.getRouteId().toLowerCase().contains(hierarchyType.toLowerCase()))
                .collect(Collectors.toList());

        if (matchingRoutes.isEmpty()) {
            log.debug("No routes found for controller '{}' with hierarchy type '{}'", controllerName, hierarchyType);
            return false;
        }

        List<String> tagsOn = normalizeList(togglesProperties.getTags().getOn());
        List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());

        // Whitelist mode (tagsOn): routes are enabled only if they have at least one enabled tag
        // This takes precedence over blacklist mode
        if (!tagsOn.isEmpty()) {
            for (com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata metadata : matchingRoutes) {
                List<String> routeTags = metadata.getTags();
                
                // Route has no tags but tagsOn is set - route is disabled
                if (routeTags == null || routeTags.isEmpty()) {
                    log.debug("Route '{}' DISABLED - has no tags but tagsOn whitelist is active", metadata.getRouteId());
                    return true;
                }
                
                // Check if route has any tag from the whitelist
                boolean hasEnabledTag = routeTags.stream().anyMatch(tagsOn::contains);
                if (!hasEnabledTag) {
                    log.debug("Route '{}' DISABLED - none of its tags {} are in whitelist {}", 
                        metadata.getRouteId(), routeTags, tagsOn);
                    return true;
                }
            }
            log.debug("All {} {} routes have enabled tags", controllerName, hierarchyType);
            return false;
        }

        // Blacklist mode (tagsOff): routes are disabled if they have any disabled tag
        if (!tagsOff.isEmpty()) {
            for (com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata metadata : matchingRoutes) {
                if (metadata.getTags() == null || metadata.getTags().isEmpty()) {
                    continue;
                }

                for (String tag : metadata.getTags()) {
                    if (tagsOff.contains(tag)) {
                        log.debug("Route '{}' DISABLED - has disabled tag '{}'", metadata.getRouteId(), tag);
                        return true;
                    }
                }
            }
        }

        log.debug("No disabled tags found for {} {} routes", controllerName, hierarchyType);
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
    
    /**
     * Maps backend operationId to gateway route ID.
     * Backend uses patterns like "findChildrenByEntityId" while gateway uses "findEntityChildren".
     * Also handles bulk update operations: "updateEntities" -> "updateAllEntities".
     * 
     * @param backendOperationId The operationId from backend OAS
     * @return The matching gateway route ID, or the original operationId if no mapping found
     */
    private String mapBackendOperationIdToGatewayRouteId(String backendOperationId) {
        if (backendOperationId == null) {
            return null;
        }
        
        // Direct match - most common case (e.g., "findEntities", "createEntity")
        if (routeMetadataCache.containsKey(backendOperationId)) {
            return backendOperationId;
        }
        
        // CRITICAL: Map bulk update operations
        // Backend uses "updateEntities" but gateway route is "updateAllEntities"
        // This applies to all controllers: entities, lists, relations, entityReactions, listReactions
        if (backendOperationId.equals("updateEntities")) {
            return "updateAllEntities";
        }
        if (backendOperationId.equals("updateLists")) {
            return "updateAllLists";
        }
        if (backendOperationId.equals("updateRelations")) {
            return "updateAllRelations";
        }
        if (backendOperationId.equals("updateEntityReactions")) {
            return "updateAllEntityReactions";
        }
        if (backendOperationId.equals("updateListReactions")) {
            return "updateAllListReactions";
        }
        
        // Backend hierarchical operationId patterns:
        // - findChildrenBy{Controller}Id  → find{Controller}Children
        // - findParentsBy{Controller}Id   → find{Controller}Parents
        // - createChild{Controller}       → createChild{Controller} (usually matches)
        
        // Handle findChildrenBy{X}Id -> find{X}Children pattern
        if (backendOperationId.startsWith("findChildrenBy") && backendOperationId.endsWith("Id")) {
            // Extract controller name: findChildrenByEntityId -> Entity
            String controllerPart = backendOperationId.substring("findChildrenBy".length(), backendOperationId.length() - 2);
            
            // Try standard pattern first: findEntityChildren
            String mappedId = "find" + controllerPart + "Children";
            if (routeMetadataCache.containsKey(mappedId)) {
                return mappedId;
            }
            
            // Try reaction pattern: findChildrenByEntityReactionId -> findChildrenEntityReactionsByReactionId
            // This is for entity-reactions and list-reactions controllers
            if (controllerPart.endsWith("Reaction")) {
                // findChildrenByEntityReactionId -> findChildrenEntityReactionsByReactionId
                String reactionMappedId = "findChildren" + controllerPart + "sByReactionId";
                if (routeMetadataCache.containsKey(reactionMappedId)) {
                    return reactionMappedId;
                }
            }
        }
        
        // Handle findParentsBy{X}Id -> find{X}Parents pattern
        if (backendOperationId.startsWith("findParentsBy") && backendOperationId.endsWith("Id")) {
            // Extract controller name: findParentsByEntityId -> Entity
            String controllerPart = backendOperationId.substring("findParentsBy".length(), backendOperationId.length() - 2);
            
            // Try standard pattern first: findEntityParents
            String mappedId = "find" + controllerPart + "Parents";
            if (routeMetadataCache.containsKey(mappedId)) {
                return mappedId;
            }
            
            // Try reaction pattern: findParentsByEntityReactionId -> findParentsEntityReactionsByReactionId
            if (controllerPart.endsWith("Reaction")) {
                String reactionMappedId = "findParents" + controllerPart + "sByReactionId";
                if (routeMetadataCache.containsKey(reactionMappedId)) {
                    return reactionMappedId;
                }
            }
        }
        
        // Handle createChild{X} -> create{X}Child pattern
        // Backend: createChildEntity -> Gateway: createEntityChild
        if (backendOperationId.equals("createChildEntity")) {
            return "createEntityChild";
        }
        if (backendOperationId.equals("createChildList")) {
            return "createListChild";
        }
        if (backendOperationId.equals("createChildEntityReaction")) {
            return "createChildEntityReaction";  // Same name for reactions
        }
        if (backendOperationId.equals("createChildListReaction")) {
            return "createChildListReaction";  // Same name for reactions
        }
        
        // No mapping found, return original
        return backendOperationId;
    }
    
    /**
     * Checks if a route should be filtered based on its tags matching disabled tags.
     * Uses route metadata from Spring Cloud Gateway configuration.
     * 
     * @param routeId The route ID to check (can be backend operationId)
     * @return true if the route has any disabled tags, false otherwise
     */
    private boolean isRouteFilteredByTags(String routeId) {
        if (routeId == null || routeId.trim().isEmpty()) {
            return false;
        }
        
        // Get tag filtering configuration
        List<String> tagsOn = normalizeList(togglesProperties.getTags().getOn());
        List<String> tagsOff = normalizeList(togglesProperties.getTags().getOff());
        
        // If no tag filtering is configured, don't filter anything
        if (tagsOn.isEmpty() && tagsOff.isEmpty()) {
            return false;
        }
        
        // Map backend operationId to gateway route ID
        String mappedRouteId = mapBackendOperationIdToGatewayRouteId(routeId);
        
        // Get route metadata
        com.tarcinapp.entitypersistencegateway.oas.service.RouteMetadataService.RouteMetadata metadata = 
            routeMetadataCache.get(mappedRouteId);
        
        // DEBUG: Log routing decisions (using INFO to ensure visibility)
        if (metadata != null) {
            log.debug("Tag filter check: routeId={}, mappedRouteId={}, routeTags={}, tagsOn={}, tagsOff={}", 
                routeId, mappedRouteId, metadata.getTags(), tagsOn, tagsOff);
        } else {
            log.debug("Tag filter check: routeId={}, mappedRouteId={}, metadata=NULL, tagsOn={}, tagsOff={}", 
                routeId, mappedRouteId, tagsOn, tagsOff);
        }
        
        // Handle case where metadata is not found or has no tags
        if (metadata == null || metadata.getTags() == null || metadata.getTags().isEmpty()) {
            // In whitelist mode (tagsOn), routes without tags should be filtered OUT
            // In blacklist mode (tagsOff), routes without tags should NOT be filtered
            boolean filtered = !tagsOn.isEmpty();
            log.debug("Tag filter result: routeId={} -> filtered={} (no tags/metadata)", routeId, filtered);
            return filtered;
        }
        
        if (!tagsOn.isEmpty()) {
            // Whitelist mode: route is filtered if it has NO enabled tags
            boolean hasTag = metadata.hasAnyTag(tagsOn);
            boolean filtered = !hasTag;
            log.debug("Tag filter result (whitelist): routeId={}, hasTag={}, filtered={}", routeId, hasTag, filtered);
            return filtered;
        } else if (!tagsOff.isEmpty()) {
            // Blacklist mode: route is filtered if it has ANY disabled tags
            boolean hasTag = metadata.hasAnyTag(tagsOff);
            log.debug("Tag filter result (blacklist): routeId={}, hasTag={}, filtered={}", routeId, hasTag, hasTag);
            return hasTag;
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
     * Processes:
     * 1. Base controller schemas (Entity, List, Relation, EntityReaction, ListReaction)
     * 2. Top-level aliases AND their children/parents hierarchies
     */
    private void addMergedDomainSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }
        
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        
        try {
            // 1. Add BASE CONTROLLER schemas first
            // Fetch POST, PATCH, and GET schemas separately for each controller to ensure
            // correct schema variants: Resource uses GET, New uses POST, Patch uses PATCH
            
            // Entities controller
            JsonNode entityPostBase = backendSchemaService.getBackendSchemaForController("entities", "POST");
            JsonNode entityPatchBase = backendSchemaService.getBackendSchemaForController("entities", "PATCH");
            JsonNode entityResourceBase = backendSchemaService.getBackendSchemaForController("entities", "GET");
            
            // Lists controller
            JsonNode listPostBase = backendSchemaService.getBackendSchemaForController("lists", "POST");
            JsonNode listPatchBase = backendSchemaService.getBackendSchemaForController("lists", "PATCH");
            JsonNode listResourceBase = backendSchemaService.getBackendSchemaForController("lists", "GET");
            
            // Relations controller
            JsonNode relationPostBase = backendSchemaService.getBackendSchemaForController("relations", "POST");
            JsonNode relationPatchBase = backendSchemaService.getBackendSchemaForController("relations", "PATCH");
            JsonNode relationResourceBase = backendSchemaService.getBackendSchemaForController("relations", "GET");
            
            // EntityReactions controller
            JsonNode entityReactionPostBase = backendSchemaService.getBackendSchemaForController("entityReactions", "POST");
            JsonNode entityReactionPatchBase = backendSchemaService.getBackendSchemaForController("entityReactions", "PATCH");
            JsonNode entityReactionResourceBase = backendSchemaService.getBackendSchemaForController("entityReactions", "GET");
            
            // ListReactions controller
            JsonNode listReactionPostBase = backendSchemaService.getBackendSchemaForController("listReactions", "POST");
            JsonNode listReactionPatchBase = backendSchemaService.getBackendSchemaForController("listReactions", "PATCH");
            JsonNode listReactionResourceBase = backendSchemaService.getBackendSchemaForController("listReactions", "GET");

            // Fallback to empty ObjectNode if fetch failed
            if (entityPostBase == null) entityPostBase = objectMapper.createObjectNode();
            if (entityPatchBase == null) entityPatchBase = objectMapper.createObjectNode();
            if (entityResourceBase == null) entityResourceBase = objectMapper.createObjectNode();
            
            if (listPostBase == null) listPostBase = objectMapper.createObjectNode();
            if (listPatchBase == null) listPatchBase = objectMapper.createObjectNode();
            if (listResourceBase == null) listResourceBase = objectMapper.createObjectNode();
            
            if (relationPostBase == null) relationPostBase = entityPostBase;
            if (relationPatchBase == null) relationPatchBase = entityPatchBase;
            if (relationResourceBase == null) relationResourceBase = entityResourceBase;
            
            if (entityReactionPostBase == null) entityReactionPostBase = entityPostBase;
            if (entityReactionPatchBase == null) entityReactionPatchBase = entityPatchBase;
            if (entityReactionResourceBase == null) entityReactionResourceBase = entityResourceBase;
            
            if (listReactionPostBase == null) listReactionPostBase = entityPostBase;
            if (listReactionPatchBase == null) listReactionPatchBase = entityPatchBase;
            if (listReactionResourceBase == null) listReactionResourceBase = entityResourceBase;

            addBaseControllerSchemas(schemas, 
                entityPostBase, entityPatchBase, entityResourceBase,
                listPostBase, listPatchBase, listResourceBase,
                relationPostBase, relationPatchBase, relationResourceBase,
                entityReactionPostBase, entityReactionPatchBase, entityReactionResourceBase,
                listReactionPostBase, listReactionPatchBase, listReactionResourceBase);
            
            // 2. Process each controller's aliases
            openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
                if (controllerConfig.getAliases() == null) return;
                
                // Fetch controller-specific bases
                JsonNode postBase = backendSchemaService.getBackendSchemaForController(controllerName, "POST"); // Create
                JsonNode patchBase = backendSchemaService.getBackendSchemaForController(controllerName, "PATCH"); // Update
                JsonNode resourceBase = backendSchemaService.getBackendSchemaForController(controllerName, "GET"); // Resource
                
                if (postBase == null) postBase = objectMapper.createObjectNode();
                if (patchBase == null) patchBase = objectMapper.createObjectNode();
                if (resourceBase == null) resourceBase = objectMapper.createObjectNode();
                
                final JsonNode effectivePostBase = postBase;
                final JsonNode effectivePatchBase = patchBase;
                final JsonNode effectiveResourceBase = resourceBase;
                
                controllerConfig.getAliases().forEach(aliasConfig -> {
                    String parentAlias = aliasConfig.getAlias();
                    
                    // Process the top-level alias: /authors → Author
                    addSchemaForAlias(schemas, aliasConfig, effectivePostBase, effectivePatchBase, effectiveResourceBase, null, null, controllerName);
                    
                    // Process children hierarchy: /books/{id}/chapters → BookChildChapter
                    processHierarchySchemas(schemas, aliasConfig.getChildren(), effectivePostBase, effectivePatchBase, effectiveResourceBase, parentAlias, "Child", controllerName);
                    
                    // Process parents hierarchy: /books/{id}/authors → BookParentAuthor
                    processHierarchySchemas(schemas, aliasConfig.getParents(), effectivePostBase, effectivePatchBase, effectiveResourceBase, parentAlias, "Parent", controllerName);
                });
            });
            
        } catch (Exception e) {
            log.error("Failed to process base schemas: {}", e.getMessage());
        }
    }
    
    /**
     * Adds base controller schemas (Entity, List, Relation, EntityReaction, ListReaction).
     * These are used for base controller paths like /api/v1/entities, /api/v1/relations.
     * Each schema variant uses the appropriate backend schema:
     * - Resource (GET response) for base schema (includes read-only fields)
     * - New (POST request) for creation schema
     * - Patch (PATCH request) for partial update schema
     */
    @SuppressWarnings({"rawtypes"})
    private void addBaseControllerSchemas(Map<String, Schema> schemas, 
            JsonNode entityPostBase, JsonNode entityPatchBase, JsonNode entityResourceBase,
            JsonNode listPostBase, JsonNode listPatchBase, JsonNode listResourceBase,
            JsonNode relationPostBase, JsonNode relationPatchBase, JsonNode relationResourceBase,
            JsonNode entityReactionPostBase, JsonNode entityReactionPatchBase, JsonNode entityReactionResourceBase,
            JsonNode listReactionPostBase, JsonNode listReactionPatchBase, JsonNode listReactionResourceBase) {
        log.debug("addBaseControllerSchemas invoked with POST/PATCH/GET bases");
        
        // Define base controller schema mappings: schemaName -> (postBase, patchBase, resourceBase, controllerName)
        Map<String, Object[]> baseControllerSchemas = new LinkedHashMap<>();
        baseControllerSchemas.put("Entity", new Object[]{entityPostBase, entityPatchBase, entityResourceBase, "entities"});
        baseControllerSchemas.put("List", new Object[]{listPostBase, listPatchBase, listResourceBase, "lists"});
        baseControllerSchemas.put("Relation", new Object[]{relationPostBase, relationPatchBase, relationResourceBase, "relations"});
        baseControllerSchemas.put("EntityReaction", new Object[]{entityReactionPostBase, entityReactionPatchBase, entityReactionResourceBase, "entityReactions"});
        baseControllerSchemas.put("ListReaction", new Object[]{listReactionPostBase, listReactionPatchBase, listReactionResourceBase, "listReactions"});
        
        baseControllerSchemas.forEach((schemaName, config) -> {
            JsonNode postBase = (JsonNode) config[0];
            JsonNode patchBase = (JsonNode) config[1];
            JsonNode resourceBase = (JsonNode) config[2];
            String controllerName = (String) config[3];
            
            try {
                // ALWAYS override base schema (Resource/GET) with authoritative schema from BackendSchemaService
                // This ensures we use the properly resolved schema with all properties including nested refs
                // The backend OAS may have simplified schemas that were incorrectly named via simplifySchemaName()
                boolean hadExisting = schemas.containsKey(schemaName);
                Schema schema = createSchemaFromJsonNode(resourceBase, controllerName);
                schemas.put(schemaName, schema);
                if (hadExisting) {
                    log.debug("Replaced existing schema '{}' with authoritative GET schema (recordType: {})", schemaName, controllerName);
                } else {
                    log.debug("Added base controller schema: {} from GET (recordType: {})", schemaName, controllerName);
                }
                
                // ALWAYS override "New" variant for POST operations using POST base schema
                String newSchemaName = "New" + schemaName;
                hadExisting = schemas.containsKey(newSchemaName);
                Schema newSchema = createSchemaFromJsonNode(postBase, controllerName);
                newSchema.setRequired(null); // No required for creation (gateway adds defaults)
                schemas.put(newSchemaName, newSchema);
                if (hadExisting) {
                    log.debug("Replaced existing schema '{}' with authoritative POST schema (recordType: {})", newSchemaName, controllerName);
                } else {
                    log.debug("Added New variant schema: {} from POST (recordType: {})", newSchemaName, controllerName);
                }
                
                // ALWAYS override "Patch" variant for PATCH operations using PATCH base schema
                // PATCH allows partial updates, so required fields must be removed
                String patchSchemaName = "Patch" + schemaName;
                hadExisting = schemas.containsKey(patchSchemaName);
                Schema patchSchema = createSchemaFromJsonNode(patchBase, controllerName);
                patchSchema.setRequired(null); // No required for partial update
                schemas.put(patchSchemaName, patchSchema);
                if (hadExisting) {
                    log.debug("Replaced existing schema '{}' with authoritative PATCH schema (recordType: {})", patchSchemaName, controllerName);
                } else {
                    log.debug("Added Patch variant schema: {} from PATCH (recordType: {})", patchSchemaName, controllerName);
                }
            } catch (Exception e) {
                log.warn("Failed to create base controller schema '{}': {}", schemaName, e.getMessage());
            }
        });
    }
    
    /**
     * Creates a Schema object from a JsonNode base schema.
     */
    @SuppressWarnings({"rawtypes"})
    private Schema<?> createSchemaFromJsonNode(JsonNode baseNode, String controllerName) {
        Schema schema = new Schema();
        schema.setType("object");
        
        Map<String, Schema> properties = new LinkedHashMap<>();
        
        if (baseNode.has("properties")) {
            baseNode.get("properties").fields().forEachRemaining(entry -> {
                Schema propSchema = convertJsonNodeToSchema(entry.getValue());
                properties.put(entry.getKey(), propSchema);
            });
        }
        
        schema.setProperties(properties);
        
        // Add required fields
        if (baseNode.has("required") && baseNode.get("required").isArray()) {
            java.util.List<String> required = new ArrayList<>();
            for (JsonNode req : baseNode.get("required")) {
                required.add(req.asText());
            }
            if (!required.isEmpty()) {
                schema.setRequired(required);
            }
        }
        
        // Inject x-record-type vendor extension
        if (schema.getExtensions() == null) {
            schema.setExtensions(new LinkedHashMap<>());
        }
        schema.getExtensions().put("x-record-type", controllerName);
        
        // Clean up any tsType/schemaOptions artifacts from the schema
        cleanupSchema(schema);
        
        return schema;
    }
    
    /**
     * Converts a JsonNode to a Schema object, properly handling all nested properties
     * including 'items' for array types. Uses ArraySchema for array types to ensure
     * proper serialization of items.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Schema convertJsonNodeToSchema(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Schema();
        }
        
        // Determine the type first
        String type = null;
        boolean nullable = false;
        
        if (node.has("type")) {
            JsonNode typeNode = node.get("type");
            if (typeNode.isArray()) {
                // Handle nullable types like ["string", "null"]
                for (JsonNode t : typeNode) {
                    if (!"null".equals(t.asText())) {
                        type = t.asText();
                        break;
                    }
                }
                nullable = true;
            } else {
                type = typeNode.asText();
            }
        }
        
        // Use ArraySchema for array types to properly serialize items
        Schema schema;
        if ("array".equals(type) && node.has("items")) {
            ArraySchema arraySchema = new ArraySchema();
            Schema itemsSchema = convertJsonNodeToSchema(node.get("items"));
            arraySchema.setItems(itemsSchema);
            schema = arraySchema;
            // ArraySchema already sets type to "array"
        } else {
            schema = new Schema();
            if (type != null) {
                schema.setType(type);
            }
            // Handle items for non-ArraySchema (fallback, shouldn't happen)
            if (node.has("items")) {
                Schema itemsSchema = convertJsonNodeToSchema(node.get("items"));
                schema.setItems(itemsSchema);
            }
        }
        
        if (nullable) {
            schema.setNullable(true);
        }
        
        // Handle format
        if (node.has("format")) {
            schema.setFormat(node.get("format").asText());
        }
        
        // Handle pattern
        if (node.has("pattern")) {
            schema.setPattern(node.get("pattern").asText());
        }
        
        // Handle enum
        if (node.has("enum") && node.get("enum").isArray()) {
            List<Object> enumValues = new ArrayList<>();
            for (JsonNode e : node.get("enum")) {
                enumValues.add(e.asText());
            }
            schema.setEnum(enumValues);
        }
        
        // Handle properties for objects
        if (node.has("properties")) {
            Map<String, Schema> props = new LinkedHashMap<>();
            node.get("properties").fields().forEachRemaining(entry -> {
                props.put(entry.getKey(), convertJsonNodeToSchema(entry.getValue()));
            });
            schema.setProperties(props);
        }
        
        // Handle additionalProperties
        if (node.has("additionalProperties")) {
            JsonNode addProps = node.get("additionalProperties");
            if (addProps.isBoolean()) {
                schema.setAdditionalProperties(addProps.asBoolean());
            } else if (addProps.isObject()) {
                schema.setAdditionalProperties(convertJsonNodeToSchema(addProps));
            }
        }
        
        // Handle required
        if (node.has("required") && node.get("required").isArray()) {
            List<String> required = new ArrayList<>();
            for (JsonNode r : node.get("required")) {
                required.add(r.asText());
            }
            schema.setRequired(required);
        }
        
        // Handle minimum/maximum
        if (node.has("minimum")) {
            schema.setMinimum(node.get("minimum").decimalValue());
        }
        if (node.has("maximum")) {
            schema.setMaximum(node.get("maximum").decimalValue());
        }
        
        // Handle minLength/maxLength
        if (node.has("minLength")) {
            schema.setMinLength(node.get("minLength").asInt());
        }
        if (node.has("maxLength")) {
            schema.setMaxLength(node.get("maxLength").asInt());
        }
        
        // Handle description
        if (node.has("description")) {
            schema.setDescription(node.get("description").asText());
        }
        
        // Handle example
        if (node.has("example")) {
            schema.setExample(node.get("example").asText());
        }
        
        // Handle default
        if (node.has("default")) {
            schema.setDefault(node.get("default").asText());
        }
        
        return schema;
    }
    
    /**
     * Recursively processes hierarchy (children/parents) and adds their schemas.
     * @param parentAlias The parent alias (e.g., "books")
     * @param hierarchyType "Child" or "Parent" for naming
     * @param controllerName The record type name (entities, lists, relations, etc.)
     */
    private void processHierarchySchemas(Map<String, Schema> schemas, 
            List<AliasConfig> hierarchy,
            JsonNode effectivePostBase,
            JsonNode effectivePatchBase,
            JsonNode effectiveResourceBase,
            String parentAlias,
            String hierarchyType,
            String controllerName) {
        if (hierarchy == null || hierarchy.isEmpty()) return;
        
        for (var nestedConfig : hierarchy) {
            // Add schema for this hierarchy level with path-specific name
            // e.g., BookChildChapter, BookParentAuthor
            addSchemaForAlias(schemas, nestedConfig, effectivePostBase, effectivePatchBase, effectiveResourceBase, parentAlias, hierarchyType, controllerName);
            
            // Recurse into nested children (nested under the current hierarchy item)
            String newParent = parentAlias + hierarchyType + capitalizeFirst(nestedConfig.getKind());
            processHierarchySchemas(schemas, nestedConfig.getChildren(), effectivePostBase, effectivePatchBase, effectiveResourceBase, newParent, "Child", controllerName);
            processHierarchySchemas(schemas, nestedConfig.getParents(), effectivePostBase, effectivePatchBase, effectiveResourceBase, newParent, "Parent", controllerName);
        }
    }
    
    /**
     * Creates and adds merged schema for a single alias config.
     * @param parentAlias If not null, creates path-specific schema name (e.g., BookChildChapter)
     * @param hierarchyType "Child" or "Parent" - used in naming for nested schemas
     */
    private void addSchemaForAlias(Map<String, Schema> schemas,
            AliasConfig aliasConfig,
            JsonNode effectivePostBase,
            JsonNode effectivePatchBase,
            JsonNode effectiveResourceBase,
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
            
            // 1. Resource Schema (GET/PUT) - use Resource Base (includes ID, read-only fields)
            Schema mergedSchema = mergeSchemaWithBase(aliasConfig.getSchema(), effectiveResourceBase, controllerName);
            schemas.put(schemaName, mergedSchema);
            
            // 2. New Variant (POST) - use POST Base (excludes ID, uses create constraints)
            // Also add "New" variant without required base fields for POST
            Schema newSchema = mergeSchemaWithBase(aliasConfig.getSchema(), effectivePostBase, controllerName);
            // Remove base-schema required fields (gateway provides defaults for those)
            // but keep user-config required fields (those ARE required from the client)
            newSchema.setRequired(extractUserRequiredFields(aliasConfig.getSchema()));
            schemas.put("New" + schemaName, newSchema);
            
            // 3. Patch Variant (PATCH) - use PATCH Base (partial update)
            // Also add "Patch" variant without required fields for PATCH (partial updates)
            Schema patchSchema = mergeSchemaWithBase(aliasConfig.getSchema(), effectivePatchBase, controllerName);
            patchSchema.setRequired(null); // No required for partial update
            schemas.put("Patch" + schemaName, patchSchema);
            
            log.debug("Added merged domain schemas: {}, New{}, Patch{} (from kind: {})", 
                schemaName, schemaName, schemaName, aliasConfig.getKind());
            
            // 4. Route-specific schemas - override kind-level for specific operations
            // If a route has a custom schema, create a route-specific variant
            addRouteSpecificSchemas(schemas, aliasConfig, schemaName, 
                effectivePostBase, effectivePatchBase, effectiveResourceBase, controllerName);
        } catch (Exception e) {
            log.warn("Failed to merge schema for kind '{}': {}", 
                aliasConfig.getKind(), e.getMessage());
        }
    }
    
    /**
     * Creates route-specific schemas when routes have custom schema overrides.
     * 
     * <p>When a route (e.g., createEntity) has a schema override in its configuration,
     * this creates a route-specific schema variant that will be used instead of
     * the kind-level schema for that specific operation.</p>
     * 
     * <p>Schema naming convention: {SchemaName}{RouteId}
     * Examples: NewBookCreateEntity, PatchBookUpdateEntityById</p>
     * 
     * @param schemas The schemas map to add to
     * @param aliasConfig The alias configuration with optional route overrides
     * @param schemaName The base schema name (e.g., "Book")
     * @param effectivePostBase Base POST schema from backend
     * @param effectivePatchBase Base PATCH schema from backend
     * @param effectiveResourceBase Base GET/Resource schema from backend
     * @param controllerName The controller name for x-record-type
     */
    @SuppressWarnings("rawtypes")
    private void addRouteSpecificSchemas(Map<String, Schema> schemas, 
            AliasConfig aliasConfig, 
            String schemaName,
            JsonNode effectivePostBase,
            JsonNode effectivePatchBase,
            JsonNode effectiveResourceBase,
            String controllerName) {
        
        if (aliasConfig.getRoutes() == null || aliasConfig.getRoutes().isEmpty()) {
            return;
        }
        
        for (Map.Entry<String, RouteConfig> routeEntry : aliasConfig.getRoutes().entrySet()) {
            String routeId = routeEntry.getKey();
            RouteConfig routeConfig = routeEntry.getValue();
            
            // Skip routes without custom schemas
            if (routeConfig.getSchema() == null || routeConfig.getSchema().isBlank()) {
                continue;
            }
            
            try {
                // Determine which schema variant to create based on route type
                // Routes that create use POST base: createEntity, createList, etc.
                // Routes that update use PATCH base: updateEntityById, updateListById, etc.
                // Routes that replace use Resource base: replaceEntityById, replaceListById, etc.
                
                String routeIdLower = routeId.toLowerCase();
                String routeSchemaName;
                Schema routeSchema;
                
                if (routeIdLower.contains("create")) {
                    // POST REQUEST variant: New{SchemaName}{RouteId}
                    routeSchemaName = "New" + schemaName + capitalizeFirst(routeId);
                    routeSchema = mergeSchemaWithBase(routeConfig.getSchema(), effectivePostBase, controllerName);
                    // Remove base-schema required fields (gateway provides defaults for those)
                    // but keep user-config required fields (those ARE required from the client)
                    routeSchema.setRequired(extractUserRequiredFields(routeConfig.getSchema()));
                    log.debug("Created route-specific POST request schema: {} (from route: {})", routeSchemaName, routeId);
                    
                    // POST RESPONSE variant: {SchemaName}{RouteId} — merged with Resource base
                    // The backend returns all fields (including read-only like _id, timestamps)
                    // so the response schema should use the resource base, not the POST base.
                    String responseSchemaName = schemaName + capitalizeFirst(routeId);
                    Schema responseSchema = mergeSchemaWithBase(routeConfig.getSchema(), effectiveResourceBase, controllerName);
                    schemas.put(responseSchemaName, responseSchema);
                    log.debug("Created route-specific POST response schema: {} (from route: {})", responseSchemaName, routeId);
                } else if (routeIdLower.contains("update") || routeIdLower.contains("patch")) {
                    // PATCH variant: Patch{SchemaName}{RouteId}
                    routeSchemaName = "Patch" + schemaName + capitalizeFirst(routeId);
                    routeSchema = mergeSchemaWithBase(routeConfig.getSchema(), effectivePatchBase, controllerName);
                    routeSchema.setRequired(null); // No required for partial update
                    log.debug("Created route-specific PATCH schema: {} (from route: {})", routeSchemaName, routeId);
                } else if (routeIdLower.contains("replace") || routeIdLower.contains("put")) {
                    // PUT variant: {SchemaName}{RouteId}
                    routeSchemaName = schemaName + capitalizeFirst(routeId);
                    routeSchema = mergeSchemaWithBase(routeConfig.getSchema(), effectiveResourceBase, controllerName);
                    log.debug("Created route-specific PUT schema: {} (from route: {})", routeSchemaName, routeId);
                } else {
                    // Default to resource variant
                    routeSchemaName = schemaName + capitalizeFirst(routeId);
                    routeSchema = mergeSchemaWithBase(routeConfig.getSchema(), effectiveResourceBase, controllerName);
                    log.debug("Created route-specific schema: {} (from route: {})", routeSchemaName, routeId);
                }
                
                schemas.put(routeSchemaName, routeSchema);
                
            } catch (Exception e) {
                log.warn("Failed to create route-specific schema for route '{}': {}", routeId, e.getMessage());
            }
        }
    }
    
    /**
     * Extracts required fields from user config schema JSON only (not base schema).
     * Returns null if no required fields are specified in the user config.
     * 
     * @param schemaJson The user config schema JSON string
     * @return List of required field names from user config, or null if none specified
     */
    private List<String> extractUserRequiredFields(String schemaJson) {
        try {
            JsonNode node = objectMapper.readTree(schemaJson);
            if (node.has("required") && node.get("required").isArray()) {
                List<String> required = new ArrayList<>();
                for (JsonNode req : node.get("required")) {
                    required.add(req.asText());
                }
                return required.isEmpty() ? null : required;
            }
        } catch (Exception e) {
            log.warn("Failed to parse required fields from config schema: {}", e.getMessage());
        }
        return null;
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
    @SuppressWarnings({"rawtypes"})
    private Schema<?> mergeSchemaWithBase(String aliasSchemaJson, JsonNode baseSchemaNode, String controllerName) 
            throws JsonProcessingException {
        
        JsonNode aliasNode = objectMapper.readTree(aliasSchemaJson);
        
        Schema merged = new Schema();
        merged.setType("object");
        
        // Merge properties
        Map<String, Schema<?>> properties = new LinkedHashMap<>();
        
        // First add base properties - use proper conversion to handle items, etc.
        if (baseSchemaNode.has("properties")) {
            baseSchemaNode.get("properties").fields().forEachRemaining(entry -> {
                Schema propSchema = convertJsonNodeToSchema(entry.getValue());
                properties.put(entry.getKey(), propSchema);
            });
        }
        
        // Then overlay alias properties (overrides base)
        if (aliasNode.has("properties")) {
            aliasNode.get("properties").fields().forEachRemaining(entry -> {
                Schema propSchema = convertJsonNodeToSchema(entry.getValue());
                properties.put(entry.getKey(), propSchema);
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
    @SuppressWarnings({"rawtypes"})
    private void bindRequestBodiesToDomainSchemas(OpenAPI openApi) {
        if (openApi.getPaths() == null) return;
        
        // Build a map of path → aliasConfig for quick lookup
        Map<String, AliasContext> pathToAliasContext = buildPathToAliasContextMap();
        
        log.debug("bindRequestBodiesToDomainSchemas: pathToAliasContext map = {}", pathToAliasContext.keySet());
        log.debug("bindRequestBodiesToDomainSchemas: OAS paths = {}", openApi.getPaths().keySet());
        
        int requestBindCount = 0;
        int responseBindCount = 0;
        
        for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem pathItem = entry.getValue();
            
            AliasContext aliasContext = findAliasContextForPath(path, pathToAliasContext);
            if (aliasContext == null) {
                log.debug("bindRequestBodiesToDomainSchemas: No alias context for path {}", path);
                continue; // No alias config for this path, skip binding
            }
            
            // Use path-specific schema name (e.g., Author, BooksChildChapter, BooksParentAuthor)
            String schemaName = aliasContext.getSchemaName();
            log.debug("bindRequestBodiesToDomainSchemas: path={} → schemaName={}", path, schemaName);
            boolean isCollectionPath = !path.endsWith("/{id}") && !path.endsWith("/count");
            boolean isCountPath = path.endsWith("/count");
            
            // === BIND REQUEST BODIES ===
            
            // Bind POST request body → route-specific schema or fall back to NewXxx schema
            if (pathItem.getPost() != null) {
                String postSchemaName = resolveRequestBodySchemaName(
                    pathItem.getPost(), schemaName, "New", openApi);
                if (bindOperationRequestBody(pathItem.getPost(), postSchemaName, openApi)) {
                    requestBindCount++;
                    log.debug("Bound POST {} requestBody → {}", path, postSchemaName);
                }
                if (bindOperationResponseFromBackend(pathItem.getPost(), schemaName, openApi, aliasContext.getControllerName())) {
                    responseBindCount++;
                    log.debug("Bound POST {} response → {} (backend-shaped)", path, schemaName);
                }
            }
            
            // Bind PUT request body → route-specific schema or fall back to Xxx schema
            if (pathItem.getPut() != null) {
                String putSchemaName = resolveRequestBodySchemaName(
                    pathItem.getPut(), schemaName, "", openApi);
                if (bindOperationRequestBody(pathItem.getPut(), putSchemaName, openApi)) {
                    requestBindCount++;
                    log.debug("Bound PUT {} requestBody → {}", path, putSchemaName);
                }
                if (bindOperationResponseFromBackend(pathItem.getPut(), schemaName, openApi, aliasContext.getControllerName())) {
                    responseBindCount++;
                    log.debug("Bound PUT {} response → {} (backend-shaped)", path, schemaName);
                }
            }
            
            // Bind PATCH request body → route-specific schema or fall back to PatchXxx schema
            if (pathItem.getPatch() != null) {
                String patchSchemaName = resolveRequestBodySchemaName(
                    pathItem.getPatch(), schemaName, "Patch", openApi);
                if (bindOperationRequestBody(pathItem.getPatch(), patchSchemaName, openApi)) {
                    requestBindCount++;
                    log.debug("Bound PATCH {} requestBody → {}", path, patchSchemaName);
                }
                if (bindOperationResponseFromBackend(pathItem.getPatch(), schemaName, openApi, aliasContext.getControllerName())) {
                    responseBindCount++;
                    log.debug("Bound PATCH {} response → {} (backend-shaped)", path, schemaName);
                }
            }
            
            // === BIND RESPONSE SCHEMAS FOR GET ===
            
            if (pathItem.getGet() != null && !isCountPath) {
                // GET on collection → array of Xxx schema
                // GET on instance → single Xxx schema
                // Check for route-specific response schema (e.g., BookFindEntityById)
                String getResponseSchemaName = resolveResponseSchemaName(
                    pathItem.getGet(), schemaName, "", openApi);
                if (bindOperationResponse(pathItem.getGet(), getResponseSchemaName, openApi, isCollectionPath)) {
                    responseBindCount++;
                    log.debug("Bound GET {} response → {}{}",  path, isCollectionPath ? "array of " : "", getResponseSchemaName);
                } else {
                    // Fallback: domain schema not found (no 'schema' block configured for alias).
                    // Create a response schema from the kind-level or base controller schema,
                    // then bind the GET response to it (with array wrapping for collections).
                    log.debug("GET {} response schema '{}' not found, trying fallback via backend schema creation", 
                        path, getResponseSchemaName);
                    if (createAndBindGetResponseSchema(pathItem.getGet(), schemaName, openApi, 
                            aliasContext.getControllerName(), isCollectionPath)) {
                        responseBindCount++;
                        log.debug("Bound GET {} response → {} (fallback){}", path, schemaName, 
                            isCollectionPath ? " [array]" : "");
                    }
                }
            }
            
            // === BIND RESPONSE SCHEMAS FOR DELETE ===
            
            if (pathItem.getDelete() != null) {
                if (bindOperationResponseFromBackend(pathItem.getDelete(), schemaName, openApi, aliasContext.getControllerName())) {
                    responseBindCount++;
                    log.debug("Bound DELETE {} response → {} (backend-shaped)", path, schemaName);
                }
            }
        }
        
        log.info("Bound {} request bodies and {} responses to domain-specific schemas", 
            requestBindCount, responseBindCount);
    }
    
    /**
     * Resolves the appropriate request body schema name for an operation.
     * Checks if a route-specific schema exists (e.g., NewBookCreateEntity) and uses it,
     * otherwise falls back to the kind-level schema (e.g., NewBook).
     * 
     * @param operation The operation to resolve schema for
     * @param schemaName The base schema name (e.g., "Book")
     * @param prefix The prefix for the schema (e.g., "New" for POST, "Patch" for PATCH, "" for PUT)
     * @param openApi The OpenAPI spec to check for schemas
     * @return The resolved schema name to use
     */
    private String resolveRequestBodySchemaName(Operation operation, String schemaName, String prefix, OpenAPI openApi) {
        String kindLevelSchemaName = prefix + schemaName;
        
        if (operation == null) {
            return kindLevelSchemaName;
        }
        
        // Get original route ID from extension - this maps back to the route config key
        // e.g., for operation 'createBook', the original route ID is 'createEntity'
        String routeId = null;
        if (operation.getExtensions() != null) {
            Object ext = operation.getExtensions().get("x-original-route-id");
            if (ext != null) {
                routeId = ext.toString();
            }
        }
        
        if (routeId == null || routeId.isEmpty()) {
            // Fall back to operation ID if no extension
            routeId = operation.getOperationId();
        }
        
        if (routeId == null || routeId.isEmpty()) {
            return kindLevelSchemaName;
        }
        
        // Build route-specific schema name: {prefix}{schemaName}{CapitalizedRouteId}
        // e.g., NewBookCreateEntity, PatchBookUpdateEntityById, BookReplaceEntityById
        String capitalizedRouteId = Character.toUpperCase(routeId.charAt(0)) + routeId.substring(1);
        String routeSpecificSchemaName = prefix + schemaName + capitalizedRouteId;
        
        // Check if route-specific schema exists
        if (openApi.getComponents() != null && 
            openApi.getComponents().getSchemas() != null &&
            openApi.getComponents().getSchemas().containsKey(routeSpecificSchemaName)) {
            log.debug("Using route-specific schema '{}' for operation '{}' (routeId: {})", 
                routeSpecificSchemaName, operation.getOperationId(), routeId);
            return routeSpecificSchemaName;
        }
        
        // Fall back to kind-level schema
        log.debug("Route-specific schema '{}' not found, using kind-level schema '{}' for operation '{}' (routeId: {})",
            routeSpecificSchemaName, kindLevelSchemaName, operation.getOperationId(), routeId);
        return kindLevelSchemaName;
    }
    
    /**
     * Resolves the appropriate response schema name for an operation.
     * Checks if a route-specific schema exists (e.g., BookFindEntityById) and uses it,
     * otherwise falls back to the kind-level schema (e.g., Book).
     * 
     * <p>This is similar to resolveRequestBodySchemaName but used for response schemas,
     * particularly for GET operations where route-level schema overrides should affect
     * the response body.</p>
     * 
     * @param operation The operation to resolve schema for
     * @param schemaName The base schema name (e.g., "Book")
     * @param prefix The prefix for the schema (usually "" for responses)
     * @param openApi The OpenAPI spec to check for schemas
     * @return The resolved schema name to use
     */
    private String resolveResponseSchemaName(Operation operation, String schemaName, String prefix, OpenAPI openApi) {
        String kindLevelSchemaName = prefix.isEmpty() ? schemaName : prefix + schemaName;
        
        if (operation == null) {
            return kindLevelSchemaName;
        }
        
        // Get original route ID from extension - this maps back to the route config key
        // e.g., for operation 'getBook', the original route ID is 'findEntityById'
        String routeId = null;
        if (operation.getExtensions() != null) {
            Object ext = operation.getExtensions().get("x-original-route-id");
            if (ext != null) {
                routeId = ext.toString();
            }
        }
        
        if (routeId == null || routeId.isEmpty()) {
            // Fall back to operation ID if no extension
            routeId = operation.getOperationId();
        }
        
        if (routeId == null || routeId.isEmpty()) {
            return kindLevelSchemaName;
        }
        
        // Build route-specific schema name: {prefix}{schemaName}{CapitalizedRouteId}
        // e.g., BookFindEntityById, BookFindEntities
        String capitalizedRouteId = Character.toUpperCase(routeId.charAt(0)) + routeId.substring(1);
        String routeSpecificSchemaName = (prefix.isEmpty() ? "" : prefix) + schemaName + capitalizedRouteId;
        
        // Check if route-specific schema exists
        if (openApi.getComponents() != null && 
            openApi.getComponents().getSchemas() != null &&
            openApi.getComponents().getSchemas().containsKey(routeSpecificSchemaName)) {
            log.debug("Using route-specific response schema '{}' for operation '{}' (routeId: {})", 
                routeSpecificSchemaName, operation.getOperationId(), routeId);
            return routeSpecificSchemaName;
        }
        
        // Fall back to kind-level schema
        log.debug("Route-specific response schema '{}' not found, using kind-level schema '{}' for operation '{}' (routeId: {})",
            routeSpecificSchemaName, kindLevelSchemaName, operation.getOperationId(), routeId);
        return kindLevelSchemaName;
    }
    
    /**
     * Binds an operation's request body to a domain schema using $ref.
     * Returns true if binding was performed.
     */
    @SuppressWarnings({"rawtypes"})
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
     * Binds an operation response to a backend-shaped schema that is still transformed
     * (alias-specific name + x-record-type) so pruning can apply.
     */
    private boolean bindOperationResponseFromBackend(Operation operation,
                                                     String schemaName,
                                                     OpenAPI openApi,
                                                     String controllerName) {
        if (operation == null || operation.getResponses() == null || openApi == null) {
            return false;
        }

        Schema<?> sourceSchema = extractResponseSchema(operation);
        if (sourceSchema == null) {
            return false;
        }

        String routeId = getOriginalRouteId(operation);
        if (routeId == null || routeId.isEmpty()) {
            routeId = operation.getOperationId();
        }

        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        String capitalizedRouteId = Character.toUpperCase(routeId.charAt(0)) + routeId.substring(1);
        String responseSchemaName = schemaName + capitalizedRouteId;

        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }

        if (!openApi.getComponents().getSchemas().containsKey(responseSchemaName)) {
            // Check if a kind-level merged schema exists (e.g., "Book") — use it instead
            // of cloning the raw backend schema, so domain fields are included in responses.
            Schema<?> kindLevelSchema = openApi.getComponents().getSchemas().get(schemaName);
            if (kindLevelSchema != null) {
                Schema<?> cloned = cloneSchema(kindLevelSchema);
                ensureRecordTypeExtension(cloned, controllerName);
                openApi.getComponents().getSchemas().put(responseSchemaName, cloned);
                log.debug("Created response schema '{}' from kind-level schema '{}'", responseSchemaName, schemaName);
            } else {
                // Fallback: no merged domain schema available, clone raw backend response
                Schema<?> resolved = resolveSchemaRef(openApi, sourceSchema);
                Schema<?> cloned = cloneSchema(resolved != null ? resolved : sourceSchema);
                ensureRecordTypeExtension(cloned, controllerName);
                openApi.getComponents().getSchemas().put(responseSchemaName, cloned);
                log.debug("Created response schema '{}' from raw backend (no domain schema available)", responseSchemaName);
            }
        }

        ApiResponse response200 = operation.getResponses().get("200");
        boolean bound = false;
        if (response200 != null && bindResponseContent(response200, responseSchemaName, false)) {
            bound = true;
        }

        ApiResponse response201 = operation.getResponses().get("201");
        if (response201 != null && bindResponseContent(response201, responseSchemaName, false)) {
            bound = true;
        }

        return bound;
    }

    private Schema<?> extractResponseSchema(Operation operation) {
        if (operation == null || operation.getResponses() == null) {
            return null;
        }

        ApiResponse response = operation.getResponses().get("200");
        if (response == null) {
            response = operation.getResponses().get("201");
        }
        if (response == null || response.getContent() == null) {
            return null;
        }

        io.swagger.v3.oas.models.media.MediaType mediaType = response.getContent().get("application/json");
        if (mediaType == null) {
            mediaType = response.getContent().values().stream().findFirst().orElse(null);
        }
        return mediaType != null ? mediaType.getSchema() : null;
    }

    private String getOriginalRouteId(Operation operation) {
        if (operation == null || operation.getExtensions() == null) {
            return null;
        }
        Object ext = operation.getExtensions().get("x-original-route-id");
        return ext != null ? ext.toString() : null;
    }

    private Schema<?> resolveSchemaRef(OpenAPI openApi, Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        if (schema.get$ref() != null && schema.get$ref().startsWith("#/components/schemas/")) {
            String name = schema.get$ref().substring("#/components/schemas/".length());
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                return openApi.getComponents().getSchemas().get(name);
            }
        }
        return schema;
    }

    private Schema<?> cloneSchema(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper swaggerMapper = io.swagger.v3.core.util.Json.mapper();
            String json = swaggerMapper.writeValueAsString(schema);
            return swaggerMapper.readValue(json, Schema.class);
        } catch (IllegalArgumentException e) {
            log.debug("Failed to clone schema via ObjectMapper: {}", e.getMessage());
            return schema;
        } catch (Exception e) {
            log.debug("Failed to clone schema via swagger mapper: {}", e.getMessage());
            return schema;
        }
    }

    /**
     * Creates a response schema for GET operations when the domain schema doesn't exist.
     * Falls back to the kind-level schema (e.g., "Book") or base controller schema (e.g., "Entity").
     * Supports array wrapping for collection endpoints.
     *
     * @param operation      The GET operation
     * @param schemaName     The target schema name (e.g., "BooksChildChapter" or "Book")
     * @param openApi        The OpenAPI spec
     * @param controllerName The controller name for x-record-type
     * @param isArray        Whether to wrap the response in an array
     * @return true if a schema was created and bound
     */
    @SuppressWarnings({"rawtypes"})
    private boolean createAndBindGetResponseSchema(Operation operation, String schemaName,
                                                    OpenAPI openApi, String controllerName,
                                                    boolean isArray) {
        if (operation == null || operation.getResponses() == null || openApi == null) {
            return false;
        }

        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }

        Map<String, Schema> schemas = openApi.getComponents().getSchemas();

        // If the target schema already exists, just bind it
        if (schemas.containsKey(schemaName)) {
            return bindGetResponse(operation, schemaName, isArray);
        }

        // Try to find a fallback schema:
        // Kind-level schema or base controller schema (e.g., "Entity" for entities controller)
        Schema<?> fallbackSchema = null;
        String fallbackSource = null;

        String baseControllerSchema = getBaseControllerSchemaName(controllerName);

        if (baseControllerSchema != null && schemas.containsKey(baseControllerSchema)) {
            fallbackSchema = schemas.get(baseControllerSchema);
            fallbackSource = baseControllerSchema;
        }

        if (fallbackSchema == null) {
            log.debug("No fallback schema found for GET response binding of '{}'", schemaName);
            return false;
        }

        // Clone the fallback schema with the target name
        Schema<?> cloned = cloneSchema(fallbackSchema);
        ensureRecordTypeExtension(cloned, controllerName);
        schemas.put(schemaName, cloned);
        log.debug("Created GET response schema '{}' from fallback '{}' for controller '{}'", 
            schemaName, fallbackSource, controllerName);

        return bindGetResponse(operation, schemaName, isArray);
    }

    /**
     * Binds a GET operation's 200 response to a schema, optionally wrapping in an array.
     */
    private boolean bindGetResponse(Operation operation, String schemaName, boolean isArray) {
        ApiResponse response200 = operation.getResponses().get("200");
        if (response200 != null) {
            return bindResponseContent(response200, schemaName, isArray);
        }
        return false;
    }

    /**
     * Returns the base controller schema name for a given controller.
     * E.g., "entities" → "Entity", "lists" → "List", "entityReactions" → "EntityReaction"
     */
    private String getBaseControllerSchemaName(String controllerName) {
        if (controllerName == null) return null;
        switch (controllerName) {
            case "entities": return "Entity";
            case "lists": return "List";
            case "relations": return "Relation";
            case "entityReactions": return "EntityReaction";
            case "listReactions": return "ListReaction";
            default: return null;
        }
    }

    private void ensureRecordTypeExtension(Schema<?> schema, String controllerName) {
        if (schema == null) {
            return;
        }
        if (schema.getExtensions() == null) {
            schema.setExtensions(new LinkedHashMap<>());
        }
        if (controllerName != null && !controllerName.isBlank()) {
            schema.getExtensions().putIfAbsent("x-record-type", controllerName);
        }
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
         * 
         * For aliases with specific schemas: Author, Book, BooksChildChapter
         * For base controllers (entities, lists, etc.): Entity, List (even for children/parents)
         * 
         * IMPORTANT: Base controller children/parents paths use the simple kind name
         * because there are no alias-specific nested schemas defined.
         */
        public String getSchemaName() {
            String kindName = kind.substring(0, 1).toUpperCase() + kind.substring(1);
            
            // For base controller paths (parentAlias matches the controller path like "entities"),
            // children/parents should use the simple type name (Entity, List, etc.)
            // because no alias-specific nested schemas are defined.
            if (parentAlias != null && hierarchyType != null) {
                // Check if this is a base controller path (parent alias is controller base path)
                boolean isBaseControllerPath = parentAlias.equals("entities") 
                    || parentAlias.equals("lists")
                    || parentAlias.equals("relations")
                    || parentAlias.equals("entity-reactions")
                    || parentAlias.equals("list-reactions");
                
                if (isBaseControllerPath) {
                    // Base controller children/parents use simple kind name
                    return kindName;
                }
                
                // Alias-specific nested schemas: BooksChildChapter, BooksParentAuthor
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
     * 
     * IMPORTANT: Paths include the base URI prefix (e.g., /api/v1/books)
     * Also includes base controller paths (e.g., /api/v1/entities, /api/v1/relations)
     */
    private Map<String, AliasContext> buildPathToAliasContextMap() {
        Map<String, AliasContext> map = new HashMap<>();
        String baseUri = getBaseUriPrefix();
        
        // 1. Add BASE CONTROLLER paths (for generic /entities, /relations, etc.)
        addBaseControllerPathsToMap(map, baseUri);
        
        // 2. Add ALIAS paths (for /entities/books, /relations/contains, etc.)
        // CRITICAL: Alias paths include the controller base path!
        // e.g., /api/v1/entities/books, NOT /api/v1/books
        openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
            if (controllerConfig.getAliases() == null) return;
            
            // Get the controller base path (e.g., "entities", "relations", "entity-reactions")
            String controllerBasePath = getInboundControllerBasePath(controllerName);
            
            for (AliasConfig aliasConfig : controllerConfig.getAliases()) {
                String alias = aliasConfig.getAlias();
                String kind = aliasConfig.getKind();
                
                if (alias == null || kind == null) continue;
                
                // Add paths for top-level alias WITH controller base path
                // /{baseUri}/{controllerBasePath}/{alias} - collection (POST creates new)
                // e.g., /api/v1/entities/books, /api/v1/relations/contains
                String aliasBasePath = baseUri + "/" + controllerBasePath + "/" + alias;
                map.put(aliasBasePath, new AliasContext(alias, kind, controllerName, false, null, null));
                // /{baseUri}/{controllerBasePath}/{alias}/count - count
                map.put(aliasBasePath + "/count", new AliasContext(alias, kind, controllerName, false, null, null));
                // /{baseUri}/{controllerBasePath}/{alias}/{id} - instance (PUT/PATCH updates)
                map.put(aliasBasePath + "/{id}", new AliasContext(alias, kind, controllerName, false, null, null));
                
                // Add paths for children - these are NESTED under THIS alias
                // Path pattern: /{baseUri}/{controllerBasePath}/{thisAlias}/{id}/{childAlias}
                if (aliasConfig.getChildren() != null) {
                    for (AliasConfig childConfig : aliasConfig.getChildren()) {
                        String childAlias = childConfig.getAlias();
                        String childKind = childConfig.getKind();
                        
                        if (childAlias == null || childKind == null) continue;
                        
                        // /{baseUri}/{controllerBasePath}/{alias}/{id}/{childAlias}
                        String childPath = aliasBasePath + "/{id}/" + childAlias;
                        map.put(childPath, new AliasContext(childAlias, childKind, controllerName, true, alias, "Child"));
                    }
                }
                
                // Add paths for parents - these are NESTED under THIS alias to query parents
                // Path pattern: /{baseUri}/{controllerBasePath}/{thisAlias}/{id}/{parentAlias}
                if (aliasConfig.getParents() != null) {
                    for (AliasConfig parentConfig : aliasConfig.getParents()) {
                        String parentAlias = parentConfig.getAlias();
                        String parentKind = parentConfig.getKind();
                        
                        if (parentAlias == null || parentKind == null) continue;
                        
                        // /{baseUri}/{controllerBasePath}/{alias}/{id}/{parentAlias}
                        String parentPath = aliasBasePath + "/{id}/" + parentAlias;
                        map.put(parentPath, new AliasContext(parentAlias, parentKind, controllerName, true, alias, "Parent"));
                    }
                }
            }
        });
        
        log.debug("Built path-to-alias map with {} entries: {}", map.size(), map.keySet());
        return map;
    }
    
    /**
     * Adds base controller paths to the path-to-alias map.
     * These are generic paths like /entities, /relations that use base schemas.
     * Uses the configured controller base paths (e.g., entity-reactions instead of entityReactions).
     * 
     * @param map The map to add paths to
     * @param baseUri The base URI prefix (e.g., /api/v1)
     */
    private void addBaseControllerPathsToMap(Map<String, AliasContext> map, String baseUri) {
        // Entity controller: uses entitiesBasePath (e.g., "entities")
        map.put(baseUri + "/" + entitiesBasePath, new AliasContext(entitiesBasePath, "Entity", "entities", false, null, null));
        map.put(baseUri + "/" + entitiesBasePath + "/count", new AliasContext(entitiesBasePath, "Entity", "entities", false, null, null));
        map.put(baseUri + "/" + entitiesBasePath + "/{id}", new AliasContext(entitiesBasePath, "Entity", "entities", false, null, null));
        // Add children/parents paths for entities - these return Entity arrays
        map.put(baseUri + "/" + entitiesBasePath + "/{id}/children", new AliasContext("children", "Entity", "entities", true, entitiesBasePath, "Child"));
        map.put(baseUri + "/" + entitiesBasePath + "/{id}/parents", new AliasContext("parents", "Entity", "entities", true, entitiesBasePath, "Parent"));
        
        // List controller: uses listsBasePath (e.g., "lists")
        map.put(baseUri + "/" + listsBasePath, new AliasContext(listsBasePath, "List", "lists", false, null, null));
        map.put(baseUri + "/" + listsBasePath + "/count", new AliasContext(listsBasePath, "List", "lists", false, null, null));
        map.put(baseUri + "/" + listsBasePath + "/{id}", new AliasContext(listsBasePath, "List", "lists", false, null, null));
        // Add children/parents paths for lists
        map.put(baseUri + "/" + listsBasePath + "/{id}/children", new AliasContext("children", "List", "lists", true, listsBasePath, "Child"));
        map.put(baseUri + "/" + listsBasePath + "/{id}/parents", new AliasContext("parents", "List", "lists", true, listsBasePath, "Parent"));
        
        // Relation controller: uses relationsBasePath (e.g., "relations")
        map.put(baseUri + "/" + relationsBasePath, new AliasContext(relationsBasePath, "Relation", "relations", false, null, null));
        map.put(baseUri + "/" + relationsBasePath + "/count", new AliasContext(relationsBasePath, "Relation", "relations", false, null, null));
        map.put(baseUri + "/" + relationsBasePath + "/{id}", new AliasContext(relationsBasePath, "Relation", "relations", false, null, null));
        
        // Entity Reaction controller: uses entityReactionsBasePath (e.g., "entity-reactions")
        map.put(baseUri + "/" + entityReactionsBasePath, new AliasContext(entityReactionsBasePath, "EntityReaction", "entityReactions", false, null, null));
        map.put(baseUri + "/" + entityReactionsBasePath + "/count", new AliasContext(entityReactionsBasePath, "EntityReaction", "entityReactions", false, null, null));
        map.put(baseUri + "/" + entityReactionsBasePath + "/{id}", new AliasContext(entityReactionsBasePath, "EntityReaction", "entityReactions", false, null, null));
        // Add children/parents paths for entity-reactions
        map.put(baseUri + "/" + entityReactionsBasePath + "/{id}/children", new AliasContext("children", "EntityReaction", "entityReactions", true, entityReactionsBasePath, "Child"));
        map.put(baseUri + "/" + entityReactionsBasePath + "/{id}/parents", new AliasContext("parents", "EntityReaction", "entityReactions", true, entityReactionsBasePath, "Parent"));
        
        // List Reaction controller: uses listReactionsBasePath (e.g., "list-reactions")
        map.put(baseUri + "/" + listReactionsBasePath, new AliasContext(listReactionsBasePath, "ListReaction", "listReactions", false, null, null));
        map.put(baseUri + "/" + listReactionsBasePath + "/count", new AliasContext(listReactionsBasePath, "ListReaction", "listReactions", false, null, null));
        map.put(baseUri + "/" + listReactionsBasePath + "/{id}", new AliasContext(listReactionsBasePath, "ListReaction", "listReactions", false, null, null));
        // Add children/parents paths for list-reactions
        map.put(baseUri + "/" + listReactionsBasePath + "/{id}/children", new AliasContext("children", "ListReaction", "listReactions", true, listReactionsBasePath, "Child"));
        map.put(baseUri + "/" + listReactionsBasePath + "/{id}/parents", new AliasContext("parents", "ListReaction", "listReactions", true, listReactionsBasePath, "Parent"));
        
        log.debug("Added base controller paths: {}, {}, {}, {}, {}", 
            entitiesBasePath, listsBasePath, relationsBasePath, entityReactionsBasePath, listReactionsBasePath);
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
        
        // Gateway Validation Error schema - matches createErrorResponse() in ValidateRequestBodyByKindSchema
        // Actual structure: { error: { name, status, message, details: [{code, field, message}] } }
        if (!schemas.containsKey("GatewayValidationError")) {
            Schema validationError = new Schema();
            validationError.setType("object");
            validationError.setDescription("Gateway validation error response");
            
            // Build the nested error object structure
            Schema errorObject = new Schema();
            errorObject.setType("object");
            
            Map<String, Schema> errorProps = new LinkedHashMap<>();
            errorProps.put("name", new Schema().type("string").example("ValidationError"));
            errorProps.put("status", new Schema().type("integer").example(422));
            errorProps.put("message", new Schema().type("string").example("The request is not valid."));
            
            // details is an array of ValidationErrorDetail
            ArraySchema detailsArray = new ArraySchema();
            detailsArray.setItems(new Schema().$ref("#/components/schemas/ValidationErrorDetail"));
            errorProps.put("details", detailsArray);
            
            errorObject.setProperties(errorProps);
            errorObject.setRequired(Arrays.asList("name", "status", "message", "details"));
            
            // Root object has single "error" property
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("error", errorObject);
            
            validationError.setProperties(props);
            validationError.setRequired(Arrays.asList("error"));
            schemas.put("GatewayValidationError", validationError);
        }
        
        // Validation Error Detail schema - matches ValidationMessage structure: {code, field, message}
        if (!schemas.containsKey("ValidationErrorDetail")) {
            Schema detail = new Schema();
            detail.setType("object");
            
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("code", new Schema().type("string").example("required"));
            props.put("field", new Schema().type("string").example("$.name").description("JSON path to the field"));
            props.put("message", new Schema().type("string").example("is required"));
            
            detail.setProperties(props);
            detail.setRequired(Arrays.asList("code", "field", "message"));
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
        
        // NOTE: RateLimitError schema removed - DynamicRateLimiter returns 429 status with NO body
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
        
        // Add 422 Unprocessable Entity - ONLY for methods with request bodies
        // Gateway validates all request bodies, so 422 is always possible for POST/PUT/PATCH
        if (hasRequestBody && !responses.containsKey("422")) {
            ApiResponse unprocessable = new ApiResponse();
            unprocessable.setDescription("Unprocessable Entity - Semantic validation failed");
            unprocessable.setContent(createJsonContent("#/components/schemas/GatewayValidationError"));
            responses.addApiResponse("422", unprocessable);
        }
        
        // Add 429 Too Many Requests (rate limiting) - NO body returned by DynamicRateLimiter
        if (!responses.containsKey("429")) {
            ApiResponse rateLimit = new ApiResponse();
            rateLimit.setDescription("Too Many Requests - Rate limit exceeded. No response body is returned.");
            // No content - DynamicRateLimiter returns only 429 status with no body
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
