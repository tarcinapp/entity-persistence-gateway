package com.tarcinapp.entitypersistencegateway.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PolicyResult {
    
    private boolean allow;

    public boolean isAllow() {
        return this.allow;
    }

    @JsonProperty(value="allow")
    public void setAllow(boolean allow) {
        this.allow = allow;
    }
}