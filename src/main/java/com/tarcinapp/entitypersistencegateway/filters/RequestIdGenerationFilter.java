package com.tarcinapp.entitypersistencegateway.filters;

import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

import com.tarcinapp.entitypersistencegateway.GatewayContext;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class RequestIdGenerationFilter implements GlobalFilter, Ordered {

    @Value("${app.requestHeaders.authenticationSubject}")
    private String requestIdHeader;

    @Value("${app.requestIdPrefix}")
    private String requestIdPrefix;

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewayContext gc = exchange.getAttribute(GATEWAY_CONTEXT_ATTR);

        StringBuilder sb = new StringBuilder();

        // create date part
        Date date = Calendar.getInstance().getTime();  
        DateFormat dateFormat = new SimpleDateFormat("yyyymmddhhmmss");  
        String datePart = dateFormat.format(date);

        // create auth party part
        String authPart = gc.getAuthParty().toUpperCase();

        // create random part
        String random = RandomStringUtils.randomAlphanumeric(5).toUpperCase();

        sb.append(requestIdPrefix);
        sb.append("-");
        sb.append(datePart);
        sb.append("-");
        sb.append(authPart);
        sb.append("-");
        sb.append(random);

        String requestId = sb.toString();

        ServerHttpRequest request = exchange.getRequest()
            .mutate()
            .header(requestIdHeader, requestId)
            .build();

        gc.setRequestId(requestId);

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return 1;
    }
}