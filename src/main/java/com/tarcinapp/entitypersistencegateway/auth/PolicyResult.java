package com.tarcinapp.entitypersistencegateway.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PolicyResult {
    
    private boolean allow;
    private String reason;

    public boolean isAllow() {
        return this.allow;
    }

    @JsonProperty(value="allow")
    public void setAllow(boolean allow) {
        this.allow = allow;
    }

    public String getReason() {
        return this.reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

}