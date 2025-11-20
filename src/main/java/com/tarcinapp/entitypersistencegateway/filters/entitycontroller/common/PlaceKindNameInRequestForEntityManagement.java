package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.common;

import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig.EntityKindsSingleConfig;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;
import reactor.core.publisher.Mono;

/*
 * This filter is used to place kind name extracted from the path to the request payload for create, update and patch entity requests.
 * 
 * Takes kind alias from URI and checks if it is configured as an entity kind.
 * If it is configured as an entity kind, it places kind name to the request payload as kind: "kindName".
 * 
 * Original entity's URL is placed to the request payload as originalUrl: "originalUrl". Because the original URL is needed for the authorization logic.
 */
@Component
public class PlaceKindNameInRequestForEntityManagement
        extends
        AbstractRequestPayloadModifierFilterFactory<PlaceKindNameInRequestForEntityManagement.Config, String, String> {

    @Autowired
    private EntityKindsConfig entityKindsConfig;
    
    @Value("${app.inbound.controllerPaths.entities:entities}")
    private String entitiesControllerPath;
    
    private Logger logger = LogManager.getLogger(PlaceKindNameInRequestForEntityManagement.class);

    public PlaceKindNameInRequestForEntityManagement() {
        super(Config.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String payload) {

        logger.debug("PlaceKindNameInRequestForEntityManagement filter is started.");

        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String kindAlias = uriVariables.get("kindAlias");
        String recordId = uriVariables.get("recordId");
        
        logger.debug("Caller sent POST, PUT or PATCH kind alias '" + kindAlias + "'. Checking if " + kindAlias
            + " is configured as an entity kind.");

        EntityKindsSingleConfig foundEntityKindConfig = entityKindsConfig.getEntityKinds().stream()
            .filter(entityKind -> Optional.ofNullable(entityKind.getAlias())
                    .equals(Optional.ofNullable(kindAlias)))
            .findFirst()
            .orElse(null);

        if (foundEntityKindConfig == null) {
            logger.debug("There is no kind alias configuration found for path /" + kindAlias);
            logger.debug("Exiting PlaceKindNameInRequestForEntityManagement filter with 404.");

            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return Mono.empty();
        }

        logger.debug("/" + kindAlias + " is configured to entity kind: '" + foundEntityKindConfig.getName() + "'.");

        /*
         * Place original resource URL to the request payload as originalUrl: "originalUrl".
         * Because the original URL is needed for the authorization logic.
         */
        KindAliasConfigAttr kindAliasConfigAttr = new KindAliasConfigAttr();
        kindAliasConfigAttr.setKindAliasConfigured(true);
        kindAliasConfigAttr.setKindName(foundEntityKindConfig.getName());
        kindAliasConfigAttr.setOriginalResourceUrl("/" + entitiesControllerPath + "/" + recordId);

        // Place kindAliasConfigAttr to the request attributes.
        exchange.getAttributes().put("KindAliasConfigAttr", kindAliasConfigAttr);

        /*
         * Place kind name to the request payload as kind: "kindName".
         */
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });

            payloadMap.put("kind", foundEntityKindConfig.getName());
            logger.debug("Kind name '" + foundEntityKindConfig.getName() + "' is placed to the request payload.");


            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(payloadMap);

            return Mono.just(outboundJsonRequestStr);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    public static class Config {
    }

}
