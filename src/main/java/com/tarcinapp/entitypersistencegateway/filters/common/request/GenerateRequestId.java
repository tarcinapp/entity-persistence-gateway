package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.config.MdcContextLifterConfiguration;

import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;

/**
 * Gateway filter factory that generates a meaningful request ID after authentication.
 * 
 * <p>This filter should be placed in route configurations AFTER the AuthenticateRequest filter
 * to ensure the authenticated party (azp) information is available for inclusion in the
 * request ID.</p>
 * 
 * <p>The generated request ID format is: {PREFIX}-{AZP}-{TIMESTAMP}-{RANDOM}</p>
 * <ul>
 *   <li>PREFIX: Application shortcode (configured via app.shortcode)</li>
 *   <li>AZP: Authorized party from JWT token (or "UNKNOWN" if not authenticated)</li>
 *   <li>TIMESTAMP: Current timestamp in yyyyMMddHHmmssSSS format</li>
 *   <li>RANDOM: 5-character alphanumeric random string</li>
 * </ul>
 * 
 * <p>This filter also logs the transition from the initial (Spring) request ID to the
 * newly generated request ID at INFO level, allowing log readers to correlate early
 * request logs (before auth) with later logs (after auth).</p>
 * 
 * <p>The request ID is:</p>
 * <ul>
 *   <li>Added as a header to the request (configurable via app.requestId)</li>
 *   <li>Stored in exchange attributes under key "RequestId"</li>
 *   <li>Set in the MDC context for logging correlation</li>
 *   <li>Propagated through reactive streams via Reactor context and exchange attributes</li>
 * </ul>
 */
@Component
@Slf4j
public class GenerateRequestId extends AbstractGatewayFilterFactory<GenerateRequestId.Config> {

    @Value("${app.requestId}")
    private String requestIdHeader;

    @Value("${app.shortcode}")
    private String requestIdPrefix;

    /**
     * Exchange attribute key for storing the request ID.
     */
    public static final String REQUEST_ID_ATTR = "RequestId";

    public GenerateRequestId() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            GatewaySecurityContext gc = exchange.getAttribute(GatewaySecurityContext.GATEWAY_SECURITY_CONTEXT_ATTR);

            // Get the Spring request ID that was set by InitializeMdcContextFilter
            String springRequestId = exchange.getAttribute(InitializeMdcContextFilter.INITIAL_REQUEST_ID_ATTR);
            if (springRequestId == null) {
                springRequestId = exchange.getRequest().getId();
            }

            StringBuilder sb = new StringBuilder();

            // Create date part
            Date date = Calendar.getInstance().getTime();
            DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");
            String datePart = dateFormat.format(date);

            /**
             * Authorized Party is the 'azp' field in the JSON token.
             * The "azp" claim is used to indicate the party (usually a client application)
             * to which the issuer of the token has granted authorization.
             */
            String authParty = gc != null && gc.getAuthParty() != null ? gc.getAuthParty() : "UNKNOWN";

            // Create random part
            String random = RandomStringUtils.randomAlphanumeric(5);

            sb.append(requestIdPrefix.toUpperCase());
            sb.append("-");
            sb.append(authParty.toUpperCase());
            sb.append("-");
            sb.append(datePart);
            sb.append("-");
            sb.append(random.toUpperCase());

            String newRequestId = sb.toString();

            // Add request ID to outgoing request header
            ServerHttpRequest request = exchange.getRequest()
                    .mutate()
                    .header(requestIdHeader, newRequestId)
                    .build();

            // Store in exchange attributes
            exchange.getAttributes().put(REQUEST_ID_ATTR, newRequestId);

            // Update MDC in exchange attributes and current thread
            // This ensures all subsequent code (including response decorators) can access it
            MdcContextLifterConfiguration.updateRequestIdInExchange(exchange, newRequestId);

            // Log the request ID transition at INFO level for correlation
            log.info("Request ID generated: {} (Spring ID: {})", newRequestId, springRequestId);

            // Create updated MDC context map for reactive propagation
            Map<String, String> mdcContext = new HashMap<>();
            mdcContext.put(MdcContextLifterConfiguration.SPRING_REQUEST_ID_MDC_KEY, springRequestId);
            mdcContext.put(MdcContextLifterConfiguration.REQUEST_ID_MDC_KEY, newRequestId);

            // Continue the filter chain with updated MDC context
            return chain.filter(exchange.mutate().request(request).build())
                    .contextWrite(ctx -> MdcContextLifterConfiguration.putMdcContext(ctx, mdcContext));
        };
    }

    /**
     * Configuration class for the GenerateRequestId filter.
     * Currently empty but can be extended with configurable options.
     */
    public static class Config {
        // Configuration properties can be added here if needed
        // For example: custom prefix override, timestamp format, etc.
    }
}