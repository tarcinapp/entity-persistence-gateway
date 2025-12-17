package com.tarcinapp.entitypersistencegateway.filters.common.request;

import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This filter inspects incoming HTTP Query Parameters to enforce Field Level Security on read operations.
 *
 * STRATEGY:
 * 1. "Surgical In-Place Replacement" for WHERE clauses:
 * Replaces forbidden params with impossible conditions (_id=__FORBIDDEN__) to prevent access/inference.
 * 2. "Surgical Removal" for ORDER clauses:
 * Removes forbidden sorting parameters to prevent "Side-Channel Attacks" (inference by sorting).
 *
 * SCOPE:
 * - Root Level Queries (Where & Order)
 * - Include Scope Queries (Level 1)
 * - Nested Include Scope Queries (Level 2)
 * - Lookups are ignored (handled in Response phase).
 */
@Component
@Slf4j
public class PreventQueryByForbiddenFields extends AbstractGatewayFilterFactory<PreventQueryByForbiddenFields.Config> {

    // Regex patterns
    private static final Pattern BRACKET_CONTENT_PATTERN = Pattern.compile("\\[([^\\[\\]]+)\\]");
    private static final Pattern LEVEL_1_INCLUDE_INDEX_PATTERN = Pattern.compile("filter\\[include\\]\\[(\\d+)\\]\\[relation\\]");
    private static final Pattern LEVEL_2_INCLUDE_RELATION_PATTERN = Pattern.compile("filter\\[include\\]\\[(\\d+)\\]\\[scope\\]\\[include\\]\\[(\\d+)\\]\\[relation\\]");

    // LoopBack Operators and Keywords
    private static final Set<String> COMPARISON_OPERATORS = Set.of(
            "eq", "gt", "gte", "lt", "lte", "between", "inq", "nin", "neq",
            "like", "nlike", "ilike", "nilike", "regexp", "near"
    );
    private static final Set<String> LOGICAL_OPERATORS = Set.of("and", "or");
    private static final Set<String> STRUCTURE_KEYWORDS = Set.of(
            "filter", "where", "include", "scope", "fields", "limit", "skip", "order", "lookup"
    );

    public PreventQueryByForbiddenFields() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            try {
                // 1. Retrieve Forbidden Fields Library
                ForbiddenFieldsLibrary library = exchange.getAttribute(FetchForbiddenFieldsGatewayFilterFactory.GATEWAY_CONTEXT_FORBIDDEN_FIELDS);

                if (library == null || exchange.getRequest().getQueryParams().isEmpty()) {
                    return chain.filter(exchange);
                }

                // 2. Identify Context
                Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                String rootRecordType = (route != null) ? (String) route.getMetadata().get("recordType") : null;

                MultiValueMap<String, String> originalParams = exchange.getRequest().getQueryParams();
                String rootKind = resolveKind(originalParams);

                // Mutable copy (Deep copy to ensure lists are mutable for inplace modification)
                // Using new LinkedMultiValueMap<>(originalParams) is not enough because the inner lists remain unmodifiable.
                MultiValueMap<String, String> mutableParams = new LinkedMultiValueMap<>();
                originalParams.forEach((key, values) -> mutableParams.put(key, new ArrayList<>(values)));

                if (log.isDebugEnabled()) {
                    String incomingDecoded = buildDecodedUri(exchange.getRequest().getURI(), originalParams);
                    log.debug("Incoming URI (decoded): {}", incomingDecoded);
                }

                boolean isModified = false;

                // 3. Pre-process Include Relations
                IncludeRelationMap includeMap = buildIncludeRelationMaps(originalParams);

                // 4. Iterate
                List<String> paramKeys = new ArrayList<>(mutableParams.keySet());

                for (String key : paramKeys) {
                    
                    if (key.contains("[lookup]")) continue;

                    // --- WHERE CLAUSE CHECKS ---

                    // A. Root Level Where Check
                    if (isRootWhereClause(key)) {
                        String fieldName = extractFieldName(key);
                        if (fieldName != null && isForbidden(library, rootRecordType, rootKind, fieldName)) {
                            log.debug("Security: Replacing forbidden root field '{}' with impossible condition.", fieldName);
                            sanitizeQueryParam(mutableParams, key, fieldName);
                            isModified = true;
                        }
                    }
                    // B. Level 1 Include Where Check
                    else if (isLevel1IncludeWhereClause(key)) {
                        String index = extractLevel1IncludeIndex(key);
                        String relationName = includeMap.getLevel1().get(index);
                        String targetRecordType = mapRelationToRecordType(rootRecordType, relationName);
                        String fieldName = extractFieldName(key);

                        if (targetRecordType != null && fieldName != null && isForbidden(library, targetRecordType, null, fieldName)) {
                            log.debug("Security: Replacing forbidden Level 1 field '{}' in relation '{}'.", fieldName, relationName);
                            sanitizeQueryParam(mutableParams, key, fieldName);
                            isModified = true;
                        }
                    }
                    // C. Level 2 Include Where Check
                    else if (isLevel2IncludeWhereClause(key)) {
                        String[] indices = extractLevel2IncludeIndices(key);
                        if (indices != null) {
                            String idx1 = indices[0];
                            String idx2 = indices[1];
                            String l1Relation = includeMap.getLevel1().get(idx1);
                            String l1RecordType = mapRelationToRecordType(rootRecordType, l1Relation);
                            String l2Relation = includeMap.getLevel2().get(idx1 + "_" + idx2);
                            String l2RecordType = mapRelationToRecordType(l1RecordType, l2Relation);
                            String fieldName = extractFieldName(key);

                            if (l2RecordType != null && fieldName != null && isForbidden(library, l2RecordType, null, fieldName)) {
                                log.debug("Security: Replacing forbidden Level 2 field '{}' in relation '{}'.", fieldName, l2Relation);
                                sanitizeQueryParam(mutableParams, key, fieldName);
                                isModified = true;
                            }
                        }
                    }

                    // --- ORDER CLAUSE CHECKS ---

                    // D. Root Level Order Check
                    else if (isRootOrderClause(key)) {
                        List<String> values = mutableParams.get(key);
                        if (checkAndRemoveForbiddenOrders(values, library, rootRecordType, rootKind)) {
                            isModified = true;
                            if (values.isEmpty()) mutableParams.remove(key);
                        }
                    }
                    // E. Level 1 Include Order Check
                    else if (isLevel1IncludeOrderClause(key)) {
                        String index = extractLevel1IncludeIndex(key);
                        String relationName = includeMap.getLevel1().get(index);
                        String targetRecordType = mapRelationToRecordType(rootRecordType, relationName);

                        if (targetRecordType != null) {
                            List<String> values = mutableParams.get(key);
                            if (checkAndRemoveForbiddenOrders(values, library, targetRecordType, null)) {
                                log.debug("Security: Removing forbidden Level 1 order in relation '{}'.", relationName);
                                isModified = true;
                                if (values.isEmpty()) mutableParams.remove(key);
                            }
                        }
                    }
                    // F. Level 2 Include Order Check
                    else if (isLevel2IncludeOrderClause(key)) {
                        String[] indices = extractLevel2IncludeIndices(key);
                        if (indices != null) {
                            String idx1 = indices[0];
                            String idx2 = indices[1];
                            String l1Relation = includeMap.getLevel1().get(idx1);
                            String l1RecordType = mapRelationToRecordType(rootRecordType, l1Relation);
                            String l2Relation = includeMap.getLevel2().get(idx1 + "_" + idx2);
                            String l2RecordType = mapRelationToRecordType(l1RecordType, l2Relation);

                            if (l2RecordType != null) {
                                List<String> values = mutableParams.get(key);
                                if (checkAndRemoveForbiddenOrders(values, library, l2RecordType, null)) {
                                    log.debug("Security: Removing forbidden Level 2 order in relation '{}'.", l2Relation);
                                    isModified = true;
                                    if (values.isEmpty()) mutableParams.remove(key);
                                }
                            }
                        }
                    }
                }

                // 5. Rebuild
                if (isModified) {
                    try {
                        URI newUri = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                                .replaceQueryParams(mutableParams)
                                .build()
                                .toUri();

                        if (log.isDebugEnabled()) {
                            String modifiedDecoded = buildDecodedUri(newUri, mutableParams);
                            log.debug("Modified URI (decoded): {}", modifiedDecoded);
                        }

                        return chain.filter(exchange.mutate()
                                .request(exchange.getRequest().mutate().uri(newUri).build())
                                .build());
                    } catch (IllegalArgumentException e) {
                        log.error("PreventQueryByForbiddenFields: URI Construction Error: {}", e.getMessage());
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Query Parameters");
                    }
                }

                return chain.filter(exchange);

            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.error("PreventQueryByForbiddenFields: Unexpected error: {}", e.getMessage(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Security check failed");
            }
        };
    }

    /**
     * Helper to check order values (e.g., "salary DESC", "name ASC") and remove forbidden ones.
     */
    private boolean checkAndRemoveForbiddenOrders(List<String> values, ForbiddenFieldsLibrary library, String recordType, String kind) {
        if (values == null) return false;
        
        return values.removeIf(val -> {
            // Extract field name from "field DESC" or "field"
            String field = val.trim().split("\\s+")[0];
            
            if (isForbidden(library, recordType, kind, field)) {
                log.debug("Security: Removing forbidden order on field '{}'.", field);
                return true;
            }
            return false;
        });
    }

    /**
     * REPLACES the forbidden parameter with an impossible _id condition IN PLACE.
     */
    private void sanitizeQueryParam(MultiValueMap<String, String> params, String key, String fieldName) {
        params.remove(key);

        String fieldToken = "[" + fieldName + "]";
        int fieldIndex = key.lastIndexOf(fieldToken);

        if (fieldIndex != -1) {
            String basePath = key.substring(0, fieldIndex);
            String replacementKey = basePath + "[_id]";
            params.set(replacementKey, "__FORBIDDEN__");
        }
    }

    // --- Helpers (Core Logic) ---

    private boolean isForbidden(ForbiddenFieldsLibrary library, String recordType, String kind, String field) {
        if (recordType == null || field == null) return false;
        List<String> forbiddenList = library.resolveForbiddenFields(recordType, kind);
        return forbiddenList != null && forbiddenList.contains(field);
    }

    private String resolveKind(MultiValueMap<String, String> queryParams) {
        if (queryParams.containsKey("kind")) return queryParams.getFirst("kind");
        for (String key : queryParams.keySet()) {
            if (key.endsWith("[_kind]")) return queryParams.getFirst(key);
        }
        return null;
    }

    private String mapRelationToRecordType(String rootType, String relationName) {
        if (rootType == null || relationName == null) return null;
        if ("_entities".equals(relationName)) return "entities";
        if ("_reactions".equals(relationName)) {
            if ("lists".equals(rootType)) return "listReactions";
            if ("entities".equals(rootType)) return "entityReactions";
        }
        return null;
    }

    // --- Clause Parsers ---

    private boolean isRootWhereClause(String key) {
        return (key.startsWith("filter[where]") || key.startsWith("where["))
                && !key.contains("[include]") && !key.contains("[lookup]");
    }
    
    private boolean isRootOrderClause(String key) {
        return (key.startsWith("filter[order]") || key.startsWith("order"))
                && !key.contains("[include]") && !key.contains("[lookup]");
    }

    private boolean isLevel1IncludeWhereClause(String key) {
        return key.contains("filter[include]") && key.contains("[scope][where]") && !key.contains("[scope][include]");
    }

    private boolean isLevel1IncludeOrderClause(String key) {
        return key.contains("filter[include]") && key.contains("[scope][order]") && !key.contains("[scope][include]");
    }

    private boolean isLevel2IncludeWhereClause(String key) {
        return key.contains("filter[include]") && key.contains("[scope][include]") && key.contains("[scope][where]");
    }

    private boolean isLevel2IncludeOrderClause(String key) {
        return key.contains("filter[include]") && key.contains("[scope][include]") && key.contains("[scope][order]");
    }

    // --- Include Map Building ---

    @Data
    private static class IncludeRelationMap {
        Map<String, String> level1 = new HashMap<>();
        Map<String, String> level2 = new HashMap<>();
    }

    private IncludeRelationMap buildIncludeRelationMaps(MultiValueMap<String, String> params) {
        IncludeRelationMap map = new IncludeRelationMap();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) continue;
            
            Matcher m1 = LEVEL_1_INCLUDE_INDEX_PATTERN.matcher(key);
            if (m1.find()) {
                map.getLevel1().put(m1.group(1), values.get(0));
                continue;
            }

            Matcher m2 = LEVEL_2_INCLUDE_RELATION_PATTERN.matcher(key);
            if (m2.find()) {
                map.getLevel2().put(m2.group(1) + "_" + m2.group(2), values.get(0));
            }
        }
        return map;
    }

    private String extractLevel1IncludeIndex(String key) {
        Pattern p = Pattern.compile("filter\\[include\\]\\[(\\d+)\\]");
        Matcher m = p.matcher(key);
        return m.find() ? m.group(1) : null;
    }

    private String[] extractLevel2IncludeIndices(String key) {
        Pattern p = Pattern.compile("filter\\[include\\]\\[(\\d+)\\]\\[scope\\]\\[include\\]\\[(\\d+)\\]");
        Matcher m = p.matcher(key);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    private String extractFieldName(String key) {
        List<String> segments = new ArrayList<>();
        Matcher m = BRACKET_CONTENT_PATTERN.matcher(key);
        while (m.find()) segments.add(m.group(1));

        if (segments.isEmpty()) return null;

        for (int i = segments.size() - 1; i >= 0; i--) {
            String segment = segments.get(i);
            if (segment.matches("\\d+")) continue;
            if (STRUCTURE_KEYWORDS.contains(segment)) continue;
            if (LOGICAL_OPERATORS.contains(segment)) continue;
            if (COMPARISON_OPERATORS.contains(segment)) continue;
            return segment;
        }
        return null;
    }

    @Data
    public static class Config {
    }

    // --- Logging Helpers ---

    private String buildDecodedUri(URI baseUri, MultiValueMap<String, String> params) {
        String scheme = baseUri.getScheme();
        String authority = baseUri.getRawAuthority();
        String path = baseUri.getRawPath();

        String decodedAuthority = authority != null ? URLDecoder.decode(authority, StandardCharsets.UTF_8) : null;
        String decodedPath = path != null ? URLDecoder.decode(path, StandardCharsets.UTF_8) : "";

        String decodedQuery;
        if (params != null && !params.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, List<String>> e : params.entrySet()) {
                String k = e.getKey();
                List<String> vals = e.getValue();
                if (vals == null || vals.isEmpty()) {
                    if (!first) sb.append('&');
                    sb.append(k);
                    first = false;
                } else {
                    for (String v : vals) {
                        if (!first) sb.append('&');
                        sb.append(k);
                        sb.append('=');
                        if (v != null) sb.append(v);
                        first = false;
                    }
                }
            }
            decodedQuery = sb.length() > 0 ? sb.toString() : null;
        } else {
            String rawQuery = baseUri.getRawQuery();
            decodedQuery = rawQuery != null ? URLDecoder.decode(rawQuery, StandardCharsets.UTF_8) : null;
        }

        StringBuilder full = new StringBuilder();
        if (scheme != null) {
            full.append(scheme).append("://");
        }
        if (decodedAuthority != null) {
            full.append(decodedAuthority);
        }
        full.append(decodedPath);
        if (decodedQuery != null && !decodedQuery.isEmpty()) {
            full.append('?').append(decodedQuery);
        }
        return full.toString();
    }
}