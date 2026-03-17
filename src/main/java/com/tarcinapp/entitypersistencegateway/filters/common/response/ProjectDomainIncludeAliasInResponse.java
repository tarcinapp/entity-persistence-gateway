package com.tarcinapp.entitypersistencegateway.filters.common.response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.IncludeAliasProjectionAttr;
import com.tarcinapp.entitypersistencegateway.filters.base.AbstractResponsePayloadModifierFilterFactory;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ProjectDomainIncludeAliasInResponse extends
        AbstractResponsePayloadModifierFilterFactory<ProjectDomainIncludeAliasInResponse.Config, String, String> {

    private final ObjectMapper objectMapper;

    public ProjectDomainIncludeAliasInResponse(ObjectMapper objectMapper) {
        super(Config.class, String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> modifyResponsePayload(Config config, ServerWebExchange exchange, String payload) {
        IncludeAliasProjectionAttr attr = exchange.getAttribute(IncludeAliasProjectionAttr.INCLUDE_ALIAS_PROJECTION_ATTR);

        if (attr == null || attr.getRules() == null || attr.getRules().isEmpty()) {
            return Mono.just(payload);
        }

        try {
            Object responseData = objectMapper.readValue(payload, Object.class);
            boolean changed = applyRulesRecursively(responseData, attr.getRules());
            if (!changed) {
                return Mono.just(payload);
            }

            return Mono.just(objectMapper.writeValueAsString(responseData));
        } catch (IOException e) {
            log.warn("Failed to project include aliases in response payload: {}", e.getMessage());
            return Mono.just(payload);
        }
    }

    private boolean applyRulesRecursively(Object node, List<IncludeAliasProjectionAttr.Rule> rules) {
        boolean changed = false;

        if (node instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;

            for (IncludeAliasProjectionAttr.Rule rule : rules) {
                Object value = map.get(rule.getGenericRelation());
                if (value == null || map.containsKey(rule.getAlias())) {
                    continue;
                }

                if (isKindCompatible(value, rule.getKind())) {
                    map.remove(rule.getGenericRelation());
                    map.put(rule.getAlias(), value);
                    changed = true;
                }
            }

            for (Object value : map.values()) {
                changed = applyRulesRecursively(value, rules) || changed;
            }
            return changed;
        }

        if (node instanceof List<?> list) {
            for (Object item : list) {
                changed = applyRulesRecursively(item, rules) || changed;
            }
        }

        return changed;
    }

    private boolean isKindCompatible(Object relationValue, String expectedKind) {
        if (relationValue instanceof Map<?, ?> rawMap) {
            Object kind = rawMap.get("_kind");
            return expectedKind.equals(kind);
        }

        if (relationValue instanceof List<?> list) {
            if (list.isEmpty()) {
                return true;
            }

            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawMap)) {
                    return false;
                }

                Object kind = rawMap.get("_kind");
                if (!expectedKind.equals(kind)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    public static class Config {
    }
}
