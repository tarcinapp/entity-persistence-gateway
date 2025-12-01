package com.tarcinapp.entitypersistencegateway.helpers;

import java.util.Set;

import com.networknt.schema.ValidationMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class JsonValidationException extends RuntimeException {

    private Set<ValidationMessage> errors;

    public JsonValidationException() {
    } 
}
