package com.tarcinapp.entitypersistencegateway.filters.common;

import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;

import org.springframework.http.MediaType;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class SetOwnerUsersToRequest
        extends AbstractGatewayFilterFactory<SetOwnerUsersToRequest.Config> {

    private static final String OWNER_USERS_FIELD_NAME = "ownerUsers";
    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    Logger logger = LogManager.getLogger(SetOwnerUsersToRequest.class);

    public SetOwnerUsersToRequest() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setContentType(MediaType.APPLICATION_JSON_VALUE)
                    .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                        GatewaySecurityContext gc = (GatewaySecurityContext)exchange1.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);

                        if(gc == null) {
                            return Mono.just(inboundJsonRequestStr);
                        }
                        
                        String authSubject = gc.getAuthSubject();

                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                                new TypeReference<Map<String, Object>>() {
                                });

                            if(authSubject != null)
                                inboundJsonRequestMap.put(OWNER_USERS_FIELD_NAME, new String[]{authSubject});
                            
                            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

                            return Mono.just(outboundJsonRequestStr);
                        } catch (JsonMappingException e) {
                            logger.error(e);
                        } catch (JsonProcessingException e) {
                            logger.error(e);
                        }

                    return Mono.just(inboundJsonRequestStr);
                });

            return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
        };
    }
     
    public static class Config {
        
    }
}