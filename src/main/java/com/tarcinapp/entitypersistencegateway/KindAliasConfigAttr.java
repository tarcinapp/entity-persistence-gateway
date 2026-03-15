package com.tarcinapp.entitypersistencegateway;

import lombok.Data;

/*
 * This class holds the configuration parameters when a kind alias is configured.
 * - Helps other filters to check if kind alias configured.
 * - Helps other filters to get the entity kind name if kind alias configured.
 * - Helps other filters to get original resource URL if kind alias configured.
 *   For instance, if the kind alias is configured as /users, then the original resource URL is /entities/{id}.
 *   Knowing the original resource URL is needed for the authorization logic.
 *   Authorization logic needs to know the original resource URL in order to check if the user is authorized to access the original resource.
 * 
 * Validation Context Fields (computed by resolution filters):
 * - effectiveValidationEnabled: Pre-computed validation flag using priority hierarchy
 * - isHierarchyRequest: True if request was resolved via HierarchyKindAliasResolver
 * - hierarchySchemaKey: Schema key for hierarchy-level override (format: "hierarchy:{controller}:{rootKind}:{targetAlias}")
 * - hierarchyRouteSchemaKey: Schema key for hierarchy route-level override (format: "hierarchy-route:{controller}:{rootKind}:{targetAlias}:{routeId}")
 * - isThroughRequest: True if request was resolved via ThroughKindAliasResolver
 * - throughSchemaKey: Schema key for through-level override (format: "through:{controller}:{rootKind}:{targetAlias}")
 * - throughRouteSchemaKey: Schema key for through route-level override (format: "through-route:{controller}:{rootKind}:{targetAlias}:{routeId}")
 */
@Data
public class KindAliasConfigAttr {
    public static final String KIND_ALIAS_CONFIG_ATTR = "KindAliasConfigAttr";
    
    // Core identity fields
    boolean isKindAliasConfigured;
    String kindName;
    String kindAlias;
    String controllerName;
    String baseControllerName;
    String recordType;
    String originalResourceUrl;
    
    // Validation context fields (computed by resolution filters)
    Boolean effectiveValidationEnabled;
    boolean isHierarchyRequest;
    String hierarchySchemaKey;
    String hierarchyRouteSchemaKey;
    
    // Through alias resolution (computed by ThroughKindAliasResolver)
    boolean isThroughRequest;
    String throughSchemaKey;
    String throughRouteSchemaKey;
}
