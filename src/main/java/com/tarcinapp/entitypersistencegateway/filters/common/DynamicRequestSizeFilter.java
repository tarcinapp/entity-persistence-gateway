package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig;
import com.tarcinapp.entitypersistencegateway.config.KindAliasPathsConfig.KindAliasPathSingleConfig;

/**
 * Dynamic request size filter that resolves kind-specific sizes at runtime.
 * 
 * Since Spring cannot resolve nested placeholders like ${app.request-sizes.kinds.${kindAlias}.create},
 * this filter:
 * 1. Extracts the kindAlias from the URI path variable at request time
 * 2. Looks up app.request-sizes.kinds.{kindAlias}.{operation} from Environment
 * 3. Falls back to the default maxSize if no kind-specific size is configured
 * 
 * Example:
 * - Request to /api/v1/entities/books (POST)
 * - Filter extracts kindAlias="books" and takes the record type from config (e.g. "entities, lists, etc.)")
 * - Looks for app.request-sizes.entities.kinds.books.create
 * - If found, uses that size; otherwise uses config.maxSize (default)
 */
@Component
public class DynamicRequestSizeFilter extends AbstractGatewayFilterFactory<DynamicRequestSizeFilter.Config> {

    private Logger logger = LogManager.getLogger(DynamicRequestSizeFilter.class);

    @Autowired
    private Environment environment;

    @Autowired
    private KindAliasPathsConfig kindAliasPathsConfig;

    public DynamicRequestSizeFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            HttpMethod method = exchange.getRequest().getMethod();
            String recordType = config.getRecordType();

             if (recordType == null || recordType.isEmpty()) {
                logger.error("DynamicRequestSizeFilter filter requires recordType to be set in config.");

                exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return exchange.getResponse().setComplete();
            }
            
            // Only check size for methods with request bodies
            if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.PATCH) {
                return chain.filter(exchange);
            }

            // Get the kindAlias from URI path variables
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindAlias = uriVariables.get("kindAlias");

            // Map kindAlias to kind name
            String kindName = null;
            if (kindAlias != null && kindAliasPathsConfig != null) {
                kindName = kindAliasPathsConfig.getKindAliasPaths().stream()
                        .filter(singleKindAliasConfig -> singleKindAliasConfig.getAlias() != null
                                && singleKindAliasConfig.getRecordType() != null
                                && singleKindAliasConfig.getAlias().equals(kindAlias)
                                && (singleKindAliasConfig.getRecordType().equals(recordType)))
                        .map(KindAliasPathSingleConfig::getName)
                        .findFirst()
                        .orElse(null);
            }

            DataSize effectiveMaxSize = config.getMaxSize();

            // Use kindName for property key
            if (kindName != null) {
                String operation = determineOperation(exchange);

                if (operation != null) {
                    String propertyKeyKind = "app.request-sizes." + recordType + ".kinds." + kindName + "." + operation;
                    String propertyKeyRecordType = "app.request-sizes." + recordType + "." + operation;
                    String propertyKeyDefault = "app.request-sizes.default." + operation;

                    String kindSpecificSize = environment.getProperty(propertyKeyKind);
                    if (kindSpecificSize != null) {
                        try {
                            effectiveMaxSize = DataSize.parse(kindSpecificSize);
                            logger.debug("Using kind-specific request size for '" + kindName + "': " + effectiveMaxSize + " (from " + propertyKeyKind + ")");
                        } catch (IllegalArgumentException e) {
                            logger.warn("Invalid DataSize format for " + propertyKeyKind + ": " + kindSpecificSize + ". Using fallback.");
                        }
                    } else {
                        String recordTypeSize = environment.getProperty(propertyKeyRecordType);
                        if (recordTypeSize != null) {
                            try {
                                effectiveMaxSize = DataSize.parse(recordTypeSize);
                                logger.debug("Using recordType request size for '" + recordType + "': " + effectiveMaxSize + " (from " + propertyKeyRecordType + ")");
                            } catch (IllegalArgumentException e) {
                                logger.warn("Invalid DataSize format for " + propertyKeyRecordType + ": " + recordTypeSize + ". Using fallback.");
                            }
                        } else {
                            String defaultSize = environment.getProperty(propertyKeyDefault);
                            if (defaultSize != null) {
                                try {
                                    effectiveMaxSize = DataSize.parse(defaultSize);
                                    logger.debug("Using default request size: " + effectiveMaxSize + " (from " + propertyKeyDefault + ")");
                                } catch (IllegalArgumentException e) {
                                    logger.warn("Invalid DataSize format for " + propertyKeyDefault + ": " + defaultSize + ". Using config default: " + effectiveMaxSize);
                                }
                            } else {
                                logger.debug("No size property found for kind, recordType, or default. Using config default: " + effectiveMaxSize);
                            }
                        }
                    }
                }
            } else {
                // No kindName found, fallback to recordType and default
                String operation = determineOperation(exchange);
                if (operation != null) {
                    String propertyKeyRecordType = "app.request-sizes." + recordType + "." + operation;
                    String propertyKeyDefault = "app.request-sizes.default." + operation;

                    String recordTypeSize = environment.getProperty(propertyKeyRecordType);
                    if (recordTypeSize != null) {
                        try {
                            effectiveMaxSize = DataSize.parse(recordTypeSize);
                            logger.debug("Using recordType request size for '" + recordType + "': " + effectiveMaxSize + " (from " + propertyKeyRecordType + ")");
                        } catch (IllegalArgumentException e) {
                            logger.warn("Invalid DataSize format for " + propertyKeyRecordType + ": " + recordTypeSize + ". Using fallback.");
                        }
                    } else {
                        String defaultSize = environment.getProperty(propertyKeyDefault);
                        if (defaultSize != null) {
                            try {
                                effectiveMaxSize = DataSize.parse(defaultSize);
                                logger.debug("Using default request size: " + effectiveMaxSize + " (from " + propertyKeyDefault + ")");
                            } catch (IllegalArgumentException e) {
                                logger.warn("Invalid DataSize format for " + propertyKeyDefault + ": " + defaultSize + ". Using config default: " + effectiveMaxSize);
                            }
                        } else {
                            logger.debug("No size property found for recordType or default. Using config default: " + effectiveMaxSize);
                        }
                    }
                }
            }

            // Get content length from request headers
            long contentLength = exchange.getRequest().getHeaders().getContentLength();
            
            if (contentLength > effectiveMaxSize.toBytes()) {
                logger.warn("Request size " + DataSize.ofBytes(contentLength) + " exceeds maximum allowed size " + effectiveMaxSize + " for recordType '" + recordType + "' value: " + kindAlias);
                exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                return exchange.getResponse().setComplete();
            }

            logger.debug("Request size check passed. Size: " + DataSize.ofBytes(contentLength) + ", Max: " + effectiveMaxSize);
            return chain.filter(exchange);
        };
    }

    /**
     * Determines the operation type based on HTTP method and path.
     * Returns: "create", "update", or "createChild"
     */
    private String determineOperation(org.springframework.web.server.ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        
        if (method == HttpMethod.POST) {
            // If path contains /children, it's a create operation too
            return "create";
        } else if (method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            return "update";
        }
        
        return null;
    }

    public static class Config {
        private DataSize maxSize = DataSize.ofMegabytes(5); // 5MB fallback default
        private String recordType;

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }

        public String getRecordType() {
            return recordType;
        }

        public void setRecordType(String recordType) {
            this.recordType = recordType;
        }
    }
}