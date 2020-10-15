package com.tarcinapp.entitypersistencegateway.filters;

import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewayContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class ConvertKindPathToQueryFilterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ConvertKindPathToQueryFilterGatewayFilterFactory.Config> {

    Logger logger = LogManager.getLogger(ConvertKindPathToQueryFilterGatewayFilterFactory.class);

    public ConvertKindPathToQueryFilterGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
            String kindPath = uriVariables.get("kindPath");

            System.out.println("!!!");
            System.out.println("MapKindPathFilter invoked: " + kindPath);
            System.out.println("!!!");
            return chain.filter(exchange);
        };
    }
     
    public static class Config {
        
    }
}