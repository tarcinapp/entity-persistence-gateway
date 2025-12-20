package com.tarcinapp.entitypersistencegateway.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * Marker interface for exceptions that carry a structured error body
 * to be written directly to the HTTP response by the global handler.
 */
public interface ErrorBodyCarrier {
    /**
     * HTTP status to return.
     */
    HttpStatusCode getStatusCode();

    /**
     * Serialized error body (usually JSON) to write.
     */
    String getErrorResponseJson();

    /**
     * Content type of the error body. Defaults to application/json.
     */
    default MediaType getContentType() {
        return MediaType.APPLICATION_JSON;
    }
}
