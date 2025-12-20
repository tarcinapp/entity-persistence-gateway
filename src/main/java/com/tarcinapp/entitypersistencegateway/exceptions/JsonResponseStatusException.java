package com.tarcinapp.entitypersistencegateway.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A ResponseStatusException that carries a JSON error body to be written as-is.
 * This is a generic utility exception utilized by filters when they need to return
 * a complex JSON error response (e.g. Validation Errors) instead of a simple message.
 */
public class JsonResponseStatusException extends ResponseStatusException implements ErrorBodyCarrier {
    private final String errorResponseJson;

    public JsonResponseStatusException(HttpStatus status, String errorResponseJson) {
        super(status, null);
        this.errorResponseJson = errorResponseJson;
    }

    public JsonResponseStatusException(HttpStatusCode statusCode, String errorResponseJson) {
        super(statusCode, null);
        this.errorResponseJson = errorResponseJson;
    }

    @Override
    public String getErrorResponseJson() {
        return errorResponseJson;
    }
}