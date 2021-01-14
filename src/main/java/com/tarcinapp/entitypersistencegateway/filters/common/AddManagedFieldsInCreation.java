package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.dto.ManagedField;

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
public class AddManagedFieldsInCreation extends AbstractGatewayFilterFactory<AddManagedFieldsInCreation.Config> {

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    
    @Autowired(required = false)
    Key key;

    Logger logger = LogManager.getLogger(AddManagedFieldsInCreation.class);

    public AddManagedFieldsInCreation() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            logger.debug("AddManagedFieldsInCreation filter is started");

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

                            if(authSubject != null) {
                                
                                String now = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now());

                                /**
                                 * We used putIfAbsent here because user may be authorized to send custom values for these fields.
                                 */
                                inboundJsonRequestMap.putIfAbsent(ManagedField.OWNER_USERS.getFieldName(), new String[]{authSubject});
                                inboundJsonRequestMap.putIfAbsent(ManagedField.CREATED_BY.getFieldName(), authSubject);
                                inboundJsonRequestMap.putIfAbsent(ManagedField.LAST_UPDATED_BY.getFieldName(), authSubject);
                                inboundJsonRequestMap.putIfAbsent(ManagedField.CREATION_DATE_TIME.getFieldName(), now);
                                inboundJsonRequestMap.putIfAbsent(ManagedField.LAST_UPDATED_DATE_TIME.getFieldName(), now);
                            }        
                            
                            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

                            logger.debug("Request payload is modified with adding the owner user. New payload: ", outboundJsonRequestStr);

                            return Mono.just(outboundJsonRequestStr);
                        } catch (JsonMappingException e) {
                            return Mono.error(e);
                        } catch (JsonProcessingException e) {
                            return Mono.error(e);
                        }
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }

    public static class Config {
        
    }
}