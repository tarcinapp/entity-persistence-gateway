package com.tarcinapp.entitypersistencegateway.util;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock implementation of GatewayFilterChain for testing filters.
 * Captures the exchange passed through and tracks whether filter was called.
 */
public class MockGatewayFilterChain implements GatewayFilterChain {

    private final AtomicBoolean filterCalled = new AtomicBoolean(false);
    private final AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger(0);
    private Mono<Void> returnValue = Mono.empty();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange) {
        filterCalled.set(true);
        capturedExchange.set(exchange);
        callCount.incrementAndGet();
        return returnValue;
    }

    /**
     * Check if the filter chain's filter method was called.
     */
    public boolean wasFilterCalled() {
        return filterCalled.get();
    }

    /**
     * Get the exchange that was passed to the filter chain.
     */
    public ServerWebExchange getCapturedExchange() {
        return capturedExchange.get();
    }

    /**
     * Get the number of times filter was called.
     */
    public int getCallCount() {
        return callCount.get();
    }

    /**
     * Reset the mock to initial state.
     */
    public void reset() {
        filterCalled.set(false);
        capturedExchange.set(null);
        callCount.set(0);
        returnValue = Mono.empty();
    }

    /**
     * Configure the mock to return a specific Mono when filter is called.
     */
    public MockGatewayFilterChain withReturnValue(Mono<Void> returnValue) {
        this.returnValue = returnValue;
        return this;
    }

    /**
     * Configure the mock to return an error when filter is called.
     */
    public MockGatewayFilterChain withError(Throwable error) {
        this.returnValue = Mono.error(error);
        return this;
    }

    /**
     * Get an attribute from the captured exchange.
     */
    @SuppressWarnings("unchecked")
    public <T> T getCapturedAttribute(String name) {
        ServerWebExchange exchange = capturedExchange.get();
        if (exchange == null) {
            return null;
        }
        return (T) exchange.getAttribute(name);
    }

    /**
     * Check if the captured exchange has an attribute with the given name.
     */
    public boolean hasAttribute(String name) {
        ServerWebExchange exchange = capturedExchange.get();
        return exchange != null && exchange.getAttributes().containsKey(name);
    }
}
