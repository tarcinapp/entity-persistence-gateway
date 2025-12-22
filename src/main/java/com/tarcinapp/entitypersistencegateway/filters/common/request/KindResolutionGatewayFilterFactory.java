package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;
import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class KindResolutionGatewayFilterFactory
        extends AbstractGatewayFilterFactory<KindResolutionGatewayFilterFactory.Config> {

    @Autowired(required = false)
    private OpenApiProperties openApiProperties;

    public KindResolutionGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Restore MDC from exchange attributes for proper logging
            MdcContextLifterConfiguration.restoreMdcFromExchange(exchange);

            // Initialize the attribute object with default values (configured = false)
            KindAliasConfigAttr kindAliasConfigAttr = new KindAliasConfigAttr();
            kindAliasConfigAttr.setKindAliasConfigured(false);

            // Put it into attributes immediately so downstream filters can always find it
            exchange.getAttributes().put(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, kindAliasConfigAttr);

            // 1. Extract Alias and Record ID from URL
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindAlias = uriVariables.get("kindAlias");
            String recordId = uriVariables.get("recordId");

            // Resolve controllerName and baseControllerName from route metadata
            String controllerName = null;
            String baseControllerName = null;
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route != null && route.getMetadata() != null) {
                Object controllerNameMeta = route.getMetadata().get("controllerName");
                if (controllerNameMeta != null) {
                    controllerName = controllerNameMeta.toString();
                }
                Object baseControllerNameMeta = route.getMetadata().get("baseControllerName");
                if (baseControllerNameMeta != null) {
                    baseControllerName = baseControllerNameMeta.toString();
                }
            }

            // Prefer explicit baseControllerName; fall back to controllerName
            String lookupControllerName = (baseControllerName == null || baseControllerName.isBlank())
                    ? controllerName
                    : baseControllerName;

            // 2. Perform Lookup if Alias exists and Config is loaded
            if (kindAlias != null && openApiProperties != null) {

                if (lookupControllerName == null || lookupControllerName.isBlank()) {
                    log.error("Kind Resolution: controllerName is missing in route metadata for alias '{}'.", kindAlias);
                    exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    return Mono.empty();
                }

                OpenApiProperties.AliasContext aliasContext = openApiProperties
                        .getAliasContext(lookupControllerName, kindAlias);

                if (aliasContext != null && aliasContext.getAliasConfig() != null) {
                    String kindName = aliasContext.getAliasConfig().getKind();

                    // Prefer controller name from config; fall back to route metadata if absent
                    String resolvedControllerName = aliasContext.getControllerName();
                    if (resolvedControllerName == null || resolvedControllerName.isBlank()) {
                        resolvedControllerName = lookupControllerName;
                    }

                    // Resolve record type from filter config or metadata (still needed for other filters)
                    String recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "KindResolution");

                    // Populate the attribute object
                    kindAliasConfigAttr.setKindAliasConfigured(true);
                    kindAliasConfigAttr.setKindAlias(kindAlias);
                    kindAliasConfigAttr.setKindName(kindName);
                    kindAliasConfigAttr.setControllerName(resolvedControllerName);
                    kindAliasConfigAttr.setBaseControllerName(lookupControllerName);
                    kindAliasConfigAttr.setRecordType(recordType);

                    // Construct Original Resource URL if recordType and recordId are present
                    if (recordId != null && recordType != null) {
                        String originalResourceUrl = "/" + recordType + "/" + recordId;
                        kindAliasConfigAttr.setOriginalResourceUrl(originalResourceUrl);
                    }

                        log.debug("Kind Resolution: Alias '{}' resolved to Kind '{}' under controller '{}' (recordType: {}). Original URL: {}",
                            kindAlias, kindName, resolvedControllerName, recordType, kindAliasConfigAttr.getOriginalResourceUrl());
                } else {
                    log.debug("Kind Resolution: Alias '{}' could not be resolved to any kind.", kindAlias);
                    log.debug("Exiting route with 404.");

                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return Mono.empty();
                }
            }

            return chain.filter(exchange);
        };
    }

    @Data
    public static class Config {
        private String recordType;
    }
}