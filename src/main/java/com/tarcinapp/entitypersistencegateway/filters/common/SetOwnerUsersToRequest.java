package com.tarcinapp.entitypersistencegateway.filters.common;

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

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class SetOwnerUsersToRequest
        extends AbstractGatewayFilterFactory<SetOwnerUsersToRequest.Config> {

    private static final String OWNER_USERS_FIELD_NAME = "ownerUsers";
    private static final String OWNER_GROUPS_FIELD_NAME = "ownerGroups";
    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

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
                        GatewayContext gc = (GatewayContext)exchange1.getAttributes().get(GATEWAY_CONTEXT_ATTR);

                        if(gc == null) {
                            return Mono.just(inboundJsonRequestStr);
                        }
                        
                        String authSubject = gc.getAuthSubject();
                        ArrayList<String> groups = gc.getGroups();

                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                                new TypeReference<Map<String, Object>>() {
                                });

                            if(authSubject != null)
                                inboundJsonRequestMap.put(OWNER_USERS_FIELD_NAME, new String[]{authSubject});

                            if(groups != null)
                                inboundJsonRequestMap.put(OWNER_GROUPS_FIELD_NAME, groups.toArray(new String[groups.size()]));
                            
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