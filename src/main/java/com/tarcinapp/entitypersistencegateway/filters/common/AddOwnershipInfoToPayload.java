package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AddOwnershipInfoToPayload extends AbstractGatewayFilterFactory<AddOwnershipInfoToPayload.Config> {

    private static final String OWNER_USERS_FIELD_NAME = "ownerUsers";
    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";

    @Autowired
    Key key;

    Logger logger = LogManager.getLogger(AddOwnershipInfoToPayload.class);

    public AddOwnershipInfoToPayload() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("AddOwnershipInfoToPayload filter is started");

            if(key == null) {
                logger.warn("RS256 key is not configured. Ownership information won't be added to the request payload! Please configure a valid RS256 public key to enable record ownership.");

                return chain.filter(exchange);
            }

            return this.filter(exchange, chain);
        };
    }

    private Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setContentType(MediaType.APPLICATION_JSON_VALUE)
                    .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                        GatewaySecurityContext gc = (GatewaySecurityContext)exchange1.getAttributes().get(GATEWAY_SECURITY_CONTEXT_ATTR);
                        
                        String authSubject = gc.getAuthSubject();

                        logger.debug("Owner user is: ", authSubject);

                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                                new TypeReference<Map<String, Object>>() {
                                });

                            if(authSubject != null)
                                inboundJsonRequestMap.put(OWNER_USERS_FIELD_NAME, new String[]{authSubject});
                            
                            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

                            logger.debug("Request payload is modified by adding the owner user. New payload: ", outboundJsonRequestStr);

                            return Mono.just(outboundJsonRequestStr);
                        } catch (JsonMappingException e) {
                            logger.error(e);
                        } catch (JsonProcessingException e) {
                            logger.error(e);
                        }

                    return Mono.just(inboundJsonRequestStr);
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    public static class Config {
        
    }
}