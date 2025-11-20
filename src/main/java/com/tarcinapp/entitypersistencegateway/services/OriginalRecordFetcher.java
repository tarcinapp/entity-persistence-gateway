package com.tarcinapp.entitypersistencegateway.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;

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
     * Handles kind alias resolution and relation endpoint patterns.
     */
    public Mono<Object> fetchOriginalRecord(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String originalResourceUrl = request.getPath().toString();

        // Check if we have a kind alias configuration
        KindAliasConfigAttr kindAliasConfigAttr = exchange.getAttribute("KindAliasConfigAttr");
        if (kindAliasConfigAttr != null && kindAliasConfigAttr.isKindAliasConfigured()) {
            originalResourceUrl = kindAliasConfigAttr.getOriginalResourceUrl();
            logger.debug("Using kind alias mapped URL: " + originalResourceUrl);
        }

        // Handle relation endpoints (children/parents)
        originalResourceUrl = resolveRelationEndpoint(originalResourceUrl);

        logger.debug("Fetching original record from: " + originalResourceUrl);

        return backendBaseClient.get(originalResourceUrl, Object.class);
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
