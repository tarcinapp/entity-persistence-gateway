package com.tarcinapp.entitypersistencegateway.clients.opa;

import java.util.LinkedHashMap;

public class GenericPolicyResponse {
    
    private LinkedHashMap<String, Object> result;

    public LinkedHashMap<String, Object> getResult() {
        return this.result;
    }

    public void setResult(LinkedHashMap<String, Object> result) {
        this.result = result;
    }

}
