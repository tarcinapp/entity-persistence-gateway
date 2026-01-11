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
import lombok.Data;
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
    
    private final OpenApiProperties openApiProperties;
    private final OasOrchestratorProperties orchestratorProperties;
    
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
        log.debug("Starting OAS transformation");
        
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
        
        log.info("OAS transformation complete: {} paths virtualized", 
            virtualizedPaths != null ? virtualizedPaths.size() : 0);
        
        return transformed;
    }
    
    /**
     * Builds API info from gateway configuration.
     */
    private Info buildInfo() {
        Info info = new Info();
        info.setTitle(openApiProperties.getTitle());
        info.setVersion(openApiProperties.getVersion());
        info.setDescription(openApiProperties.getDescription());
        
        if (openApiProperties.getContact() != null) {
            Contact contact = new Contact();
            contact.setName(openApiProperties.getContact().getName());
            contact.setEmail(openApiProperties.getContact().getEmail());
            contact.setUrl(openApiProperties.getContact().getUrl());
            info.setContact(contact);
        }
        
        return info;
    }
    
    /**
     * Builds server list from gateway configuration.
     */
    private List<Server> buildServers() {
        if (openApiProperties.getServers() == null) {
            return Collections.emptyList();
        }
        
        return openApiProperties.getServers().stream()
            .map(s -> {
                Server server = new Server();
                server.setUrl(s.getUrl());
                server.setDescription(s.getDescription());
                return server;
            })
            .collect(Collectors.toList());
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
        result.addAll(generateBasePaths(pathPrefix, aliasConfig, rawPaths, controllerName));
        
        // Generate hierarchy paths (children and parents)
        result.addAll(generateHierarchyPaths(pathPrefix, aliasConfig, rawPaths, controllerName));
        
        return result;
    }
    
    /**
     * Generates base paths (collection: /books, instance: /books/{id}).
     */
    private List<TransformedPath> generateBasePaths(
            String backendPrefix,
            AliasConfig aliasConfig,
            Paths rawPaths,
            String controllerName) {
        
        List<TransformedPath> result = new ArrayList<>();
        String virtualPrefix = "/" + aliasConfig.getAlias();
        
        // Collection path: /entities → /books
        PathItem collectionPathItem = rawPaths.get(backendPrefix);
        if (collectionPathItem != null) {
            PathItem transformed = transformPathItem(
                collectionPathItem, aliasConfig, controllerName, false
            );
            // Inject kind filter into query parameters
            injectKindParameter(transformed, aliasConfig.getKind());
            result.add(new TransformedPath(virtualPrefix, transformed));
        }
        
        // Count path: /entities/count → /books/count
        PathItem countPathItem = rawPaths.get(backendPrefix + "/count");
        if (countPathItem != null) {
            PathItem transformed = transformPathItem(
                countPathItem, aliasConfig, controllerName, false
            );
            injectKindParameter(transformed, aliasConfig.getKind());
            result.add(new TransformedPath(virtualPrefix + "/count", transformed));
        }
        
        // Instance path: /entities/{id} → /books/{id}
        PathItem instancePathItem = rawPaths.get(backendPrefix + "/{id}");
        if (instancePathItem != null) {
            PathItem transformed = transformPathItem(
                instancePathItem, aliasConfig, controllerName, true
            );
            result.add(new TransformedPath(virtualPrefix + "/{id}", transformed));
        }
        
        return result;
    }
    
    /**
     * Generates hierarchy paths for children and parents.
     */
    private List<TransformedPath> generateHierarchyPaths(
            String backendPrefix,
            AliasConfig aliasConfig,
            Paths rawPaths,
            String controllerName) {
        
        List<TransformedPath> result = new ArrayList<>();
        String virtualPrefix = "/" + aliasConfig.getAlias();
        
        // Children paths: /entities/{id}/children → /books/{id}/chapters
        if (aliasConfig.getChildren() != null) {
            PathItem childrenPathItem = rawPaths.get(backendPrefix + "/{id}/children");
            
            aliasConfig.getChildren().forEach(childAlias -> {
                if (childrenPathItem != null) {
                    PathItem transformed = transformPathItem(
                        childrenPathItem, childAlias, controllerName, true
                    );
                    injectKindParameter(transformed, childAlias.getKind());
                    
                    String virtualPath = virtualPrefix + "/{id}/" + childAlias.getAlias();
                    result.add(new TransformedPath(virtualPath, transformed));
                }
                
                // Recursively process nested hierarchies
                result.addAll(generateHierarchyPaths(
                    backendPrefix + "/{id}/children", 
                    childAlias, 
                    rawPaths, 
                    controllerName
                ));
            });
        }
        
        // Parents paths: /entities/{id}/parents → /books/{id}/authors
        if (aliasConfig.getParents() != null) {
            PathItem parentsPathItem = rawPaths.get(backendPrefix + "/{id}/parents");
            
            aliasConfig.getParents().forEach(parentAlias -> {
                if (parentsPathItem != null) {
                    PathItem transformed = transformPathItem(
                        parentsPathItem, parentAlias, controllerName, true
                    );
                    injectKindParameter(transformed, parentAlias.getKind());
                    
                    String virtualPath = virtualPrefix + "/{id}/" + parentAlias.getAlias();
                    result.add(new TransformedPath(virtualPath, transformed));
                }
            });
        }
        
        return result;
    }
    
    /**
     * Transforms a PathItem by rewriting operations.
     */
    private PathItem transformPathItem(
            PathItem original, 
            AliasConfig aliasConfig,
            String controllerName,
            boolean isInstancePath) {
        
        PathItem transformed = new PathItem();
        transformed.setDescription(aliasConfig.getDescription());
        
        // Transform each HTTP method's operation
        if (original.getGet() != null) {
            transformed.setGet(transformOperation(
                original.getGet(), aliasConfig, controllerName, "get", isInstancePath
            ));
        }
        if (original.getPost() != null) {
            transformed.setPost(transformOperation(
                original.getPost(), aliasConfig, controllerName, "post", isInstancePath
            ));
        }
        if (original.getPut() != null) {
            transformed.setPut(transformOperation(
                original.getPut(), aliasConfig, controllerName, "put", isInstancePath
            ));
        }
        if (original.getPatch() != null) {
            transformed.setPatch(transformOperation(
                original.getPatch(), aliasConfig, controllerName, "patch", isInstancePath
            ));
        }
        if (original.getDelete() != null) {
            transformed.setDelete(transformOperation(
                original.getDelete(), aliasConfig, controllerName, "delete", isInstancePath
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
            boolean isInstancePath) {
        
        Operation transformed = new Operation();
        
        // Copy base properties
        transformed.setParameters(original.getParameters() != null 
            ? new ArrayList<>(original.getParameters()) 
            : new ArrayList<>());
        transformed.setRequestBody(original.getRequestBody());
        transformed.setResponses(original.getResponses());
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
                : Collections.singletonList(capitalizeFirst(aliasConfig.getAlias())));
        } else if (orchestratorProperties.getTransformation().isAutoGenerateOperationIds()) {
            // Auto-generate operation metadata
            transformed.setOperationId(generateOperationId(originalOpId, aliasConfig));
            transformed.setSummary(generateSummary(originalOpId, aliasConfig, httpMethod, isInstancePath));
            transformed.setTags(Collections.singletonList(capitalizeFirst(aliasConfig.getAlias())));
        } else {
            // Fall back to original
            transformed.setOperationId(originalOpId);
            transformed.setSummary(original.getSummary());
            transformed.setTags(original.getTags());
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
}
