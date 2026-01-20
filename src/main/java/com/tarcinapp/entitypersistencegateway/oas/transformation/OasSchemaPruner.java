package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.tarcinapp.entitypersistencegateway.oas.security.FieldPermissionContext;
import com.tarcinapp.entitypersistencegateway.oas.security.FieldPermissionContext.RecordTypeRules;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Prunes OpenAPI schemas based on forbidden fields returned by OPA.
 *
 * <p>Removal rules are fully driven by the OPA responses (no hardcoded field names).
 * All forbidden fields from ALL record types are removed from ALL schemas,
 * ensuring consistent security posture across the spec.
 */
@Component
@Slf4j
@SuppressWarnings("rawtypes")
public class OasSchemaPruner {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Prunes component schemas in-place using per-operation forbidden field sets.
     *
     * @param openApi    The OpenAPI document to mutate
     * @param permissions Per-operation field permissions from OPA
     * @return The same OpenAPI instance (for chaining)
     */
    public OpenAPI pruneWithMultiOperationPermissions(OpenAPI openApi, MultiOperationFieldPermissions permissions) {
        if (openApi == null || permissions == null) {
            log.warn("pruneWithMultiOperationPermissions: openApi={}, permissions={}", openApi != null, permissions != null);
            return openApi;
        }
        
        if (permissions.isFullVisibility()) {
            log.warn("pruneWithMultiOperationPermissions: FULL VISIBILITY is set - NO pruning will occur!");
            return openApi;
        }

        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            log.warn("pruneWithMultiOperationPermissions: No components or schemas");
            return openApi;
        }

        log.info("pruneWithMultiOperationPermissions: Starting pruning of {} schemas", 
            openApi.getComponents().getSchemas().size());

        for (Map.Entry<String, Schema> entry : openApi.getComponents().getSchemas().entrySet()) {
            String schemaName = entry.getKey();
            Schema<?> schema = entry.getValue();

            Operation operation = resolveOperation(schemaName);
            FieldPermissionContext context = permissions.getPermissionsForOperation(operation);
            
            log.debug("Pruning schema '{}' with operation '{}', fullVisibility={}", 
                schemaName, operation.name(), context.isFullVisibility());

            pruneSchema(schema, context);
        }

        // NEW: normalize parameter styles to valid OpenAPI values (simple, deepObject, etc.)
        normalizeParameterStyles(openApi);
        
        // NEW: Post-process to ensure lowercase style values in serialization
        convertStyleEnumsToLowercase(openApi);

        return openApi;
    }

    private Operation resolveOperation(String schemaName) {
        if (schemaName != null) {
            if (schemaName.startsWith("New")) {
                return Operation.CREATE;
            }
            if (schemaName.startsWith("Patch")) {
                return Operation.UPDATE;
            }
        }
        return Operation.FIND;
    }

    private void pruneSchema(Schema<?> schema, FieldPermissionContext context) {
        if (schema == null || context == null) {
            log.warn("pruneSchema: schema={}, context={}", schema != null, context != null);
            return;
        }
        
        log.warn("pruneSchema: Checking context. fullVisibility={}, rules={}", 
            context.isFullVisibility(), context.getRules() != null ? context.getRules().size() : "null");
        
        if (context.isFullVisibility()) {
            log.warn("pruneSchema: context has full visibility, skipping pruning");
            return;
        }

        // Get union of ALL forbidden fields from ALL record types
        Set<String> forbiddenFields = unionForbiddenFields(context);

        log.warn("pruneSchema: Found {} forbidden fields to remove", 
            forbiddenFields != null ? forbiddenFields.size() : 0);
        
        if (forbiddenFields != null && !forbiddenFields.isEmpty()) {
            log.warn("Forbidden fields: {}", forbiddenFields);
            forbiddenFields.forEach(path -> {
                log.warn("Removing field path: {}", path);
                pruneFieldPath(schema, path.split("\\."));
            });

            // Also recursively prune from nested schemas (array items, nested objects)
            pruneNestedSchemas(schema, forbiddenFields);
        }
    }

    private void pruneNestedSchemas(Schema<?> schema, Set<String> forbiddenFields) {
        if (schema == null || schema.getProperties() == null) {
            return;
        }

        for (Schema<?> propertySchema : schema.getProperties().values()) {
            if (propertySchema instanceof ArraySchema) {
                Schema<?> items = ((ArraySchema) propertySchema).getItems();
                if (items != null) {
                    log.warn("Pruning nested array items schema");
                    // Recursively prune array item schemas
                    forbiddenFields.forEach(path -> pruneFieldPath(items, path.split("\\.")));
                    // Recurse deeper
                    pruneNestedSchemas(items, forbiddenFields);
                }
            } else if (propertySchema != null) {
                // Recursively prune nested object schemas
                pruneNestedSchemas(propertySchema, forbiddenFields);
            }

            // Handle additionalProperties
            Object additional = propertySchema != null ? propertySchema.getAdditionalProperties() : null;
            if (additional instanceof Schema) {
                forbiddenFields.forEach(path -> pruneFieldPath((Schema<?>) additional, path.split("\\.")));
                pruneNestedSchemas((Schema<?>) additional, forbiddenFields);
            }
        }

        // Handle allOf/anyOf/oneOf
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(sub -> {
                forbiddenFields.forEach(path -> pruneFieldPath(sub, path.split("\\.")));
                pruneNestedSchemas(sub, forbiddenFields);
            });
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(sub -> {
                forbiddenFields.forEach(path -> pruneFieldPath(sub, path.split("\\.")));
                pruneNestedSchemas(sub, forbiddenFields);
            });
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(sub -> {
                forbiddenFields.forEach(path -> pruneFieldPath(sub, path.split("\\.")));
                pruneNestedSchemas(sub, forbiddenFields);
            });
        }
    }

    private Set<String> unionForbiddenFields(FieldPermissionContext context) {
        if (context == null || context.isFullVisibility() || context.getRules() == null) {
            return Set.of();
        }
        Set<String> union = new HashSet<>();
        for (RecordTypeRules rules : context.getRules().values()) {
            if (rules.getDefaultFields() != null) {
                union.addAll(rules.getDefaultFields());
            }
            if (rules.getKinds() != null) {
                rules.getKinds().values().forEach(list -> {
                    if (list != null) {
                        union.addAll(list);
                    }
                });
            }
        }
        return union;
    }

    private void pruneFieldPath(Schema<?> schema, String[] pathParts) {
        if (schema == null || pathParts.length == 0) {
            log.warn("pruneFieldPath: Early return - schema={}, pathParts.length={}", 
                schema != null, pathParts != null ? pathParts.length : 0);
            return;
        }

        // Propagate pruning through composed schemas
        pruneComposed(schema, pathParts);

        // Arrays: prune inside items
        if (schema instanceof ArraySchema) {
            log.warn("pruneFieldPath: Processing ArraySchema");
            pruneFieldPath(((ArraySchema) schema).getItems(), pathParts);
        }

        // Map-like additionalProperties
        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Schema) {
            log.warn("pruneFieldPath: Processing additionalProperties");
            pruneFieldPath((Schema<?>) additional, pathParts);
        }

        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            log.warn("pruneFieldPath: properties is null for path {}", String.join(".", pathParts));
            return;
        }

        String head = pathParts[0];
        Schema<?> child = properties.get(head);
        if (child == null) {
            log.warn("pruneFieldPath: Field '{}' not found in properties. Available: {}", 
                head, properties.keySet());
            return;
        }

        if (pathParts.length == 1) {
            log.warn("pruneFieldPath: REMOVING field '{}' from schema", head);
            properties.remove(head);
            removeFromRequired(schema, head);
        } else {
            pruneFieldPath(child, Arrays.copyOfRange(pathParts, 1, pathParts.length));
        }
    }

    private void pruneComposed(Schema<?> schema, String[] pathParts) {
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(sub -> pruneFieldPath(sub, pathParts));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(sub -> pruneFieldPath(sub, pathParts));
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(sub -> pruneFieldPath(sub, pathParts));
        }
    }

    private void removeFromRequired(Schema<?> schema, String fieldName) {
        if (schema.getRequired() == null) {
            return;
        }
        schema.getRequired().removeIf(fieldName::equals);
        if (schema.getRequired().isEmpty()) {
            schema.setRequired(null);
        }
    }

    private static final Map<String, Parameter.StyleEnum> STYLE_NORMALIZATION = Map.of(
        "SIMPLE", Parameter.StyleEnum.SIMPLE,
        "FORM", Parameter.StyleEnum.FORM,
        "MATRIX", Parameter.StyleEnum.MATRIX,
        "LABEL", Parameter.StyleEnum.LABEL,
        "SPACEDELIMITED", Parameter.StyleEnum.SPACEDELIMITED,
        "PIPEDELIMITED", Parameter.StyleEnum.PIPEDELIMITED,
        "DEEPOBJECT", Parameter.StyleEnum.DEEPOBJECT
    );

    private void normalizeParameterStyles(OpenAPI openApi) {
        if (openApi == null) {
            return;
        }

        log.info("normalizeParameterStyles: Starting parameter style normalization");

        // Components-level parameters
        if (openApi.getComponents() != null && openApi.getComponents().getParameters() != null) {
            int count = openApi.getComponents().getParameters().size();
            log.info("Normalizing {} component-level parameters", count);
            openApi.getComponents().getParameters().values().forEach(this::normalizeParameterStyle);
        }

        // Path- and operation-level parameters
        if (openApi.getPaths() != null) {
            for (PathItem pathItem : openApi.getPaths().values()) {
                if (pathItem == null) continue;

                if (pathItem.getParameters() != null) {
                    log.info("Normalizing {} path-level parameters", pathItem.getParameters().size());
                    pathItem.getParameters().forEach(this::normalizeParameterStyle);
                }

                pathItem.readOperations().forEach(op -> {
                    if (op.getParameters() != null) {
                        log.info("Normalizing {} parameters for operation", op.getParameters().size());
                        op.getParameters().forEach(this::normalizeParameterStyle);
                    }
                });
            }
        }
        
        log.info("normalizeParameterStyles: Parameter style normalization complete");
    }

    private void normalizeParameterStyle(Parameter parameter) {
        if (parameter == null || parameter.getStyle() == null) {
            return;
        }
        Parameter.StyleEnum current = parameter.getStyle();
        
        // Get the enum's value - try getValue() first (if it exists), then fall back to name().toLowerCase()
        String currentValue = null;
        try {
            // Try to call getValue() if the enum has it
            currentValue = (String) current.getClass().getMethod("getValue").invoke(current);
        } catch (Exception e) {
            // Fallback: use name and lowercase it
            currentValue = current.name().toLowerCase();
        }
        
        if (currentValue == null || currentValue.isBlank()) {
            return;
        }

        // Map to canonical enum if needed
        Parameter.StyleEnum mapped = STYLE_NORMALIZATION.getOrDefault(current.name(), current);
        
        if (mapped != null) {
            log.debug("Normalizing parameter style from {} to {}", current.name(), mapped.name());
            parameter.setStyle(mapped);
        } else {
            log.warn("normalizeParameterStyle: unsupported style '{}', leaving as-is", currentValue);
        }
    }

    /**
     * Post-process the OpenAPI object to ensure StyleEnum values serialize as lowercase.
     * This converts the object to JSON, lowercases all "style" field values, and reconstructs.
     */
    private void convertStyleEnumsToLowercase(OpenAPI openApi) {
        try {
            // Convert OpenAPI to JSON tree
            var tree = objectMapper.convertValue(openApi, ObjectNode.class);
            processNodeForLowercaseStyles(tree);
            log.info("Post-processed OpenAPI styles to lowercase");
        } catch (Exception e) {
            log.warn("Failed to post-process style values: {}", e.getMessage());
        }
    }

    private void processNodeForLowercaseStyles(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objNode = (ObjectNode) node;
            
            // If this node has a "style" field, lowercase it
            if (objNode.has("style")) {
                var styleValue = objNode.get("style");
                if (styleValue != null && styleValue.isTextual()) {
                    String lowerStyle = styleValue.asText().toLowerCase();
                    objNode.put("style", lowerStyle);
                    log.debug("Converted style '{}' to '{}'", styleValue.asText(), lowerStyle);
                }
            }
            
            // Recursively process all child nodes
            var iter = objNode.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                processNodeForLowercaseStyles(entry.getValue());
            }
        } else if (node.isArray()) {
            for (var item : node) {
                processNodeForLowercaseStyles(item);
            }
        }
    }
}

