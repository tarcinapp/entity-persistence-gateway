package com.tarcinapp.entitypersistencegateway.helpers;

import java.util.Set;

import com.networknt.schema.ValidationMessage;

public class JsonValidationException extends RuntimeException {

    private Set<ValidationMessage> errors;

    public JsonValidationException() {
    }

    public JsonValidationException(Set<ValidationMessage> errors) {
        this.errors = errors;
    }
    
    public Set<ValidationMessage> getErrors() {
        return errors;
    }

    public void setErrors(Set<ValidationMessage> errors) {
        this.errors = errors;
    }

    
}
