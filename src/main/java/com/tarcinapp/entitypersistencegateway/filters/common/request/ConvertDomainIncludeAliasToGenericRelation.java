package com.tarcinapp.entitypersistencegateway.filters.common.request;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.tarcinapp.entitypersistencegateway.IncludeAliasProjectionAttr;
import com.tarcinapp.entitypersistencegateway.config.OpenApiProperties;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ConvertDomainIncludeAliasToGenericRelation
        extends AbstractGatewayFilterFactory<ConvertDomainIncludeAliasToGenericRelation.Config> {

    private static final String RELATION_SUFFIX = "[relation]";
    private static final Set<String> GENERIC_RELATIONS = Set.of("_entities", "_reactions");

    private static final Pattern INCLUDE_RELATION_KEY_PATTERN = Pattern.compile(
            "^filter\\[include\\]\\[\\d+\\](?:\\[scope\\]\\[include\\]\\[\\d+\\])*\\[relation\\]$");

    private final OpenApiProperties openApiProperties;

    public ConvertDomainIncludeAliasToGenericRelation(OpenApiProperties openApiProperties) {
        super(Config.class);
        this.openApiProperties = openApiProperties;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            if (openApiProperties == null || openApiProperties.getControllers() == null) {
                return chain.filter(exchange);
            }

            MultiValueMap<String, String> originalParams = exchange.getRequest().getQueryParams();
            MultiValueMap<String, String> newParams = new LinkedMultiValueMap<>();
            List<IncludeAliasProjectionAttr.Rule> projectionRules = new ArrayList<>();

            // Start from a shallow copy. We mutate keys for where-wrapping when needed.
            originalParams.forEach((key, values) -> newParams.addAll(key, values));

            for (Map.Entry<String, List<String>> entry : originalParams.entrySet()) {
                String key = entry.getKey();
                if (!isIncludeRelationKey(key)) {
                    continue;
                }

                List<String> values = entry.getValue();
                if (values == null || values.isEmpty()) {
                    continue;
                }

                String relationValue = values.get(0);
                if (relationValue == null || relationValue.isBlank() || GENERIC_RELATIONS.contains(relationValue)) {
                    continue;
                }

                Resolution resolution = resolveAlias(relationValue);
                if (resolution == null) {
                    continue;
                }

                // Replace relation alias with generic relation key.
                newParams.remove(key);
                newParams.add(key, resolution.genericRelation);

                String includePrefix = key.substring(0, key.length() - RELATION_SUFFIX.length());
                String wherePrefix = includePrefix + "[scope][where]";

                if (hasWhereClause(newParams, wherePrefix)) {
                    wrapExistingWhereUnderAnd(newParams, wherePrefix);
                    newParams.add(wherePrefix + "[and][0][_kind]", resolution.kind);
                } else {
                    newParams.add(wherePrefix + "[_kind]", resolution.kind);
                }

                projectionRules.add(new IncludeAliasProjectionAttr.Rule(
                        resolution.genericRelation,
                        resolution.alias,
                        resolution.kind));
            }

            if (projectionRules.isEmpty()) {
                return chain.filter(exchange);
            }

            exchange.getAttributes().put(
                    IncludeAliasProjectionAttr.INCLUDE_ALIAS_PROJECTION_ATTR,
                    new IncludeAliasProjectionAttr(projectionRules));

            URI newUri = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                    .replaceQueryParams(newParams)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            if (log.isDebugEnabled()) {
                try {
                    String encodedQuery = UriComponentsBuilder.newInstance().queryParams(newParams).build().encode().getQuery();
                    String decoded = encodedQuery == null
                            ? ""
                            : java.net.URLDecoder.decode(encodedQuery, StandardCharsets.UTF_8.name());
                    log.debug("Converted domain include aliases. New query: {}", decoded);
                } catch (Exception e) {
                    log.debug("Converted domain include aliases. New URI: {}", newUri);
                }
            }

            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(req -> req.uri(newUri))
                    .build();

            return chain.filter(modifiedExchange);
        };
    }

    private boolean isIncludeRelationKey(String key) {
        Matcher matcher = INCLUDE_RELATION_KEY_PATTERN.matcher(key);
        return matcher.matches();
    }

    private boolean hasWhereClause(MultiValueMap<String, String> params, String wherePrefix) {
        String start = wherePrefix + "[";
        for (String key : params.keySet()) {
            if (key.startsWith(start)) {
                return true;
            }
        }
        return false;
    }

    private void wrapExistingWhereUnderAnd(MultiValueMap<String, String> params, String wherePrefix) {
        MultiValueMap<String, String> rewritten = new LinkedMultiValueMap<>();
        String sourceStart = wherePrefix + "[";

        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            String targetKey = key;

            if (key.startsWith(sourceStart) && !key.startsWith(wherePrefix + "[and][")) {
                targetKey = wherePrefix + "[and][1]" + key.substring(wherePrefix.length());
            }

            rewritten.addAll(targetKey, entry.getValue());
        }

        params.clear();
        params.addAll(rewritten);
    }

    private Resolution resolveAlias(String alias) {
        List<Resolution> matches = new ArrayList<>();

        openApiProperties.getControllers().forEach((controllerName, controllerConfig) -> {
            if (controllerConfig == null || controllerConfig.getAliases() == null) {
                return;
            }

            controllerConfig.getAliases().forEach(aliasConfig -> {
                if (aliasConfig == null || aliasConfig.getAlias() == null || aliasConfig.getKind() == null) {
                    return;
                }

                if (alias.equals(aliasConfig.getAlias())) {
                    String genericRelation = resolveGenericRelation(controllerName);
                    if (genericRelation != null) {
                        matches.add(new Resolution(genericRelation, aliasConfig.getAlias(), aliasConfig.getKind()));
                    }
                }
            });
        });

        if (matches.isEmpty()) {
            return null;
        }

        if (matches.size() > 1) {
            log.warn("Ambiguous include alias '{}' found in multiple controllers. Skipping transformation.", alias);
            return null;
        }

        return matches.get(0);
    }

    private String resolveGenericRelation(String controllerName) {
        if (controllerName == null) {
            return null;
        }

        String normalized = controllerName.toLowerCase();
        if (normalized.contains("reaction")) {
            return "_reactions";
        }

        // entities, lists, relations and through-style alias collections resolve to generic entities relation bucket
        return "_entities";
    }

    private static class Resolution {
        private final String genericRelation;
        private final String alias;
        private final String kind;

        private Resolution(String genericRelation, String alias, String kind) {
            this.genericRelation = genericRelation;
            this.alias = alias;
            this.kind = kind;
        }
    }

    public static class Config {
    }
}
