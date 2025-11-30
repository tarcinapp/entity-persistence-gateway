package com.tarcinapp.entitypersistencegateway.clients.opa;

import java.util.LinkedHashMap;

import lombok.Data;

@Data
public class GenericPolicyResponse {
    
    private LinkedHashMap<String, Object> result;
}
