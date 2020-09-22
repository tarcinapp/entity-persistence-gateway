package com.tarcinapp.entitypersistencegateway.filters;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import io.netty.buffer.ByteBufAllocator;
import jdk.jfr.ContentType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class SetOwnerUsers2RequestGatewayFilterFactory
        extends AbstractGatewayFilterFactory<SetOwnerUsers2RequestGatewayFilterFactory.Config> {

    @Value("${app.requestHeaders.authenticationSubject}")
    private String authSubjectHeader;

    public SetOwnerUsers2RequestGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setContentType(MediaType.APPLICATION_JSON_VALUE)
                    .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                        ObjectMapper objectMapper = new ObjectMapper();
                        
                        String authSubject = exchange1.getRequest().getHeaders().getFirst(authSubjectHeader);


                        try {
                            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                                new TypeReference<Map<String, Object>>() {
                                });
                            inboundJsonRequestMap.put("ownerUsers", new String[]{authSubject});

                            String outboundJsonRequestStr = new ObjectMapper().writeValueAsString(inboundJsonRequestMap);

                            return Mono.just(outboundJsonRequestStr);
                        } catch (JsonMappingException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (JsonProcessingException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }

                    return Mono.just(inboundJsonRequestStr);
                });

            return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
        };
    }
 
    public static class Config {
        
    }
}