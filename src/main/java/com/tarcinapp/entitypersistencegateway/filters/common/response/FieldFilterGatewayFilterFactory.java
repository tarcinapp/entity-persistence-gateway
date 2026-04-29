package com.tarcinapp.entitypersistencegateway.filters.common.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.IncludeAliasProjectionAttr;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.auth.ForbiddenFieldsLibrary;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.common.request.FetchForbiddenFieldsGatewayFilterFactory;
import com.tarcinapp.entitypersistencegateway.helpers.QueryStringTargetAnalyzer;
import com.tarcinapp.entitypersistencegateway.services.FieldFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * The Final Assembly:
 * This Gateway Filter intercepts the backend response and applies the
 * context-aware field masking logic using the FieldFilterService.
 * It expects 'FetchForbiddenFieldsGatewayFilterFactory' to have already
 * fetched the rules and placed them in the Exchange Attributes.
 */
@Component
@Slf4j
public class FieldFilterGatewayFilterFactory
        extends AbstractResponsePayloadModifierFilterFactory<FieldFilterGatewayFilterFactory.Config, String, String> {

    private final FieldFilterService fieldFilterService;
    private final QueryStringTargetAnalyzer queryStringTargetAnalyzer;
    private final ObjectMapper objectMapper;

    public FieldFilterGatewayFilterFactory(
            ObjectMapper objectMapper,
            FieldFilterService fieldFilterService,
            QueryStringTargetAnalyzer queryStringTargetAnalyzer) {
        // Pass Config class, Input Class (String JSON), Output Class (String JSON) to base
        super(Config.class, String.class, String.class);
        
        this.fieldFilterService = fieldFilterService;
        this.queryStringTargetAnalyzer = queryStringTargetAnalyzer;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyResponsePayload(Config config, ServerWebExchange exchange, String payload) {

        // 1. Retrieve the library from Exchange Attributes
        // (Populated by FetchForbiddenFieldsGatewayFilterFactory in the request phase)
        ForbiddenFieldsLibrary library = exchange.getAttribute(FetchForbiddenFieldsGatewayFilterFactory.GATEWAY_CONTEXT_FORBIDDEN_FIELDS);

        // 2. Check if this is a kind-alias route — _kind must be stripped from the response
        //    because kind is implied by the URL path segment and is invisible to clients.
        KindAliasConfigAttr kindAliasAttr = exchange.getAttribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR);
        boolean isKindAlias = kindAliasAttr != null && kindAliasAttr.isKindAliasConfigured();

        boolean hasFieldRules = library != null && library.getRules() != null && !library.getRules().isEmpty();

        // 3. Fail-Fast: nothing to do if neither field-masking nor kind-alias stripping is needed.
        if (!isKindAlias && !hasFieldRules) {
            log.trace("No forbidden field rules and not a kind-alias route. Skipping filter.");
            return Mono.just(payload);
        }

        // 4. Identify Targets: Analyze Query String for Includes & Lookups
        // This tells the service exactly where to look for relational data to avoid full scan.
        List<String> targetPaths = resolveEffectiveTargetPaths(exchange);

        // 4.1 Identify Lookup Constraints: For Polymorphic Lookup Audit
        // This map tells us which lookup properties were filtered by which fields.
        Map<String, Set<String>> lookupConstraints = queryStringTargetAnalyzer.resolveLookupConstraints(exchange.getRequest().getQueryParams());

        try {
            // 5. Deserialize: Convert JSON String to Java Object (Map or List)
            // We use Object.class to handle both Single Record (Map) and Collection (List) responses dynamically.
            Object data = objectMapper.readValue(payload, Object.class);

            // 6. Apply OPA-driven field masking (if rules exist)
            if (hasFieldRules) {
                fieldFilterService.filterPayload(data, library, targetPaths, lookupConstraints);
            }

            // 7. Strip _kind from all records on kind-alias routes.
            //    _kind is implied by the URL path — it must not leak into responses seen by clients.
            //    Applies to root records and every nested include/lookup target.
            if (isKindAlias) {
                fieldFilterService.stripKindField(data, targetPaths);
            }

            // 8. Serialize: Convert back to JSON String
            return Mono.just(objectMapper.writeValueAsString(data));

        } catch (IOException e) {
            log.error("Error processing JSON payload during field filtering: {}", e.getMessage(), e);
            // In case of parsing error, return original payload to avoid breaking valid but unparsable responses.
            return Mono.just(payload);
        }
    }

    public static class Config {
        // No specific configuration needed as we rely on context attributes
    }

    private List<String> resolveEffectiveTargetPaths(ServerWebExchange exchange) {
        List<String> targetPaths = queryStringTargetAnalyzer.resolveTargetFields(exchange.getRequest().getQueryParams());
        IncludeAliasProjectionAttr projectionAttr = exchange.getAttribute(IncludeAliasProjectionAttr.INCLUDE_ALIAS_PROJECTION_ATTR);

        if (projectionAttr == null || projectionAttr.getRules() == null || projectionAttr.getRules().isEmpty()) {
            return targetPaths;
        }

        Set<String> expandedTargets = new LinkedHashSet<>(targetPaths);
        for (IncludeAliasProjectionAttr.Rule rule : projectionAttr.getRules()) {
            if (rule.getAlias() != null && !rule.getAlias().isBlank()) {
                expandedTargets.add(rule.getAlias());
            }
            if (rule.getGenericRelation() != null && !rule.getGenericRelation().isBlank()) {
                expandedTargets.add(rule.getGenericRelation());
            }
        }

        return new ArrayList<>(expandedTargets);
    }
}