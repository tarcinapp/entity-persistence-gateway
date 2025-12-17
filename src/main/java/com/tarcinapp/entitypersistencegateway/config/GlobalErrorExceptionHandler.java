package com.tarcinapp.entitypersistencegateway.config;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Global Error Exception Handler for Spring Cloud Gateway (WebFlux).
 * 
 * This handler catches all exceptions that bubble up from the filter chain
 * and returns a consistent JSON error response to the client.
 * 
 * It follows the "Error Isolation" principle where filters catch their own
 * local errors but propagate downstream/unexpected errors up the chain
 * to be handled here.
 * 
 * Order -2 ensures this runs before the default ResponseStatusExceptionHandler (Order 0)
 * and before DefaultErrorWebExceptionHandler (Order -1).
 */
@Slf4j
@Order(-2)
@Component
public class GlobalErrorExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // DEBUG: Log exception chain to diagnose wrapping issues
        log.debug("GlobalErrorExceptionHandler received exception: {} - {}", 
                ex.getClass().getName(), ex.getMessage());
        Throwable debugCause = ex.getCause();
        int depth = 1;
        while (debugCause != null) {
            log.debug("  Cause chain [{}]: {} - {}", depth, 
                    debugCause.getClass().getName(), debugCause.getMessage());
            debugCause = debugCause.getCause();
            depth++;
        }

        // Find the most relevant exception (unwrap if needed)
        Throwable relevantError = findRelevantException(ex);
        log.debug("Relevant exception determined: {} - {}", 
                relevantError.getClass().getName(), relevantError.getMessage());

        // Determine HTTP status code
        HttpStatusCode statusCode = determineHttpStatus(ex);
        HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.debug("Determined HTTP status: {}", statusCode);

        // Log the error with context
        logError(exchange, relevantError, httpStatus);

        // Build the error response body
        Map<String, Object> errorBody = buildErrorBody(exchange, relevantError, httpStatus);

        // Set response status and content type
        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Write JSON response
        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(errorBody);
            org.springframework.core.io.buffer.DataBuffer buffer = 
                    exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to write error response", e);
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * Determines the HTTP status code based on the exception type.
     * Unwraps the exception chain to find ResponseStatusException if present.
     * 
     * @param error The exception that was thrown
     * @return The appropriate HTTP status code
     */
    private HttpStatusCode determineHttpStatus(Throwable error) {
        // Check the exception itself
        if (error instanceof ResponseStatusException) {
            return ((ResponseStatusException) error).getStatusCode();
        }
        
        // Check the cause chain for wrapped ResponseStatusException
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof ResponseStatusException) {
                return ((ResponseStatusException) cause).getStatusCode();
            }
            cause = cause.getCause();
        }
        
        // Default to 500 Internal Server Error for unknown exceptions
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
    
    /**
     * Finds the most relevant exception in the cause chain.
     * Prefers ResponseStatusException if found.
     * 
     * @param error The root exception
     * @return The most relevant exception for error reporting
     */
    private Throwable findRelevantException(Throwable error) {
        if (error instanceof ResponseStatusException) {
            return error;
        }
        
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof ResponseStatusException) {
                return cause;
            }
            cause = cause.getCause();
        }
        
        return error;
    }

    /**
     * Logs the error with request context information.
     * 
     * @param exchange The server web exchange
     * @param error The exception that was thrown
     * @param httpStatus The determined HTTP status
     */
    private void logError(ServerWebExchange exchange, Throwable error, HttpStatus httpStatus) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        String requestId = exchange.getRequest().getId();

        if (httpStatus.is5xxServerError()) {
            log.error("Global Error Handler - {} {} [requestId={}] - Status: {} - Error: {}",
                    method, path, requestId, httpStatus.value(), error.getMessage(), error);
        } else if (httpStatus.is4xxClientError()) {
            log.warn("Global Error Handler - {} {} [requestId={}] - Status: {} - Error: {}",
                    method, path, requestId, httpStatus.value(), error.getMessage());
        } else {
            log.info("Global Error Handler - {} {} [requestId={}] - Status: {} - Error: {}",
                    method, path, requestId, httpStatus.value(), error.getMessage());
        }
    }

    /**
     * Builds a structured JSON error response body.
     * 
     * @param exchange The server web exchange
     * @param error The exception that was thrown
     * @param httpStatus The determined HTTP status
     * @return A map representing the JSON error response
     */
    private Map<String, Object> buildErrorBody(ServerWebExchange exchange, Throwable error, HttpStatus httpStatus) {
        Map<String, Object> errorBody = new LinkedHashMap<>();
        
        // Timestamp in ISO 8601 format
        errorBody.put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        
        // Request path
        errorBody.put("path", exchange.getRequest().getPath().value());
        
        // HTTP status code
        errorBody.put("status", httpStatus.value());
        
        // HTTP status reason phrase
        errorBody.put("error", httpStatus.getReasonPhrase());
        
        // Error message - use the exception message or a default
        String message = extractErrorMessage(error);
        errorBody.put("message", message);
        
        // Request ID for traceability
        errorBody.put("requestId", exchange.getRequest().getId());
        
        return errorBody;
    }

    /**
     * Extracts a meaningful error message from the exception.
     * 
     * @param error The exception
     * @return A user-friendly error message
     */
    private String extractErrorMessage(Throwable error) {
        if (error instanceof ResponseStatusException) {
            ResponseStatusException rse = (ResponseStatusException) error;
            String reason = rse.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        
        return "An unexpected error occurred";
    }
}
