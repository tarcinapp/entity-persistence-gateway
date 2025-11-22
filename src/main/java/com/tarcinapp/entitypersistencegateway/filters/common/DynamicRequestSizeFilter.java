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
 * Since Spring cannot resolve nested placeholders like ${app.requestSizes.kinds.${kindAlias}.create},
 * this filter:
 * 1. Extracts the kindAlias from the URI path variable at request time
 * 2. Looks up app.requestSizes.kinds.{kindAlias}.{operation} from Environment
 * 3. Falls back to the default maxSize if no kind-specific size is configured
 * 
 * Example:
 * - Request to /api/v1/entities/books (POST)
 * - Filter extracts kindAlias="books" 
 * - Looks for app.requestSizes.kinds.books.create
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
                        .filter(k -> kindAlias.equals(k.getAlias()))
                        .map(KindAliasPathSingleConfig::getName)
                        .findFirst()
                        .orElse(null);
            }

            DataSize effectiveMaxSize = config.getMaxSize();

            // Use kindName (not kindAlias) for property key
            if (kindName != null) {
                String operation = determineOperation(exchange);

                if (operation != null) {
                    String propertyKey = "app.requestSizes.kinds." + kindName + "." + operation;
                    String kindSpecificSize = environment.getProperty(propertyKey);

                    if (kindSpecificSize != null) {
                        try {
                            effectiveMaxSize = DataSize.parse(kindSpecificSize);
                            logger.debug("Using kind-specific request size for '" + kindName + "': " + effectiveMaxSize + " (from " + propertyKey + ")");
                        } catch (IllegalArgumentException e) {
                            logger.warn("Invalid DataSize format for " + propertyKey + ": " + kindSpecificSize + ". Using default: " + effectiveMaxSize);
                        }
                    } else {
                        logger.debug("No kind-specific size found for '" + kindName + "' operation '" + operation + "'. Using default: " + effectiveMaxSize);
                    }
                }
            } else {
                logger.debug("No kindName found for alias '" + kindAlias + "'. Using default size: " + effectiveMaxSize);
            }

            // Get content length from request headers
            long contentLength = exchange.getRequest().getHeaders().getContentLength();
            
            if (contentLength > effectiveMaxSize.toBytes()) {
                logger.warn("Request size " + DataSize.ofBytes(contentLength) + " exceeds maximum allowed size " + effectiveMaxSize + " for kind: " + kindAlias);
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
        String path = exchange.getRequest().getPath().value();
        
        if (method == HttpMethod.POST) {
            // If path contains /children, it's a createChild operation
            if (path.contains("/children")) {
                return "createChild";
            }
            return "create";
        } else if (method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            return "update";
        }
        
        return null;
    }

    public static class Config {
        private DataSize maxSize = DataSize.ofMegabytes(5); // 5MB fallback default

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }
    }
}
