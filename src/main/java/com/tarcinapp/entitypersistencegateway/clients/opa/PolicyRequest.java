package com.tarcinapp.entitypersistencegateway.clients.opa;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

public class PolicyRequest {
    
    private PolicyData input;

    public PolicyData getInput() {
        return this.input;
    }

    public void setInput(PolicyData input) {
        this.input = input;
    }
}
