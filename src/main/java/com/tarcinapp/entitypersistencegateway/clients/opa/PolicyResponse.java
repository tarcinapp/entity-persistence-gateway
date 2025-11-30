package com.tarcinapp.entitypersistencegateway.clients.opa;

import com.tarcinapp.entitypersistencegateway.auth.PolicyResult;

import lombok.Data;

@Data
public class PolicyResponse {
    
    private PolicyResult result;
}
