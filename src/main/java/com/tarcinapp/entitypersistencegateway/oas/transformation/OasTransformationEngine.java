package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties.*;
import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            OasOrchestratorProperties orchestratorProperties) {
        this.openApiProperties = openApiProperties;
        this.orchestratorProperties = orchestratorProperties;
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
        
        // 2. Transform paths based on alias configurations
        Paths virtualizedPaths = transformPaths(rawOas.getPaths());
        transformed.setPaths(virtualizedPaths);
        
        // 3. Copy and optionally simplify schemas
        if (rawOas.getComponents() != null) {
            transformed.setComponents(transformComponents(rawOas.getComponents()));
        }
        
        // 4. Fix broken $ref references (e.g., #/definitions/X → #/components/schemas/X)
        fixBrokenRefs(transformed);
        
        // 5. Fix schema validation keywords (uniqueItems must be on array, not items)
        fixSchemaValidationKeywords(transformed);
        
        // 6. Deduplicate parameters (name+in must be unique per operation)
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
     */
    private Paths transformPaths(Paths rawPaths) {
        if (rawPaths == null) {
            return new Paths();
        }
        
        Paths virtualizedPaths = new Paths();
        
        // Process each controller's aliases
        openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
            if (controllerConfig.getAliases() == null) {
                return;
            }
            
            controllerConfig.getAliases().forEach(aliasConfig -> {
                List<TransformedPath> aliasPaths = generatePathsForAlias(
                    controllerName, aliasConfig, rawPaths
                );
                
                aliasPaths.forEach(tp -> {
                    if (virtualizedPaths.containsKey(tp.getVirtualPath())) {
                        log.warn("Duplicate virtualized path: {}", tp.getVirtualPath());
                    } else {
                        virtualizedPaths.addPathItem(tp.getVirtualPath(), tp.getPathItem());
                    }
                });
            });
        });
        
        // Optionally include generic endpoints
        if (orchestratorProperties.getTransformation().isIncludeGenericEndpoints()) {
            rawPaths.forEach((path, pathItem) -> {
                if (!isPathVirtualized(path, virtualizedPaths)) {
                    virtualizedPaths.addPathItem(path, pathItem);
                }
            });
        }
        
        return virtualizedPaths;
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
     * Generates base paths (collection: /books, instance: /books/{id}).
     * @param parentAlias If not null, this is a nested resource under the parent
     */
    private List<TransformedPath> generateBasePaths(
            String backendPrefix,
            AliasConfig aliasConfig,
            Paths rawPaths,
            String controllerName,
            AliasConfig parentAlias) {
        
        List<TransformedPath> result = new ArrayList<>();
        String virtualPrefix = "/" + aliasConfig.getAlias();
        
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
     */
    private List<TransformedPath> generateHierarchyPaths(
            String backendPrefix,
            AliasConfig aliasConfig,
            Paths rawPaths,
            String controllerName,
            AliasConfig rootParentAlias) {
        
        List<TransformedPath> result = new ArrayList<>();
        String virtualPrefix = "/" + aliasConfig.getAlias();
        
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
        transformed.setRequestBody(original.getRequestBody());
        
        // MANDATORY: responses object is required per OpenAPI spec
        if (original.getResponses() != null && !original.getResponses().isEmpty()) {
            transformed.setResponses(original.getResponses());
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
        transformed.setRequestBody(original.getRequestBody());
        
        // MANDATORY: responses object is required per OpenAPI spec
        if (original.getResponses() != null && !original.getResponses().isEmpty()) {
            transformed.setResponses(original.getResponses());
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
     * Internal class representing a transformed path.
     */
    @Data
    private static class TransformedPath {
        private final String virtualPath;
        private final PathItem pathItem;
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
}
