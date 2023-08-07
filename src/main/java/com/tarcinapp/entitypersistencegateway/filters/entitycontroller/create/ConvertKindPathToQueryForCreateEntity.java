package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.create;

import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties.Web.Server;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig;
import com.tarcinapp.entitypersistencegateway.config.EntityKindsConfig.EntityKindsSingleConfig;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractRequestPayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.common.AddManagedFieldsInCreation;

import reactor.core.publisher.Mono;

@Component
public class ConvertKindPathToQueryForCreateEntity
        extends
        AbstractRequestPayloadModifierFilterFactory<ConvertKindPathToQueryForCreateEntity.Config, String, String> {

    @Autowired
    private EntityKindsConfig entityKindsConfig;
    private Logger logger = LogManager.getLogger(ConvertKindPathToQueryForCreateEntity.class);

    public ConvertKindPathToQueryForCreateEntity() {
        super(Config.class, String.class, String.class);
    }

    @Override
    public Mono<String> modifyRequestPayload(Config config, ServerWebExchange exchange, String payload) {
        logger.debug("ConvertKindPathToQueryForCreateEntity filter is started.");

        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindPath = uriVariables.get("kindPath");

            logger.debug("Caller sent POST kindPath '" + kindPath + "'. Checking if " + kindPath
                    + " is configured as an entity kind.");

            EntityKindsSingleConfig foundEntityKindConfig = entityKindsConfig.getEntityKinds().stream()
                    .filter(entityKind -> Optional.ofNullable(entityKind.getPathMap())
                            .equals(Optional.ofNullable(kindPath)))
                    .findFirst()
                    .orElse(null);

            if (foundEntityKindConfig == null) {
                logger.debug("There is no kindPath configuration found for path /" + kindPath);
                logger.debug("Exiting ConvertKindPathToQueryForCreateEntity filter with 404.");

                // TODO: return 404
            }

            logger.debug("/" + kindPath + " is configured to entity kind: '" + foundEntityKindConfig.getName() + "'.");

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
