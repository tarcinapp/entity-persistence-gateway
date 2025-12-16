package com.tarcinapp.entitypersistencegateway.filters.common.response;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

/**
 * The Final Assembly:
 * This Gateway Filter intercepts the backend response and applies the
 * context-aware field masking logic using the FieldFilterService.
 * * Unlike the previous version, this filter does NOT call OPA.
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

        // 2. Fail-Fast: If no rules found in context, skip processing.
        if (library == null || library.getRules() == null || library.getRules().isEmpty()) {
            log.trace("No forbidden field rules found in context. Skipping filter.");
            return Mono.just(payload);
        }

        // 3. Identify Targets: Analyze Query String for Includes & Lookups
        // This tells the service exactly where to look for relational data to avoid full scan.
        List<String> targetPaths = queryStringTargetAnalyzer.resolveTargetFields(exchange.getRequest().getQueryParams());
        
        try {
            // 4. Deserialize: Convert JSON String to Java Object (Map or List)
            // We use Object.class to handle both Single Record (Map) and Collection (List) responses dynamically.
            Object data = objectMapper.readValue(payload, Object.class);

            // 5. Execute: Call the Surgeon to clean the data
            Object filteredData = fieldFilterService.filterPayload(data, library, targetPaths);

            // 6. Serialize: Convert back to JSON String
            return Mono.just(objectMapper.writeValueAsString(filteredData));

        } catch (IOException e) {
            log.error("Error processing JSON payload during field filtering: {}", e.getMessage(), e);
            // In case of parsing error, return original payload to avoid breaking valid but unparsable responses.
            return Mono.just(payload);
        }
    }

    public static class Config {
        // No specific configuration needed as we rely on context attributes
    }
}