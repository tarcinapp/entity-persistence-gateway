package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Registry service that manages and selects appropriate PolicyDataBuilder
 * implementations based on explicit route ID mapping.
 * 
 * Uses a switch-case-like mechanism for predictable, deterministic builder selection.
 */
@Slf4j
@Service
public class PolicyDataBuilderRegistry {

    @Autowired
    @Qualifier("basicPolicyDataBuilderWithPayload")
    private BasicPolicyDataBuilderWithPayload builderWithPayload;

    @Autowired
    @Qualifier("basicPolicyDataBuilderWithoutPayload")
    private BasicPolicyDataBuilderWithoutPayload builderWithoutPayload;

    @Autowired
    @Qualifier("basicPolicyDataBuilderWithPayloadAndOriginal")
    private BasicPolicyDataBuilderWithPayloadAndOriginal builderWithPayloadAndOriginal;

    @Autowired
    @Qualifier("basicPolicyDataBuilderWithoutPayloadNoOriginal")
    private BasicPolicyDataBuilderWithoutPayloadNoOriginal builderWithoutPayloadNoOriginal;

    @Autowired
    @Qualifier("policyDataBuilderWithPayloadAndParent")
    private PolicyDataBuilder builderWithPayloadAndParent;

    @Autowired
    @Qualifier("policyDataBuilderForReactionCreation")
    private PolicyDataBuilder builderForReactionCreation;

    @Autowired
    @Qualifier("policyDataBuilderForRelationCreation")
    private PolicyDataBuilder builderForRelationCreation;

    @Autowired
    @Qualifier("policyDataBuilderForThroughReactionCreation")
    private PolicyDataBuilder builderForThroughReactionCreation;

    @Autowired
    @Qualifier("policyDataBuilderForThroughWithoutPayload")
    private PolicyDataBuilder builderForThroughWithoutPayload;

    private final Map<String, PolicyDataBuilder> routeBuilderMap = new HashMap<>();

    /**
     * Initialize the route to builder mapping.
     * This method is called after dependency injection.
     */
    @PostConstruct
    public void initializeRouteMappings() {
        // Entity routes
        mapEntityRoutes();
        
        // List routes
        mapListRoutes();
        
        // Relation routes
        mapRelationRoutes();
        
        // Entity-reaction routes
        mapEntityReactionRoutes();
        
        // List-reaction routes
        mapListReactionRoutes();
        
        log.info("Initialized route mappings for " + routeBuilderMap.size() + " routes");
    }

    private void mapEntityRoutes() {
        // POST - with payload
        // POST /entities
        routeBuilderMap.put("createEntity", builderWithPayload);
        // POST /entities/{parentId}/children
        routeBuilderMap.put("createEntityChild", builderWithPayloadAndParent);
        // POST /entities/kind/{kindAlias}
        routeBuilderMap.put("createEntityByKindAlias", builderWithPayload);
        // POST /entities/kind/{kindAlias}/{id}/children
        routeBuilderMap.put("createEntityChildByKindAlias", builderWithPayloadAndParent);
        // POST /entities/kind/{kindAlias}/{parentId}/{hierarchyAlias}
        routeBuilderMap.put("createEntityHierarchyByKindAlias", builderWithPayloadAndParent);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /entities/{id}
        routeBuilderMap.put("updateEntityById", builderWithPayloadAndOriginal);
        // PUT /entities/{id}
        routeBuilderMap.put("replaceEntityById", builderWithPayloadAndOriginal);
        // PATCH /entities/kind/{kindAlias}/{id}
        routeBuilderMap.put("updateEntityByIdByKindAlias", builderWithPayloadAndOriginal);
        // PUT /entities/kind/{kindAlias}/{id}
        routeBuilderMap.put("replaceEntityByIdByKindAlias", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /entities
        routeBuilderMap.put("updateAllEntities", builderWithPayload);
        // PATCH /entities/kind/{kindAlias}
        routeBuilderMap.put("updateAllEntitiesByKindAlias", builderWithPayload);

        // GET - without payload
        // GET /entities
        routeBuilderMap.put("findEntities", builderWithoutPayloadNoOriginal);
        // GET /entities/count
        routeBuilderMap.put("countEntities", builderWithoutPayloadNoOriginal);
        // GET /entities/{id}
        routeBuilderMap.put("findEntityById", builderWithoutPayload);
        // GET /entities/{id}/children
        routeBuilderMap.put("findEntityChildren", builderWithoutPayload);
        // GET /entities/{id}/parents
        routeBuilderMap.put("findEntityParents", builderWithoutPayload);
        // GET /entities/kind/{kindAlias}
        routeBuilderMap.put("findAllEntitiesByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /entities/kind/{kindAlias}/count
        routeBuilderMap.put("countEntitiesByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /entities/kind/{kindAlias}/{id}
        routeBuilderMap.put("findEntityByIdByKindAlias", builderWithoutPayload);
        // GET /entities/kind/{kindAlias}/{id}/children
        routeBuilderMap.put("findEntityChildrenByKindAlias", builderWithoutPayload);
        // GET /entities/kind/{kindAlias}/{id}/parents
        routeBuilderMap.put("findEntityParentsByKindAlias", builderWithoutPayload);
        // GET /entities/kind/{kindAlias}/{id}/{hierarchyAlias}
        routeBuilderMap.put("findEntityHierarchyByKindAlias", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /entities/{id}
        routeBuilderMap.put("deleteEntityById", builderWithoutPayload);
        // DELETE /entities/kind/{kindAlias}/{id}
        routeBuilderMap.put("deleteEntityByIdByKindAlias", builderWithoutPayload);
    }

    private void mapListRoutes() {
        // POST - with payload
        // POST /lists
        routeBuilderMap.put("createList", builderWithPayload);
        // POST /lists/{parentId}/children
        routeBuilderMap.put("createListChild", builderWithPayloadAndParent);
        // POST /lists/{listId}/entities
        routeBuilderMap.put("createEntityByListId", builderWithPayloadAndParent);
        // POST /lists/{kindAlias}/{listId}/entities/{throughAlias} (through kind alias)
        routeBuilderMap.put("createEntityByListIdByKindAlias", builderWithPayloadAndParent);
        // POST /lists/{kindAlias}
        routeBuilderMap.put("createListByKindAlias", builderWithPayload);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /lists/{id}
        routeBuilderMap.put("updateListById", builderWithPayloadAndOriginal);
        // PUT /lists/{id}
        routeBuilderMap.put("replaceListById", builderWithPayloadAndOriginal);
        // PATCH /lists/{kindAlias}/{id}
        routeBuilderMap.put("updateListByIdByKindAlias", builderWithPayloadAndOriginal);
        // PUT /lists/{kindAlias}/{id}
        routeBuilderMap.put("replaceListByIdByKindAlias", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /lists
        routeBuilderMap.put("updateAllLists", builderWithPayload);
        // PATCH /lists/{listId}/entities
        routeBuilderMap.put("updateEntitiesByListId", builderWithPayloadAndParent);
        // PATCH /lists/{kindAlias}/{listId}/entities/{throughAlias} (through kind alias)
        routeBuilderMap.put("updateEntitiesByListIdByKindAlias", builderWithPayloadAndParent);
        // PATCH /lists/{kindAlias}
        routeBuilderMap.put("updateAllListsByKindAlias", builderWithPayload);

        // GET - without payload
        // GET /lists
        routeBuilderMap.put("findLists", builderWithoutPayloadNoOriginal);
        // GET /lists/count
        routeBuilderMap.put("countLists", builderWithoutPayloadNoOriginal);
        // GET /lists/{id}
        routeBuilderMap.put("findListById", builderWithoutPayload);
        // GET /lists/{id}/children
        routeBuilderMap.put("findListChildren", builderWithoutPayload);
        // GET /lists/{id}/parents
        routeBuilderMap.put("findListParents", builderWithoutPayload);
        // GET /lists/{listId}/entities
        routeBuilderMap.put("findEntitiesByListId", builderForThroughWithoutPayload);
        // GET /lists/{kindAlias}/{listId}/entities/{throughAlias} (through kind alias)
        routeBuilderMap.put("findEntitiesByListIdByKindAlias", builderForThroughWithoutPayload);
        // GET /entities/{entityId}/lists
        routeBuilderMap.put("findListsByEntityId", builderForThroughWithoutPayload);
        // GET /entities/{kindAlias}/{entityId}/lists/{throughAlias} (through kind alias)
        routeBuilderMap.put("findListsByEntityIdByKindAlias", builderForThroughWithoutPayload);
        // GET /lists/{kindAlias}
        routeBuilderMap.put("findAllListsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /lists/{kindAlias}/count
        routeBuilderMap.put("countListsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /lists/{kindAlias}/{id}
        routeBuilderMap.put("findListByIdByKindAlias", builderWithoutPayload);
        // GET /lists/{kindAlias}/{id}/children
        routeBuilderMap.put("findListChildrenByKindAlias", builderWithoutPayload);
        // GET /lists/{kindAlias}/{id}/parents
        routeBuilderMap.put("findListParentsByKindAlias", builderWithoutPayload);
        // GET /lists/kind/{kindAlias}/{id}/{hierarchyAlias}
        routeBuilderMap.put("findListHierarchyByKindAlias", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /lists/{id}
        routeBuilderMap.put("deleteListById", builderWithoutPayload);
        // DELETE /lists/{listId}/entities
        routeBuilderMap.put("deleteEntitiesByListId", builderForThroughWithoutPayload);
        // DELETE /lists/{kindAlias}/{listId}/entities/{throughAlias} (through kind alias)
        routeBuilderMap.put("deleteEntitiesByListIdByKindAlias", builderForThroughWithoutPayload);
        // DELETE /lists/{kindAlias}/{id}
        routeBuilderMap.put("deleteListByIdByKindAlias", builderWithoutPayload);

        // POST - with payload (children operations)
        // POST /lists/{kindAlias}/{id}/children
        routeBuilderMap.put("createListChildByKindAlias", builderWithPayloadAndParent);
        // POST /lists/kind/{kindAlias}/{parentId}/{hierarchyAlias}
        routeBuilderMap.put("createListHierarchyByKindAlias", builderWithPayloadAndParent);
    }

    private void mapRelationRoutes() {
        // POST - with payload
        // POST /relations
        routeBuilderMap.put("createRelation", builderForRelationCreation);
        // POST /relations/{kindAlias}
        routeBuilderMap.put("createRelationByKindAlias", builderForRelationCreation);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /relations/{id}
        routeBuilderMap.put("updateRelationById", builderWithPayloadAndOriginal);
        // PUT /relations/{id}
        routeBuilderMap.put("replaceRelationById", builderWithPayloadAndOriginal);
        // PATCH /relations/{kindAlias}/{id}
        routeBuilderMap.put("updateRelationByIdByKindAlias", builderWithPayloadAndOriginal);
        // PUT /relations/{kindAlias}/{id}
        routeBuilderMap.put("replaceRelationByIdByKindAlias", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /relations
        routeBuilderMap.put("updateAllRelations", builderWithPayload);
        // PATCH /relations/{kindAlias}
        routeBuilderMap.put("updateAllRelationsByKindAlias", builderWithPayload);

        // GET - without payload
        // GET /relations
        routeBuilderMap.put("findRelations", builderWithoutPayloadNoOriginal);
        // GET /relations/count
        routeBuilderMap.put("countRelations", builderWithoutPayloadNoOriginal);
        // GET /relations/{id}
        routeBuilderMap.put("findRelationById", builderWithoutPayload);
        // GET /relations/{kindAlias}
        routeBuilderMap.put("findAllRelationsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /relations/{kindAlias}/count
        routeBuilderMap.put("countRelationsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /relations/{kindAlias}/{id}
        routeBuilderMap.put("findRelationByIdByKindAlias", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /relations/{id}
        routeBuilderMap.put("deleteRelationById", builderWithoutPayload);
        // DELETE /relations/{kindAlias}/{id}
        routeBuilderMap.put("deleteRelationByIdByKindAlias", builderWithoutPayload);
    }

    private void mapEntityReactionRoutes() {
        // POST - with payload
        // POST /entity-reactions
        routeBuilderMap.put("createEntityReaction", builderForReactionCreation);
        // POST /entity-reactions/{parentId}/children
        routeBuilderMap.put("createChildEntityReaction", builderWithPayloadAndParent);
        // POST /entities/{entityId}/reactions
        routeBuilderMap.put("createReactionByEntityId", builderForThroughReactionCreation);
        // POST /entities/{kindAlias}/{entityId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("createReactionByEntityIdByKindAlias", builderForThroughReactionCreation);
        // POST /entity-reactions/{kindAlias}
        routeBuilderMap.put("createEntityReactionByKindAlias", builderForReactionCreation);
        // POST /entity-reactions/{kindAlias}/{parentId}/children
        routeBuilderMap.put("createChildEntityReactionByKindAlias", builderWithPayloadAndParent);
        // POST /entity-reactions/{kindAlias}/{parentId}/{hierarchyAlias}
        routeBuilderMap.put("createEntityReactionHierarchyByKindAlias", builderWithPayloadAndParent);
        

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /entity-reactions/{id}
        routeBuilderMap.put("updateEntityReactionById", builderWithPayloadAndOriginal);
        // PUT /entity-reactions/{id}
        routeBuilderMap.put("replaceEntityReactionById", builderWithPayloadAndOriginal);
        // PATCH /entity-reactions/{kindAlias}/{id}
        routeBuilderMap.put("updateEntityReactionByIdByKindAlias", builderWithPayloadAndOriginal);
        // PUT /entity-reactions/{kindAlias}/{id}
        routeBuilderMap.put("replaceEntityReactionByIdByKindAlias", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /entity-reactions
        routeBuilderMap.put("updateAllEntityReactions", builderWithPayload);
        // PATCH /entities/{entityId}/reactions
        routeBuilderMap.put("updateReactionsByEntityId", builderWithPayloadAndParent);
        // PATCH /entities/{kindAlias}/{entityId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("updateReactionsByEntityIdByKindAlias", builderWithPayloadAndParent);
        // PATCH /entity-reactions/{kindAlias}
        routeBuilderMap.put("updateAllEntityReactionsByKindAlias", builderWithPayload);

        // GET - without payload
        // GET /entity-reactions
        routeBuilderMap.put("findEntityReactions", builderWithoutPayloadNoOriginal);
        // GET /entity-reactions/count
        routeBuilderMap.put("countEntityReactions", builderWithoutPayloadNoOriginal);
        // GET /entity-reactions/{id}
        routeBuilderMap.put("findEntityReactionById", builderWithoutPayload);
        // GET /entity-reactions/{reactionId}/children
        routeBuilderMap.put("findChildrenEntityReactionsByReactionId", builderWithoutPayload);
        // GET /entity-reactions/{reactionId}/{hierarchyAlias}
        routeBuilderMap.put("findEntityReactionHierarchyByKindAlias", builderWithoutPayload);
        // GET /entity-reactions/{id}/parents
        routeBuilderMap.put("findParentsByEntityReactionId", builderWithoutPayload);
        // GET /entities/{entityId}/reactions
        routeBuilderMap.put("findReactionsByEntityId", builderForThroughWithoutPayload);
        // GET /entities/{kindAlias}/{entityId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("findReactionsByEntityIdByKindAlias", builderForThroughWithoutPayload);
        // GET /entity-reactions/{kindAlias}
        routeBuilderMap.put("findAllEntityReactionsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /entity-reactions/{kindAlias}/count
        routeBuilderMap.put("countEntityReactionsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /entity-reactions/{kindAlias}/{id}
        routeBuilderMap.put("findEntityReactionByIdByKindAlias", builderWithoutPayload);
        // GET /entity-reactions/{kindAlias}/{reactionId}/children
        routeBuilderMap.put("findChildrenEntityReactionsByReactionIdByKindAlias", builderWithoutPayload);
        // GET /entity-reactions/{kindAlias}/{id}/parents
        routeBuilderMap.put("findParentsByEntityReactionIdByKindAlias", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /entity-reactions/{id}
        routeBuilderMap.put("deleteEntityReactionById", builderWithoutPayload);
        // DELETE /entities/{entityId}/reactions
        routeBuilderMap.put("deleteReactionsByEntityId", builderForThroughWithoutPayload);
        // DELETE /entities/{kindAlias}/{entityId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("deleteReactionsByEntityIdByKindAlias", builderForThroughWithoutPayload);
        // DELETE /entity-reactions/{kindAlias}/{id}
        routeBuilderMap.put("deleteEntityReactionByIdByKindAlias", builderWithoutPayload);
    }

    private void mapListReactionRoutes() {
        // POST - with payload
        // POST /list-reactions
        routeBuilderMap.put("createListReaction", builderForReactionCreation);
        // POST /list-reactions/{parentId}/children
        routeBuilderMap.put("createChildListReaction", builderWithPayloadAndParent);
        // POST /lists/{listId}/reactions
        routeBuilderMap.put("createReactionByListId", builderForThroughReactionCreation);
        // POST /lists/{kindAlias}/{listId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("createReactionByListIdByKindAlias", builderForThroughReactionCreation);
        // POST /list-reactions/{kindAlias}
        routeBuilderMap.put("createListReactionByKindAlias", builderForReactionCreation);
        // POST /list-reactions/{kindAlias}/{parentId}/children
        routeBuilderMap.put("createChildListReactionByKindAlias", builderWithPayloadAndParent);
        // POST /list-reactions/{kindAlias}/{parentId}/{hierarchyAlias}
        routeBuilderMap.put("createListReactionHierarchyByKindAlias", builderWithPayloadAndParent);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /list-reactions/{id}
        routeBuilderMap.put("updateListReactionById", builderWithPayloadAndOriginal);
        // PUT /list-reactions/{id}
        routeBuilderMap.put("replaceListReactionById", builderWithPayloadAndOriginal);
        // PATCH /list-reactions/{kindAlias}/{id}
        routeBuilderMap.put("updateListReactionByIdByKindAlias", builderWithPayloadAndOriginal);
        // PUT /list-reactions/{kindAlias}/{id}
        routeBuilderMap.put("replaceListReactionByIdByKindAlias", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /list-reactions
        routeBuilderMap.put("updateAllListReactions", builderWithPayload);
        // PATCH /lists/{listId}/reactions
        routeBuilderMap.put("updateReactionsByListId", builderWithPayloadAndParent);
        // PATCH /lists/{kindAlias}/{listId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("updateReactionsByListIdByKindAlias", builderWithPayloadAndParent);
        // PATCH /list-reactions/{kindAlias}
        routeBuilderMap.put("updateAllListReactionsByKindAlias", builderWithPayload);

        // GET - without payload
        // GET /list-reactions
        routeBuilderMap.put("findListReactions", builderWithoutPayloadNoOriginal);
        // GET /list-reactions/count
        routeBuilderMap.put("countListReactions", builderWithoutPayloadNoOriginal);
        // GET /list-reactions/{id}
        routeBuilderMap.put("findListReactionById", builderWithoutPayload);
        // GET /list-reactions/{reactionId}/children
        routeBuilderMap.put("findChildrenListReactionsByReactionId", builderWithoutPayload);
        // GET /list-reactions/{id}/parents
        routeBuilderMap.put("findParentsByListReactionId", builderWithoutPayload);
        // GET /lists/{listId}/reactions
        routeBuilderMap.put("findReactionsByListId", builderForThroughWithoutPayload);
        // GET /lists/{kindAlias}/{listId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("findReactionsByListIdByKindAlias", builderForThroughWithoutPayload);
        // GET /list-reactions/{kindAlias}
        routeBuilderMap.put("findAllListReactionsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /list-reactions/{kindAlias}/count
        routeBuilderMap.put("countListReactionsByKindAlias", builderWithoutPayloadNoOriginal);
        // GET /list-reactions/{kindAlias}/{id}
        routeBuilderMap.put("findListReactionByIdByKindAlias", builderWithoutPayload);
        // GET /list-reactions/{kindAlias}/{reactionId}/children
        routeBuilderMap.put("findChildrenListReactionsByReactionIdByKindAlias", builderWithoutPayload);
        // GET /list-reactions/{kindAlias}/{id}/parents
        routeBuilderMap.put("findParentsByListReactionIdByKindAlias", builderWithoutPayload);
        // GET /list-reactions/{kindAlias}/{reactionId}/{hierarchyAlias}
        routeBuilderMap.put("findListReactionHierarchyByKindAlias", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /list-reactions/{id}
        routeBuilderMap.put("deleteListReactionById", builderWithoutPayload);
        // DELETE /lists/{listId}/reactions
        routeBuilderMap.put("deleteReactionsByListId", builderForThroughWithoutPayload);
        // DELETE /lists/{kindAlias}/{listId}/reactions/{throughAlias} (through kind alias)
        routeBuilderMap.put("deleteReactionsByListIdByKindAlias", builderForThroughWithoutPayload);
        // DELETE /list-reactions/{kindAlias}/{id}
        routeBuilderMap.put("deleteListReactionByIdByKindAlias", builderWithoutPayload);
    }

    /**
     * Selects the appropriate policy data builder based on the route ID.
     * Uses explicit mapping for deterministic, predictable selection.
     * 
     * @param exchange The server web exchange
     * @return The selected policy data builder
     */
    public PolicyDataBuilder selectBuilder(ServerWebExchange exchange) {
        String routeId = getRouteId(exchange);
        
        PolicyDataBuilder builder = routeBuilderMap.getOrDefault(routeId, builderWithoutPayload);
        
        log.debug("Route: " + routeId + " -> Builder: " + builder.getClass().getSimpleName());
        return builder;
    }

    /**
     * Extracts the route ID from the exchange
     */
    private String getRouteId(ServerWebExchange exchange) {
        Object routeIdAttr = exchange.getAttributes().get(ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR);
        return routeIdAttr != null ? routeIdAttr.toString() : "unknown";
    }

    /**
     * Returns a specific builder by type
     */
    public <T extends PolicyDataBuilder> T getBuilder(Class<T> builderClass) {
        if (builderClass.isInstance(builderWithPayload)) {
            return builderClass.cast(builderWithPayload);
        } else if (builderClass.isInstance(builderWithoutPayload)) {
            return builderClass.cast(builderWithoutPayload);
        } else if (builderClass.isInstance(builderWithPayloadAndOriginal)) {
            return builderClass.cast(builderWithPayloadAndOriginal);
        } else if (builderClass.isInstance(builderWithoutPayloadNoOriginal)) {
            return builderClass.cast(builderWithoutPayloadNoOriginal);
        } else if (builderClass.isInstance(builderWithPayloadAndParent)) {
            return builderClass.cast(builderWithPayloadAndParent);
        } else if (builderClass.isInstance(builderForReactionCreation)) {
            return builderClass.cast(builderForReactionCreation);
        } else if (builderClass.isInstance(builderForRelationCreation)) {
            return builderClass.cast(builderForRelationCreation);
        }
        
        throw new IllegalStateException(
            "No builder of type " + builderClass.getSimpleName() + " found");
    }

    /**
     * Register a custom builder for a specific route ID
     */
    public void registerBuilder(String routeId, PolicyDataBuilder builder) {
        routeBuilderMap.put(routeId, builder);
        log.info("Registered custom builder for route: " + routeId);
    }
}
