package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import com.tarcinapp.entitypersistencegateway.oas.security.FieldPermissionContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component responsible for pruning OpenAPI schemas based on field-level permissions.
 * 
 * <p>This pruner removes properties from schemas that the user is forbidden to see,
 * ensuring the generated OAS only documents fields the user can actually access.</p>
 * 
 * <h2>Pruning Strategy:</h2>
 * <ol>
 *   <li>Deep clone the OpenAPI object to avoid mutating the cached version</li>
 *   <li>For each schema, determine the record type (entities, lists, etc.)</li>
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
    
    // Pattern to extract record type from schema names
    private static final Pattern RECORD_TYPE_PATTERN = Pattern.compile(
        "^(GenericEntity|Entity|List|Relation|EntityReaction|ListReaction)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Mapping from schema name patterns to record types
    private static final Map<String, String> SCHEMA_TO_RECORD_TYPE = Map.of(
        "entity", "entities",
        "genericentity", "entities",
        "list", "lists",
        "relation", "relations",
        "entityreaction", "entityReactions",
        "listreaction", "listReactions",
        "listtoentityrelation", "relations"
    );
    
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
            
            String recordType = inferRecordType(schemaName);
            if (recordType == null) {
                log.trace("Could not infer record type for schema: {}", schemaName);
                continue;
            }
            
            // Get forbidden fields for this record type
            // For schemas, we use getAllForbiddenFields since they're not kind-specific
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
     * Infers the record type from a schema name.
     * 
     * @param schemaName The schema name (e.g., "GenericEntityWithRelations")
     * @return The record type (e.g., "entities") or null if unrecognized
     */
    private String inferRecordType(String schemaName) {
        if (schemaName == null) {
            return null;
        }
        
        String lowerName = schemaName.toLowerCase();
        
        for (Map.Entry<String, String> entry : SCHEMA_TO_RECORD_TYPE.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        // Try pattern matching
        Matcher matcher = RECORD_TYPE_PATTERN.matcher(schemaName);
        if (matcher.find()) {
            String match = matcher.group(1).toLowerCase();
            return SCHEMA_TO_RECORD_TYPE.get(match);
        }
        
        return null;
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
