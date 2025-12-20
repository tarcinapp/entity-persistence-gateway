package com.tarcinapp.entitypersistencegateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.exceptions.ErrorBodyCarrier;
import com.tarcinapp.entitypersistencegateway.services.RequestIdService;
import lombok.extern.slf4j.Slf4j;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global Error Exception Handler for Spring Cloud Gateway (WebFlux).
 *
 * Responsibilities:
 * 1. Catch all exceptions from filters (Auth, Validation, System).
 * 2. Format errors to match the Backend Error Contract ({ "error": { ... } }).
 * 3. Ensure Request ID traceability is always present in error bodies for consistency.
 * * NOTE ON BACKEND ERRORS: 
 * This handler catches internal Gateway exceptions and connectivity issues (502, 504).
 * To process business errors returned by the backend (4xx, 5xx), a separate 
 * ModifyResponseBody filter is required to "stamp" those responses with the requestId.
 */
@Slf4j
@Order(-2)
@Component
public class GlobalErrorExceptionHandler implements WebExceptionHandler {

    private final ObjectMapper objectMapper;
    private final RequestIdService requestIdService;

    // Type reference for JSON manipulation
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    public GlobalErrorExceptionHandler(ObjectMapper objectMapper, RequestIdService requestIdService) {
        this.objectMapper = objectMapper;
        this.requestIdService = requestIdService;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        
        // 1. Resolve Safe Request ID via Central Service
        // This ensures the ID is available for both logging and body injection.
        String requestId = requestIdService.resolveOrCreateId(exchange);

        // 2. Resolve Exception & Status
        Throwable relevantError = findRelevantException(ex);
        HttpStatusCode statusCode = determineHttpStatus(relevantError);
        HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // 3. Special Case: Exception carries its own pre-formatted body (e.g. Validation)
        // We still need to ensure requestId is present in these bodies for consistency.
        if (relevantError instanceof ErrorBodyCarrier) {
            return handleCustomBodyError(exchange, (ErrorBodyCarrier) relevantError, requestId);
        }

        // 4. Standard Case: Generate Backend-Compliant JSON for generic errors
        return handleStandardError(exchange, relevantError, httpStatus, requestId);
    }

    /**
     * Handles exceptions with pre-formatted bodies. 
     * Injects requestId into the body if it's missing to ensure consistency with backend contract.
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
            // Standardize: Inject requestId into the 'error' object of the custom body
            Map<String, Object> bodyMap = objectMapper.readValue(rawJson, MAP_TYPE_REF);
            
            if (bodyMap.containsKey("error") && bodyMap.get("error") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorNode = (Map<String, Object>) bodyMap.get("error");
                errorNode.putIfAbsent("requestId", requestId);
                finalJson = objectMapper.writeValueAsString(bodyMap);
            }
        } catch (Exception e) {
            log.warn("Failed to inject requestId into custom error body. Returning original. reqId: {}", requestId);
        }

        byte[] bytes = finalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> handleStandardError(ServerWebExchange exchange, Throwable error, HttpStatus httpStatus, String requestId) {
        logError(exchange, error, httpStatus, requestId);

        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Build Nested Error Body: { "error": { ... } }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> errorNode = new LinkedHashMap<>();

        // -- Mandatory Backend Spec Fields --
        errorNode.put("statusCode", httpStatus.value());
        errorNode.put("name", mapStatusToName(httpStatus));
        errorNode.put("message", extractErrorMessage(error));
        errorNode.put("code", mapStatusToCode(httpStatus));
        
        // -- Infra Fields (Consistency is key here) --
        errorNode.put("requestId", requestId);
        errorNode.put("path", exchange.getRequest().getPath().value());

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

    // --- Helpers ---

    private String mapStatusToName(HttpStatus status) {
        switch (status) {
            case BAD_REQUEST: return "BadRequestError";
            case UNAUTHORIZED: return "UnauthorizedError";
            case FORBIDDEN: return "ForbiddenError";
            case NOT_FOUND: return "NotFoundError";
            case METHOD_NOT_ALLOWED: return "MethodNotAllowedError";
            case NOT_ACCEPTABLE: return "NotAcceptableError";
            case REQUEST_TIMEOUT: return "RequestTimeoutError";
            case CONFLICT: return "ConflictError";
            case UNSUPPORTED_MEDIA_TYPE: return "UnsupportedMediaTypeError";
            case UNPROCESSABLE_ENTITY: return "UnprocessableEntityError";
            case TOO_MANY_REQUESTS: return "LimitExceededError";
            case INTERNAL_SERVER_ERROR: return "InternalServerError";
            case NOT_IMPLEMENTED: return "NotImplementedError";
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
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof ResponseStatusException) {
                return ((ResponseStatusException) cause).getStatusCode();
            }
            cause = cause.getCause();
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

    private String extractErrorMessage(Throwable error) {
        if (error instanceof ResponseStatusException) {
            String reason = ((ResponseStatusException) error).getReason();
            if (reason != null && !reason.isBlank()) return reason;
        }
        return error.getMessage() != null ? error.getMessage() : "An unexpected error occurred.";
    }
}