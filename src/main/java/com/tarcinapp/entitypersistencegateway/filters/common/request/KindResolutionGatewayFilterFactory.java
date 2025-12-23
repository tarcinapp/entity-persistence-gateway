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
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * KindResolutionGatewayFilterFactory resolves business 'kind' and 'recordType' 
 * from the URL alias and route metadata.
 */
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

            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindAlias = uriVariables.get("kindAlias");
            String recordId = uriVariables.get("recordId");

            // Extract metadata from route
            String controllerName = null;
            String baseControllerName = null;
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            
            if (route != null && route.getMetadata() != null) {
                Object cn = route.getMetadata().get("controllerName");
                if (cn != null) controllerName = cn.toString();
                
                Object bcn = route.getMetadata().get("baseControllerName");
                if (bcn != null) baseControllerName = bcn.toString();
            }

            // Determine lookup context
            String lookupControllerName = (baseControllerName == null || baseControllerName.isBlank())
                    ? controllerName
                    : baseControllerName;

            if (kindAlias != null && openApiProperties != null) {

                if (lookupControllerName == null || lookupControllerName.isBlank()) {
                    log.error("Kind Resolution: controllerName missing for alias '{}'.", kindAlias);
                    exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    return Mono.empty();
                }

                OpenApiProperties.AliasContext aliasContext = openApiProperties.getAliasContext(lookupControllerName, kindAlias);

                if (aliasContext != null && aliasContext.getAliasConfig() != null) {
                    OpenApiProperties.AliasConfig aliasConfig = aliasContext.getAliasConfig();
                    String kindName = aliasConfig.getKind();
                    String resolvedControllerName = aliasContext.getControllerName();

                    if (resolvedControllerName == null || resolvedControllerName.isBlank()) {
                        resolvedControllerName = lookupControllerName;
                    }

                    // Resolve recordType (logical name like 'entityReactions')
                    String recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "KindResolution");

                    // Populate KindAliasConfigAttr
                    kindAliasConfigAttr.setKindAliasConfigured(true);
                    kindAliasConfigAttr.setKindAlias(kindAlias);
                    kindAliasConfigAttr.setKindName(kindName);
                    kindAliasConfigAttr.setControllerName(resolvedControllerName);
                    kindAliasConfigAttr.setBaseControllerName(lookupControllerName);
                    kindAliasConfigAttr.setRecordType(recordType);

                    // Construct Original Resource URL using TECHNICAL path segment
                    if (recordId != null && recordType != null) {
                        String technicalPath = resolveTechnicalPath(recordType);
                        String originalResourceUrl = "/" + technicalPath + "/" + recordId;
                        kindAliasConfigAttr.setOriginalResourceUrl(originalResourceUrl);
                    }

                    // Validation flag for downstream
                    boolean isValidationEnabled = aliasConfig.getValidationEnabled() == null || aliasConfig.getValidationEnabled();
                    exchange.getAttributes().put("isValidationEnabled", isValidationEnabled);

                    log.debug("Resolved KindAlias: recordType={}, kind={}, technicalPath={}, originalUrl={}", 
                        recordType, kindName, resolveTechnicalPath(recordType), kindAliasConfigAttr.getOriginalResourceUrl());

                } else {
                    log.warn("Kind alias '{}' not resolved in controller context '{}'", kindAlias, lookupControllerName);
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return Mono.empty();
                }
            }

            return chain.filter(exchange);
        };
    }

    /**
     * Maps the logical recordType (e.g., 'entityReactions') to the 
     * technical URL segment (e.g., 'entity-reactions').
     */
    private String resolveTechnicalPath(String recordType) {
        if (recordType == null) return null;

        switch (recordType) {
            case "entityReactions":
                return "entity-reactions";
            case "listReactions":
                return "list-reactions";
            default:
                return recordType;
        }
    }

    @Data
    public static class Config {
        private String recordType;
    }
}