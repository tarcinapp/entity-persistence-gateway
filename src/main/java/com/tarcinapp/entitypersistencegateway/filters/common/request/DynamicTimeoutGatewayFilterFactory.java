package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import com.tarcinapp.entitypersistencegateway.helpers.RecordTypeResolver;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;

@Component
@Slf4j
public class DynamicTimeoutGatewayFilterFactory extends AbstractGatewayFilterFactory<DynamicTimeoutGatewayFilterFactory.Config> {

    // Spring Cloud Gateway uses these specific string keys to look up timeout overrides in exchange attributes.
    // We define them manually to avoid IDE indexing issues and ensure compatibility.
    public static final String CONNECT_TIMEOUT_ATTR = "connect_timeout";
    public static final String RESPONSE_TIMEOUT_ATTR = "response_timeout";

    @Autowired
    private Environment environment;

    public DynamicTimeoutGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        // This filter must run before NettyRoutingFilter (Order: Integer.MAX_VALUE).
        // -1 is a safe place to ensure attributes are set before routing logic kicks in.
        return new OrderedGatewayFilter((exchange, chain) -> {
            // Restore MDC from exchange attributes for proper logging
            MdcContextLifterConfiguration.restoreMdcFromExchange(exchange);

            // 1. Resolve recordType with hierarchical fallback
            String recordType = RecordTypeResolver.resolve(config.getRecordType(), exchange, "DynamicTimeout");

            // Default values from route arguments (YAML)
            Integer defaultConnectTimeout = config.getConnectTimeoutMs();
            Integer defaultResponseTimeout = config.getResponseTimeoutMs();

            // 2. Kind Alias Resolution (from Attributes)
            String kindName = null;
            KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);
            
            if (kindAliasConfigAttr != null && kindAliasConfigAttr.isKindAliasConfigured()) {
                kindName = kindAliasConfigAttr.getKindName();
            }

            String operation = resolveOperationFromRoute(exchange);

            Integer finalConnectTimeout = defaultConnectTimeout;
            Integer finalResponseTimeout = defaultResponseTimeout;

            // 3. Dynamic Override Logic
            if (kindName != null && recordType != null) {
                // Key format: app.timeouts.<recordType>.kinds.<kindName>...
                String kindBaseKey = "app.timeouts." + recordType + ".kinds." + kindName;
                
                // Specific Operation Override
                String opConnectKey = kindBaseKey + "." + operation + ".connectTimeoutMs";
                String opResponseKey = kindBaseKey + "." + operation + ".responseTimeoutMs";

                // Default Kind Override
                String defConnectKey = kindBaseKey + ".default.connectTimeoutMs";
                String defResponseKey = kindBaseKey + ".default.responseTimeoutMs";

                // Resolve Connect Timeout
                Integer opConnectVal = getIntProperty(opConnectKey);
                if (opConnectVal != null) {
                    finalConnectTimeout = opConnectVal;
                } else {
                    Integer defConnectVal = getIntProperty(defConnectKey);
                    if (defConnectVal != null) {
                        finalConnectTimeout = defConnectVal;
                    }
                }

                // Resolve Response Timeout
                Integer opResponseVal = getIntProperty(opResponseKey);
                if (opResponseVal != null) {
                    finalResponseTimeout = opResponseVal;
                } else {
                    Integer defResponseVal = getIntProperty(defResponseKey);
                    if (defResponseVal != null) {
                        finalResponseTimeout = defResponseVal;
                    }
                }
            }

            // 4. Apply Timeouts to Exchange Attributes
            if (finalConnectTimeout != null) {
                // Use local constant
                exchange.getAttributes().put(CONNECT_TIMEOUT_ATTR, finalConnectTimeout);
                log.debug("Applied Connect Timeout: {} ms for route: {}", finalConnectTimeout, operation);
            }

            if (finalResponseTimeout != null) {
                // Use local constant
                exchange.getAttributes().put(RESPONSE_TIMEOUT_ATTR, Duration.ofMillis(finalResponseTimeout));
                log.debug("Applied Response Timeout: {} ms for route: {}", finalResponseTimeout, operation);
            }

            return chain.filter(exchange);

        }, -1);
    }

    // --- Helper Methods ---

    private String resolveOperationFromRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return (route != null) ? route.getId() : "unknown";
    }

    private Integer getIntProperty(String key) {
        try {
            return environment.getProperty(key, Integer.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    public static class Config {
        private String recordType;
        private Integer connectTimeoutMs;
        private Integer responseTimeoutMs;
    }
}