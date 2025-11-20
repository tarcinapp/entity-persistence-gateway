package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import jakarta.annotation.PostConstruct;

/**
 * Registry service that manages and selects appropriate PolicyDataBuilder
 * implementations based on explicit route ID mapping.
 * 
 * Uses a switch-case-like mechanism for predictable, deterministic builder selection.
 */
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

    private final Map<String, PolicyDataBuilder> routeBuilderMap = new HashMap<>();

    private static final Logger logger = LogManager.getLogger(PolicyDataBuilderRegistry.class);

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
        
        logger.info("Initialized route mappings for " + routeBuilderMap.size() + " routes");
    }

    private void mapEntityRoutes() {
        // POST - with payload
        // POST /entities
        routeBuilderMap.put("createEntity", builderWithPayload);
        // POST /entities/{parentId}/children
        routeBuilderMap.put("createEntityChild", builderWithPayloadAndParent);
        // POST /entities/kind/{kindAlias}
        routeBuilderMap.put("createEntityByKindAlias", builderWithPayload);

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

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /lists/{id}
        routeBuilderMap.put("updateListById", builderWithPayloadAndOriginal);
        // PUT /lists/{id}
        routeBuilderMap.put("replaceListById", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /lists
        routeBuilderMap.put("updateAllLists", builderWithPayload);
        // PATCH /lists/{listId}/entities
        routeBuilderMap.put("updateEntitiesByListId", builderWithPayloadAndParent);

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
        routeBuilderMap.put("findEntitiesByListId", builderWithoutPayload);
        // GET /entities/{entityId}/lists
        routeBuilderMap.put("findListsByEntityId", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /lists/{id}
        routeBuilderMap.put("deleteListById", builderWithoutPayload);
        // DELETE /lists/{listId}/entities
        routeBuilderMap.put("deleteEntitiesByListId", builderWithoutPayload);
    }

    private void mapRelationRoutes() {
        // POST - with payload
        // POST /relations
        routeBuilderMap.put("createRelation", builderForRelationCreation);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /relations/{id}
        routeBuilderMap.put("updateRelationById", builderWithPayloadAndOriginal);
        // PUT /relations/{id}
        routeBuilderMap.put("replaceRelationById", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /relations
        routeBuilderMap.put("updateAllRelations", builderWithPayload);

        // GET - without payload
        // GET /relations
        routeBuilderMap.put("findRelations", builderWithoutPayloadNoOriginal);
        // GET /relations/count
        routeBuilderMap.put("countRelations", builderWithoutPayloadNoOriginal);
        // GET /relations/{id}
        routeBuilderMap.put("findRelationById", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /relations/{id}
        routeBuilderMap.put("deleteRelationById", builderWithoutPayload);
    }

    private void mapEntityReactionRoutes() {
        // POST - with payload
        // POST /entity-reactions
        routeBuilderMap.put("createEntityReaction", builderForReactionCreation);
        // POST /entity-reactions/{parentId}/children
        routeBuilderMap.put("createChildEntityReaction", builderWithPayloadAndParent);
        // POST /entities/{entityId}/reactions
        routeBuilderMap.put("createReactionByEntityId", builderWithPayloadAndParent);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /entity-reactions/{id}
        routeBuilderMap.put("updateEntityReactionById", builderWithPayloadAndOriginal);
        // PUT /entity-reactions/{id}
        routeBuilderMap.put("replaceEntityReactionById", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /entity-reactions
        routeBuilderMap.put("updateAllEntityReactions", builderWithPayload);
        // PATCH /entities/{entityId}/reactions
        routeBuilderMap.put("updateReactionsByEntityId", builderWithPayloadAndParent);

        // GET - without payload
        // GET /entity-reactions
        routeBuilderMap.put("findEntityReactions", builderWithoutPayloadNoOriginal);
        // GET /entity-reactions/count
        routeBuilderMap.put("countEntityReactions", builderWithoutPayloadNoOriginal);
        // GET /entity-reactions/{id}
        routeBuilderMap.put("findEntityReactionById", builderWithoutPayload);
        // GET /entity-reactions/{reactionId}/children
        routeBuilderMap.put("findChildrenEntityReactionsByReactionId", builderWithoutPayload);
        // GET /entity-reactions/{id}/parents
        routeBuilderMap.put("findParentsByEntityReactionId", builderWithoutPayload);
        // GET /entities/{entityId}/reactions
        routeBuilderMap.put("findReactionsByEntityId", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /entity-reactions/{id}
        routeBuilderMap.put("deleteEntityReactionById", builderWithoutPayload);
        // DELETE /entities/{entityId}/reactions
        routeBuilderMap.put("deleteReactionsByEntityId", builderWithoutPayload);
    }

    private void mapListReactionRoutes() {
        // POST - with payload
        // POST /list-reactions
        routeBuilderMap.put("createListReaction", builderForReactionCreation);
        // POST /list-reactions/{parentId}/children
        routeBuilderMap.put("createChildListReaction", builderWithPayloadAndParent);
        // POST /lists/{listId}/reactions
        routeBuilderMap.put("createReactionByListId", builderWithPayloadAndParent);

        // PATCH/PUT - with payload (lock is handled by separate AcquireLockForUpdate filter)
        // PATCH /list-reactions/{id}
        routeBuilderMap.put("updateListReactionById", builderWithPayloadAndOriginal);
        // PUT /list-reactions/{id}
        routeBuilderMap.put("replaceListReactionById", builderWithPayloadAndOriginal);

        // PATCH - with payload (bulk update)
        // PATCH /list-reactions
        routeBuilderMap.put("updateAllListReactions", builderWithPayload);
        // PATCH /lists/{listId}/reactions
        routeBuilderMap.put("updateReactionsByListId", builderWithPayloadAndParent);

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
        routeBuilderMap.put("findReactionsByListId", builderWithoutPayload);

        // DELETE - without payload
        // DELETE /list-reactions/{id}
        routeBuilderMap.put("deleteListReactionById", builderWithoutPayload);
        // DELETE /lists/{listId}/reactions
        routeBuilderMap.put("deleteReactionsByListId", builderWithoutPayload);
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
        
        logger.debug("Route: " + routeId + " -> Builder: " + builder.getClass().getSimpleName());
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
        logger.info("Registered custom builder for route: " + routeId);
    }
}
