package com.tarcinapp.entitypersistencegateway.filters.common;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class GenerateRequestId implements GlobalFilter {

    @Value("${app.requestHeaders.requestId}")
    private String requestIdHeader;

    @Value("${app.shortcode}")
    private String requestIdPrefix;

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    private final static String REQUEST_ID_ATTR = "RequestId";

    private Logger logger = LogManager.getLogger(GenerateRequestId.class);

    public GenerateRequestId() {
    }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewaySecurityContext gc = exchange.getAttribute(GATEWAY_SECURITY_CONTEXT_ATTR);

        StringBuilder sb = new StringBuilder();

        // create date part
        Date date = Calendar.getInstance().getTime();
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        String datePart = dateFormat.format(date);

        /**
         * Authorized Party is the 'azp' field in the JSON token.
         * The "azp" claim is used to indicate the party (usually a client application)
         * to which the issuer of the token has granted authorization.
         */
        String authParty = gc.getAuthParty() != null ? gc.getAuthParty() : "unknown";

        // create random part
        String random = RandomStringUtils.randomAlphanumeric(5);

        sb.append(requestIdPrefix.toUpperCase());
        sb.append("-");
        sb.append(authParty.toUpperCase());
        sb.append("-");
        sb.append(datePart);
        sb.append("-");
        sb.append(random.toUpperCase());

        String requestId = sb.toString();


        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .header(requestIdHeader, requestId)
                .build();

        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);

        ThreadContext.put(REQUEST_ID_ATTR, requestId);

        logger.debug("Request id is generated: " + requestId);

        return chain.filter(exchange.mutate().request(request).build());
    }

    public static class Config {

    }
}