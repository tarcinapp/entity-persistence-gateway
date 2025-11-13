package com.tarcinapp.entitypersistencegateway.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.KindPathConfigAttr;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import reactor.core.publisher.Mono;

/**
 * Service responsible for fetching original records from the backend
 * for policy evaluation purposes.
 */
@Service
public class OriginalRecordFetcher {

    @Autowired
    private IBackendClientBase backendBaseClient;

    private static final Logger logger = LogManager.getLogger(OriginalRecordFetcher.class);

    /**
     * Fetches the original record and attaches it to policy data
     */
    public Mono<Void> fetchAndAttach(PolicyData policyData, ServerWebExchange exchange, String recordId) {
        return fetchOriginalRecord(exchange)
                .flatMap(originalRecord -> {
                    policyData.setOriginalRecord(originalRecord);
                    return Mono.empty();
                });
    }

    /**
     * Fetches the original record from the backend based on the request context.
     * Handles kindPath resolution and relation endpoint patterns.
     */
    public Mono<AnyRecordBase> fetchOriginalRecord(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String originalResourceUrl = request.getPath().toString();

        // Check if we have a kindPath configuration
        KindPathConfigAttr kindPathConfigAttr = exchange.getAttribute("KindPathConfigAttr");
        if (kindPathConfigAttr != null && kindPathConfigAttr.isKindPathConfigured()) {
            originalResourceUrl = kindPathConfigAttr.getOriginalResourceUrl();
            logger.debug("Using kind path mapped URL: " + originalResourceUrl);
        }

        // Handle relation endpoints (children/parents)
        originalResourceUrl = resolveRelationEndpoint(originalResourceUrl);

        logger.debug("Fetching original record from: " + originalResourceUrl);

        return backendBaseClient.get(originalResourceUrl, AnyRecordBase.class);
    }

    /**
     * Resolves relation endpoints by removing /children or /parents suffixes
     */
    private String resolveRelationEndpoint(String path) {
        if (path.contains("/children") || path.contains("/parents")) {
            return path.replaceAll("/(children|parents)$", "");
        }
        return path;
    }

    /**
     * Builds the root path for a record by removing relation suffixes
     */
    public String buildRootPath(String requestPath, String recordId) {
        return requestPath.replaceAll("\\/" + recordId + "\\/.*", "\\/" + recordId);
    }
}
