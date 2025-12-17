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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This filter inspects incoming HTTP Query Parameters to enforce Field Level Security on read operations.
 *
 * STRATEGY: "Surgical In-Place Replacement"
 * Instead of deleting the whole query scope (which breaks OR conditions),
 * we replace ONLY the specific forbidden parameter with an impossible condition.
 *
 * Example:
 * Input:  filter[where][or][0][secretField]=X & filter[where][or][1][publicField]=Y
 * Action: Remove 'secretField'. Add 'filter[where][or][0][_id] = __FORBIDDEN__'
 * Result: (_id == __FORBIDDEN__ OR publicField == Y)
 *
 * Outcome:
 * - AND queries: (_id=F AND public=Y) -> Returns Nothing (Secure)
 * - OR queries:  (_id=F OR public=Y)  -> Returns Public=Y results (Secure & Functional)
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

                // Mutable copy
                MultiValueMap<String, String> mutableParams = new LinkedMultiValueMap<>(originalParams);
                boolean isModified = false;

                // 3. Pre-process Include Relations
                IncludeRelationMap includeMap = buildIncludeRelationMaps(originalParams);

                // 4. Iterate
                List<String> paramKeys = new ArrayList<>(mutableParams.keySet());

                for (String key : paramKeys) {
                    
                    if (key.contains("[lookup]")) continue;

                    // A. Root Level Check
                    if (isRootWhereClause(key)) {
                        String fieldName = extractFieldName(key);
                        if (fieldName != null && isForbidden(library, rootRecordType, rootKind, fieldName)) {
                            log.debug("Security: Replacing forbidden root field '{}' with impossible condition.", fieldName);
                            sanitizeQueryParam(mutableParams, key, fieldName);
                            isModified = true;
                        }
                    }
                    // B. Level 1 Include Check
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
                    // C. Level 2 Include Check
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
                }

                // 5. Rebuild
                if (isModified) {
                    try {
                        URI newUri = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                                .replaceQueryParams(mutableParams)
                                .build()
                                .toUri();

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
     * REPLACES the forbidden parameter with an impossible _id condition IN PLACE.
     * This preserves the logical structure (OR/AND arrays) of the query.
     *
     * Logic:
     * Key:   filter[where][or][0][age][gt]
     * Field: age
     * 1. Find index of "[age]"
     * 2. Substring to get prefix: "filter[where][or][0]"
     * 3. Construct replacement: "filter[where][or][0][_id]"
     * 4. Remove old key, set new key to "__FORBIDDEN__"
     */
    private void sanitizeQueryParam(MultiValueMap<String, String> params, String key, String fieldName) {
        // Remove the original forbidden parameter
        params.remove(key);

        // Calculate the base path by stripping the field name and any subsequent operators
        // Example: "...[age][gt]" -> field is "age"
        // We want to replace "[age][gt]" with "[_id]"
        
        String fieldToken = "[" + fieldName + "]";
        int fieldIndex = key.lastIndexOf(fieldToken);

        if (fieldIndex != -1) {
            String basePath = key.substring(0, fieldIndex);
            String replacementKey = basePath + "[_id]";
            
            // Set the impossible condition
            // This effectively changes (age > X) to (_id == __FORBIDDEN__)
            params.set(replacementKey, "__FORBIDDEN__");
        }
    }

    // --- Helpers (Same as before) ---

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

    private boolean isRootWhereClause(String key) {
        return (key.startsWith("filter[where]") || key.startsWith("where["))
                && !key.contains("[include]") && !key.contains("[lookup]");
    }

    private boolean isLevel1IncludeWhereClause(String key) {
        return key.contains("filter[include]") && key.contains("[scope][where]") && !key.contains("[scope][include]");
    }

    private boolean isLevel2IncludeWhereClause(String key) {
        return key.contains("filter[include]") && key.contains("[scope][include]") && key.contains("[scope][where]");
    }

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
}