package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Global filter that initializes MDC context with Spring's request ID at the very beginning
 * of the request processing pipeline.
 * 
 * <p>This filter runs with the highest priority (lowest order) to ensure that all subsequent
 * filters and log statements have access to a request identifier, even before authentication
 * takes place.</p>
 * 
 * <p>The request ID used here is Spring WebFlux's built-in request ID (from ServerHttpRequest),
 * which is available from the very start of request processing. This ID will later be
 * supplemented by a more meaningful custom request ID (including the authenticated party info)
 * by the {@link GenerateRequestId} filter.</p>
 * 
 * <p>Two MDC keys are maintained:</p>
 * <ul>
 *   <li>{@code SpringRequestId} - Spring's built-in request ID (always available)</li>
 *   <li>{@code RequestId} - Custom request ID (shows "NO_REQUEST" until GenerateRequestId runs)</li>
 * </ul>
 * 
 * <p>For logs emitted outside of any request context (e.g., during application startup
 * or scheduled tasks), the MDC will contain "NO_REQUEST" for both keys.</p>
 */
@Slf4j
@Component
public class InitializeMdcContextFilter implements GlobalFilter, Ordered {

    /**
     * Attribute key used to store the initial (Spring) request ID in the exchange.
     * This allows other filters to access the original request ID if needed.
     */
    public static final String INITIAL_REQUEST_ID_ATTR = "InitialRequestId";

    @Override
    public int getOrder() {
        // Run as early as possible, before any other filter
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get Spring's built-in request ID
        String springRequestId = exchange.getRequest().getId();
        
        // Store the initial request ID in exchange attributes for later reference
        exchange.getAttributes().put(INITIAL_REQUEST_ID_ATTR, springRequestId);
        
        // Set MDC in exchange attributes and current thread
        // RequestId will be "NO_REQUEST" until GenerateRequestId filter runs
        MdcContextLifterConfiguration.setMdcInExchange(exchange, springRequestId, 
                MdcContextLifterConfiguration.NO_REQUEST_INDICATOR);
        
        log.debug("MDC initialized with Spring request ID: {}", springRequestId);

        // Create MDC context map to be propagated through reactive streams
        Map<String, String> mdcContext = new HashMap<>();
        mdcContext.put(MdcContextLifterConfiguration.SPRING_REQUEST_ID_MDC_KEY, springRequestId);
        mdcContext.put(MdcContextLifterConfiguration.REQUEST_ID_MDC_KEY, 
                MdcContextLifterConfiguration.NO_REQUEST_INDICATOR);

        // Continue the filter chain with MDC context in Reactor context
        return chain.filter(exchange)
                .contextWrite(ctx -> MdcContextLifterConfiguration.putMdcContext(ctx, mdcContext))
                .doFinally(signalType -> {
                    // Clean up MDC when request completes
                    MDC.clear();
                });
    }
}
