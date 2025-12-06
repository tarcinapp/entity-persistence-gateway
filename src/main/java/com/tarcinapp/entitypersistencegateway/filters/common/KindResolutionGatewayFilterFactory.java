package com.tarcinapp.entitypersistencegateway.filters.common;

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
        // Order: -100
        // This filter must run before other business logic filters (Cache, Timeout,
        // Auth).
        // This ensures others can use the pre-resolved kind configuration.
        return new OrderedGatewayFilter((exchange, chain) -> {
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

            // 2. Perform Lookup if Alias exists and Config is loaded
            if (kindAlias != null && openApiProperties != null) {

                OpenApiProperties.AliasContext aliasContext = openApiProperties.getAliasContext(kindAlias);

                if (aliasContext != null && aliasContext.getAliasConfig() != null) {
                    String kindName = aliasContext.getAliasConfig().getKind();

                    // Prefer controller name from config; fall back to route metadata if absent
                    String recordType = aliasContext.getControllerName();
                    if (recordType == null || recordType.isBlank()) {
                        recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "KindResolution");
                    }

                    // Populate the attribute object
                    kindAliasConfigAttr.setKindAliasConfigured(true);
                    kindAliasConfigAttr.setKindName(kindName);
                    kindAliasConfigAttr.setRecordType(recordType);

                    // Construct Original Resource URL if recordType and recordId are present
                    if (recordId != null && recordType != null) {
                        String originalResourceUrl = "/" + recordType + "/" + recordId;
                        kindAliasConfigAttr.setOriginalResourceUrl(originalResourceUrl);
                    }

                    log.debug("Kind Resolution: Alias '{}' resolved to Kind '{}'. Original URL: {}",
                            kindAlias, kindName, kindAliasConfigAttr.getOriginalResourceUrl());
                } else {
                    log.debug("Kind Resolution: Alias '{}' could not be resolved to any kind.", kindAlias);
                    log.debug("Exiting route with 404.");

                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return Mono.empty();
                }
            }

            return chain.filter(exchange);

        }, -100);
    }

    @Data
    public static class Config {
        private String recordType;
    }
}