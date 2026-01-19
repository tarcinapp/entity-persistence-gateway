package com.tarcinapp.entitypersistencegateway.oas.transformation;

import com.tarcinapp.entitypersistencegateway.oas.security.FieldPermissionContext;
import com.tarcinapp.entitypersistencegateway.oas.security.FieldPermissionContext.RecordTypeRules;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions;
import com.tarcinapp.entitypersistencegateway.oas.security.MultiOperationFieldPermissions.Operation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
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
public class OasSchemaPruner {

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
}

