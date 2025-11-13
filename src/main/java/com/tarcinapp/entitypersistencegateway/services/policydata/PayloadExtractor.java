package com.tarcinapp.entitypersistencegateway.services.policydata;

import java.time.ZonedDateTime;
import java.util.List;
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
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import reactor.core.publisher.Mono;

/**
 * Service responsible for extracting request payloads and preparing them
 * for policy evaluation.
 */
@Service
public class PayloadExtractor {

    private static final Logger logger = LogManager.getLogger(PayloadExtractor.class);

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
                        ObjectMapper objectMapper = new ObjectMapper();
                        Map<String, Object> payloadJSON = objectMapper.readValue(
                            inboundJsonRequestStr,
                            new TypeReference<Map<String, Object>>() {}
                        );

                        AnyRecordBase recordBaseFromPayload = prepareRecordBaseFromPayload(payloadJSON);
                        policyData.setRequestPayload(recordBaseFromPayload);

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

    /**
     * Prepares a record base object from the request payload.
     * Extracts managed fields for policy evaluation.
     */
    private AnyRecordBase prepareRecordBaseFromPayload(Map<String, Object> payloadJSON) {
        AnyRecordBase recordBase = new AnyRecordBase();

        try {
            // Extract managed fields
            recordBase.set_id((String) payloadJSON.get("_id"));
            recordBase.set_kind((String) payloadJSON.get("_kind"));
            recordBase.set_name((String) payloadJSON.get("_name"));
            recordBase.set_slug((String) payloadJSON.get("_slug"));
            recordBase.set_visibility((String) payloadJSON.get("_visibility"));

            // Parse date fields
            String createdDateTime = (String) payloadJSON.get("_createdDateTime");
            if (createdDateTime != null) {
                recordBase.set_createdDateTime(ZonedDateTime.parse(createdDateTime));
            }

            String validFromDateTime = (String) payloadJSON.get("_validFromDateTime");
            if (validFromDateTime != null) {
                recordBase.set_validFromDateTime(ZonedDateTime.parse(validFromDateTime));
            }

            String validUntilDateTime = (String) payloadJSON.get("_validUntilDateTime");
            if (validUntilDateTime != null) {
                recordBase.set_validUntilDateTime(ZonedDateTime.parse(validUntilDateTime));
            }

            // Extract ownership fields
            @SuppressWarnings("unchecked")
            List<String> ownerUsers = (List<String>) payloadJSON.get("_ownerUsers");
            recordBase.set_ownerUsers(ownerUsers);

            @SuppressWarnings("unchecked")
            List<String> ownerGroups = (List<String>) payloadJSON.get("_ownerGroups");
            recordBase.set_ownerGroups(ownerGroups);

            return recordBase;
        } catch (java.time.format.DateTimeParseException e) {
            logger.error("Invalid date format in request payload", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid date format. Dates must be in ISO-8601 format with timezone", e);
        } catch (ClassCastException e) {
            logger.error("Invalid field type in request payload", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid field type in request payload", e);
        }
    }
}
