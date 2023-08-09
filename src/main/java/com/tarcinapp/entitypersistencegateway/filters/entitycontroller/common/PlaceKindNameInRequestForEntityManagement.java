package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.common;

import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig.EntityKindsSingleConfig;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;
import reactor.core.publisher.Mono;

/*
 * This filter is used to place kind name extracted from the path to the request payload for create, update and patch entity requests.
 * 
 * Takes kindPath from URI and checks if it is configured as an entity kind.
 * If it is configured as an entity kind, it places kind name to the request payload as kind: "kindName".
 * 
 */
@Component
public class PlaceKindNameInRequestForEntityManagement
        extends
        AbstractRequestPayloadModifierFilterFactory<PlaceKindNameInRequestForEntityManagement.Config, String, String> {

    @Autowired
    private EntityKindsConfig entityKindsConfig;
    private Logger logger = LogManager.getLogger(PlaceKindNameInRequestForEntityManagement.class);

    public PlaceKindNameInRequestForEntityManagement() {
        super(Config.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String payload) {
        logger.debug("PlaceKindNameInRequestForEntityManagement filter is started.");

        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String kindPath = uriVariables.get("kindPath");
        

        logger.debug("Caller sent POST, PUT or PATCH kindPath '" + kindPath + "'. Checking if " + kindPath
            + " is configured as an entity kind.");

        EntityKindsSingleConfig foundEntityKindConfig = entityKindsConfig.getEntityKinds().stream()
            .filter(entityKind -> Optional.ofNullable(entityKind.getPathMap())
                    .equals(Optional.ofNullable(kindPath)))
            .findFirst()
            .orElse(null);

        if (foundEntityKindConfig == null) {
            logger.debug("There is no kindPath configuration found for path /" + kindPath);
            logger.debug("Exiting PlaceKindNameInRequestForEntityManagement filter with 404.");

            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return Mono.empty();
        }

        logger.debug("/" + kindPath + " is configured to entity kind: '" + foundEntityKindConfig.getName() + "'.");
    


        /*
         * Place kind name to the request payload as kind: "kindName".
         */
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });

            payloadMap.put("kind", foundEntityKindConfig.getName());

            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(payloadMap);

            return Mono.just(outboundJsonRequestStr);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    public static class Config {
    }

}
