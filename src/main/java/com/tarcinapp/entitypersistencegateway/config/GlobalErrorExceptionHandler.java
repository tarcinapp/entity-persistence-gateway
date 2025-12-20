package com.tarcinapp.entitypersistencegateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.exceptions.ErrorBodyCarrier;
import com.tarcinapp.entitypersistencegateway.services.RequestIdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global Error Exception Handler for Spring Cloud Gateway (WebFlux).
 * * Updated to respect Spring Boot's standard error properties:
 * - server.error.include-message
 * - server.error.include-stacktrace
 */
@Slf4j
@Order(-2)
@Component
public class GlobalErrorExceptionHandler implements WebExceptionHandler {

    private final ObjectMapper objectMapper;
    private final RequestIdService requestIdService;

    // Standard Spring Error Properties
    @Value("${server.error.include-message:never}")
    private String includeMessage;

    @Value("${server.error.include-stacktrace:never}")
    private String includeStacktrace;

    private static final String ALWAYS = "always";
    private static final String NEVER = "never";

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    public GlobalErrorExceptionHandler(ObjectMapper objectMapper, RequestIdService requestIdService) {
        this.objectMapper = objectMapper;
        this.requestIdService = requestIdService;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        
        String requestId = requestIdService.resolveOrCreateId(exchange);

        Throwable relevantError = findRelevantException(ex);
        HttpStatusCode statusCode = determineHttpStatus(relevantError);
        HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
        
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        if (relevantError instanceof ErrorBodyCarrier) {
            return handleCustomBodyError(exchange, (ErrorBodyCarrier) relevantError, requestId);
        }

        return handleStandardError(exchange, relevantError, httpStatus, requestId);
    }

    /**
     * Handles exceptions with pre-formatted bodies (e.g. Validation).
     * Masks the 'message' field if it's a 5xx error and include-message is set to 'never'.
     */
    private Mono<Void> handleCustomBodyError(ServerWebExchange exchange, ErrorBodyCarrier carrier, String requestId) {
        HttpStatus status = HttpStatus.resolve(carrier.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        logError(exchange, new RuntimeException("Custom Body Error: " + carrier.getErrorResponseJson()), status, requestId);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(carrier.getContentType());

        String rawJson = carrier.getErrorResponseJson();
        String finalJson = rawJson;

        try {
            Map<String, Object> bodyMap = objectMapper.readValue(rawJson, MAP_TYPE_REF);
            
            if (bodyMap.containsKey("error") && bodyMap.get("error") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorNode = (Map<String, Object>) bodyMap.get("error");
                
                errorNode.putIfAbsent("requestId", requestId);

                // Security: Mask message in custom 5xx errors if configured
                if (status.is5xxServerError() && NEVER.equalsIgnoreCase(includeMessage)) {
                    errorNode.put("message", "An internal server error occurred.");
                }

                finalJson = objectMapper.writeValueAsString(bodyMap);
            }
        } catch (Exception e) {
            log.warn("Failed to process custom error body. reqId: {}", requestId);
        }

        byte[] bytes = finalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> handleStandardError(ServerWebExchange exchange, Throwable error, HttpStatus httpStatus, String requestId) {
        logError(exchange, error, httpStatus, requestId);

        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> errorNode = new LinkedHashMap<>();

        errorNode.put("statusCode", httpStatus.value());
        errorNode.put("name", mapStatusToName(httpStatus));
        
        // --- Respect include-message ---
        errorNode.put("message", resolveErrorMessage(error, httpStatus));
        
        errorNode.put("code", mapStatusToCode(httpStatus));
        errorNode.put("requestId", requestId);
        errorNode.put("path", exchange.getRequest().getPath().value());

        // --- Respect include-stacktrace ---
        if (ALWAYS.equalsIgnoreCase(includeStacktrace)) {
            errorNode.put("stacktrace", getStackTrace(error));
        }

        root.put("error", errorNode);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(root);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            log.error("Failed to write error response JSON", e);
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * Resolves the error message based on the status code and security configuration.
     */
    private String resolveErrorMessage(Throwable error, HttpStatus status) {
        // If it's a 5xx error and messages should be hidden, mask it.
        if (status.is5xxServerError() && NEVER.equalsIgnoreCase(includeMessage)) {
            return "An internal server error occurred.";
        }

        if (error instanceof ResponseStatusException) {
            String reason = ((ResponseStatusException) error).getReason();
            if (reason != null && !reason.isBlank()) return reason;
        }
        
        return error.getMessage() != null ? error.getMessage() : "An unexpected error occurred.";
    }

    private String getStackTrace(Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        return sw.toString();
    }

    private String mapStatusToName(HttpStatus status) {
        switch (status) {
            case BAD_REQUEST: return "BadRequestError";
            case UNAUTHORIZED: return "UnauthorizedError";
            case FORBIDDEN: return "ForbiddenError";
            case NOT_FOUND: return "NotFoundError";
            case METHOD_NOT_ALLOWED: return "MethodNotAllowedError";
            case UNPROCESSABLE_ENTITY: return "UnprocessableEntityError";
            case TOO_MANY_REQUESTS: return "LimitExceededError";
            case INTERNAL_SERVER_ERROR: return "InternalServerError";
            case BAD_GATEWAY: return "BadGatewayError";
            case SERVICE_UNAVAILABLE: return "ServiceUnavailableError";
            case GATEWAY_TIMEOUT: return "GatewayTimeoutError";
            default: return "UnknownError";
        }
    }

    private String mapStatusToCode(HttpStatus status) {
        return "GATEWAY-" + status.name();
    }

    private HttpStatusCode determineHttpStatus(Throwable error) {
        if (error instanceof ResponseStatusException) {
            return ((ResponseStatusException) error).getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private Throwable findRelevantException(Throwable error) {
        if (error instanceof ResponseStatusException || error instanceof ErrorBodyCarrier) {
            return error;
        }
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof ResponseStatusException || cause instanceof ErrorBodyCarrier) {
                return cause;
            }
            cause = cause.getCause();
        }
        return error;
    }

    private void logError(ServerWebExchange exchange, Throwable error, HttpStatus httpStatus, String requestId) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        
        if (httpStatus.is5xxServerError()) {
            log.error("Global Error - {} {} [reqId={}] - Status: {} - Error: {}",
                    method, path, requestId, httpStatus.value(), error.getMessage(), error);
        } else {
            log.warn("Global Error - {} {} [reqId={}] - Status: {} - Error: {}",
                    method, path, requestId, httpStatus.value(), error.getMessage());
        }
    }
}