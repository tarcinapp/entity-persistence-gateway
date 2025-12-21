package com.tarcinapp.entitypersistencegateway.util;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Fluent builder for creating mock ServerWebExchange instances for testing.
 */
public class MockExchangeBuilder {

    private HttpMethod method = HttpMethod.GET;
    private String path = "/api/v1/entities";
    private String body = "";
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private String routeId = "testRoute";
    private String recordType = "entities";

    public static MockExchangeBuilder create() {
        return new MockExchangeBuilder();
    }

    public MockExchangeBuilder method(HttpMethod method) {
        this.method = method;
        return this;
    }

    public MockExchangeBuilder get() {
        return method(HttpMethod.GET);
    }

    public MockExchangeBuilder post() {
        return method(HttpMethod.POST);
    }

    public MockExchangeBuilder put() {
        return method(HttpMethod.PUT);
    }

    public MockExchangeBuilder patch() {
        return method(HttpMethod.PATCH);
    }

    public MockExchangeBuilder delete() {
        return method(HttpMethod.DELETE);
    }

    public MockExchangeBuilder path(String path) {
        this.path = path;
        return this;
    }

    public MockExchangeBuilder body(String body) {
        this.body = body;
        return this;
    }

    public MockExchangeBuilder header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    public MockExchangeBuilder authorization(String token) {
        return header("Authorization", "Bearer " + token);
    }

    public MockExchangeBuilder contentType(MediaType mediaType) {
        return header("Content-Type", mediaType.toString());
    }

    public MockExchangeBuilder attribute(String name, Object value) {
        this.attributes.put(name, value);
        return this;
    }

    public MockExchangeBuilder queryParam(String name, String value) {
        this.queryParams.put(name, value);
        return this;
    }

    public MockExchangeBuilder routeId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public MockExchangeBuilder recordType(String recordType) {
        this.recordType = recordType;
        return this;
    }

    public MockExchangeBuilder withSecurityContext(Object securityContext) {
        return attribute("securityContext", securityContext);
    }

    public MockExchangeBuilder withCachedRequestBody(String body) {
        return attribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR, body);
    }

    public ServerWebExchange build() {
        StringBuilder uriBuilder = new StringBuilder(path);
        if (!queryParams.isEmpty()) {
            uriBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) {
                    uriBuilder.append("&");
                }
                uriBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }

        MockServerHttpRequest.BodyBuilder requestBuilder = MockServerHttpRequest
                .method(method, URI.create(uriBuilder.toString()));

        headers.forEach(requestBuilder::header);

        if (!body.isEmpty()) {
            requestBuilder.contentType(MediaType.APPLICATION_JSON);
        }

        MockServerHttpRequest request = body.isEmpty()
                ? requestBuilder.build()
                : requestBuilder.body(body);

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Add route metadata
        Route route = Route.async()
                .id(routeId)
                .uri(URI.create("http://localhost:8080"))
                .predicate(e -> true)
                .metadata("recordType", recordType)
                .build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        // Add custom attributes
        attributes.forEach((k, v) -> exchange.getAttributes().put(k, v));

        return exchange;
    }
}
