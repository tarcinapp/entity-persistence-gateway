package com.tarcinapp.entitypersistencegateway.clients.opa;

import com.tarcinapp.entitypersistencegateway.authorization.PolicyResult;

public class PolicyResponse {
    
    private PolicyResult result;

    public PolicyResult getResult() {
        return this.result;
    }

    public void setResult(PolicyResult result) {
        this.result = result;
    }

}
