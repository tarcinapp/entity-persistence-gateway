package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.util.HashMap;
import java.util.Map;

import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import com.tarcinapp.entitypersistencegateway.services.RequestIdService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Gateway filter factory that generates a meaningful request ID after authentication.
 */
@Component
@Slf4j
public class GenerateRequestId extends AbstractGatewayFilterFactory<GenerateRequestId.Config> {

    @Value("${app.requestId}")
    private String requestIdHeader;

    @Autowired
    private RequestIdService requestIdService;

    public static final String REQUEST_ID_ATTR = "RequestId";

    public GenerateRequestId() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            
            // Get the Spring request ID for correlation log
            String springRequestId = exchange.getAttribute(InitializeMdcContextFilter.INITIAL_REQUEST_ID_ATTR);
            if (springRequestId == null) {
                springRequestId = exchange.getRequest().getId();
            }

            // 1. Generate the new ID using the central service
            String newRequestId = requestIdService.generateNewId(exchange);

            // 2. Add to outgoing request header
            ServerHttpRequest request = exchange.getRequest()
                    .mutate()
                    .header(requestIdHeader, newRequestId)
                    .build();

            // 3. Store in exchange attributes
            exchange.getAttributes().put(REQUEST_ID_ATTR, newRequestId);

            // 4. Update MDC Context
            MdcContextLifterConfiguration.updateRequestIdInExchange(exchange, newRequestId);

            // Log transition
            log.info("Request ID generated: {} (Spring ID: {})", newRequestId, springRequestId);

            // Prepare MDC map for reactive propagation
            Map<String, String> mdcContext = new HashMap<>();
            mdcContext.put(MdcContextLifterConfiguration.SPRING_REQUEST_ID_MDC_KEY, springRequestId);
            mdcContext.put(MdcContextLifterConfiguration.REQUEST_ID_MDC_KEY, newRequestId);

            return chain.filter(exchange.mutate().request(request).build())
                    .contextWrite(ctx -> MdcContextLifterConfiguration.putMdcContext(ctx, mdcContext));
        };
    }

    public static class Config {
    }
}