package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import reactor.core.publisher.Mono;

/**
 * Service responsible for extracting request payloads and preparing them
 * for policy evaluation.
 */
@Service
public class PayloadExtractor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    private static final Logger logger = LogManager.getLogger(PayloadExtractor.class);

    private final ObjectMapper objectMapper;

    public PayloadExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts the request payload and attaches it to policy data.
     */
    public Mono<Void> extractAndAttachPayload(PolicyData policyData, ServerWebExchange exchange, 
                                                GatewayFilterChain chain) {
        ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = 
            new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {
                    try {
                        Map<String, Object> payloadJSON = objectMapper.readValue(
                            inboundJsonRequestStr,
                            MAP_TYPE_REFERENCE
                        );

                        policyData.setRequestPayload(payloadJSON);

                        logger.debug("Request payload attached to policy data");

                        return Mono.just(inboundJsonRequestStr);
                    } catch (JsonProcessingException e) {
                        logger.error("Failed to parse JSON payload", e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, 
                            "Invalid JSON in request body");
                    }
                });

        return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
    }
}
