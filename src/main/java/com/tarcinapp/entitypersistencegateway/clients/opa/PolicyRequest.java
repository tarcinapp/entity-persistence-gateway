package com.tarcinapp.entitypersistencegateway.clients.opa;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import lombok.Data;

@Data
public class PolicyRequest {
    
    private PolicyData input;
}
