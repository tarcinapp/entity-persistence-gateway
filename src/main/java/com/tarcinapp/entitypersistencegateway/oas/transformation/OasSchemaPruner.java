package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import com.tarcinapp.entitypersistencegateway.oas.security.FieldPermissionContext;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions.Operation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Component responsible for pruning OpenAPI schemas based on field-level permissions.
 * 
 * <p>This pruner removes properties from schemas that the user is forbidden to see,
 * ensuring the generated OAS only documents fields the user can actually access.</p>
 * 
 * <h2>Schema Identification Strategy:</h2>
 * <p>Uses OpenAPI vendor extension <code>x-record-type</code> to determine the record type
 * of each schema. This extension is injected by OasTransformationEngine during schema
 * generation and contains values: entities, lists, relations, entityReactions, listReactions.</p>
 * 
 * <h2>Pruning Strategy:</h2>
 * <ol>
 *   <li>Deep clone the OpenAPI object to avoid mutating the cached version</li>
 *   <li>For each schema, read the x-record-type vendor extension</li>
 *   <li>If x-record-type is missing, log CRITICAL error and skip schema (fail-safe)</li>
 *   <li>Retrieve forbidden fields from the permission context</li>
 *   <li>Recursively remove properties from the schema</li>
 *   <li>Also remove from 'required' arrays if present</li>
 * </ol>
 * 
 * <h2>Nested Field Support:</h2>
 * <p>Supports dot-notation for nested fields: "address.zipCode" will remove
 * the zipCode property from the nested address object.</p>
 */
@Component
@Slf4j
public class OasSchemaPruner {
    
    private final OasOrchestratorProperties properties;
    
    // Vendor extension property for record type identification
    private static final String RECORD_TYPE_EXTENSION = "x-record-type";
    
    public OasSchemaPruner(OasOrchestratorProperties properties) {
        this.properties = properties;
    }
    
    /**
     * Prunes the OpenAPI specification based on field permissions.
     * 
     * @param openApi The transformed OpenAPI spec
     * @param permissions The field permission context from OPA
     * @return A pruned copy of the OpenAPI spec
     */
    public OpenAPI prune(OpenAPI openApi, FieldPermissionContext permissions) {
        if (permissions.isFullVisibility()) {
            log.debug("Full visibility - skipping schema pruning");
            return openApi;
        }
        
        log.debug("Starting schema pruning with {} record type rules", 
            permissions.getRules().size());
        
        // Deep clone to avoid mutating the original
        OpenAPI pruned = deepClone(openApi);
        
        Components components = pruned.getComponents();
        if (components == null || components.getSchemas() == null) {
            log.debug("No schemas to prune");
            return pruned;
        }
        
        int totalPruned = 0;
        Map<String, Schema> schemas = components.getSchemas();
        
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String schemaName = entry.getKey();
            Schema<?> schema = entry.getValue();
            
            // Read record type from x-record-type vendor extension
            String recordType = getRecordTypeFromExtension(schema, schemaName);
            if (recordType == null) {
                // CRITICAL: Schema has no x-record-type extension
                log.error("CRITICAL: Schema '{}' missing x-record-type vendor extension. " +
                    "This is a security breach - schema pruning CANNOT proceed without explicit record type metadata. " +
                    "All transformations must inject x-record-type extension.", schemaName);
                // Skip this schema rather than guessing
                continue;
            }
            
            // Get forbidden fields for this record type
            Set<String> forbiddenFields = permissions.getAllForbiddenFields(recordType);
            
            if (forbiddenFields.isEmpty()) {
                continue;
            }
            
            int prunedCount = pruneSchemaProperties(schema, forbiddenFields, schemaName);
            totalPruned += prunedCount;
            
            if (prunedCount > 0) {
                log.debug("Pruned {} fields from schema '{}' (recordType: {})",
                    prunedCount, schemaName, recordType);
            }
        }
        
        log.info("Schema pruning complete: {} total fields pruned", totalPruned);
        
        return pruned;
    }
    
    /**
     * Prunes the OpenAPI specification based on MULTI-OPERATION field permissions.
     * 
     * <p>This method uses different forbidden field sets for different operations:</p>
     * <ul>
     *   <li><strong>GET response schemas:</strong> Uses FIND permissions</li>
     *   <li><strong>POST request body schemas:</strong> Uses CREATE permissions</li>
     *   <li><strong>PATCH/PUT request body schemas:</strong> Uses UPDATE permissions</li>
     * </ul>
     * 
     * <p>The method walks through all paths and operations, identifies which schemas
     * are used where, and applies the appropriate forbidden fields.</p>
     * 
     * @param openApi The transformed OpenAPI spec
     * @param permissions The multi-operation permissions from OPA
     * @return A pruned copy of the OpenAPI spec
     */
    public OpenAPI pruneWithMultiOperationPermissions(OpenAPI openApi, MultiOperationFieldPermissions permissions) {
        if (permissions.isFullVisibility()) {
            log.debug("Full visibility for all operations - skipping schema pruning");
            return openApi;
        }
        
        log.info("Starting multi-operation schema pruning");
        
        // Deep clone to avoid mutating the original
        OpenAPI pruned = deepClone(openApi);
        
        // Track which schemas are used by which operations
        Map<String, Set<Operation>> schemaOperationUsage = analyzeSchemaUsage(pruned);
        
        log.debug("Analyzed schema usage: {} schemas mapped to operations", schemaOperationUsage.size());
        
        Components components = pruned.getComponents();
        if (components == null || components.getSchemas() == null) {
            log.debug("No schemas to prune");
            return pruned;
        }
        
        int totalPruned = 0;
        Map<String, Schema> schemas = components.getSchemas();
        
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String schemaName = entry.getKey();
            Schema<?> schema = entry.getValue();
            
            // Read record type from x-record-type vendor extension
            String recordType = getRecordTypeFromExtension(schema, schemaName);
            if (recordType == null) {
                log.warn("Schema '{}' missing x-record-type extension, skipping pruning", schemaName);
                continue;
            }
            
            // Get the operations this schema is used for
            Set<Operation> operations = schemaOperationUsage.getOrDefault(schemaName, Collections.emptySet());
            
            // Determine which forbidden fields to use based on operations
            Set<String> forbiddenFields = computeForbiddenFieldsForSchema(
                schemaName, recordType, operations, permissions);
            
            if (forbiddenFields.isEmpty()) {
                continue;
            }
            
            int prunedCount = pruneSchemaProperties(schema, forbiddenFields, schemaName);
            totalPruned += prunedCount;
            
            if (prunedCount > 0) {
                log.debug("Pruned {} fields from schema '{}' (recordType: {}, operations: {})",
                    prunedCount, schemaName, recordType, operations);
            }
        }
        
        log.info("Multi-operation schema pruning complete: {} total fields pruned", totalPruned);
        
        return pruned;
    }
    
    /**
     * Analyzes the OpenAPI paths to determine which schemas are used by which operations.
     * 
     * @param openApi The OpenAPI spec to analyze
     * @return Map of schema name to set of operations that use it
     */
    private Map<String, Set<Operation>> analyzeSchemaUsage(OpenAPI openApi) {
        Map<String, Set<Operation>> usage = new HashMap<>();
        
        if (openApi.getPaths() == null) {
            return usage;
        }
        
        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            PathItem pathItem = pathEntry.getValue();
            
            // GET operations use FIND permissions (response schemas)
            if (pathItem.getGet() != null) {
                collectResponseSchemas(pathItem.getGet().getResponses(), usage, Operation.FIND);
            }
            
            // POST operations use CREATE permissions (request body schemas)
            if (pathItem.getPost() != null) {
                collectRequestBodySchemas(pathItem.getPost().getRequestBody(), usage, Operation.CREATE);
                collectResponseSchemas(pathItem.getPost().getResponses(), usage, Operation.CREATE);
            }
            
            // PATCH/PUT operations use UPDATE permissions (request body schemas)
            if (pathItem.getPatch() != null) {
                collectRequestBodySchemas(pathItem.getPatch().getRequestBody(), usage, Operation.UPDATE);
                collectResponseSchemas(pathItem.getPatch().getResponses(), usage, Operation.UPDATE);
            }
            if (pathItem.getPut() != null) {
                collectRequestBodySchemas(pathItem.getPut().getRequestBody(), usage, Operation.UPDATE);
                collectResponseSchemas(pathItem.getPut().getResponses(), usage, Operation.UPDATE);
            }
            
            // DELETE operations - also mark any schemas used
            if (pathItem.getDelete() != null) {
                collectResponseSchemas(pathItem.getDelete().getResponses(), usage, Operation.FIND);
            }
        }
        
        return usage;
    }
    
    /**
     * Extracts schema names from request body and adds them to usage map.
     */
    private void collectRequestBodySchemas(RequestBody requestBody, Map<String, Set<Operation>> usage, Operation operation) {
        if (requestBody == null || requestBody.getContent() == null) {
            return;
        }
        
        for (MediaType mediaType : requestBody.getContent().values()) {
            if (mediaType.getSchema() != null) {
                String schemaRef = extractSchemaRef(mediaType.getSchema());
                if (schemaRef != null) {
                    usage.computeIfAbsent(schemaRef, k -> new HashSet<>()).add(operation);
                }
            }
        }
    }
    
    /**
     * Extracts schema names from responses and adds them to usage map.
     */
    private void collectResponseSchemas(io.swagger.v3.oas.models.responses.ApiResponses responses, 
                                         Map<String, Set<Operation>> usage, Operation operation) {
        if (responses == null) {
            return;
        }
        
        for (ApiResponse response : responses.values()) {
            Content content = response.getContent();
            if (content == null) {
                continue;
            }
            
            for (MediaType mediaType : content.values()) {
                if (mediaType.getSchema() != null) {
                    String schemaRef = extractSchemaRef(mediaType.getSchema());
                    if (schemaRef != null) {
                        usage.computeIfAbsent(schemaRef, k -> new HashSet<>()).add(operation);
                    }
                    
                    // Also check items for array schemas
                    if (mediaType.getSchema().getItems() != null) {
                        String itemsRef = extractSchemaRef(mediaType.getSchema().getItems());
                        if (itemsRef != null) {
                            usage.computeIfAbsent(itemsRef, k -> new HashSet<>()).add(operation);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Extracts the schema name from a $ref string.
     */
    private String extractSchemaRef(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        
        String ref = schema.get$ref();
        if (ref != null && ref.startsWith("#/components/schemas/")) {
            return ref.replace("#/components/schemas/", "");
        }
        
        // For inline schemas with items
        if (schema.getItems() != null) {
            return extractSchemaRef(schema.getItems());
        }
        
        return null;
    }
    
    /**
     * Computes the set of forbidden fields for a schema based on its operation usage.
     * 
     * <p>If a schema is used by multiple operations, we use the INTERSECTION of allowed fields
     * (or UNION of forbidden fields) to ensure consistency.</p>
     */
    private Set<String> computeForbiddenFieldsForSchema(String schemaName, String recordType, 
                                                         Set<Operation> operations, 
                                                         MultiOperationFieldPermissions permissions) {
        if (operations.isEmpty()) {
            // Schema not directly referenced by any path, use the most restrictive (union all)
            log.trace("Schema '{}' not directly referenced, using union of all operation forbidden fields", schemaName);
            Set<String> allForbidden = new HashSet<>();
            for (Operation op : Operation.values()) {
                FieldPermissionContext ctx = permissions.getPermissionsForOperation(op);
                allForbidden.addAll(ctx.getAllForbiddenFields(recordType));
            }
            return allForbidden;
        }
        
        // Use union of forbidden fields from all operations this schema is used in
        Set<String> forbidden = new HashSet<>();
        for (Operation op : operations) {
            FieldPermissionContext ctx = permissions.getPermissionsForOperation(op);
            Set<String> opForbidden = ctx.getAllForbiddenFields(recordType);
            forbidden.addAll(opForbidden);
            
            log.trace("Schema '{}' used in {}: {} forbidden fields from OPA", 
                schemaName, op, opForbidden.size());
        }
        
        return forbidden;
    }
    
    /**
     * Reads the record type from the x-record-type vendor extension in the schema.
     * 
     * @param schema The schema to inspect
     * @param schemaName The schema name (for logging)
     * @return The record type string (entities, lists, relations, entityReactions, listReactions) or null if missing
     */
    private String getRecordTypeFromExtension(Schema<?> schema, String schemaName) {
        if (schema == null || schema.getExtensions() == null) {
            return null;
        }
        
        Object extension = schema.getExtensions().get(RECORD_TYPE_EXTENSION);
        if (extension == null) {
            return null;
        }
        
        String recordType = extension.toString();
        if (recordType.isEmpty()) {
            log.warn("Schema '{}' has empty x-record-type extension", schemaName);
            return null;
        }
        
        log.trace("Schema '{}' has x-record-type: {}", schemaName, recordType);
        return recordType;
    }
    
    /**
     * Prunes a single schema's properties based on forbidden fields.
     * 
     * @param schema The schema to prune
     * @param forbiddenFields Set of forbidden field names (may include dot-notation)
     * @param schemaName For logging purposes
     * @return Number of fields pruned
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int pruneSchemaProperties(Schema<?> schema, Set<String> forbiddenFields, String schemaName) {
        int prunedCount = 0;
        
        // Handle composed schemas (allOf, oneOf, anyOf)
        if (schema instanceof ComposedSchema) {
            ComposedSchema composed = (ComposedSchema) schema;
            
            List<Schema> allOf = composed.getAllOf();
            if (allOf != null) {
                for (Schema subSchema : allOf) {
                    prunedCount += pruneSchemaProperties(subSchema, forbiddenFields, schemaName);
                }
            }
            
            List<Schema> oneOf = composed.getOneOf();
            if (oneOf != null) {
                for (Schema subSchema : oneOf) {
                    prunedCount += pruneSchemaProperties(subSchema, forbiddenFields, schemaName);
                }
            }
            
            List<Schema> anyOf = composed.getAnyOf();
            if (anyOf != null) {
                for (Schema subSchema : anyOf) {
                    prunedCount += pruneSchemaProperties(subSchema, forbiddenFields, schemaName);
                }
            }
            
            return prunedCount;
        }
        
        // Handle array schemas
        if (schema instanceof ArraySchema) {
            ArraySchema arraySchema = (ArraySchema) schema;
            Schema items = arraySchema.getItems();
            if (items != null) {
                prunedCount += pruneSchemaProperties(items, forbiddenFields, schemaName);
            }
            return prunedCount;
        }
        
        // Handle regular object schemas
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return 0;
        }
        
        // Separate simple fields from nested paths
        Set<String> simpleFields = new HashSet<>();
        Map<String, Set<String>> nestedPaths = new HashMap<>();
        
        for (String field : forbiddenFields) {
            if (field.contains(".")) {
                // Nested path: "address.zipCode"
                String[] parts = field.split("\\.", 2);
                nestedPaths.computeIfAbsent(parts[0], k -> new HashSet<>()).add(parts[1]);
            } else {
                simpleFields.add(field);
            }
        }
        
        // Remove simple forbidden fields
        for (String field : simpleFields) {
            if (properties.containsKey(field)) {
                properties.remove(field);
                prunedCount++;
                
                // Also remove from required list
                removeFromRequired(schema, field);
            }
        }
        
        // Handle nested paths
        for (Map.Entry<String, Set<String>> nestedEntry : nestedPaths.entrySet()) {
            String parentField = nestedEntry.getKey();
            Set<String> childFields = nestedEntry.getValue();
            
            Schema nestedSchema = properties.get(parentField);
            if (nestedSchema != null) {
                prunedCount += pruneSchemaProperties(nestedSchema, childFields, schemaName + "." + parentField);
            }
        }
        
        // Recursively prune all nested object schemas
        for (Map.Entry<String, Schema> propEntry : new HashMap<>(properties).entrySet()) {
            Schema propSchema = propEntry.getValue();
            if (propSchema != null && propSchema.getProperties() != null) {
                // This nested schema might have its own forbidden fields
                prunedCount += pruneSchemaProperties(propSchema, forbiddenFields, 
                    schemaName + "." + propEntry.getKey());
            }
        }
        
        return prunedCount;
    }
    
    /**
     * Removes a field from the schema's required array.
     */
    @SuppressWarnings("unchecked")
    private void removeFromRequired(Schema<?> schema, String field) {
        List<String> required = schema.getRequired();
        if (required != null) {
            required.remove(field);
        }
    }
    
    /**
     * Deep clones an OpenAPI object.
     * 
     * <p>This is necessary to avoid mutating the cached raw/transformed OAS
     * when pruning for different users.</p>
     */
    private OpenAPI deepClone(OpenAPI original) {
        // Use serialization for deep clone
        // This is simpler and more reliable than manual copying
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(original);
            return mapper.readValue(json, OpenAPI.class);
        } catch (Exception e) {
            log.warn("Failed to deep clone OpenAPI via JSON, falling back to shallow operations: {}",
                e.getMessage());
            return shallowCloneWithSchemas(original);
        }
    }
    
    /**
     * Fallback shallow clone that at least clones schemas.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private OpenAPI shallowCloneWithSchemas(OpenAPI original) {
        OpenAPI cloned = new OpenAPI();
        cloned.setInfo(original.getInfo());
        cloned.setServers(original.getServers());
        cloned.setTags(original.getTags());
        cloned.setPaths(original.getPaths());
        cloned.setSecurity(original.getSecurity());
        cloned.setExternalDocs(original.getExternalDocs());
        cloned.setExtensions(original.getExtensions());
        
        if (original.getComponents() != null) {
            Components clonedComponents = new Components();
            
            // Clone schemas specifically
            if (original.getComponents().getSchemas() != null) {
                Map<String, Schema> clonedSchemas = new LinkedHashMap<>();
                original.getComponents().getSchemas().forEach((name, schema) -> {
                    clonedSchemas.put(name, cloneSchema(schema));
                });
                clonedComponents.setSchemas(clonedSchemas);
            }
            
            // Copy other components by reference (they won't be modified)
            clonedComponents.setSecuritySchemes(original.getComponents().getSecuritySchemes());
            clonedComponents.setParameters(original.getComponents().getParameters());
            clonedComponents.setRequestBodies(original.getComponents().getRequestBodies());
            clonedComponents.setResponses(original.getComponents().getResponses());
            clonedComponents.setHeaders(original.getComponents().getHeaders());
            clonedComponents.setExamples(original.getComponents().getExamples());
            clonedComponents.setLinks(original.getComponents().getLinks());
            clonedComponents.setCallbacks(original.getComponents().getCallbacks());
            
            cloned.setComponents(clonedComponents);
        }
        
        return cloned;
    }
    
    /**
     * Clones a schema, ensuring properties map is mutable.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Schema cloneSchema(Schema<?> original) {
        if (original == null) {
            return null;
        }
        
        Schema cloned;
        
        if (original instanceof ComposedSchema) {
            ComposedSchema composedCloned = new ComposedSchema();
            ComposedSchema composedOriginal = (ComposedSchema) original;
            
            if (composedOriginal.getAllOf() != null) {
                List<Schema> clonedAllOf = new ArrayList<>();
                composedOriginal.getAllOf().forEach(s -> clonedAllOf.add(cloneSchema(s)));
                composedCloned.setAllOf(clonedAllOf);
            }
            if (composedOriginal.getOneOf() != null) {
                List<Schema> clonedOneOf = new ArrayList<>();
                composedOriginal.getOneOf().forEach(s -> clonedOneOf.add(cloneSchema(s)));
                composedCloned.setOneOf(clonedOneOf);
            }
            if (composedOriginal.getAnyOf() != null) {
                List<Schema> clonedAnyOf = new ArrayList<>();
                composedOriginal.getAnyOf().forEach(s -> clonedAnyOf.add(cloneSchema(s)));
                composedCloned.setAnyOf(clonedAnyOf);
            }
            
            cloned = composedCloned;
        } else if (original instanceof ArraySchema) {
            ArraySchema arrayCloned = new ArraySchema();
            arrayCloned.setItems(cloneSchema(((ArraySchema) original).getItems()));
            cloned = arrayCloned;
        } else {
            cloned = new Schema();
        }
        
        // Copy common properties
        cloned.setType(original.getType());
        cloned.setFormat(original.getFormat());
        cloned.setDescription(original.getDescription());
        cloned.setTitle(original.getTitle());
        cloned.setDefault(original.getDefault());
        cloned.setEnum(original.getEnum());
        cloned.setExample(original.getExample());
        cloned.setExternalDocs(original.getExternalDocs());
        cloned.setDeprecated(original.getDeprecated());
        cloned.set$ref(original.get$ref());
        cloned.setReadOnly(original.getReadOnly());
        cloned.setWriteOnly(original.getWriteOnly());
        cloned.setNullable(original.getNullable());
        cloned.setMinimum(original.getMinimum());
        cloned.setMaximum(original.getMaximum());
        cloned.setMinLength(original.getMinLength());
        cloned.setMaxLength(original.getMaxLength());
        cloned.setPattern(original.getPattern());
        cloned.setAdditionalProperties(original.getAdditionalProperties());
        
        // Clone properties map (must be mutable)
        if (original.getProperties() != null) {
            Map<String, Schema> clonedProps = new LinkedHashMap<>();
            original.getProperties().forEach((key, value) -> {
                clonedProps.put(key, cloneSchema(value));
            });
            cloned.setProperties(clonedProps);
        }
        
        // Clone required list (must be mutable)
        if (original.getRequired() != null) {
            cloned.setRequired(new ArrayList<>(original.getRequired()));
        }
        
        return cloned;
    }
}
