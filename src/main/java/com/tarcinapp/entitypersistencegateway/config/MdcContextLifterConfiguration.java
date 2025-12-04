package com.tarcinapp.entitypersistencegateway.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class that enables MDC (Mapped Diagnostic Context) propagation
 * across reactive streams in Project Reactor.
 * 
 * <p>In reactive programming, the execution can switch between different threads,
 * which means the traditional ThreadLocal-based MDC values would be lost.
 * This configuration ensures that MDC values are properly propagated through
 * the Reactor context, maintaining logging correlation across async boundaries.</p>
 * 
 * <p>The MDC values are stored in the Reactor context under the key "MDC_CONTEXT_MAP"
 * and are automatically restored when execution continues on a different thread.</p>
 * 
 * <p>Two request IDs are maintained:</p>
 * <ul>
 *   <li>{@code SpringRequestId} - The original Spring WebFlux request ID (available from the start)</li>
 *   <li>{@code RequestId} - The custom generated request ID (available after authentication)</li>
 * </ul>
 */
@Slf4j
@Configuration
public class MdcContextLifterConfiguration {

    /**
     * The key used to store MDC context map in Reactor's context.
     * This key is used by filters to put/get MDC values.
     */
    public static final String MDC_CONTEXT_MAP = "MDC_CONTEXT_MAP";

    /**
     * MDC key for the Spring-generated request ID.
     * This is available from the very start of request processing.
     */
    public static final String SPRING_REQUEST_ID_MDC_KEY = "SpringRequestId";

    /**
     * MDC key for the custom generated request ID (after authentication).
     */
    public static final String REQUEST_ID_MDC_KEY = "RequestId";

    /**
     * Exchange attribute key for storing MDC context map.
     * This allows retrieval from any filter, including response decorators.
     */
    public static final String MDC_CONTEXT_ATTR = "MDC_CONTEXT_ATTR";

    /**
     * Indicator value used when logging occurs outside of a request context.
     */
    public static final String NO_REQUEST_INDICATOR = "NO_REQUEST";

    private static final String MDC_CONTEXT_REACTOR_KEY = MdcContextLifterConfiguration.class.getName();

    @PostConstruct
    public void setupMdcContextLifter() {
        log.info("Setting up MDC context lifter for reactive streams");
        
        Hooks.onEachOperator(MDC_CONTEXT_REACTOR_KEY,
            Operators.lift((scannable, coreSubscriber) -> new MdcContextLifter<>(coreSubscriber)));
    }

    @PreDestroy
    public void cleanupMdcContextLifter() {
        log.info("Cleaning up MDC context lifter");
        Hooks.resetOnEachOperator(MDC_CONTEXT_REACTOR_KEY);
    }

    /**
     * A CoreSubscriber wrapper that lifts MDC context from Reactor context
     * to the thread-local MDC before each signal and cleans up afterwards.
     */
    private static class MdcContextLifter<T> implements CoreSubscriber<T> {
        
        private final CoreSubscriber<T> coreSubscriber;

        MdcContextLifter(CoreSubscriber<T> coreSubscriber) {
            this.coreSubscriber = coreSubscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            copyToMdc(coreSubscriber.currentContext());
            coreSubscriber.onSubscribe(subscription);
        }

        @Override
        public void onNext(T t) {
            copyToMdc(coreSubscriber.currentContext());
            coreSubscriber.onNext(t);
        }

        @Override
        public void onError(Throwable throwable) {
            copyToMdc(coreSubscriber.currentContext());
            coreSubscriber.onError(throwable);
        }

        @Override
        public void onComplete() {
            copyToMdc(coreSubscriber.currentContext());
            coreSubscriber.onComplete();
        }

        @Override
        public Context currentContext() {
            return coreSubscriber.currentContext();
        }

        /**
         * Copies MDC context from Reactor context to the current thread's MDC.
         * If no MDC context is found in Reactor context, the current MDC is cleared.
         */
        private void copyToMdc(Context context) {
            if (context.isEmpty()) {
                MDC.clear();
                return;
            }

            if (!context.hasKey(MDC_CONTEXT_MAP)) {
                MDC.clear();
                return;
            }

            Map<String, String> mdcContext = context.get(MDC_CONTEXT_MAP);
            
            if (mdcContext == null || mdcContext.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdcContext);
            }
        }
    }

    /**
     * Utility method to put MDC context into a Reactor Context.
     * Use this in filters to store MDC values that should be propagated.
     * 
     * @param context The current Reactor context
     * @param mdcContextMap The MDC key-value pairs to store
     * @return A new Context with the MDC values stored
     */
    public static Context putMdcContext(Context context, Map<String, String> mdcContextMap) {
        return context.put(MDC_CONTEXT_MAP, mdcContextMap);
    }

    /**
     * Utility method to get MDC context from a Reactor Context.
     * 
     * @param context The Reactor context
     * @return The MDC context map, or null if not present
     */
    public static Map<String, String> getMdcContext(Context context) {
        if (context.hasKey(MDC_CONTEXT_MAP)) {
            return context.get(MDC_CONTEXT_MAP);
        }
        return null;
    }

    /**
     * Sets MDC context in both the exchange attributes and the current thread's MDC.
     * This method should be used when MDC values need to be accessible from 
     * response decorators and other places where Reactor context is not available.
     * 
     * @param exchange The server web exchange
     * @param springRequestId The Spring-generated request ID
     * @param requestId The custom generated request ID (can be null before authentication)
     */
    public static void setMdcInExchange(ServerWebExchange exchange, String springRequestId, String requestId) {
        Map<String, String> mdcContext = new HashMap<>();
        mdcContext.put(SPRING_REQUEST_ID_MDC_KEY, springRequestId);
        mdcContext.put(REQUEST_ID_MDC_KEY, requestId != null ? requestId : NO_REQUEST_INDICATOR);
        
        exchange.getAttributes().put(MDC_CONTEXT_ATTR, mdcContext);
        
        // Also set in current thread's MDC
        MDC.setContextMap(mdcContext);
    }

    /**
     * Updates the custom request ID in both exchange attributes and current thread's MDC.
     * This preserves the Spring request ID while updating the custom one.
     * 
     * @param exchange The server web exchange
     * @param requestId The new custom request ID
     */
    public static void updateRequestIdInExchange(ServerWebExchange exchange, String requestId) {
        @SuppressWarnings("unchecked")
        Map<String, String> mdcContext = (Map<String, String>) exchange.getAttributes().get(MDC_CONTEXT_ATTR);
        
        if (mdcContext == null) {
            mdcContext = new HashMap<>();
            mdcContext.put(SPRING_REQUEST_ID_MDC_KEY, exchange.getRequest().getId());
        }
        
        mdcContext.put(REQUEST_ID_MDC_KEY, requestId);
        exchange.getAttributes().put(MDC_CONTEXT_ATTR, mdcContext);
        
        // Also update current thread's MDC
        MDC.setContextMap(mdcContext);
    }

    /**
     * Restores MDC from exchange attributes to the current thread's MDC.
     * This should be called at the beginning of any code that logs and may
     * run on a different thread (e.g., response decorators).
     * 
     * @param exchange The server web exchange
     */
    public static void restoreMdcFromExchange(ServerWebExchange exchange) {
        @SuppressWarnings("unchecked")
        Map<String, String> mdcContext = (Map<String, String>) exchange.getAttributes().get(MDC_CONTEXT_ATTR);
        
        if (mdcContext != null && !mdcContext.isEmpty()) {
            MDC.setContextMap(mdcContext);
        }
    }

    /**
     * Gets MDC context map from exchange attributes.
     * 
     * @param exchange The server web exchange
     * @return The MDC context map, or null if not present
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> getMdcFromExchange(ServerWebExchange exchange) {
        return (Map<String, String>) exchange.getAttributes().get(MDC_CONTEXT_ATTR);
    }
}
